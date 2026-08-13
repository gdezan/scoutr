package dev.scoutr.app.ui

import dev.scoutr.app.ui.screens.timeInState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class BoardFormatTest {
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
    fun boardLadder_countsDaysForever() {
        // Exercised through the screen wrapper: the wrapper must keep the
        // board's "count days forever" policy (dateAfterDays = null).
        assertEquals("now", timeInState((now - 5_000).toDouble(), nowMs = now))
        assertEquals("12m", timeInState((now - 12 * 60_000).toDouble(), nowMs = now))
        assertEquals("2h", timeInState((now - 2 * 3_600_000).toDouble(), nowMs = now))
        assertEquals("3d", timeInState((now - 3 * 86_400_000).toDouble(), nowMs = now))
        // No dateAfterDays: day counting continues past a week.
        assertEquals("40d", timeInState((now - 40L * 86_400_000L).toDouble(), nowMs = now))
    }

    @Test
    fun timeInState_nullWhenUnknown() {
        assertNull(timeInState(null))
    }
}
