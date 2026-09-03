package dev.scoutr.app.ui.motion

import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutrMotionTest {
    @Test
    fun progressAnimationBecomesImmediateForReducedMotion() {
        val reduced = ScoutrMotion.progressSpec(reduceMotion = true)
        val ordinary = ScoutrMotion.progressSpec(reduceMotion = false)

        assertTrue(reduced is TweenSpec)
        assertEquals(0, (reduced as TweenSpec).durationMillis)
        assertTrue(ordinary is TweenSpec)
        assertEquals(ScoutrMotion.DURATION_NORMAL, (ordinary as TweenSpec).durationMillis)
    }
}
