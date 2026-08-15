package dev.scoutr.app.ui.screens


import dev.scoutr.app.data.UsageWindow
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
        assertEquals("resets now", resetLabel(now + 40, now))
        assertEquals("resets in 12m", resetLabel(now + 12 * 60, now))
        assertEquals("resets in 2h 15m", resetLabel(now + (2 * 60 + 15) * 60, now))
        assertEquals("resets in 3d 2h", resetLabel(now + (3 * 24 + 2) * 60 * 60, now))
    }
    @Test
    fun placesTimeMarkerAtElapsedShareOfWindow() {
        val window = UsageWindow(windowSeconds = 5 * 60 * 60, resetAt = 10_000L)

        assertEquals(0.5f, quotaTimeProgress(window, 10_000L - (2 * 60 * 60 + 30 * 60)))
        assertEquals(0f, quotaTimeProgress(window, 10_000L - 6 * 60 * 60))
        assertEquals(1f, quotaTimeProgress(window, 10_001L))
    }

    @Test
    fun omitsTimeMarkerWithoutACompleteWindow() {
        assertNull(quotaTimeProgress(UsageWindow(windowSeconds = 3600), 1_000L))
        assertNull(quotaTimeProgress(UsageWindow(resetAt = 2_000L), 1_000L))
    }

    @Test
    fun derivesDeepseekPricingFromTheUtcClock() {
        // Peak windows are 01:00–04:00 and 06:00–10:00 UTC; everything else is off-peak.
        assertEquals(DeepseekPricing(true, "peak pricing · off-peak at 04:00 UTC"), deepseekPricing(hourUtc(2)))
        assertEquals(DeepseekPricing(true, "peak pricing · off-peak at 10:00 UTC"), deepseekPricing(hourUtc(8)))
        assertEquals(DeepseekPricing(false, "off-peak pricing · peak at 06:00 UTC"), deepseekPricing(hourUtc(5)))
        assertEquals(DeepseekPricing(false, "off-peak pricing · peak at 01:00 UTC"), deepseekPricing(hourUtc(13)))
        assertEquals(DeepseekPricing(false, "off-peak pricing · peak at 01:00 UTC"), deepseekPricing(hourUtc(0)))
    }

    @Test
    fun deepseekPricingSplitsAtWindowEdges() {
        assertEquals(true, deepseekPricing(hourUtc(1)).peak)
        assertEquals(true, deepseekPricing(hourUtc(3)).peak)
        assertEquals(false, deepseekPricing(hourUtc(4)).peak)
        assertEquals(true, deepseekPricing(hourUtc(6)).peak)
        assertEquals(true, deepseekPricing(hourUtc(9)).peak)
        assertEquals(false, deepseekPricing(hourUtc(10)).peak)
    }

    private fun hourUtc(hour: Int): Long = hour * 3_600L
}
