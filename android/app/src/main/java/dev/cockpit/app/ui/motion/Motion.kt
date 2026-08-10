package dev.cockpit.app.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Cockpit motion vocabulary — the one place springs, tweens, and their
 * reduce-motion variants are calibrated. The world is always-dark and
 * glance-first: interactive motion is quick and quiet; overlay arrival
 * (palette, sheets) is a short fade+scale from the bottom edge; nothing
 * ever bounces or spins.
 */
object CockpitMotion {

    /** Standard interactive motion (rows, chips, list placement). */
    const val DURATION_STANDARD = 220

    /** Emphasized transitions (screen-level content swaps). */
    const val DURATION_EMPHASIZED = 300

    /** Overlay arrival (palette, dialogs). */
    const val DURATION_OVERLAY = 180

    /** Interactive rows settle fast and never bounce. */
    fun standardSpring(): FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Fade spec for LazyColumn entries (animateItem). */
    fun itemSpec(reduceMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reduceMotion) tween(0) else standardSpring()


    /** Layout reflow when list items move (placement axis of animateItem). */
    fun itemPlacementSpec(reduceMotion: Boolean): FiniteAnimationSpec<IntOffset> =
        if (reduceMotion) tween(0) else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Overlay arrival. */
    fun overlaySpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) tween(0) else tween(DURATION_OVERLAY)
}

/** System-level reduce-motion flag, provided by CockpitTheme. */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun useReduceMotion(): Boolean = LocalReduceMotion.current

/**
 * Overlay arrival: fade + scale up from the bottom edge. Collapses to a
 * no-op under reduce motion. Wrap the overlay's content.
 */
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
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = CockpitMotion.overlaySpec(reduceMotion),
        label = "overlayPresence",
    )
    Box(
        modifier.graphicsLayer {
            alpha = progress
            scaleX = 0.96f + 0.04f * progress
            scaleY = 0.96f + 0.04f * progress
            transformOrigin = TransformOrigin(0.5f, 0.9f)
        },
        content = content,
    )
}
