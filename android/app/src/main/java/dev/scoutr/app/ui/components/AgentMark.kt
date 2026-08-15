package dev.scoutr.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.scoutr.app.R

/**
 * Which agent owns a session, drawn rather than spelled.
 *
 * Claude Code bakes a rotating spinner (`◑`) into its pane title, so the board
 * used to animate a meaningless quarter-circle next to every Claude agent. That
 * glyph is status, which the ring already carries; the mark that belongs there is
 * the agent's own.
 *
 * This is a deliberate, agreed exception to §9d's "everything else is a shade of
 * the canvas" rule: an agent's identity is not a state, but distinguishing three
 * marks at 14dp in one ink color reads as noise, so each brand keeps its own
 * color here. Pi has none of its own (white-on-near-black is its only published
 * treatment), so it takes the theme's ink color instead of a fixed hex.
 */
@Composable
fun AgentMark(kind: String?, modifier: Modifier = Modifier, size: Dp = 13.dp) {
    val mark = markFor(kind) ?: return
    Icon(
        painter = painterResource(mark.iconRes),
        contentDescription = mark.description,
        tint = mark.tint ?: Color.Unspecified,
        modifier = modifier.size(size),
    )
}

private data class AgentIcon(val iconRes: Int, val description: String, val tint: Color?)

@Composable
private fun markFor(kind: String?): AgentIcon? = when (kind?.lowercase()) {
    "claude" -> AgentIcon(R.drawable.ic_agent_claude, "Claude Code", tint = null)
    "pi" -> AgentIcon(R.drawable.ic_agent_pi, "Pi", tint = MaterialTheme.colorScheme.onSurface)
    "agy", "gemini", "antigravity_cli", "antigravity" ->
        AgentIcon(R.drawable.ic_agent_gemini, "Antigravity", tint = null)
    else -> null
}
