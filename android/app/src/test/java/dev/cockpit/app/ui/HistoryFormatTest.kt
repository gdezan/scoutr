package dev.cockpit.app.ui

import dev.cockpit.app.ui.screens.shortModel
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFormatTest {
    // Fixed instant: the ladder is clock-independent (the shared formatter
    // takes nowMs explicitly; the screen wrapper keeps the live clock).
    private val now = 1_700_000_000_000L

    @Test
    fun historyLadder_switchesToDateAfterSevenDays() {
        assertEquals("now", relativeTime((now - 5_000).toDouble(), nowMs = now, dateAfterDays = 7))
        assertEquals("12m", relativeTime((now - 12 * 60_000).toDouble(), nowMs = now, dateAfterDays = 7))
        assertEquals("2h", relativeTime((now - 2 * 3_600_000).toDouble(), nowMs = now, dateAfterDays = 7))
        assertEquals("3d", relativeTime((now - 3 * 86_400_000).toDouble(), nowMs = now, dateAfterDays = 7))
        // Past a week the timestamp renders as a locale-US "MMM d" date:
        // now - 40d = 2023-10-05T22:13:20Z.
        assertEquals("Oct 5", relativeTime((now - 40L * 86_400_000L).toDouble(), nowMs = now, dateAfterDays = 7))
    }

    @Test
    fun relativeTime_blankForUnknown() {
        assertEquals("", dev.cockpit.app.ui.screens.relativeTime(0.0))
    }

    @Test
    fun shortModel_trimsProviderPrefix() {
        assertEquals("gpt-5.4", shortModel("openai-codex/gpt-5.4"))
        assertEquals("claude-sonnet-4-6", shortModel("anthropic/claude-sonnet-4-6"))
        assertEquals("bare-model", shortModel("bare-model"))
    }
}
