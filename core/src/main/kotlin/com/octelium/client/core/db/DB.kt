package com.octelium.client.core.db

import com.google.protobuf.InvalidProtocolBufferException
import com.octelium.client.core.domain.toTimestamp
import com.octelium.client.core.security.writeAtomically
import octelium.api.client.config.v1.Configv1
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.main.auth.v1.Authv1
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val DB_FILE_NAME = "octelium.db"
const val ENCRYPTION_KEY_LEN = 32

private val encryptedStatePrefix = "octelium-db-v1:".toByteArray()

private const val NONCE_LEN = 12
private const val TAG_LEN = 128
private const val TRANSFORMATION = "AES/GCM/NoPadding"

class DBException(message: String, cause: Throwable? = null) : IOException(message, cause)

class DB(
    dir: File,
    key: ByteArray,
    private val now: () -> Instant = Instant::now,
    private val random: SecureRandom = SecureRandom(),
) {
    private val file = File(dir, DB_FILE_NAME)
    private val key: SecretKeySpec

    private var state: Configv1.State? = null

    init {
        if (key.size != ENCRYPTION_KEY_LEN) {
            throw IllegalArgumentException("The encryption key must be $ENCRYPTION_KEY_LEN bytes")
        }

        this.key = SecretKeySpec(key, "AES")
    }

    @Synchronized
    fun migrate() {
        if (!file.exists()) {
            write(Configv1.State.getDefaultInstance())
        }
    }

    @Synchronized
    fun get(domain: String): Configv1.State.Domain? = read().domainMapMap[domain]

    @Synchronized
    fun list(): Map<String, Configv1.State.Domain> = read().domainMapMap

    @Synchronized
    fun getSessionToken(domain: String): Authv1.SessionToken? =
        get(domain)?.takeIf { it.hasSessionToken() }?.sessionToken

    @Synchronized
    fun setSessionToken(domain: String, arg: Authv1.SessionToken) = update(domain) {
        it.setSessionToken(arg).setSessionTokenSetAt(toTimestamp(now()))
    }

    @Synchronized
    fun setDomainSettings(domain: String, arg: Daemonv1.DomainSettings) = update(domain) {
        it.setSettings(arg)
    }

    @Synchronized
    fun deleteSessionToken(domain: String) {
        if (get(domain) == null) {
            return
        }

        update(domain) { it.clearSessionToken().clearSessionTokenSetAt() }
    }

    @Synchronized
    fun deleteStaleSessionToken(domain: String, refreshToken: String) {
        if (get(domain)?.sessionToken?.refreshToken != refreshToken) {
            return
        }

        update(domain) { it.clearSessionToken().clearSessionTokenSetAt() }
    }

    @Synchronized
    fun delete(domain: String) {
        val cur = read()
        if (!cur.containsDomainMap(domain)) {
            return
        }

        write(cur.toBuilder().removeDomainMap(domain).build())
    }

    private fun update(domain: String, fn: (Configv1.State.Domain.Builder) -> Configv1.State.Domain.Builder) {
        val cur = read()
        val itm = cur.domainMapMap[domain] ?: Configv1.State.Domain.getDefaultInstance()

        write(cur.toBuilder().putDomainMap(domain, fn(itm.toBuilder()).build()).build())
    }

    private fun read(): Configv1.State {
        state?.let { return it }

        if (!file.exists()) {
            return Configv1.State.getDefaultInstance().also { state = it }
        }

        val data = try {
            file.readBytes()
        } catch (err: IOException) {
            throw DBException("Could not read the state: ${err.message}", err)
        }

        val ret = try {
            Configv1.State.parseFrom(if (data.isEmpty()) data else open(data))
        } catch (err: InvalidProtocolBufferException) {
            throw DBException("Could not unmarshal the state: ${err.message}", err)
        }

        state = ret

        return ret
    }

    private fun write(arg: Configv1.State) {
        try {
            writeAtomically(file, seal(arg.toByteArray()))
        } catch (err: IOException) {
            throw DBException("Could not write the state: ${err.message}", err)
        }

        state = arg
    }

    private fun seal(plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN)
        random.nextBytes(nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN, nonce))
        cipher.updateAAD(encryptedStatePrefix)

        return encryptedStatePrefix + nonce + cipher.doFinal(plaintext)
    }

    private fun open(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < encryptedStatePrefix.size + NONCE_LEN ||
            !ciphertext.copyOfRange(0, encryptedStatePrefix.size).contentEquals(encryptedStatePrefix)
        ) {
            throw DBException("The state is not encrypted")
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_LEN, ciphertext, encryptedStatePrefix.size, NONCE_LEN),
            )
            cipher.updateAAD(encryptedStatePrefix)

            val offset = encryptedStatePrefix.size + NONCE_LEN
            cipher.doFinal(ciphertext, offset, ciphertext.size - offset)
        } catch (err: GeneralSecurityException) {
            throw DBException("Could not decrypt the state", err)
        }
    }
}
