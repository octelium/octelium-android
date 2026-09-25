package com.octelium.client.core.local

import octelium.api.client.daemon.v1.Daemonv1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class EventsTest {

    private fun getStatus(instanceID: String, revision: Long): Daemonv1.GetStatusResponse =
        Daemonv1.GetStatusResponse.newBuilder().setInstanceID(instanceID).setRevision(revision).build()

    private fun getLog(msg: String, level: LogLevel = LogLevel.INFO): LogEntry =
        LogEntry(level, Instant.ofEpochSecond(1790157723), msg)

    @Test
    fun testShouldReplaceStatus() {
        assertTrue(shouldReplaceStatus(null, getStatus("a", 1)))
        assertTrue(shouldReplaceStatus(getStatus("a", 1), getStatus("a", 2)))
        assertTrue(shouldReplaceStatus(getStatus("a", 2), getStatus("a", 2)))
        assertFalse(shouldReplaceStatus(getStatus("a", 3), getStatus("a", 2)))
        assertTrue(shouldReplaceStatus(getStatus("a", 3), getStatus("b", 1)))
    }

    @Test
    fun testStatusStore() {
        val s = StatusStore()
        assertNull(s.status.value)

        s.update(getStatus("a", 2))
        assertEquals(2L, s.status.value?.revision)

        s.update(getStatus("a", 1))
        assertEquals(2L, s.status.value?.revision)

        s.update(getStatus("a", 5))
        assertEquals(5L, s.status.value?.revision)

        s.update(getStatus("b", 1))
        assertEquals("b", s.status.value?.instanceID)

        s.reset()
        assertNull(s.status.value)
    }

    @Test
    fun testLogStore() {
        val s = LogStore(capacity = 3)
        assertTrue(s.logs.value.isEmpty())

        for (i in 1..5) {
            s.add(getLog("log-$i"))
        }

        assertEquals(listOf("log-3", "log-4", "log-5"), s.logs.value.map { it.message })

        s.clear()
        assertTrue(s.logs.value.isEmpty())
    }

    @Test
    fun testLogger() {
        val received = mutableListOf<LogEntry>()

        run {
            val l = Logger(LogLevel.INFO) { received.add(it) }
            l.debug("debug")
            l.info("info")
            l.warn("warn")
            l.log(getLog("entry", LogLevel.ERROR))
            l.log(getLog("debug entry", LogLevel.DEBUG))
        }

        assertEquals(listOf("info", "warn", "entry"), received.map { it.message })
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), received.map { it.level })

        received.clear()
        Logger(LogLevel.DEBUG) { received.add(it) }.debug("debug")
        assertEquals(listOf("debug"), received.map { it.message })
    }

    @Test
    fun testFormatLog() {
        assertEquals("10:02:03 WARN  Could not rebind", formatLog(getLog("Could not rebind", LogLevel.WARN), ZoneOffset.UTC))
        assertEquals("10:02:03 ERROR failed", formatLog(getLog("failed", LogLevel.ERROR), ZoneOffset.UTC))
    }
}
