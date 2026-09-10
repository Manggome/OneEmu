package com.manggome.oneemu.ui.common

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 1536 → "1.5 KB". Korean UI keeps the Latin unit abbreviations. */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) String.format(Locale.US, "%.0f %s", v, units[i]) else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** Epoch millis → locale-formatted date + time ("2026. 9. 10. 오후 3:12"). */
fun formatDateTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMillis))
