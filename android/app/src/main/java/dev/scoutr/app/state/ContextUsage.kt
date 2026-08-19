package dev.scoutr.app.state

import dev.scoutr.app.data.SessionEntry
import kotlin.math.roundToLong

/** How full a session's context window is, derived from its last assistant turn. */
data class ContextUsage(
    /** Tokens occupying the window: input + cacheRead + cacheWrite of the last assistant turn. */
    val usedTokens: Long,
    /** The model's window, or null when the catalog reports none (or has not loaded). */
    val windowTokens: Long?,
) {
    /** 0f..1f, or null without a window. Values above 1f are clamped. */
    val fraction: Float?
        get() {
            val window = windowTokens ?: return null
            if (window <= 0) return null
            return (usedTokens.toFloat() / window.toFloat()).coerceIn(0f, 1f)
        }

    /** `124k/200k`, or `124k` without a window. */
    val label: String
        get() {
            val window = windowTokens
            return if (window != null && window > 0) {
                "${formatTokens(usedTokens)}/${formatTokens(window)}"
            } else {
                formatTokens(usedTokens)
            }
        }

    /** Quiet, Warning (>=80%), or Critical (>=95%); always Quiet without a window. */
    val tone: ContextTone
        get() {
            val f = fraction ?: return ContextTone.Quiet
            return when {
                f >= CRITICAL_THRESHOLD -> ContextTone.Critical
                f >= WARNING_THRESHOLD -> ContextTone.Warning
                else -> ContextTone.Quiet
            }
        }
}

enum class ContextTone { Quiet, Warning, Critical }

private const val WARNING_THRESHOLD = 0.8f
private const val CRITICAL_THRESHOLD = 0.95f

/** Null when no assistant entry reports usage. */
fun contextUsageOf(entries: List<SessionEntry>, windowTokens: Long?): ContextUsage? {
    val entry = entries.asReversed().firstOrNull { entry ->
        entry.role == "assistant" &&
            entry.usage?.let { it.input != null || it.cacheRead != null || it.cacheWrite != null } == true
    } ?: return null
    val usage = entry.usage!!
    val used = (usage.input ?: 0) + (usage.cacheRead ?: 0) + (usage.cacheWrite ?: 0)
    return ContextUsage(usedTokens = used.coerceAtLeast(0), windowTokens = windowTokens)
}

/**
 * `< 1_000` → exact (`842`); `< 1_000_000` → rounded thousands (`124k`, half
 * rounds up); otherwise millions with one decimal, trailing `.0` trimmed (`1M`,
 * `1.2M`).
 */
private fun formatTokens(tokens: Long): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 1_000_000 -> "${(tokens.toDouble() / 1_000.0).roundToLong()}k"
    else -> {
        val millions = (tokens.toDouble() / 100_000.0).roundToLong() / 10.0
        val trimmed = if (millions == millions.toLong().toDouble()) millions.toLong().toString() else millions.toString()
        "${trimmed}M"
    }
}
