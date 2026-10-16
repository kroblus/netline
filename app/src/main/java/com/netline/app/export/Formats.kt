package com.netline.app.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun timestamp(at: Long, pattern: String = "HH:mm:ss"): String = DateTimeFormatter.ofPattern(pattern, Locale.US)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
fun speed(value: Double): String = when {
    value >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", value / (1024 * 1024))
    value >= 1024 -> String.format(Locale.US, "%.1f KB/s", value / 1024)
    else -> String.format(Locale.US, "%.0f B/s", value)
}
fun bytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024))
    value >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", value / (1024.0 * 1024))
    value >= 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
    else -> "$value B"
}
fun duration(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d:%02d".format(Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
}
