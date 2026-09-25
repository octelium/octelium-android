package com.octelium.client.core.domain

import com.google.protobuf.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

fun toInstant(arg: Timestamp?): Instant? {
    if (arg == null || (arg.seconds == 0L && arg.nanos == 0)) {
        return null
    }

    return Instant.ofEpochSecond(arg.seconds, arg.nanos.toLong())
}

fun toTimestamp(arg: Instant): Timestamp = Timestamp.newBuilder()
    .setSeconds(arg.epochSecond)
    .setNanos(arg.nano)
    .build()

fun toRFC3339(arg: Timestamp?): String? = toInstant(arg)?.let { DateTimeFormatter.ISO_INSTANT.format(it) }

fun printDuration(from: Timestamp?, now: Instant = Instant.now()): String {
    val start = toInstant(from) ?: return ""
    val seconds = Duration.between(start, now).seconds.coerceAtLeast(0)

    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60

    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

fun printTimeAgo(arg: Timestamp?, now: Instant = Instant.now()): String {
    val at = toInstant(arg) ?: return "—"
    val seconds = Duration.between(at, now).seconds

    if (seconds < 0) {
        return "in the future"
    }

    return when {
        seconds < 45 -> "a few seconds ago"
        seconds < 90 -> "a minute ago"
        seconds < 45 * 60 -> "${(seconds + 30) / 60} minutes ago"
        seconds < 90 * 60 -> "an hour ago"
        seconds < 22 * 3600 -> "${(seconds + 1800) / 3600} hours ago"
        seconds < 36 * 3600 -> "a day ago"
        seconds < 26 * 86400 -> "${(seconds + 43200) / 86400} days ago"
        seconds < 46 * 86400 -> "a month ago"
        seconds < 320 * 86400 -> "${(seconds + 15 * 86400) / (30 * 86400)} months ago"
        seconds < 548 * 86400 -> "a year ago"
        else -> "${(seconds + 182 * 86400) / (365 * 86400)} years ago"
    }
}
