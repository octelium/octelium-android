package com.octelium.client.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class StateKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeWrapper(private var key: ByteArray = ByteArray(32) { it.toByte() }) : KeyWrapper {
        var deleted = 0

        override fun wrap(plaintext: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plaintext)
        }

        override fun unwrap(ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)),
            )
            return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        }

        override fun delete() {
            deleted++
            key = ByteArray(32) { (it + deleted).toByte() }
        }
    }

    private fun assertUnavailable(fn: () -> Unit): StateKeyUnavailableException {
        try {
            fn()
            fail()
        } catch (err: StateKeyUnavailableException) {
            return err
        }

        throw IllegalStateException()
    }

    @Test
    fun testGetOrCreate() {
        val keyFile = File(tmp.root, "state-key")
        val stateDir = File(tmp.root, "state")
        val wrapper = FakeWrapper()

        val s = StateKeyStore(keyFile, stateDir, wrapper)

        val key = s.getOrCreate()
        assertEquals(STATE_KEY_LEN, key.size)
        assertTrue(keyFile.exists())
        assertFalse(keyFile.readBytes().contentEquals(key))
        assertFalse(File(tmp.root, ".state-key.tmp").exists())

        assertArrayEquals(key, s.getOrCreate())
        assertArrayEquals(key, StateKeyStore(keyFile, stateDir, wrapper).getOrCreate())
        assertNotEquals(key.toList(), StateKeyStore(File(tmp.root, "other"), File(tmp.root, "x"), wrapper).getOrCreate().toList())
    }

    @Test
    fun testUnavailable() {
        val keyFile = File(tmp.root, "state-key")
        val stateDir = File(tmp.root, "state")
        val wrapper = FakeWrapper()
        val s = StateKeyStore(keyFile, stateDir, wrapper)

        run {
            s.getOrCreate()
            stateDir.mkdirs()
            File(stateDir, "octelium.db").writeText("encrypted")

            wrapper.delete()
            val err = assertUnavailable { s.getOrCreate() }
            assertEquals("Could not unwrap the state key", err.message)
            assertTrue(keyFile.exists())
        }

        run {
            keyFile.delete()
            val err = assertUnavailable { s.getOrCreate() }
            assertEquals("The state exists but its key is missing", err.message)
        }

        run {
            keyFile.writeBytes(wrapper.wrap(ByteArray(16)))
            val err = assertUnavailable { s.getOrCreate() }
            assertEquals("The state key must be 32 bytes", err.message)
        }

        run {
            s.reset()
            assertFalse(keyFile.exists())
            assertFalse(stateDir.exists())
            assertEquals(2, wrapper.deleted)
            assertEquals(STATE_KEY_LEN, s.getOrCreate().size)
        }
    }

    @Test
    fun testInstallationID() {
        val file = File(tmp.root, "installation-id")

        val id = InstallationID(file).get()
        assertTrue(isValidInstallationID(id))
        assertEquals(id, InstallationID(file).get())

        file.writeText("invalid")
        val id2 = InstallationID(file).get()
        assertNotEquals(id, id2)
        assertTrue(isValidInstallationID(id2))
        assertEquals(id2, file.readText())
    }

    @Test
    fun testIsValidInstallationID() {
        assertTrue(isValidInstallationID("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(isValidInstallationID(""))
        assertFalse(isValidInstallationID("invalid"))
        assertFalse(isValidInstallationID("0f8fad5b-d9cb-469f-a165"))
    }

    @Test
    fun testWriteAtomically() {
        val file = File(tmp.root, "dir/sub/file")
        writeAtomically(file, "hello".toByteArray())
        assertEquals("hello", file.readText())
        writeAtomically(file, "world".toByteArray())
        assertEquals("world", file.readText())
    }
}
