package dev.scoutr.app.ui

/**
 * Claude Code bakes its spinner into the pane title — `◑ finalizing redesign` —
 * so the board rendered a rotating quarter-circle beside every Claude agent. It
 * is status, not identity: the status ring already carries that, and the system
 * only animates things that are actually happening (§9d). It also reads as a
 * broken character next to pi's `π`, which really is part of the name.
 *
 * The agent's own brand mark is drawn beside the title (`AgentMark`), so a title
 * that repeats it — pi's older `π - scoutr` naming — spells the agent twice. Newer
 * sessions are named plainly, so the prefix is stripped rather than the mark.
 *
 * Strip a leading status glyph and a leading agent-name prefix; nothing else.
 */
fun agentDisplayTitle(title: String?): String {
    val raw = title?.trim().orEmpty()
    if (raw.isEmpty()) return raw
    val stripped = raw.trimStart(*STATUS_GLYPHS).trimStart()
    val named = AGENT_NAME_PREFIX.replace(stripped, "")
    // A title made only of glyphs and marks is still better than an empty row.
    return named.ifBlank { stripped.ifBlank { raw } }
}

/** `π - name`, `π — name`, `π: name`, `π name` — the mark plus an optional separator. */
private val AGENT_NAME_PREFIX = Regex("""^π\s*[-–—:·]?\s+""")

/** Claude Code's spinner frames, plus the asterisks it uses for thinking states. */
private val STATUS_GLYPHS = charArrayOf(
    '◐', '◓', '◑', '◒', '◴', '◷', '◶', '◵',
    '✳', '✻', '✽', '✶', '✢', '✺', '✷',
)
