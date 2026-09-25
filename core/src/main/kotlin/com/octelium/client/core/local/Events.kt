package com.octelium.client.core.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import octelium.api.client.daemon.v1.Daemonv1
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

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class LogEntry(
    val level: LogLevel,
    val createdAt: Instant,
    val message: String,
)

class Logger(
    private val level: LogLevel = LogLevel.INFO,
    private val sink: (LogEntry) -> Unit = {},
) {
    fun debug(message: String) = log(LogLevel.DEBUG, message)

    fun info(message: String) = log(LogLevel.INFO, message)

    fun warn(message: String) = log(LogLevel.WARN, message)

    fun error(message: String) = log(LogLevel.ERROR, message)

    fun log(level: LogLevel, message: String) {
        log(LogEntry(level, Instant.now(), message))
    }

    fun log(entry: LogEntry) {
        if (entry.level >= level) {
            sink(entry)
        }
    }
}

class LogStore(private val capacity: Int = DEFAULT_LOG_CAPACITY) {
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun add(log: LogEntry) {
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

fun formatLog(arg: LogEntry, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(arg.createdAt)

    return "$at ${arg.level.name.padEnd(5)} ${arg.message}"
}
