package dev.scoutr.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which agent owns a session, drawn rather than spelled.
 *
 * Claude Code bakes a rotating spinner (`◑`) into its pane title, so the board
 * used to animate a meaningless quarter-circle next to every Claude agent. That
 * glyph is status, which the ring already carries; the mark that belongs there is
 * Claude's own. Pi needs nothing drawn — its `π` is genuinely part of the name.
 *
 * The mark takes [onSurfaceVariant] rather than a brand color: green means live
 * and red means you, and "everything else is a shade of the canvas" (§9d). An
 * agent's identity is not a state, so it does not get a loud color.
 */
@Composable
fun AgentMark(kind: String?, modifier: Modifier = Modifier, size: Dp = 13.dp) {
    if (kind?.lowercase() != "claude") return
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Claude Code" },
    ) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outer = this.size.minDimension / 2f
        val inner = outer * INNER_RATIO
        val stroke = this.size.minDimension * STROKE_RATIO
        // An asterisk of evenly spaced tapered rays. The real mark's rays vary
        // slightly in length, but below ~16dp that variation is a rounding error,
        // and an even star stays legible where an uneven one reads as noise.
        repeat(RAYS) { i ->
            val angle = (i.toFloat() / RAYS) * TWO_PI + RAY_PHASE
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                color = ink,
                start = Offset(center.x + dx * inner, center.y + dy * inner),
                end = Offset(center.x + dx * outer, center.y + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val RAYS = 11
private const val TWO_PI = (2.0 * Math.PI).toFloat()

/** Rotated so a ray points straight up, the way the mark is normally set. */
private const val RAY_PHASE = -TWO_PI / 4f

/** Rays start off-centre so the middle stays open rather than blotting closed. */
private const val INNER_RATIO = 0.24f
private const val STROKE_RATIO = 0.13f
