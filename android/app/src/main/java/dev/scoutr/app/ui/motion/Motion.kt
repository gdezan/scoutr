package dev.scoutr.app.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.IntOffset

/**
 * Scoutr motion vocabulary. Interactive feedback is quick and quiet: rows
 * arrive with a fade, list placement never animates, and overlays fade only.
 * The WorkingIndicator owns the only intentional looping animation.
 */
object ScoutrMotion {
    const val DURATION_PRESS = 90
    const val DURATION_ARRIVE = 140
    const val DURATION_OVERLAY = 140

    /** Fade spec for LazyColumn entries (animateItem). */
    fun itemSpec(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(0) else tween(DURATION_ARRIVE)

    /** New rows never animate the list's placement axis. */
    fun itemPlacementSpec(reduceMotion: Boolean): FiniteAnimationSpec<IntOffset> =
        tween(0)

    /** Overlay arrival is a fade only. */
    fun overlaySpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) tween(0) else tween(DURATION_OVERLAY)
}

/** System-level reduce-motion flag, provided by ScoutrTheme. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun useReduceMotion(): Boolean = LocalReduceMotion.current

/** Fade an overlay into place without scale or translation. */
@Composable
fun OverlayPresence(
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (reduceMotion) {
        Box(modifier, content = content)
        return
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = ScoutrMotion.overlaySpec(reduceMotion),
        label = "overlayPresence",
    )
    Box(modifier.alpha(progress), content = content)
}
