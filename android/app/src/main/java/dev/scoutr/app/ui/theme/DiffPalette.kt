package dev.scoutr.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Diff/version-control palette, mirroring the gdezan-material version_control
 * and gitDecoration token families (targets/vscode + src/palette.json in
 * ~/Dev/gdezan-material). State is the color: inserted cyan, deleted red,
 * modified orange, renamed light blue, conflict amber — applied with the
 * same faint line backgrounds the VS Code diff editor uses (#00C8FF12 /
 * #FF000012 at ~7% alpha).
 */
object DiffPalette {
    val Added = Color(0xFF00C8FF)
    val Deleted = Color(0xFFFF0000)
    val Modified = Color(0xFFFC8B49)
    val Renamed = Color(0xFF89DDFF)
    val Conflict = Color(0xFFFFCB6B)
    val Ignored = Color(0xFF848484)

    /** Diff editor inserted-line background tint (~7% alpha). */
    val AddedBackground = Added.copy(alpha = 0.07f)

    /** Diff editor removed-line background tint (~7% alpha). */
    val DeletedBackground = Deleted.copy(alpha = 0.07f)
}
