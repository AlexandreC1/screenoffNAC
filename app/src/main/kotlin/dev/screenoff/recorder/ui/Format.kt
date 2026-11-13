package dev.screenoff.recorder.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 1536 -> "1.5 KB", 1_288_490_189 -> "1.2 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

/** 185_000 -> "3:05", 3_725_000 -> "1:02:05". */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", m, s)
    }
}

/** Wall-clock milliseconds to a local date and time string. */
fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** 180 -> "3 min", 5400 -> "1 h 30 min", 45 -> "45 s". */
fun formatMinutes(seconds: Int): String {
    if (seconds < 60) return "$seconds s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}
