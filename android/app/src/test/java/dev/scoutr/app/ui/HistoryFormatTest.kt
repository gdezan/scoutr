package dev.scoutr.app.ui

import dev.scoutr.app.ui.screens.shortModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class HistoryFormatTest {
    // Fixed instant: the ladder is clock-independent (both the shared
    // formatter and the screen wrapper take nowMs explicitly).
    private val now = 1_700_000_000_000L
    private val originalTz = TimeZone.getDefault()

    @Before
    fun pinUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    @Test
    fun historyLadder_switchesToDateAfterSevenDays() {
        // Exercised through the screen wrapper: the wrapper must keep the
        // history policy (dateAfterDays = 7, so the date leg is reachable).
        assertEquals("now", dev.scoutr.app.ui.screens.relativeTime((now - 5_000).toDouble(), nowMs = now))
        assertEquals("12m", dev.scoutr.app.ui.screens.relativeTime((now - 12 * 60_000).toDouble(), nowMs = now))
        assertEquals("2h", dev.scoutr.app.ui.screens.relativeTime((now - 2 * 3_600_000).toDouble(), nowMs = now))
        assertEquals("3d", dev.scoutr.app.ui.screens.relativeTime((now - 3 * 86_400_000).toDouble(), nowMs = now))
        // Past a week the timestamp renders as a "MMM d" date (UTC-pinned):
        // now - 40d = 2023-10-05T22:13:20Z → Oct 5 in every timezone.
        assertEquals(
            "Oct 5",
            dev.scoutr.app.ui.screens.relativeTime((now - 40L * 86_400_000L).toDouble(), nowMs = now),
        )
    }

    @Test
    fun relativeTime_blankForUnknown() {
        assertEquals("", dev.scoutr.app.ui.screens.relativeTime(0.0))
    }

    @Test
    fun shortModel_trimsProviderPrefix() {
        assertEquals("gpt-5.4", shortModel("openai-codex/gpt-5.4"))
        assertEquals("claude-sonnet-4-6", shortModel("anthropic/claude-sonnet-4-6"))
        assertEquals("bare-model", shortModel("bare-model"))
    }
}
