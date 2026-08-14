package dev.scoutr.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Scoutr lockup mark: a scout mid-stride, drawn as teal limbs behind two
 * green rings on a green groundline. Geometry is transcribed from
 * `docs/design/Scoutr Design System.dc.html` §8b, authored in a 28x30 box and
 * scaled from there so the proportions survive any size.
 */
@Composable
fun ScoutrMark(modifier: Modifier = Modifier, size: Dp = MARK_HEIGHT) {
    val limb = MaterialTheme.colorScheme.secondary
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .size(width = size * (MARK_W / MARK_H), height = size)
            .semantics { contentDescription = "Scoutr" },
    ) {
        val u = this.size.height / MARK_H

        fun bar(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
            drawRoundRect(
                color = color,
                topLeft = Offset(x * u, y * u),
                size = Size(w * u, h * u),
                cornerRadius = CornerRadius(r * u, r * u),
            )

        fun ring(x: Float, y: Float, d: Float, stroke: Float) {
            val radius = (d - stroke) / 2f * u
            drawCircle(
                color = accent,
                radius = radius,
                center = Offset((x + d / 2f) * u, (y + d / 2f) * u),
                style = Stroke(width = stroke * u),
            )
        }

        // Back leg and back arm.
        bar(2f, 5f, 6f, 22f, 3f, limb)
        bar(2f, 6f, 10f, 6f, 3f, limb)
        // Head.
        ring(9f, 2f, 12f, 2.5f)
        // Front leg and front arm.
        bar(12f, 14f, 5f, 13f, 2f, limb)
        bar(12f, 16f, 8f, 5f, 2f, limb)
        // Trailing ring — the second scout, half a stride behind.
        ring(18f, 13f, 9f, 2f)
        // Groundline.
        bar(0f, 26f, 25f, 4f, 2f, accent)
    }
}

private const val MARK_W = 28f
private const val MARK_H = 30f
private val MARK_HEIGHT = 30.dp
