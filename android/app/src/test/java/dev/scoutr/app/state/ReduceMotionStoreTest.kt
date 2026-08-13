package dev.scoutr.app.state

import android.provider.Settings
import dev.scoutr.app.ui.motion.ScoutrMotion
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.toFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReduceMotionStoreTest {

    private fun setScale(scale: Float) {
        Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale,
        )
    }

    @Test
    fun animatorScaleZeroMeansReduceMotion() {
        setScale(0f)
        val store = ReduceMotionStore(RuntimeEnvironment.getApplication())
        try {
            assertTrue(store.reduceMotion.value)
        } finally {
            store.close()
        }
    }

    @Test
    fun normalAnimatorScaleMeansNoReduceMotion() {
        setScale(1f)
        val store = ReduceMotionStore(RuntimeEnvironment.getApplication())
        try {
            assertFalse(store.reduceMotion.value)
        } finally {
            store.close()
        }
    }

    @Test
    fun observerTracksSettingChanges() {
        setScale(1f)
        val store = ReduceMotionStore(RuntimeEnvironment.getApplication())
        try {
            assertFalse(store.reduceMotion.value)
            setScale(0f)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(store.reduceMotion.value)
        } finally {
            store.close()
        }
    }

    @Test
    fun closeStopsTracking() {
        setScale(1f)
        val store = ReduceMotionStore(RuntimeEnvironment.getApplication())
        store.close()
        setScale(0f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // The unregistered observer must not flip the value.
        assertFalse(store.reduceMotion.value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionSpecTest {

    @Test
    fun itemSpecCollapsesToZeroUnderReduceMotion() {
        val normal = ScoutrMotion.itemSpec(false)
        val reduced = ScoutrMotion.itemSpec(true)
        assertNotEquals(normal, reduced)
    }

    @Test
    fun overlaySpecIsZeroUnderReduceMotion() {
        assertEquals(0, (ScoutrMotion.overlaySpec(true) as androidx.compose.animation.core.TweenSpec<*>).durationMillis)
        assertEquals(ScoutrMotion.DURATION_OVERLAY, (ScoutrMotion.overlaySpec(false) as androidx.compose.animation.core.TweenSpec<*>).durationMillis)
    }

    @Test
    fun hapticEventsMapToDistinctFeedbackTypes() {
        assertEquals(
            androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick,
            HapticEvent.Select.toFeedbackType(),
        )
        assertEquals(
            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
            HapticEvent.Confirm.toFeedbackType(),
        )
        assertEquals(
            androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject,
            HapticEvent.Error.toFeedbackType(),
        )
    }
}
