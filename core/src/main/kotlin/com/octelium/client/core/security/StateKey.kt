package com.octelium.client.core.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

const val STATE_KEY_LEN = 32

interface KeyWrapper {
    fun wrap(plaintext: ByteArray): ByteArray

    fun unwrap(ciphertext: ByteArray): ByteArray

    fun delete()
}

class StateKeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

class StateKeyStore(
    private val keyFile: File,
    private val stateDir: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    fun getOrCreate(): ByteArray {
        if (keyFile.exists()) {
            val wrapped = try {
                keyFile.readBytes()
            } catch (err: IOException) {
                throw StateKeyUnavailableException("Could not read the wrapped state key", err)
            }

            val ret = try {
                wrapper.unwrap(wrapped)
            } catch (err: Exception) {
                throw StateKeyUnavailableException("Could not unwrap the state key", err)
            }

            if (ret.size != STATE_KEY_LEN) {
                throw StateKeyUnavailableException("The state key must be $STATE_KEY_LEN bytes")
            }

            return ret
        }

        if (hasState()) {
            throw StateKeyUnavailableException("The state exists but its key is missing")
        }

        val ret = ByteArray(STATE_KEY_LEN)
        random.nextBytes(ret)

        writeAtomically(keyFile, wrapper.wrap(ret))

        return ret
    }

    fun reset() {
        keyFile.delete()
        stateDir.deleteRecursively()
        wrapper.delete()
    }

    private fun hasState(): Boolean = stateDir.listFiles()?.isNotEmpty() == true
}

class InstallationID(private val file: File) {
    fun get(): String {
        if (file.exists()) {
            val ret = file.readText().trim()
            if (isValidInstallationID(ret)) {
                return ret
            }
        }

        val ret = UUID.randomUUID().toString()
        writeAtomically(file, ret.toByteArray())

        return ret
    }
}

fun isValidInstallationID(arg: String): Boolean = try {
    UUID.fromString(arg).toString() == arg.lowercase()
} catch (err: IllegalArgumentException) {
    false
}

fun writeAtomically(file: File, data: ByteArray) {
    file.parentFile?.mkdirs()

    val tmp = File(file.parentFile, ".${file.name}.tmp")
    FileOutputStream(tmp).use {
        it.write(data)
        it.fd.sync()
    }

    if (!tmp.renameTo(file)) {
        tmp.delete()
        throw IOException("Could not write ${file.name}")
    }
}
