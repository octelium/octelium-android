package com.octelium.client.core.local

import com.google.protobuf.Timestamp
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.mobile.v1.Mobilev1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class EventsTest {

    private fun getStatus(instanceID: String, revision: Long): Daemonv1.GetStatusResponse =
        Daemonv1.GetStatusResponse.newBuilder().setInstanceID(instanceID).setRevision(revision).build()

    private fun getLog(msg: String): Mobilev1.Log =
        Mobilev1.Log.newBuilder().setLevel(Mobilev1.Log.Level.INFO).setMessage(msg).build()

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
    fun testEventHandler() {
        val statusStore = StatusStore()
        val logStore = LogStore()
        val received = mutableListOf<String>()
        val h = EventHandler(statusStore, logStore) { received.add(it.message) }

        run {
            h.handle(Mobilev1.Event.newBuilder().setStatus(getStatus("a", 7)).build().toByteArray())
            assertEquals(7L, statusStore.status.value?.revision)
        }

        run {
            h.handle(Mobilev1.Event.newBuilder().setLog(getLog("hello")).build().toByteArray())
            assertEquals(listOf("hello"), logStore.logs.value.map { it.message })
            assertEquals(listOf("hello"), received)
        }

        run {
            h.handle(byteArrayOf(0xff.toByte(), 0x01))
            h.handle(Mobilev1.Event.getDefaultInstance().toByteArray())
            assertEquals(7L, statusStore.status.value?.revision)
            assertEquals(1, logStore.logs.value.size)
        }
    }

    @Test
    fun testFormatLog() {
        run {
            val log = Mobilev1.Log.newBuilder()
                .setLevel(Mobilev1.Log.Level.WARN)
                .setMessage("Could not rebind")
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1790157723))
                .build()
            assertEquals("10:02:03 WARN  Could not rebind", formatLog(log, ZoneOffset.UTC))
        }
        run {
            val log = Mobilev1.Log.newBuilder().setLevel(Mobilev1.Log.Level.ERROR).setMessage("failed").build()
            assertEquals("--:--:-- ERROR failed", formatLog(log, ZoneOffset.UTC))
        }
    }
}
