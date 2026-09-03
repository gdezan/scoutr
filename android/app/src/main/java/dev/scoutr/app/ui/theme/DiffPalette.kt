package dev.scoutr.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Diff colors stay distinct from live-state green and the primary action color. */
object DiffPalette {
    val Added = ScoutrSemantic.diffAdded.color
    val Deleted = ScoutrSemantic.diffRemoved.color
    val Modified = Color(0xFFFC8B49)
    val Renamed = Color(0xFF89DDFF)
    val Conflict = Color(0xFFFFCB6B)
    val Ignored = Color(0xFF848B96)

    /** Diff editor inserted-line background uses the semantic added container. */
    val AddedBackground = ScoutrSemantic.diffAdded.container

    /** Diff editor removed-line background uses the semantic removed container. */
    val DeletedBackground = ScoutrSemantic.diffRemoved.container
}
