package com.octelium.client.core.auth

import com.octelium.client.core.domain.toTimestamp
import octelium.api.client.config.v1.Configv1
import octelium.api.main.auth.v1.Authv1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AuthenticatorTest {

    private val setAt = Instant.ofEpochSecond(1790157723)

    private fun getDomain(expiresIn: Long, refreshTokenExpiresIn: Long): Configv1.State.Domain =
        Configv1.State.Domain.newBuilder()
            .setSessionToken(
                Authv1.SessionToken.newBuilder()
                    .setAccessToken("access")
                    .setRefreshToken("refresh")
                    .setExpiresIn(expiresIn)
                    .setRefreshTokenExpiresIn(refreshTokenExpiresIn)
            )
            .setSessionTokenSetAt(toTimestamp(setAt))
            .build()

    @Test
    fun testTokens() {
        run {
            val itm = getDomain(3600, 86400)

            assertEquals(setAt.plusSeconds(3600), getAccessTokenExpiresAt(itm))
            assertEquals(setAt.plusSeconds(1800), getAccessTokenRenewAt(itm))
            assertEquals(setAt.plusSeconds(86400), getRefreshTokenExpiresAt(itm))

            assertFalse(needsNewAccessToken(itm, setAt.plusSeconds(1799)))
            assertTrue(needsNewAccessToken(itm, setAt.plusSeconds(1801)))

            assertTrue(hasValidRefreshToken(itm, setAt.plusSeconds(86399)))
            assertFalse(hasValidRefreshToken(itm, setAt.plusSeconds(86401)))
        }

        run {
            val itm = getDomain(900, 86400)
            assertEquals(setAt.plusSeconds(300), getAccessTokenRenewAt(itm))
        }

        run {
            val itm = getDomain(0, 0)
            assertNull(getAccessTokenRenewAt(itm))
            assertNull(getRefreshTokenExpiresAt(itm))
            assertFalse(needsNewAccessToken(itm, setAt.plusSeconds(1_000_000)))
            assertFalse(hasValidRefreshToken(itm, setAt))
        }

        run {
            assertTrue(needsNewAccessToken(null))
            assertTrue(needsNewAccessToken(Configv1.State.Domain.getDefaultInstance()))
            assertFalse(hasValidRefreshToken(null))
        }
    }

    @Test
    fun testGetDeviceHostname() {
        assertEquals("Pixel 9", getDeviceHostname("  Pixel 9 "))
        assertEquals("a".repeat(32), getDeviceHostname("a".repeat(40)))
        assertEquals("a".repeat(30) + "é", getDeviceHostname("a".repeat(30) + "é".repeat(4)))
        assertEquals("a".repeat(31), getDeviceHostname("a".repeat(31) + "😀"))
    }

    @Test
    fun testGetDeviceID() {
        assertEquals(
            "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
            getDeviceID("foo"),
        )
    }
}
