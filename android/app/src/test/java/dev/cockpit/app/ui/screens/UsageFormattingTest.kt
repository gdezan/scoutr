package dev.cockpit.app.ui.screens


import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageFormattingTest {
    @Test
    fun expandsKnownWindowLabels() {
        assertEquals("5-hour limit", windowTitle("5h"))
        assertEquals("7-day limit", windowTitle("7D"))
        assertEquals("Monthly limit", windowTitle("mo"))
        assertEquals("Credits", windowTitle("Credits"))
    }

    @Test
    fun onlyAddsCurrencySymbolsForKnownCurrencies() {
        assertEquals("12.50", formatAmount(12.5, null, Locale.US))
        assertEquals("$12.50", formatAmount(12.5, "USD", Locale.US))
        assertEquals("12.50 Credits", formatAmount(12.5, "Credits", Locale.US))
    }

    @Test
    fun formatsResetCountdownAtUsefulPrecision() {
        val now = 1_000_000L

        assertNull(resetLabel(null, now))
        assertEquals("Resets now", resetLabel(now + 40, now))
        assertEquals("Resets in 12m", resetLabel(now + 12 * 60, now))
        assertEquals("Resets in 2h 15m", resetLabel(now + (2 * 60 + 15) * 60, now))
        assertEquals("Resets in 3d 2h", resetLabel(now + (3 * 24 + 2) * 60 * 60, now))
    }
}
