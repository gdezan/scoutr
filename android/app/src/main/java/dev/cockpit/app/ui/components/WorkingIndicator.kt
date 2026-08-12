package dev.cockpit.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import dev.cockpit.app.ui.motion.LocalReduceMotion
import kotlinx.coroutines.delay

/** What the tail of the transcript says the agent is doing right now. */
enum class WorkingIndicatorMode { Starting, Working, WaitingForYou }

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
 * (never a spinner — see CockpitMotion) plus a label and the time spent in the
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
        RippleGlyph(color)
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

/**
 * Two rings expanding out of a still center, the second half a cycle behind.
 * Under reduce motion it collapses to one static ring — no looping motion at
 * all, rather than slower looping motion.
 */
@Composable
private fun RippleGlyph(color: Color) {
    val reduceMotion = LocalReduceMotion.current
    if (reduceMotion) {
        Canvas(Modifier.size(GLYPH_SIZE)) {
            drawCircle(color = color.copy(alpha = 0.55f), radius = 7.dp.toPx(), style = Stroke(width = 1.5.dp.toPx()))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "workingRipple")
    val first by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RIPPLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple1",
    )
    val second by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Same cycle, half a period behind: keyframes hold at 0 for the
            // offset instead of starting a second transition out of phase.
            animation = keyframes {
                durationMillis = RIPPLE_MS
                0f at 0 using LinearEasing
                0.5f at RIPPLE_MS / 2 using LinearEasing
                1f at RIPPLE_MS
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = androidx.compose.animation.core.StartOffset(RIPPLE_MS / 2),
        ),
        label = "ripple2",
    )
    Canvas(Modifier.size(GLYPH_SIZE)) {
        listOf(first, second).forEach { progress ->
            val radius = (3.dp + (11.dp - 3.dp) * progress).toPx()
            drawCircle(
                color = color.copy(alpha = 0.55f * (1f - progress)),
                radius = radius,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

private val GLYPH_SIZE = 24.dp
private const val RIPPLE_MS = 1600
