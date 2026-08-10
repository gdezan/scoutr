package dev.cockpit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.AgentStatus
import dev.cockpit.app.CockpitApp
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.ui.motion.CockpitMotion
import dev.cockpit.app.ui.motion.HapticEvent
import dev.cockpit.app.ui.motion.rememberHaptic
import dev.cockpit.app.ui.motion.useReduceMotion

/**
 * Attention-first Board. Phase vocabulary is the section header plus a per-card
 * pill; cards carry the active model and the latest meaningful transcript line
 * so the user reads "what is it doing now" without opening the session.
 * Needs-you agents sort first and read strongest (filled accent pill + border).
 */
@Composable
fun BoardScreen(
    onOpenAgent: (AgentCard) -> Unit,
    viewModel: BoardViewModel = rememberBoardViewModel(),
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    val reduceMotion = useReduceMotion()
    // A subtle tap when an agent first lands in "needs you" so the glance is
    // backed by touch, not just color.
    val haptic = rememberHaptic()
    LaunchedEffect(ui.board.needsYou.size) {
        if (ui.board.needsYou.isNotEmpty()) haptic(HapticEvent.NeedsYou)
    }

    if (ui.loading && ui.board.total == 0) {
        BoardSkeleton(modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        if (!ui.connected) {
            item { DisconnectedBanner(onRetry = { viewModel.connect("", "") }) }
        }
        if (ui.error != null) {
            item {
                Box(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(ui.error ?: "", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        if (ui.board.total == 0) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No agents running", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            boardSection("Needs you", ui.board.needsYou, onOpenAgent, reduceMotion)
            boardSection("Working", ui.board.working, onOpenAgent, reduceMotion)
            boardSection("Done", ui.board.done, onOpenAgent, reduceMotion)
            boardSection("Idle", ui.board.idle, onOpenAgent, reduceMotion)
            boardSection("Other", ui.board.unknown, onOpenAgent, reduceMotion)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Adds a section header + its agent cards to the LazyList. */
private fun LazyListScope.boardSection(
    title: String,
    agents: List<AgentCard>,
    onOpenAgent: (AgentCard) -> Unit,
    reduceMotion: Boolean,
) {
    if (agents.isEmpty()) return
    item(key = "header_$title") {
        Row(
            Modifier.padding(top = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = agents.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
    }
    items(agents, key = { it.paneId }) { agent ->
        AgentCardRow(
            agent,
            onClick = { onOpenAgent(agent) },
            modifier = Modifier.animateItem(
                fadeInSpec = CockpitMotion.itemSpec(reduceMotion),
                placementSpec = CockpitMotion.itemPlacementSpec(reduceMotion),
                fadeOutSpec = CockpitMotion.itemSpec(reduceMotion),
            ),
        )
    }
}

@Composable
private fun DisconnectedBanner(onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(10.dp))
        Text(
            "Disconnected from the bridge",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Reconnect",
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .testTag("board_reconnect")
                .clickable(onClick = onRetry)
                .padding(6.dp),
        )
    }
}

@Composable
private fun AgentCardRow(agent: AgentCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = AgentStatus.fromWire(agent.status)
    val isNeedsYou = status == AgentStatus.NeedsYou
    val accent = statusColor(status)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isNeedsYou) 1.dp else 0.dp,
            color = if (isNeedsYou) accent else Color.Transparent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent_card_${agent.paneId}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .background(accent, RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = agent.title?.takeIf { it.isNotBlank() } ?: agent.agent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                agent.latestActivity?.takeIf { it.isNotBlank() }?.let { activity ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = activity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agent.cwd ?: agent.workspaceId,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = agent.model?.let { shortModel(it) } ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(status, agent.statusSinceMs)
        }
    }
}

/** Accent dot color per status; blocked is the loud one. */
@Composable
private fun statusColor(status: AgentStatus) = when (status) {
    AgentStatus.NeedsYou -> MaterialTheme.colorScheme.error
    AgentStatus.Working -> MaterialTheme.colorScheme.primary
    AgentStatus.Done -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
}

/**
 * The section header already names the status, so the pill only earns its
 * place by carrying what the header cannot: time in state (blocked stays a
 * filled accent pill — the one loud thing on screen).
 */
@Composable
private fun StatusPill(status: AgentStatus, statusSinceMs: Double?) {
    val isNeedsYou = status == AgentStatus.NeedsYou
    val label = if (isNeedsYou) "needs you" else timeInState(statusSinceMs) ?: statusLabel(status)
    val color = statusColor(status)
    Box(
        Modifier
            .background(
                if (isNeedsYou) color else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isNeedsYou) MaterialTheme.colorScheme.onError else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun statusLabel(status: AgentStatus) = when (status) {
    AgentStatus.NeedsYou -> "needs you"
    AgentStatus.Working -> "working"
    AgentStatus.Done -> "done"
    AgentStatus.Idle -> "idle"
    AgentStatus.Unknown -> "…"
}

/** Compact "time in state" from the bridge-stamped entry time. */
internal fun timeInState(sinceMs: Double?): String? {
    if (sinceMs == null) return null
    val minutes = ((System.currentTimeMillis() - sinceMs.toLong()) / 60_000L).toInt().coerceAtLeast(0)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

/** Stable skeleton rows (fixed geometry, no spinner flash) while first load runs. */
@Composable
private fun BoardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).testTag("board_skeleton"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(5) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.5f)
                            .height(14.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.9f)
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.6f)
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .width(52.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
                )
            }
            if (index == 4) {
                Box(Modifier.fillMaxWidth().height(4.dp))
            }
        }
    }
}

@Composable
private fun rememberBoardViewModel(): BoardViewModel {
    val app = LocalContext.current.applicationContext as CockpitApp
    return viewModel(
        factory = BoardViewModel.factory(app.container.bridge, app.container.connectionStore),
    )
}
