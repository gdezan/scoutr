package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.agentDisplayTitle
import dev.scoutr.app.ui.shortenHostPath
import dev.scoutr.app.ui.theme.ScoutrType
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.scoutr.app.data.AgentCard
import dev.scoutr.app.data.AgentStatus
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.components.AgentMark
import dev.scoutr.app.ui.components.ConfirmDialog
import dev.scoutr.app.ui.components.ReadableContentColumn
import dev.scoutr.app.ui.components.PullRefreshIndicator
import dev.scoutr.app.ui.components.StatusRing
import dev.scoutr.app.ui.components.StatusRingAnimation
import dev.scoutr.app.ui.components.PressTintSurface
import dev.scoutr.app.ui.components.pullRefreshSemantics
import dev.scoutr.app.ui.motion.ScoutrMotion
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import dev.scoutr.app.ui.motion.useReduceMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Attention-first Board. Phase vocabulary is the section header plus per-card
 * status metadata; cards carry the active model and latest meaningful transcript
 * line so the user reads "what is it doing now" without opening the session.
 * Needs-you agents sort first and read strongest through red treatment and border.
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
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading agents…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                // 2dp inside a group; the section headers carry the 14dp that
                // separates groups (reference §9a "gaps").
                verticalArrangement = Arrangement.spacedBy(2.dp),
                // Clear the board's FAB so it never covers the last card, even at
                // large font scales.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
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
        // The header carries the status color; the count stays quiet beside it so
        // the eye lands on the word, not the number (reference §8b).
        Row(
            Modifier
                .padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
                // The word and the count are separate nodes because they carry
                // separate colors, so the pair is addressed by tag.
                .semantics(mergeDescendants = true) {
                    contentDescription = "${title.uppercase()} ${agents.size}"
                }
                .testTag("board_section_${title.lowercase().replace(' ', '_')}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = ScoutrType.monoSection,
                color = sectionColor(title),
            )
            Text(
                text = "${agents.size}",
                style = ScoutrType.monoMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
                fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
            ),
        )
    }
}

@Composable
private fun DisconnectedBanner(error: String?, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
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
    agentDisplayTitle(title).takeIf { it.isNotBlank() } ?: agent

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

    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))) {
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
        PressTintSurface(
            onClick = { if (reveal.currentValue == BoardReveal.Open) closeReveal() else onClick() },
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            pressedColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .offset { IntOffset(reveal.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(reveal, reverseDirection = false, orientation = Orientation.Horizontal)
                .fillMaxWidth()
                .testTag("agent_card_${agent.paneId}"),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isNeedsYou) 1.dp else 0.dp,
                    color = if (isNeedsYou) accent else Color.Transparent,
                ),
            ) {
                // Tile anatomy per reference §8b: ring, then a text column of
                // title / latest activity / machine facts, then time in state.
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    StatusRing(
                        color = accent,
                        animation = ringAnimation(status),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AgentMark(agent.agentKind)
                            if (agent.agentKind.lowercase() == "claude") {
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = agent.cardTitle(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // A needs-you agent's question is prose addressed to the
                        // user; everything else is the machine's own last move.
                        agent.latestActivity?.takeIf { it.isNotBlank() }?.let { activity ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (isNeedsYou) activity else "▸ $activity",
                                style = if (isNeedsYou) MaterialTheme.typography.bodySmall else ScoutrType.monoMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = if (isNeedsYou) 1f else 0.65f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = listOfNotNull(
                                shortenHostPath(agent.cwd) ?: agent.workspaceId,
                                agent.model?.let { shortModel(it) },
                            ).joinToString(" · "),
                            style = ScoutrType.monoMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    TimeInState(status, agent.statusSinceMs ?: agent.latestActivityAtMs)
                    Box {
                        androidx.compose.material3.IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("agent_actions_${agent.paneId}")
                                .semantics { contentDescription = "Agent actions for ${agent.cardTitle()}" },
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp),
                            )
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
    AgentStatus.Done -> MaterialTheme.colorScheme.onSurfaceVariant
    AgentStatus.Idle -> MaterialTheme.colorScheme.outline
    AgentStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
}

/**
 * The section header already names the status, so the tile's right rail only
 * earns its place by carrying what the header cannot: time in state. Needs-you
 * keeps the red so an unanswered agent still reads loud at a glance.
 */
@Composable
private fun TimeInState(status: AgentStatus, sinceMs: Double?) {
    // Not every agent kind stamps a status transition; the last activity is the
    // same glanceable fact when it doesn't, and the status word is the last resort.
    val label = timeInState(sinceMs) ?: statusLabel(status)
    Text(
        label,
        style = ScoutrType.monoFact,
        color = when (status) {
            AgentStatus.NeedsYou -> MaterialTheme.colorScheme.error
            AgentStatus.Working -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        modifier = Modifier.padding(top = 5.dp),
    )
}

/** Section headers speak the status language: green live, red you, gray settled. */
@Composable
private fun sectionColor(title: String) = when (title.lowercase()) {
    "needs you" -> MaterialTheme.colorScheme.error
    "working" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
}

/** Only genuinely live states animate — the ripple is the icon's own gesture. */
private fun ringAnimation(status: AgentStatus) = when (status) {
    AgentStatus.Working -> StatusRingAnimation.Live
    AgentStatus.NeedsYou -> StatusRingAnimation.NeedsYou
    else -> StatusRingAnimation.Static
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
    sinceMs?.let { dev.scoutr.app.ui.relativeTime(it, nowMs = nowMs) }

@Composable
private fun rememberBoardViewModel(): BoardViewModel {
    return viewModel(
        factory = viewModelFactory<BoardViewModel> { app ->
            BoardViewModel(app.container.bridge, app.container.connectionStore)
        },
    )
}
