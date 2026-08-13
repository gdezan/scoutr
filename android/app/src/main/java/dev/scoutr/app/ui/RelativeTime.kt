package dev.scoutr.app.ui

/**
 * Compact relative time for epoch-millisecond stamps ("now", "5m", "3h", "2d"),
 * optionally rendering a "MMM d" date once past [dateAfterDays] (null = keep
 * counting days, matching the board; 7 = session-history behavior). [nowMs] is
 * injectable so tests pin a fixed instant instead of seeding the clock.
 */
internal fun relativeTime(
    epochMs: Double,
    nowMs: Long = System.currentTimeMillis(),
    dateAfterDays: Int? = null,
): String {
    if (epochMs <= 0) return ""
    val minutes = ((nowMs - epochMs.toLong()) / 60_000L).coerceAtLeast(0)
    val dayLimit = (dateAfterDays ?: Int.MAX_VALUE).toLong() * 24 * 60
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        minutes < dayLimit -> "${minutes / (24 * 60)}d"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(epochMs.toLong()))
    }
}
