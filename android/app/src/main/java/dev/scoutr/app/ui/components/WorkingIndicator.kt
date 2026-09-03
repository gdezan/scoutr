package dev.scoutr.app.ui.components

import dev.scoutr.app.ui.theme.ScoutrSpace
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.scoutr.app.ui.motion.LocalReduceMotion
import kotlinx.coroutines.delay

/** What the tail of the transcript says the agent is doing right now. */
enum class WorkingIndicatorMode { Starting, Working, WaitingForYou }

/** Animation modes for shared 9dp status rings. */
enum class StatusRingAnimation { Static, Live, NeedsYou }

/**
 * Render mode as a total function of state — including unmodelled statuses,
 * which render nothing rather than crashing. Precedence matters: a booting
 * session must not claim to be working, and a blocked agent with a question
 * card on screen must not duplicate what the card already says.
 *
 * null means "render nothing".
 */
internal fun workingIndicatorMode(
    starting: Boolean,
    agentStatus: String,
    hasPendingQuestion: Boolean,
): WorkingIndicatorMode? = when {
    starting -> WorkingIndicatorMode.Starting
    agentStatus == "blocked" && hasPendingQuestion -> null // QuestionCard owns it
    agentStatus == "blocked" -> WorkingIndicatorMode.WaitingForYou
    agentStatus == "working" -> WorkingIndicatorMode.Working
    else -> null // idle, done, unknown
}

/**
 * Time in the current status, glanceable rather than precise: seconds below a
 * minute, minutes and seconds up to ten, whole minutes past that. Clock skew
 * (a stamp in the future) reads as 0s, never a negative.
 */
internal fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes >= 10) return "${minutes}m"
    return "${minutes}m ${seconds % 60}s"
}

/** Coarser ticks once the label stops changing every second. */
private fun tickMillis(millis: Long): Long = if (millis < 60_000) 1_000L else 5_000L

/**
 * The one "the agent is busy" surface on the chat screen: an expanding ripple
 * (never a spinner — see ScoutrMotion) plus a label and the time spent in the
 * current status. State is the color: working is the AI accent, waiting on you
 * is the same error red the board uses for NeedsYou.
 */
@Composable
fun WorkingIndicator(
    mode: WorkingIndicatorMode,
    statusSinceMs: Long?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when (mode) {
        WorkingIndicatorMode.WaitingForYou -> scheme.error
        else -> scheme.primary
    }
    val label = when (mode) {
        WorkingIndicatorMode.Starting -> "Starting session…"
        WorkingIndicatorMode.Working -> "Working…"
        WorkingIndicatorMode.WaitingForYou -> "Waiting for you"
    }

    // Recomputed from the stamp on every tick, never accumulated: a status
    // change or a clock correction can't drift it.
    var elapsed by remember(statusSinceMs) {
        mutableStateOf(statusSinceMs?.let { System.currentTimeMillis() - it })
    }
    LaunchedEffect(statusSinceMs) {
        if (statusSinceMs == null) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis() - statusSinceMs
            elapsed = now
            delay(tickMillis(now))
        }
    }
    val timer = elapsed?.let(::formatElapsed)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 14.dp)
            .testTag("working_indicator")
            .semantics(mergeDescendants = true) {
                contentDescription = if (timer != null) "$label $timer" else label
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusRing(
            color = color,
            animation = if (mode == WorkingIndicatorMode.WaitingForYou) StatusRingAnimation.NeedsYou
            else StatusRingAnimation.Live,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        if (timer != null) {
            Text(
                timer,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared 9dp status ring used by Board, Sessions, and Chat. */
@Composable
fun StatusRing(
    color: Color,
    animation: StatusRingAnimation,
    modifier: Modifier = Modifier,
    layoutSize: Dp = STATUS_RING_SIZE,
) {
    Box(
        modifier = modifier.size(layoutSize),
        contentAlignment = Alignment.Center,
    ) {
        when (animation) {
            StatusRingAnimation.Live -> RippleGlyph(color)
            StatusRingAnimation.NeedsYou -> PulseGlyph(color)
            StatusRingAnimation.Static -> Canvas(Modifier.size(STATUS_RING_SIZE)) {
                drawCircle(
                    color = color,
                    radius = STATUS_RING_RADIUS.toPx(),
                    style = Stroke(width = STATUS_RING_STROKE.toPx()),
                )
            }
        }
    }
}

/** A restrained error pulse for the blocked state; live work owns the ripple. */
@Composable
private fun PulseGlyph(color: Color) {
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        Canvas(Modifier.requiredSize(GLYPH_SIZE)) {
            drawCircle(
                color = color,
                radius = STATUS_RING_RADIUS.toPx(),
                style = Stroke(width = STATUS_RING_STROKE.toPx()),
            )
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "waitingPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                WORKING_RIPPLE_MS / 2,
                easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f),
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waitingPulse",
    )
    Canvas(Modifier.requiredSize(GLYPH_SIZE)) {
        drawCircle(
            color = color.copy(alpha = 0.45f + 0.2f * (1f - progress)),
            radius = (STATUS_RING_RADIUS + 2.dp * progress).toPx(),
            style = Stroke(width = STATUS_RING_STROKE.toPx()),
        )
    }
}

/**
 * Two rings expanding out of a still center, the second half a cycle behind.
 * Under reduce motion it collapses to one static ring — no looping motion at
 * all, rather than slower looping motion.
 */
@Composable
private fun RippleGlyph(color: Color) {
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        Canvas(Modifier.requiredSize(GLYPH_SIZE)) {
            drawCircle(
                color = color,
                radius = STATUS_RING_RADIUS.toPx(),
                style = Stroke(width = STATUS_RING_STROKE.toPx()),
            )
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "workingRipple")
    val first by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(WORKING_RIPPLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple1",
    )
    val second by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(WORKING_RIPPLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = androidx.compose.animation.core.StartOffset(WORKING_RIPPLE_OFFSET_MS),
        ),
        label = "ripple2",
    )
    Canvas(Modifier.requiredSize(GLYPH_SIZE)) {
        drawCircle(
            color = color,
            radius = STATUS_RING_RADIUS.toPx(),
            style = Stroke(width = STATUS_RING_STROKE.toPx()),
        )
        listOf(first, second).forEach { progress ->
            val radius = (STATUS_RING_RADIUS + (RIPPLE_MAX_RADIUS - STATUS_RING_RADIUS) * progress).toPx()
            drawCircle(
                color = color.copy(alpha = 0.55f * (1f - progress)),
                radius = radius,
                style = Stroke(width = STATUS_RING_STROKE.toPx()),
            )
        }
    }
}

private val GLYPH_SIZE = ScoutrSpace.xl
private val STATUS_RING_SIZE = 9.dp
private val STATUS_RING_STROKE = 2.5.dp
private val STATUS_RING_RADIUS = 3.25.dp
private val RIPPLE_MAX_RADIUS = 10.75.dp
internal const val WORKING_RIPPLE_MS = 1600
internal const val WORKING_RIPPLE_OFFSET_MS = WORKING_RIPPLE_MS / 2
