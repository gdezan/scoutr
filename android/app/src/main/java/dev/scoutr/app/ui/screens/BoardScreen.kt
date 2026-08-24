package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.agentDisplayTitle
import dev.scoutr.app.ui.projectFolderName
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
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scoutr.app.data.AttentionSummary
import dev.scoutr.app.data.RepoSummary
import dev.scoutr.app.ui.theme.DiffPalette
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.AgentStatus
import dev.scoutr.app.state.BoardUiState
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.HostAvailability
import dev.scoutr.app.state.HostedSession
import dev.scoutr.app.ui.components.HostFilterOption
import dev.scoutr.app.ui.components.HostFilterSelector
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
 *
 * Multi-host: every row travels as a [HostedSession] bound to its own host, so
 * open / review / close / answer always land on the bridge that owns the pane.
 * A blocked host (incompatible / identity-changed) never renders rows; it gets
 * a compact issue card instead.
 */
@Composable
fun BoardScreen(
    onOpenAgent: (HostedSession) -> Unit = {},
    viewModel: BoardViewModel,
    modifier: Modifier = Modifier,
    onReviewAgent: (HostedSession) -> Unit = {},
    onCloseAgent: (HostedSession) -> Unit = {},
    onResolveCompatibility: () -> Unit = {},
    onQuickAnswer: (HostedSession, String) -> Unit = { _, _ -> },
    onRetryMigration: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsState()

    // The board poll runs only while the board is STARTED.
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
    var pendingClose by remember { mutableStateOf<HostedSession?>(null) }

    // Spinner only while nothing is paired yet; once hosts exist, issue
    // cards and per-host states take over (a blocked board must not read as
    // an endless load).
    if (ui.registryOrder.isEmpty() || (ui.statuses.isEmpty() && ui.hostBoards.isEmpty())) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading agents…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    pendingClose?.let { agent ->
        ConfirmDialog(
            title = "Close agent?",
            text = "Closing “${agent.session.cardTitle()}” stops its live pane. " +
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
                boardListContent(
                    ui = ui,
                    compact = false,
                    reduceMotion = reduceMotion,
                    selectedPaneId = null,
                    filterOptions = ui.filterOptions(),
                    selectedHostId = ui.filter,
                    onSelectHost = viewModel::selectFilter,
                    onOpenAgent = onOpenAgent,
                    onReviewAgent = onReviewAgent,
                    onCloseAgent = { pendingClose = it },
                    onQuickAnswer = onQuickAnswer,
                    onRetryHost = viewModel::retryHost,
                    onRefreshAll = { onRetryMigration(); viewModel.refreshBoard() },
                    onResolveCompatibility = onResolveCompatibility,
                )
            }
        }
    }
}

/** All + one chip per registered host, in registry order. */
private fun BoardUiState.filterOptions(): List<HostFilterOption> = buildList {
    if (registryOrder.size > 1) add(HostFilterOption(null, "All", null))
    registryOrder.forEach { hostId ->
        add(HostFilterOption(hostId, aliases[hostId] ?: hostId, statuses[hostId]))
    }
}

/**
 * The board's list body: the shared host-scope selector, blocked-host issue
 * cards, banners, the empty state, and the five status sections. [BoardScreen]
 * and the wide-window session panel share it and supply their own
 * PullToRefreshBox and LazyColumn, because their padding differs — the Board
 * clears its own FAB, the panel clears the panel's.
 *
 * Close is not confirmed here: each caller gates it the way its own surface
 * does, so [onCloseAgent] receives the raw request.
 */
internal fun LazyListScope.boardListContent(
    ui: BoardUiState,
    compact: Boolean,
    reduceMotion: Boolean,
    selectedPaneId: String?,
    filterOptions: List<HostFilterOption>,
    selectedHostId: String?,
    onSelectHost: (String?) -> Unit,
    onOpenAgent: (HostedSession) -> Unit,
    onReviewAgent: (HostedSession) -> Unit,
    onCloseAgent: (HostedSession) -> Unit,
    onQuickAnswer: (HostedSession, String) -> Unit,
    onRetryHost: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onResolveCompatibility: () -> Unit,
) {
    if (filterOptions.size > 1) {
        item(key = "host_filter") {
            HostFilterSelector(
                options = filterOptions,
                selectedHostId = selectedHostId,
                onSelect = onSelectHost,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }

    // Blocked hosts surface as compact cards instead of silently vanishing;
    // their rows are excluded from the merge, so without this they would only
    // be visible as missing data.
    ui.hostIssues.forEach { issue ->
        item(key = "issue_${issue.hostId}") {
            HostIssueCard(
                message = issue.message,
                reportedHostId = issue.reportedHostId,
                alias = issue.alias,
                onRetry = { onRetryHost(issue.hostId) },
                onOpenSettings = onResolveCompatibility,
            )
        }
    }

    if (ui.hostIssues.isEmpty() && !ui.connected) {
        item {
            DisconnectedBanner(
                error = ui.transientError ?: ui.migrationMessage,
                onRetry = onRefreshAll,
            )
        }
    }

    val rows = ui.hostedSessions
    if (rows.isEmpty()) {
        if (ui.hostIssues.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectedHostId == null) "No agents running" else "No agents on this host",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        fun section(status: AgentStatus) =
            rows.filter { AgentStatus.fromWire(it.session.status) == status }
        // Only needs-you cards carry quick answers; the other sections take
        // the default no-op, as they did before the body was shared.
        boardSection("Needs you", section(AgentStatus.NeedsYou), ui, compact, selectedPaneId, onOpenAgent, reduceMotion, onReviewAgent, onCloseAgent, onQuickAnswer)
        boardSection("Working", section(AgentStatus.Working), ui, compact, selectedPaneId, onOpenAgent, reduceMotion, onReviewAgent, onCloseAgent)
        boardSection("Done", section(AgentStatus.Done), ui, compact, selectedPaneId, onOpenAgent, reduceMotion, onReviewAgent, onCloseAgent)
        boardSection("Idle", section(AgentStatus.Idle), ui, compact, selectedPaneId, onOpenAgent, reduceMotion, onReviewAgent, onCloseAgent)
        boardSection("Other", section(AgentStatus.Unknown), ui, compact, selectedPaneId, onOpenAgent, reduceMotion, onReviewAgent, onCloseAgent)
    }
    item { Spacer(Modifier.height(24.dp)) }
}

/**
 * One blocked host, compactly: what failed, whose identity answered when it is
 * an identity change, and the two ways out — retry the probe, or manage the
 * host in Settings.
 */
@Composable
private fun HostIssueCard(
    message: String,
    reportedHostId: String?,
    alias: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$alias is unavailable",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    message,
                    reportedHostId?.let { "Bridge now reports id $it" },
                ).joinToString(" "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Text(
                    "Retry",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .testTag("board_compatibility_retry")
                        .clickable(onClick = onRetry)
                        .padding(top = 8.dp, end = 16.dp, bottom = 2.dp),
                )
                Text(
                    "Manage in Settings",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .testTag("board_compatibility_settings")
                        .clickable(onClick = onOpenSettings)
                        .padding(top = 8.dp, end = 8.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** Adds a section header + its agent cards to the LazyList. */
private fun LazyListScope.boardSection(
    title: String,
    agents: List<HostedSession>,
    ui: BoardUiState,
    compact: Boolean,
    selectedPaneId: String?,
    onOpenAgent: (HostedSession) -> Unit,
    reduceMotion: Boolean,
    onReviewAgent: (HostedSession) -> Unit,
    onCloseAgent: (HostedSession) -> Unit,
    onQuickAnswer: (HostedSession, String) -> Unit = { _, _ -> },
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
    // Host-qualified keys: identical pane ids on two bridges are distinct rows.
    items(agents, key = { it.profile.hostId + ":" + requireNotNull(it.session.live).paneId }) { agent ->
        // Per-host context rendered on the card itself: the alias only under
        // the All scope, and a stale marker when that host's snapshot can no
        // longer be refreshed.
        val hostLabel = ui.hostLabelFor(agent.profile.hostId)
        val staleLine = when (val availability = ui.statuses[agent.profile.hostId]) {
            is HostAvailability.Offline -> {
                val syncedAt = ui.hostBoards[agent.profile.hostId]?.fetchedAtMs
                if (syncedAt != null) {
                    "Offline · last synced ${dev.scoutr.app.ui.relativeTime(syncedAt.toDouble())}"
                } else {
                    "Offline"
                }
            }
            else -> null
        }
        val paneId = agent.session.live?.paneId.orEmpty()
        val answering = paneId.isNotEmpty() &&
            dev.scoutr.app.data.HostPaneKey(agent.profile, paneId) in ui.quickAnswering
        AgentCardRow(
            agent,
            hostLabel = hostLabel,
            staleLine = staleLine,
            answering = answering,
            onClick = { onOpenAgent(agent) },
            compact = compact,
            selected = selectedPaneId != null && selectedPaneId == agent.session.live?.paneId,
            onReview = { onReviewAgent(agent) },
            onClose = { onCloseAgent(agent) },
            onQuickAnswer = { label -> onQuickAnswer(agent, label) },
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
internal fun SessionDescriptor.cardTitle(): String =
    agentDisplayTitle(title).takeIf { it.isNotBlank() } ?: agentKind

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

/**
 * A board card. The compact form is the 320dp session panel's row: same
 * anatomy, one line of activity, and no swipe-to-reveal — 156dp of reveal does
 * not fit a 320dp column, so Review / Copy path / Close stay in the overflow
 * menu only.
 */
@Composable
private fun AgentCardRow(
    agent: HostedSession,
    hostLabel: String?,
    staleLine: String?,
    answering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selected: Boolean = false,
    onReview: () -> Unit = {},
    onClose: () -> Unit = {},
    onQuickAnswer: (String) -> Unit = {},
) {
    val session = agent.session
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptic()
    val context = LocalContext.current
    val copyPath = {
        clipboard.setText(AnnotatedString(session.cwd ?: session.live?.workspaceId.orEmpty()))
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
    if (compact) {
        AgentCardBody(
            agent = agent,
            actions = actions,
            selected = selected,
            compact = true,
            hostLabel = hostLabel,
            staleLine = staleLine,
            answering = answering,
            onClick = onClick,
            onReview = onReview,
            onQuickAnswer = onQuickAnswer,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .testTag("panel_agent_card_${session.live?.paneId}"),
        )
        return
    }

    // A drag anchor that can never be reached is dead state, so the reveal is
    // built only on the path that renders it.
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
                        .testTag("board_action_${action.key}_${session.live?.paneId}"),
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
        AgentCardBody(
            agent = agent,
            actions = actions,
            selected = selected,
            compact = false,
            hostLabel = hostLabel,
            staleLine = staleLine,
            answering = answering,
            onClick = { if (reveal.currentValue == BoardReveal.Open) closeReveal() else onClick() },
            onReview = onReview,
            onQuickAnswer = onQuickAnswer,
            modifier = Modifier
                .offset { IntOffset(reveal.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(reveal, reverseDirection = false, orientation = Orientation.Horizontal)
                .fillMaxWidth()
                .testTag("agent_card_${session.live?.paneId}"),
        )
    }
}

/**
 * The card itself: ring, title, activity, needs-you block, machine facts, time
 * in state and the overflow menu. The caller supplies the outer [modifier], so
 * the full-window row hangs its swipe offset there and the compact row does
 * not have to carry one.
 */
@Composable
private fun AgentCardBody(
    agent: HostedSession,
    actions: List<BoardAction>,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onReview: () -> Unit,
    onQuickAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
    hostLabel: String? = null,
    staleLine: String? = null,
    answering: Boolean = false,
) {
    val session = agent.session
    val status = AgentStatus.fromWire(session.status)
    val isNeedsYou = status == AgentStatus.NeedsYou
    val accent = statusColor(status)
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    PressTintSurface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        // Selection is a surface step, never a border: the needs-you border
        // stays status-owned.
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainer,
        pressedColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.semantics { this.selected = selected },
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
                        AgentMark(session.agentKind)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = session.cardTitle(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // A needs-you agent's question is prose addressed to the
                    // user; everything else is the machine's own last move.
                    session.latestActivity?.takeIf { it.isNotBlank() }?.let { activity ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isNeedsYou) activity else "▸ $activity",
                            style = if (isNeedsYou) MaterialTheme.typography.bodySmall else ScoutrType.monoMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = if (isNeedsYou) 1f else 0.65f),
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Deterministic git evidence: what changed and anything
                    // obviously risky about the repo state — final for a done
                    // agent, as-of-last-refresh for a running one.
                    val cardSummary = if (status == AgentStatus.Done) {
                        session.doneSummary?.let { it to false }
                    } else {
                        session.liveSummary?.let { it to true }
                    }
                    cardSummary?.let { (summary, live) ->
                        CardSummaryBlock(
                            summary = summary,
                            paneId = session.live?.paneId.orEmpty(),
                            live = live,
                            onReview = onReview,
                        )
                    }
                    if (isNeedsYou) {
                        AttentionBlock(
                            attention = session.attention,
                            paneId = session.live?.paneId.orEmpty(),
                            cardTitle = session.cardTitle(),
                            busy = answering,
                            onQuickAnswer = onQuickAnswer,
                            onOpen = onClick,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // Machine facts: which project, on which model. The project
                    // name is what the user recognises the card by, so it reads
                    // above the transcript line's weight, not below it.
                    val machineFacts = buildList {
                        add(projectFolderName(session.cwd) ?: session.live?.workspaceId.orEmpty())
                        hostLabel?.let(::add)
                        session.model?.let { add(shortModel(it)) }
                    }.joinToString(" · ")
                    Text(
                        text = machineFacts,
                        style = ScoutrType.monoMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Only shown while that host's snapshot cannot refresh:
                    // the row itself is deliberately kept rather than dropped.
                    staleLine?.let { stale ->
                        Text(
                            text = stale,
                            style = ScoutrType.monoMeta,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                TimeInState(status, session.statusSinceMs ?: session.updatedAtMs)
                Box {
                    androidx.compose.material3.IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("agent_actions_${session.live?.paneId}")
                            .semantics { contentDescription = "Agent actions for ${session.cardTitle()}" },
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
                                modifier = Modifier.testTag("board_menu_${action.key}_${session.live?.paneId}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tracking state as git reports it: shown only when an upstream exists and
 * the branch has actually diverged, so no invented zeros pose as evidence.
 */
internal fun trackingLabel(summary: RepoSummary): String? {
    // No upstream means the counts are meaningless, so they are omitted
    // rather than invented; a known upstream always reports both counts,
    // including synchronized "0 ahead · 0 behind".
    if (summary.upstream == null) return null
    return "${summary.ahead} ahead · ${summary.behind} behind"
}

/**
 * Deterministic repo evidence on a Board card: what changed and the branch
 * state, straight from git facts. It labels facts only — never "safe" or
 * "tests passed" — and offers Review as the drill-down through an accessibility
 * action rather than making the stats individually interactive. A live card
 * says so in its metadata: its facts are as of the last refresh, not final.
 */
@Composable
private fun CardSummaryBlock(
    summary: RepoSummary,
    paneId: String,
    live: Boolean,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val truncated = summary.statusTruncated || summary.diffTruncated
    val stats = buildAnnotatedString {
        if (summary.dirty) {
            append("${summary.changedFiles} files · ")
            withStyle(SpanStyle(color = DiffPalette.Added)) { append("+${summary.additions}") }
            append(" ")
            withStyle(SpanStyle(color = DiffPalette.Deleted)) { append("−${summary.deletions}") }
            if (truncated) append(" · summary truncated")
        } else {
            append("Working tree clean")
        }
    }
    val metadata = listOfNotNull(
        if (live) "live" else null,
        summary.branch,
        if (summary.dirty) "uncommitted" else null,
        trackingLabel(summary),
    ).joinToString(" · ")
    val base = when {
        summary.dirty ->
            "${stats.text}: ${metadata ?: "no branch"}. Review changes for details."
        else -> "${stats.text}. ${trackingLabel(summary)?.let { "$it. " } ?: ""}Review changes for details."
    }
    // A live card leads with its freshness so TalkBack never reads churny
    // numbers as final.
    val description = if (live) "As of the last refresh: $base" else base
    Column(
        modifier = modifier
            .padding(top = 3.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction("Review changes") {
                        onReview()
                        true
                    },
                )
            }
            .testTag(if (live) "board_live_summary_$paneId" else "board_done_summary_$paneId"),
    ) {
        Text(
            text = stats,
            style = ScoutrType.monoMeta,
            color = metaColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (metadata.isNotEmpty()) {
            Text(
                text = metadata,
                style = ScoutrType.monoMeta,
                color = metaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What the agent is waiting for, under the card's own activity line: the open
 * question in ordinary UI type, then either up to three one-tap answers or a
 * single Open affordance. The Board stays an inbox — anything the bridge will
 * not let it submit whole goes to Chat instead.
 */
@Composable
private fun AttentionBlock(
    attention: AttentionSummary?,
    paneId: String,
    cardTitle: String,
    onQuickAnswer: (String) -> Unit,
    onOpen: () -> Unit,
    busy: Boolean = false,
) {
    if (attention == null) return
    val question = attentionQuestionText(attention)
    val options = quickAnswerOptions(attention)

    if (question != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // Presentation only: a long question is cut here, never where it
            // is answered.
            maxLines = BOARD_ATTENTION_QUESTION_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("board_attention_question_$paneId"),
        )
        attentionQuestionCountLabel(attention)?.let { count ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = count,
                style = ScoutrType.monoMeta,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("board_attention_count_$paneId"),
            )
        }
    }

    if (options.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                OutlinedButton(
                    enabled = !busy,
                    shape = MaterialTheme.shapes.small,
                    // The server's own label is what travels; the button text
                    // is only what fits.
                    onClick = { onQuickAnswer(option.label) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp,
                        vertical = 4.dp,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Answer ${option.label} for $cardTitle" }
                        .testTag("board_quick_answer_${paneId}_${option.label}"),
                ) {
                    Text(
                        quickAnswerLabel(option.label),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    } else {
        Spacer(Modifier.height(2.dp))
        TextButton(
            onClick = onOpen,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 6.dp,
                vertical = 2.dp,
            ),
            modifier = Modifier
                .semantics { contentDescription = attentionOpenDescription(attention, cardTitle) }
                .testTag("board_attention_open_$paneId"),
        ) {
            Text("Open", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** How many lines of the open question a card is allowed to spend. */
internal const val BOARD_ATTENTION_QUESTION_LINES = 2

/** The most one-tap answers a card will ever show. */
internal const val BOARD_QUICK_ANSWER_OPTIONS = 3

/** How much of an option label fits on a compact board control. */
internal const val BOARD_QUICK_ANSWER_LABEL_CHARS = 18

/**
 * The line that says what the agent is asking. A prompt-kind attention has no
 * structured question, so the card's latest activity stays the only preview
 * rather than the Board inventing one.
 */
internal fun attentionQuestionText(attention: AttentionSummary?): String? {
    val question = attention?.takeIf { it.isAsk }?.currentQuestion ?: return null
    return question.question.trim().ifBlank { question.header.trim() }.takeIf { it.isNotEmpty() }
}

/** `N questions` when the open round holds more than the one being previewed. */
internal fun attentionQuestionCountLabel(attention: AttentionSummary?): String? =
    attention?.questionCount?.takeIf { attention.isAsk && it > 1 }?.let { "$it questions" }

/**
 * The options the Board may offer as one-tap answers. The bridge decides
 * whether a tap submits the whole ask; the Board additionally refuses anything
 * it cannot draw as a small bounded row of controls.
 */
internal fun quickAnswerOptions(attention: AttentionSummary?): List<QuestionOption> {
    val summary = attention?.takeIf { it.isAsk && it.canQuickAnswer } ?: return emptyList()
    val question = summary.currentQuestion ?: return emptyList()
    if (summary.questionCount > 1 || question.multiSelect) return emptyList()
    val options = question.options.filter { it.label.isNotBlank() }
    if (options.isEmpty() || options.size > BOARD_QUICK_ANSWER_OPTIONS) return emptyList()
    return options
}

/**
 * Display-only shortening for a quick-answer control. The answer that is sent
 * always carries the server's exact label, never this string.
 */
internal fun quickAnswerLabel(label: String, max: Int = BOARD_QUICK_ANSWER_LABEL_CHARS): String {
    val trimmed = label.trim()
    if (trimmed.length <= max) return trimmed
    return trimmed.take(max - 1).trimEnd() + "…"
}

/** What a screen reader hears on the Open affordance, since "Open" alone says nothing. */
internal fun attentionOpenDescription(attention: AttentionSummary?, cardTitle: String): String = when {
    attention == null || !attention.isAsk -> "Open $cardTitle in chat to respond"
    attention.questionCount > 1 -> "Open $cardTitle in chat to answer ${attention.questionCount} questions"
    else -> "Open $cardTitle in chat to answer"
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
