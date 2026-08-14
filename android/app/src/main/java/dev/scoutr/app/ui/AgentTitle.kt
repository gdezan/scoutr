package dev.scoutr.app.ui

/**
 * Claude Code bakes its spinner into the pane title — `◑ finalizing redesign` —
 * so the board rendered a rotating quarter-circle beside every Claude agent. It
 * is status, not identity: the status ring already carries that, and the system
 * only animates things that are actually happening (§9d). It also reads as a
 * broken character next to pi's `π`, which really is part of the name.
 *
 * Strip a leading status glyph and nothing else; `π - scoutr` survives intact.
 */
fun agentDisplayTitle(title: String?): String {
    val raw = title?.trim().orEmpty()
    if (raw.isEmpty()) return raw
    val stripped = raw.trimStart(*STATUS_GLYPHS).trimStart()
    // A title made only of glyphs is still better than an empty row.
    return stripped.ifEmpty { raw }
}

/** Claude Code's spinner frames, plus the asterisks it uses for thinking states. */
private val STATUS_GLYPHS = charArrayOf(
    '◐', '◓', '◑', '◒', '◴', '◷', '◶', '◵',
    '✳', '✻', '✽', '✶', '✢', '✺', '✷',
)
