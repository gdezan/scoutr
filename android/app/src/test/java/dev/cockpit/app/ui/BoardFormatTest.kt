package dev.cockpit.app.ui

import dev.cockpit.app.ui.screens.timeInState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardFormatTest {
    // Fixed instant: the ladder is clock-independent (the shared formatter
    // takes nowMs explicitly; the wrapper keeps the live clock).
    private val now = 1_700_000_000_000L

    @Test
    fun boardLadder_countsDaysForever() {
        assertEquals("now", relativeTime((now - 5_000).toDouble(), nowMs = now))
        assertEquals("12m", relativeTime((now - 12 * 60_000).toDouble(), nowMs = now))
        assertEquals("2h", relativeTime((now - 2 * 3_600_000).toDouble(), nowMs = now))
        assertEquals("3d", relativeTime((now - 3 * 86_400_000).toDouble(), nowMs = now))
        // No dateAfterDays: day counting continues past a week.
        assertEquals("40d", relativeTime((now - 40L * 86_400_000L).toDouble(), nowMs = now))
    }

    @Test
    fun timeInState_nullWhenUnknown() {
        assertNull(timeInState(null))
    }
}
