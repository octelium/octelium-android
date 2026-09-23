package com.octelium.client.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.octelium.client.core.security.KeyWrapper
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreKeyWrapper(
    private val context: Context,
    private val alias: String = KEY_ALIAS,
) : KeyWrapper {

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        check(iv.size == IV_LEN) { "Unexpected IV length: ${iv.size}" }

        return byteArrayOf(VERSION) + iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 1 + IV_LEN && ciphertext[0] == VERSION) { "Invalid wrapped key" }

        val key = getKey() ?: throw IllegalStateException("The Keystore wrapping key does not exist")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, ciphertext, 1, IV_LEN))

        return cipher.doFinal(ciphertext, 1 + IV_LEN, ciphertext.size - 1 - IV_LEN)
    }

    override fun delete() {
        val ks = getKeyStore()
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
    }

    private fun getKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getKey(): SecretKey? = getKeyStore().getKey(alias, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey {
        getKey()?.let { return it }

        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            try {
                return generateKey(isStrongBox = true)
            } catch (err: StrongBoxUnavailableException) {
                delete()
            }
        }

        return generateKey(isStrongBox = false)
    }

    private fun generateKey(isStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(isStrongBox)
            .build()

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        const val KEY_ALIAS = "octelium-state-key"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_LEN = 128
        private const val VERSION: Byte = 1
    }
}
