package com.octelium.client.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import kotlin.random.Random

val RECONNECT_BACKOFF_MIN: Duration = Duration.ofSeconds(2)
val RECONNECT_BACKOFF_MAX: Duration = Duration.ofMinutes(2)

class NetworkWatcher(private val getBackoff: (Int) -> Duration = ::getReconnectBackoff) {
    private val _state = MutableStateFlow(NetworkState(isAvailable = true, id = ""))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    val isAvailable: Boolean
        get() = _state.value.isAvailable

    fun set(isAvailable: Boolean, id: String) {
        _state.value = NetworkState(isAvailable, id)
    }

    suspend fun waitReconnect(attempt: Int) {
        val deadline = System.nanoTime() + getBackoff(attempt).toNanos()

        while (true) {
            val cur = _state.value
            if (!cur.isAvailable) {
                _state.first { it.isAvailable }
                return
            }

            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                return
            }

            val next = withTimeoutOrNull(Duration.ofNanos(remaining).toMillis().coerceAtLeast(1)) {
                _state.first { it != cur }
            } ?: return

            if (next.isAvailable) {
                return
            }
        }
    }
}

fun getReconnectBackoff(attempt: Int): Duration {
    val ret = RECONNECT_BACKOFF_MIN.multipliedBy(1L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(RECONNECT_BACKOFF_MAX)
    val jitterMax = minOf(ret.dividedBy(2), RECONNECT_BACKOFF_MAX.minus(ret))

    return ret.plusMillis(Random.nextLong(0, jitterMax.toMillis() + 1))
}
