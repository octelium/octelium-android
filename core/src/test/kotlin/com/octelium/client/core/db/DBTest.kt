package com.octelium.client.core.db

import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.main.auth.v1.Authv1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class DBTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(ENCRYPTION_KEY_LEN) { it.toByte() }

    private fun getSessionToken(accessToken: String, refreshToken: String): Authv1.SessionToken =
        Authv1.SessionToken.newBuilder()
            .setAccessToken(accessToken)
            .setRefreshToken(refreshToken)
            .setExpiresIn(3600)
            .setRefreshTokenExpiresIn(86400)
            .build()

    @Test
    fun testDB() {
        val dir = tmp.newFolder()
        val now = Instant.ofEpochSecond(1790157723)
        val db = DB(dir, key, now = { now })

        db.migrate()
        assertTrue(File(dir, DB_FILE_NAME).exists())
        assertTrue(db.list().isEmpty())
        assertNull(db.get("example.com"))
        assertNull(db.getSessionToken("example.com"))

        db.setSessionToken("example.com", getSessionToken("access-1", "refresh-1"))
        db.setDomainSettings("example.com", Daemonv1.DomainSettings.newBuilder().setAutoConnect(true).build())
        db.setDomainSettings("other.example.com", Daemonv1.DomainSettings.getDefaultInstance())

        run {
            val itm = db.get("example.com")!!
            assertEquals("access-1", itm.sessionToken.accessToken)
            assertEquals(now.epochSecond, itm.sessionTokenSetAt.seconds)
            assertTrue(itm.settings.autoConnect)
            assertEquals(setOf("example.com", "other.example.com"), db.list().keys)
        }

        run {
            val other = DB(dir, key)
            assertEquals("refresh-1", other.getSessionToken("example.com")?.refreshToken)
            assertTrue(other.get("example.com")!!.settings.autoConnect)
        }

        run {
            db.deleteStaleSessionToken("example.com", "refresh-0")
            assertEquals("access-1", db.getSessionToken("example.com")?.accessToken)

            db.deleteStaleSessionToken("example.com", "refresh-1")
            assertNull(db.getSessionToken("example.com"))
            assertFalse(db.get("example.com")!!.hasSessionTokenSetAt())
            assertTrue(db.get("example.com")!!.settings.autoConnect)
        }

        run {
            db.setSessionToken("example.com", getSessionToken("access-2", "refresh-2"))
            db.deleteSessionToken("example.com")
            db.deleteSessionToken("unknown.example.com")
            assertNull(db.getSessionToken("example.com"))
            assertNull(db.get("unknown.example.com"))
        }

        run {
            db.delete("example.com")
            db.delete("unknown.example.com")
            assertNull(db.get("example.com"))
            assertEquals(setOf("other.example.com"), DB(dir, key).list().keys)
        }
    }

    @Test
    fun testEncryption() {
        val dir = tmp.newFolder()

        DB(dir, key).setSessionToken("example.com", getSessionToken("access-token", "refresh-token"))

        val data = File(dir, DB_FILE_NAME).readBytes()
        assertArrayEquals("octelium-db-v1:".toByteArray(), data.copyOfRange(0, 15))
        assertFalse(String(data, Charsets.ISO_8859_1).contains("refresh-token"))

        try {
            DB(dir, ByteArray(ENCRYPTION_KEY_LEN) { 7 }).list()
            fail()
        } catch (err: DBException) {
            assertEquals("Could not decrypt the state", err.message)
        }

        File(dir, DB_FILE_NAME).writeBytes("plaintext".toByteArray())
        try {
            DB(dir, key).list()
            fail()
        } catch (err: DBException) {
            assertEquals("The state is not encrypted", err.message)
        }

        try {
            DB(dir, ByteArray(16))
            fail()
        } catch (err: IllegalArgumentException) {
            assertEquals("The encryption key must be 32 bytes", err.message)
        }
    }

    @Test
    fun testGoCompatibility() {
        val dir = tmp.newFolder()
        javaClass.getResourceAsStream("/db/octelium.db")!!.use { input ->
            File(dir, DB_FILE_NAME).outputStream().use { input.copyTo(it) }
        }

        val db = DB(dir, key)

        assertEquals(setOf("example.com", "other.example.com"), db.list().keys)

        val itm = db.get("example.com")!!
        assertEquals("access-token", itm.sessionToken.accessToken)
        assertEquals("refresh-token", itm.sessionToken.refreshToken)
        assertEquals(3600L, itm.sessionToken.expiresIn)
        assertEquals(86400L, itm.sessionToken.refreshTokenExpiresIn)
        assertEquals(1790370294L, itm.sessionTokenSetAt.seconds)
        assertTrue(itm.settings.autoConnect)
        assertEquals(Daemonv1.ConnectionOptions.TunnelMode.QUICV0, itm.settings.connectionOptions.tunnelMode)

        db.setSessionToken("example.com", getSessionToken("access-2", "refresh-2"))
        assertEquals("access-2", DB(dir, key).getSessionToken("example.com")?.accessToken)
        assertTrue(DB(dir, key).get("example.com")!!.settings.autoConnect)
    }
}
