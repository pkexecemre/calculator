package com.simplecalculator.app.core

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}

fun formatViews(views: Long): String = when {
    views >= 1_000_000_000L -> trimNumber(views / 1_000_000_000.0) + "B"
    views >= 1_000_000L -> trimNumber(views / 1_000_000.0) + "M"
    views >= 1_000L -> trimNumber(views / 1_000.0) + "K"
    else -> views.toString()
}

private fun trimNumber(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> trimNumber(bytes / 1_073_741_824.0) + " GB"
    bytes >= 1_048_576L -> trimNumber(bytes / 1_048_576.0) + " MB"
    bytes >= 1024L -> trimNumber(bytes / 1024.0) + " KB"
    else -> bytes.toString() + " B"
}
