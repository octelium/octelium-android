package com.octelium.client.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class NetworkWatcherTest {

    @Test
    fun testGetReconnectBackoff() {
        for (attempt in 0..20) {
            val ret = getReconnectBackoff(attempt)
            val base = RECONNECT_BACKOFF_MIN.multipliedBy(1L shl (attempt - 1).coerceIn(0, 6))
                .coerceAtMost(RECONNECT_BACKOFF_MAX)

            assertTrue(ret >= base)
            assertTrue(ret <= RECONNECT_BACKOFF_MAX)
            assertTrue(ret <= base.plus(base.dividedBy(2)))
        }
    }

    @Test
    fun testWaitReconnect() = runBlocking {
        run {
            val w = NetworkWatcher { Duration.ofMillis(50) }
            assertTrue(w.isAvailable)

            val start = System.nanoTime()
            w.waitReconnect(1)
            assertTrue(Duration.ofNanos(System.nanoTime() - start) >= Duration.ofMillis(50))
        }

        run {
            val w = NetworkWatcher { Duration.ofMinutes(1) }
            w.set(false, "")
            assertFalse(w.isAvailable)

            val ret = async { withTimeout(5_000) { w.waitReconnect(1) } }
            delay(50)
            assertFalse(ret.isCompleted)

            w.set(false, "other")
            delay(50)
            assertFalse(ret.isCompleted)

            w.set(true, "100")
            ret.await()
            assertEquals(NetworkState(isAvailable = true, id = "100"), w.state.value)
        }

        run {
            val w = NetworkWatcher { Duration.ofMinutes(1) }
            w.set(true, "100")

            val ret = async { withTimeout(5_000) { w.waitReconnect(3) } }
            delay(50)
            assertFalse(ret.isCompleted)

            w.set(true, "101")
            ret.await()
        }
    }
}
