package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.theme.ScoutrSpace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.encode
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.HostedSession
import dev.scoutr.app.ui.components.AppTopBar
import dev.scoutr.app.ui.components.ConfirmDialog
import dev.scoutr.app.ui.components.PullRefreshIndicator
import dev.scoutr.app.ui.components.pullRefreshSemantics
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import dev.scoutr.app.ui.motion.useReduceMotion
import dev.scoutr.app.ui.nav.DestinationNavRow

/**
 * Which session the detail pane is showing, read off the back stack rather
 * than stored. A chat entered by bootstrap has no `sessionKey` until the route
 * rewrites on convergence, so both fields matter: without the pane id a freshly
 * created session would show no highlight until then.
 */
data class PanelSelection(val sessionKey: String? = null, val paneId: String? = null)

/**
 * The wide window's left panel: the live board list at 320dp with the
 * destination row anchored at its foot, on every shell destination. The row
 * replaces the old full-width bottom bar, so nothing sits beneath the panes
 * and the detail pane keeps every vertical dp. It does not follow the
 * destination — Sessions' history
 * stays in the detail pane — so the list remains the app's standing view of
 * what is running.
 *
 * It owns the board poll whenever it is on screen. Exactly one of this and
 * [BoardScreen] is composed at a time, and `startPolling`/`stopPolling` are
 * lifecycle-guarded, so a brief overlap across a fold cannot double-start it.
 */
@Composable
fun SessionPanel(
    viewModel: BoardViewModel,
    selection: PanelSelection?,
    onOpenSession: (HostedSession) -> Unit,
    onOpenSubagent: (HostedSession, String) -> Unit = { _, _ -> },
    onReviewAgent: (HostedSession) -> Unit,
    onCloseAgent: (HostedSession) -> Unit,
    onQuickAnswer: (HostedSession, String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
    onTerminal: () -> Unit,
    onResolveCompatibility: () -> Unit,
    onRetryMigration: () -> Unit = {},
    currentRoute: String? = null,
    onSelectDestination: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()

    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val haptic = rememberHaptic()
    LaunchedEffect(ui.board.needsYou.size) {
        if (ui.board.needsYou.isNotEmpty()) haptic(HapticEvent.NeedsYou)
    }

    // Close stops a live pane, so the panel confirms it exactly as the Board
    // and Sessions do.
    var pendingClose by remember { mutableStateOf<HostedSession?>(null) }
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

    val compatible = ui.hasCompatibleHost
    val reduceMotion = useReduceMotion()
    val refreshState = rememberPullToRefreshState()

    Column(modifier.testTag("board_session_panel")) {
        AppTopBar(
            title = "Board",
            showLockup = true,
            onTerminal = onTerminal.takeIf { compatible },
            onSettings = onSettings,
        )
        // weight, not fillMaxSize: fillMaxSize resolves to the whole window
        // height and pushes the list — and its FAB — below the header.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Spinner only while nothing is paired yet; once hosts exist, issue
    // cards and per-host states take over (a blocked board must not read as
    // an endless load).
    if (ui.registryOrder.isEmpty() || (ui.statuses.isEmpty() && ui.hostBoards.isEmpty())) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading agents…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = ui.isRefreshing,
                    onRefresh = viewModel::refreshBoard,
                    modifier = Modifier
                        .fillMaxSize()
                        .pullRefreshSemantics(viewModel::refreshBoard)
                        .testTag("panel_refresh_root"),
                    state = refreshState,
                    indicator = { PullRefreshIndicator(refreshState, ui.isRefreshing) },
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = ScoutrSpace.md),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        // Clear the panel's own FAB.
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        boardListContent(
                            ui = ui,
                            compact = true,
                            reduceMotion = reduceMotion,
                            selectedPaneId = selectedPaneId(ui.board.sessions, selection),
                            filterOptions = panelFilterOptions(ui),
                            selectedHostId = ui.filter,
                            onSelectHost = viewModel::selectFilter,
                            onOpenAgent = onOpenSession,
                            onOpenSubagent = onOpenSubagent,
                            onReviewAgent = onReviewAgent,
                            onCloseAgent = { pendingClose = it },
                            onQuickAnswer = onQuickAnswer,
                            onRetryHost = viewModel::retryHost,
                            onRefreshAll = {
                                onRetryMigration()
                                viewModel.refreshBoard()
                            },
                            onResolveCompatibility = onResolveCompatibility,
                        )
                    }
                }
            }
            if (compatible) {
                FloatingActionButton(
                    onClick = onNewSession,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // The destination row at the panel's foot clears the
                        // nav bar; the FAB keeps only its 16dp stand-off.
                        .padding(ScoutrSpace.lg)
                        .testTag("panel_new_session"),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New session")
                }
            }
        }
        // The wide window's navigation: same row the compact bottom bar
        // renders, anchored at the panel's foot so the detail pane keeps its
        // full height. It owns the panel's nav-bar clearance.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DestinationNavRow(
            currentRoute = currentRoute,
            needsYouCount = ui.board.needsYou.size,
            onSelect = onSelectDestination,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        )
    }
}

/**
 * The pane the panel highlights: the canonical key wins, and the bootstrap
 * pane id covers a session whose route has not converged yet.
 */
private fun selectedPaneId(
    sessions: List<SessionDescriptor>,
    selection: PanelSelection?,
): String? {
    if (selection == null) return null
    val byKey = selection.sessionKey?.let { key ->
        sessions.firstOrNull { it.key?.encode() == key }?.live?.paneId
    }
    return byKey ?: selection.paneId
}

/** All + one chip per registered host, mirroring the Board's selector. */
private fun panelFilterOptions(ui: dev.scoutr.app.state.BoardUiState): List<dev.scoutr.app.ui.components.HostFilterOption> = buildList {
    if (ui.registryOrder.size > 1) add(dev.scoutr.app.ui.components.HostFilterOption(null, "All", null))
    ui.registryOrder.forEach { hostId ->
        add(dev.scoutr.app.ui.components.HostFilterOption(hostId, ui.aliases[hostId] ?: hostId, ui.statuses[hostId]))
    }
}
