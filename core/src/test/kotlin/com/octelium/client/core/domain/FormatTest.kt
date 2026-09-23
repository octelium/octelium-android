package com.octelium.client.core.domain

import com.google.protobuf.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FormatTest {

    private fun getTimestamp(arg: Instant): Timestamp =
        Timestamp.newBuilder().setSeconds(arg.epochSecond).setNanos(arg.nano).build()

    @Test
    fun testToInstant() {
        assertNull(toInstant(null))
        assertNull(toInstant(Timestamp.getDefaultInstance()))

        val now = Instant.parse("2026-09-23T10:00:00.5Z")
        assertEquals(now, toInstant(getTimestamp(now)))
    }

    @Test
    fun testToRFC3339() {
        assertNull(toRFC3339(null))
        assertEquals("2026-09-23T10:00:00Z", toRFC3339(getTimestamp(Instant.parse("2026-09-23T10:00:00Z"))))
    }

    @Test
    fun testPrintDuration() {
        val now = Instant.parse("2026-09-23T10:00:00Z")

        assertEquals("", printDuration(null, now))
        assertEquals("0s", printDuration(getTimestamp(now), now))
        assertEquals("0s", printDuration(getTimestamp(now.plusSeconds(10)), now))
        assertEquals("42s", printDuration(getTimestamp(now.minusSeconds(42)), now))
        assertEquals("2m 5s", printDuration(getTimestamp(now.minusSeconds(125)), now))
        assertEquals("1h 1m", printDuration(getTimestamp(now.minusSeconds(3660)), now))
        assertEquals("2d 3h", printDuration(getTimestamp(now.minusSeconds(2 * 86400 + 3 * 3600 + 59)), now))
    }

    @Test
    fun testPrintTimeAgo() {
        val now = Instant.parse("2026-09-23T10:00:00Z")

        assertEquals("—", printTimeAgo(null, now))
        assertEquals("a few seconds ago", printTimeAgo(getTimestamp(now.minusSeconds(10)), now))
        assertEquals("a minute ago", printTimeAgo(getTimestamp(now.minusSeconds(60)), now))
        assertEquals("5 minutes ago", printTimeAgo(getTimestamp(now.minusSeconds(300)), now))
        assertEquals("an hour ago", printTimeAgo(getTimestamp(now.minusSeconds(3600)), now))
        assertEquals("3 hours ago", printTimeAgo(getTimestamp(now.minusSeconds(3 * 3600)), now))
        assertEquals("a day ago", printTimeAgo(getTimestamp(now.minusSeconds(86400)), now))
        assertEquals("4 days ago", printTimeAgo(getTimestamp(now.minusSeconds(4 * 86400)), now))
        assertEquals("in the future", printTimeAgo(getTimestamp(now.plusSeconds(60)), now))
    }
}
