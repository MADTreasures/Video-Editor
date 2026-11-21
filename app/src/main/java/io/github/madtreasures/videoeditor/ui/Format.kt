package io.github.madtreasures.videoeditor.ui

import java.util.Locale

/** 75 300 ms -> "1:15.3". From one hour on the tenths are dropped: "1:02:03". */
fun formatTime(ms: Long): String {
    val totalTenths = (ms.coerceAtLeast(0) + 50) / 100
    val tenths = totalTenths % 10
    val totalSeconds = totalTenths / 10
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds, tenths)
    }
}

/** Label for a ruler mark; shows tenths only when the marks are closer than a second. */
fun formatRulerTime(ms: Long, stepMs: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val base = if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
    return if (stepMs < 1000) base + "." + (ms % 1000) / 100 else base
}

/** 1.0 -> "1x", 0.25 -> "0.25x". */
fun formatSpeed(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        speed.toString().trimEnd('0').trimEnd('.')
    }
    return text + "x"
}
