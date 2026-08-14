package dev.scoutr.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Diff colors stay distinct from live-state green and the primary action color. */
object DiffPalette {
    val Added = Color(0xFF3FC9E8)
    val Deleted = Color(0xFFFF6B70)
    val Modified = Color(0xFFFC8B49)
    val Renamed = Color(0xFF89DDFF)
    val Conflict = Color(0xFFFFCB6B)
    val Ignored = Color(0xFF848B96)

    /** Diff editor inserted-line background tint (~7% alpha). */
    val AddedBackground = Added.copy(alpha = 0.07f)

    /** Diff editor removed-line background tint (~7% alpha). */
    val DeletedBackground = Deleted.copy(alpha = 0.07f)
}
