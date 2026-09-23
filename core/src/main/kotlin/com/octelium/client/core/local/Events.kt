package com.octelium.client.core.local

import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import octelium.api.client.daemon.v1.Daemonv1
import octelium.api.client.mobile.v1.Mobilev1
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class StatusStore {
    private val _status = MutableStateFlow<Daemonv1.GetStatusResponse?>(null)
    val status: StateFlow<Daemonv1.GetStatusResponse?> = _status.asStateFlow()

    fun update(arg: Daemonv1.GetStatusResponse) {
        _status.update { cur ->
            if (shouldReplaceStatus(cur, arg)) arg else cur
        }
    }

    fun reset() {
        _status.value = null
    }
}

fun shouldReplaceStatus(cur: Daemonv1.GetStatusResponse?, next: Daemonv1.GetStatusResponse): Boolean {
    if (cur == null || cur.instanceID != next.instanceID) {
        return true
    }

    return next.revision >= cur.revision
}

class LogStore(private val capacity: Int = DEFAULT_LOG_CAPACITY) {
    private val lock = Any()
    private val buffer = ArrayDeque<Mobilev1.Log>()

    private val _logs = MutableStateFlow<List<Mobilev1.Log>>(emptyList())
    val logs: StateFlow<List<Mobilev1.Log>> = _logs.asStateFlow()

    fun add(log: Mobilev1.Log) {
        synchronized(lock) {
            buffer.addLast(log)
            while (buffer.size > capacity) {
                buffer.removeFirst()
            }
            _logs.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _logs.value = emptyList()
        }
    }

    companion object {
        const val DEFAULT_LOG_CAPACITY = 500
    }
}

class EventHandler(
    private val statusStore: StatusStore,
    private val logStore: LogStore,
    private val onLog: (Mobilev1.Log) -> Unit = {},
) {
    fun handle(data: ByteArray) {
        val ev = try {
            Mobilev1.Event.parseFrom(data)
        } catch (err: InvalidProtocolBufferException) {
            return
        }

        when (ev.typeCase) {
            Mobilev1.Event.TypeCase.STATUS -> statusStore.update(ev.status)
            Mobilev1.Event.TypeCase.LOG -> {
                logStore.add(ev.log)
                onLog(ev.log)
            }

            else -> {}
        }
    }
}

fun formatLog(arg: Mobilev1.Log, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = if (arg.hasCreatedAt()) {
        DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(zone)
            .format(Instant.ofEpochSecond(arg.createdAt.seconds, arg.createdAt.nanos.toLong()))
    } else {
        "--:--:--"
    }

    return "$at ${arg.level.name.padEnd(5)} ${arg.message}"
}
