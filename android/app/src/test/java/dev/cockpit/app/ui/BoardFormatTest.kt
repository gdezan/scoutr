package dev.cockpit.app.ui

import dev.cockpit.app.ui.screens.timeInState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardFormatTest {
    private val now = System.currentTimeMillis()

    @Test
    fun timeInState_formatsCompact() {
        assertEquals("now", timeInState((now - 5_000).toDouble()))
        assertEquals("12m", timeInState((now - 12 * 60_000).toDouble()))
        assertEquals("2h", timeInState((now - 2 * 3_600_000).toDouble()))
        assertEquals("3d", timeInState((now - 3 * 86_400_000).toDouble()))
    }

    @Test
    fun timeInState_nullWhenUnknown() {
        assertNull(timeInState(null))
    }
}
