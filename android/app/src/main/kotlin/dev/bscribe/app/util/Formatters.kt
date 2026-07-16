package dev.bscribe.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 65_000 ms → "1:05"; 3_725_000 ms → "1:02:05". */
fun formatClock(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("EEE, MMM d · HH:mm", Locale.getDefault()).format(Date(epochMs))
