package dev.scoutr.app.ui

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.scoutr.app.data.AppearancePreferencesStore
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Settings haptics switch is the one gate: [rememberHaptic] is the only
 * motor entry point in the app, so turning it off has to silence every event
 * — BEL and needs-you included — without any call site knowing about it.
 */
class HapticsGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Counts motor calls instead of feeling them. */
    private class RecordingFeedback : HapticFeedback {
        var calls = 0
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            calls++
        }
    }

    @Before
    fun clearPrefs() {
        context.getSharedPreferences(AppearancePreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun firedEventsWithHaptics(enabled: Boolean): Int {
        AppearancePreferencesStore(context).hapticsEnabled = enabled
        val feedback = RecordingFeedback()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                val haptic = rememberHaptic()
                LaunchedEffect(Unit) {
                    // One of each, so nothing slips past the gate by event type.
                    HapticEvent.entries.forEach { haptic(it) }
                }
            }
        }
        compose.waitForIdle()
        return feedback.calls
    }

    @Test
    fun enabledIsTheDefaultAndEveryEventReachesTheMotor() {
        assertEquals(HapticEvent.entries.size, firedEventsWithHaptics(enabled = true))
    }

    @Test
    fun disabledSilencesEveryEvent() {
        assertEquals(0, firedEventsWithHaptics(enabled = false))
    }
}
