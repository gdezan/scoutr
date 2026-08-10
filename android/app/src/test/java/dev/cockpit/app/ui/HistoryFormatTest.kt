package dev.cockpit.app.ui

import dev.cockpit.app.ui.screens.relativeTime
import dev.cockpit.app.ui.screens.shortModel
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFormatTest {
    private val now = System.currentTimeMillis()

    @Test
    fun relativeTime_formatsCompact() {
        assertEquals("now", relativeTime((now - 5_000).toDouble()))
        assertEquals("12m", relativeTime((now - 12 * 60_000).toDouble()))
        assertEquals("2h", relativeTime((now - 2 * 3_600_000).toDouble()))
        assertEquals("3d", relativeTime((now - 3 * 86_400_000).toDouble()))
    }

    @Test
    fun relativeTime_blankForUnknown() {
        assertEquals("", relativeTime(0.0))
    }

    @Test
    fun shortModel_trimsProviderPrefix() {
        assertEquals("gpt-5.4", shortModel("openai-codex/gpt-5.4"))
        assertEquals("claude-sonnet-4-6", shortModel("anthropic/claude-sonnet-4-6"))
        assertEquals("bare-model", shortModel("bare-model"))
    }
}
