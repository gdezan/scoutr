package dev.cockpit.app.ui.screens

import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.AgentStatus
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.state.viewModelFactory
import dev.cockpit.app.ui.components.ConfirmDialog
import dev.cockpit.app.ui.components.ReadableContentColumn
import dev.cockpit.app.ui.components.PullRefreshIndicator
import dev.cockpit.app.ui.components.pullRefreshSemantics
import dev.cockpit.app.ui.motion.CockpitMotion
import dev.cockpit.app.ui.motion.HapticEvent
import dev.cockpit.app.ui.motion.rememberHaptic
import dev.cockpit.app.ui.motion.useReduceMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Attention-first Board. Phase vocabulary is the section header plus a per-card
 * pill; cards carry the active model and the latest meaningful transcript line
 * so the user reads "what is it doing now" without opening the session.
 * Needs-you agents sort first and read strongest (filled accent pill + border).
 */
@Composable
fun BoardScreen(
    onOpenAgent: (AgentCard) -> Unit = {},
    viewModel: BoardViewModel = rememberBoardViewModel(),
    modifier: Modifier = Modifier,
    onReviewAgent: (AgentCard) -> Unit = {},
    onCloseAgent: (AgentCard) -> Unit = {},
) {
    val ui by viewModel.ui.collectAsState()

    // The board poll and ntfy push loop run only while the board is STARTED.
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val reduceMotion = useReduceMotion()
    // A subtle tap when an agent first lands in "needs you" so the glance is
    // backed by touch, not just color.
    val haptic = rememberHaptic()
    LaunchedEffect(ui.board.needsYou.size) {
        if (ui.board.needsYou.isNotEmpty()) haptic(HapticEvent.NeedsYou)
    }
    // Close stops a live pane, so it is gated the same way Sessions gates it:
    // a swipe is easy to trigger while scrolling, and every board card is by
    // definition a running agent.
    var pendingClose by remember { mutableStateOf<AgentCard?>(null) }

    if (ui.loading && ui.board.total == 0) {
        BoardSkeleton(modifier)
        return
    }

    pendingClose?.let { agent ->
        ConfirmDialog(
            title = "Close agent?",
            text = "Closing “${agent.cardTitle()}” stops its live pane. " +
                "The transcript is preserved and can be resumed from Sessions.",
            confirmLabel = "Close",
            onConfirm = {
                pendingClose = null
                onCloseAgent(agent)
            },
            onDismiss = { pendingClose = null },
        )
    }

    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = ui.isRefreshing,
        onRefresh = viewModel::refreshBoard,
        modifier = modifier
            .fillMaxSize()
            .pullRefreshSemantics(viewModel::refreshBoard)
            .testTag("board_refresh_root"),
        state = refreshState,
        indicator = { PullRefreshIndicator(refreshState, ui.isRefreshing) },
    ) {
        ReadableContentColumn(
            modifier = Modifier.fillMaxSize(),
            contentTag = "board_capture_root",
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Clear the board's FAB so it never covers the last card, even at
                // large font scales.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                if (!ui.connected) {
                    item { DisconnectedBanner(error = ui.error, onRetry = { viewModel.connect("", "") }) }
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
                    boardSection("Needs you", ui.board.needsYou, onOpenAgent, reduceMotion, onReviewAgent, { pendingClose = it })
                    boardSection("Working", ui.board.working, onOpenAgent, reduceMotion, onReviewAgent, { pendingClose = it })
                    boardSection("Done", ui.board.done, onOpenAgent, reduceMotion, onReviewAgent, { pendingClose = it })
                    boardSection("Idle", ui.board.idle, onOpenAgent, reduceMotion, onReviewAgent, { pendingClose = it })
                    boardSection("Other", ui.board.unknown, onOpenAgent, reduceMotion, onReviewAgent, { pendingClose = it })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Adds a section header + its agent cards to the LazyList. */
private fun LazyListScope.boardSection(
    title: String,
    agents: List<AgentCard>,
    onOpenAgent: (AgentCard) -> Unit,
    reduceMotion: Boolean,
    onReviewAgent: (AgentCard) -> Unit,
    onCloseAgent: (AgentCard) -> Unit,
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
            onReview = { onReviewAgent(agent) },
            onClose = { onCloseAgent(agent) },
            modifier = Modifier.animateItem(
                fadeInSpec = CockpitMotion.itemSpec(reduceMotion),
                placementSpec = CockpitMotion.itemPlacementSpec(reduceMotion),
                fadeOutSpec = CockpitMotion.itemSpec(reduceMotion),
            ),
        )
    }
}

@Composable
private fun DisconnectedBanner(error: String?, onRetry: () -> Unit) {
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
    error?.let { detail ->
        Text(
            detail,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 32.dp, end = 12.dp, bottom = 6.dp),
        )
    }
}

/** The name the card shows, so the close confirmation names the same thing. */
private fun AgentCard.cardTitle(): String =
    title?.takeIf { it.isNotBlank() } ?: agent

/** Swipe-to-reveal anchor values for a board card. */
private enum class BoardReveal { Closed, Open }

/** A single action button surfaced by the swipe-to-reveal bar. */
private data class BoardAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit,
)

@Composable
private fun AgentCardRow(
    agent: AgentCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onReview: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val status = AgentStatus.fromWire(agent.status)
    val isNeedsYou = status == AgentStatus.NeedsYou
    val accent = statusColor(status)
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptic()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val copyPath = {
        clipboard.setText(AnnotatedString(agent.cwd ?: agent.workspaceId))
        haptic(HapticEvent.Confirm)
        Toast.makeText(context, "Copied path", Toast.LENGTH_SHORT).show()
    }

    // Swipe-to-reveal action bar: review the agent's workspace + close the
    // agent's pane. Same anchored-draggable pattern as the Sessions rows so a
    // half-swiped card settles open or closed; horizontal-only so vertical
    // board scrolling is untouched. Tapping a revealed button fires the
    // action; tapping the card while open just closes the reveal.
    val actions = buildList {
        add(BoardAction("review", "Review", Icons.Outlined.Code, scheme.onSurfaceVariant, onReview))
        add(BoardAction("copy", "Copy path", Icons.Outlined.ContentCopy, scheme.onSurfaceVariant, copyPath))
        add(BoardAction("close", "Close", Icons.Outlined.Close, scheme.onSurfaceVariant, onClose))
    }
    val density = LocalDensity.current
    val revealWidthPx = with(density) { (actions.size * 52).dp.toPx() }
    val reveal = remember {
        AnchoredDraggableState(
            initialValue = BoardReveal.Closed,
            anchors = DraggableAnchors {
                BoardReveal.Closed at 0f
                BoardReveal.Open at -revealWidthPx
            },
        )
    }
    val scope = rememberCoroutineScope()
    fun closeReveal() {
        scope.launch { reveal.animateTo(BoardReveal.Closed) }
    }

    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        // Action bar, right-aligned, revealed as the card slides left. It
        // sizes itself from the card (matchParentSize) because LazyColumn
        // items measure with unbounded height.
        Row(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            horizontalArrangement = Arrangement.End,
        ) {
            actions.forEach { action ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(52.dp)
                        .clickable {
                            closeReveal()
                            action.onClick()
                        }
                        .testTag("board_action_${action.key}_${agent.paneId}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint = action.tint,
                    )
                }
            }
        }
        // Foreground card slides left on a horizontal drag.
        Box(
            Modifier
                .offset { IntOffset(reveal.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(reveal, reverseDirection = false, orientation = Orientation.Horizontal)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .clickable {
                    if (reveal.currentValue == BoardReveal.Open) closeReveal() else onClick()
                }
                .testTag("agent_card_${agent.paneId}"),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isNeedsYou) 1.dp else 0.dp,
                    color = if (isNeedsYou) accent else Color.Transparent,
                ),
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
                                text = agent.cardTitle(),
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
                    Box {
                        androidx.compose.material3.IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier
                                .testTag("agent_actions_${agent.paneId}")
                                .semantics { contentDescription = "Agent actions for ${agent.cardTitle()}" },
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = null, tint = scheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            actions.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label) },
                                    onClick = {
                                        menuOpen = false
                                        action.onClick()
                                    },
                                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                                    modifier = Modifier.testTag("board_menu_${action.key}_${agent.paneId}"),
                                )
                            }
                        }
                    }
                }
            }
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
internal fun timeInState(sinceMs: Double?, nowMs: Long = System.currentTimeMillis()): String? =
    sinceMs?.let { dev.cockpit.app.ui.relativeTime(it, nowMs = nowMs) }

/** Stable skeleton rows (fixed geometry, no spinner flash) while first load runs. */
@Composable
private fun BoardSkeleton(modifier: Modifier = Modifier) {
    ReadableContentColumn(
        modifier = modifier.fillMaxSize().padding(top = 8.dp, bottom = 0.dp),
        contentTag = "board_skeleton",
    ) {
    Column(
        modifier = Modifier.fillMaxSize(),
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
}

@Composable
private fun rememberBoardViewModel(): BoardViewModel {
    return viewModel(
        factory = viewModelFactory<BoardViewModel> { app ->
            BoardViewModel(app.container.bridge, app.container.connectionStore)
        },
    )
}
