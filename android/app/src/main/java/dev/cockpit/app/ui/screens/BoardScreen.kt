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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.AgentStatus
import dev.cockpit.app.CockpitApp
import dev.cockpit.app.state.BoardViewModel

@Composable
fun BoardScreen(
    onOpenAgent: (AgentCard) -> Unit,
    viewModel: BoardViewModel = rememberBoardViewModel(),
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()

    if (ui.loading && ui.board.total == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
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
        if (ui.board.total == 0) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No agents on the herd yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            boardSection("Needs you", ui.board.needsYou, onOpenAgent)
            boardSection("Working", ui.board.working, onOpenAgent)
            boardSection("Done", ui.board.done, onOpenAgent)
            boardSection("Idle", ui.board.idle, onOpenAgent)
            boardSection("Other", ui.board.unknown, onOpenAgent)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Adds a section header + its agent cards to the LazyList. */
private fun androidx.compose.foundation.lazy.LazyListScope.boardSection(
    title: String,
    agents: List<AgentCard>,
    onOpenAgent: (AgentCard) -> Unit,
) {
    if (agents.isEmpty()) return
    item(key = "header_$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        )
    }
    items(agents, key = { it.paneId }) { agent ->
        AgentCardRow(agent, onClick = { onOpenAgent(agent) })
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
private fun AgentCardRow(agent: AgentCard, onClick: () -> Unit) {
    val status = AgentStatus.fromWire(agent.status)
    val (dot, tint) = when (status) {
        AgentStatus.NeedsYou -> Icons.Default.PriorityHigh to MaterialTheme.colorScheme.error
        AgentStatus.Working -> Icons.Default.Bolt to MaterialTheme.colorScheme.primary
        AgentStatus.Done -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.secondary
        else -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("agent_card_${agent.paneId}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(dot, contentDescription = null, tint = tint, modifier = Modifier.width(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = agent.title?.takeIf { it.isNotBlank() } ?: agent.agent,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = agent.cwd ?: agent.workspaceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(status)
        }
    }
}

@Composable
private fun StatusPill(status: AgentStatus) {
    val label = when (status) {
        AgentStatus.NeedsYou -> "needs you"
        AgentStatus.Working -> "working"
        AgentStatus.Done -> "done"
        AgentStatus.Idle -> "idle"
        AgentStatus.Unknown -> "…"
    }
    val color = when (status) {
        AgentStatus.NeedsYou -> MaterialTheme.colorScheme.error
        AgentStatus.Working -> MaterialTheme.colorScheme.primary
        AgentStatus.Done -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun rememberBoardViewModel(): BoardViewModel {
    val app = LocalContext.current.applicationContext as CockpitApp
    return viewModel(
        factory = BoardViewModel.factory(app.container.bridge, app.container.connectionStore),
    )
}
