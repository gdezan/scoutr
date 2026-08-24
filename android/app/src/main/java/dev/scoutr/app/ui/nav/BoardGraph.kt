package dev.scoutr.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.ui.screens.BoardScreen

/**
 * The Board tab. On wide windows with a compatible bridge the panel already
 * shows the board list, so this route renders only the detail placeholder;
 * without the panel (compact, or an incompatible bridge) it stays the board
 * itself, which is what polls and what shows the banner.
 */
internal fun NavGraphBuilder.boardDestination(
    navController: NavHostController,
    boardViewModel: BoardViewModel,
    compatible: Boolean,
    showWidePlaceholder: Boolean,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
    onTerminal: (() -> Unit)?,
    openReview: (profile: HostProfileKey, cwd: String) -> Unit,
    markHostUsed: (HostProfileKey) -> Unit = {},
    onRetryMigration: () -> Unit = {},
) {
    composable(Destination.Board.route) {
        if (showWidePlaceholder) {
            BoardDetailPlaceholder()
            return@composable
        }
        TabScaffold(
            // Header composition is per-screen in the reference: the board
            // carries terminal + settings, and search belongs to Sessions
            // (§8b, §9c) — not a uniform action row on every tab.
            title = "Board",
            onSettings = onSettings,
            onTerminal = onTerminal.takeIf { compatible },
            showLockup = true,
            floatingActionButton = {
                if (compatible) {
                    FloatingActionButton(
                        onClick = onNewSession,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                }
            },
        ) { innerBoard ->
            BoardScreen(
                onOpenAgent = { agent ->
                    val session = agent.session
                    session.live?.let {
                        markHostUsed(agent.profile)
                        navController.navigateToChat(agent.profile, session.key, it.paneId, it.status)
                    }
                },
                onReviewAgent = { agent ->
                    agent.session.cwd?.let { cwd -> openReview(agent.profile, cwd) }
                },
                onCloseAgent = { agent ->
                    markHostUsed(agent.profile)
                    agent.session.live?.let {
                        boardViewModel.closeAgent(agent.profile, it.paneId)
                    }
                },
                onQuickAnswer = { agent, label ->
                    markHostUsed(agent.profile)
                    boardViewModel.quickAnswer(agent, label)
                },
                onResolveCompatibility = onSettings,
                viewModel = boardViewModel,
                onRetryMigration = onRetryMigration,
                modifier = Modifier.padding(innerBoard),
            )
        }
    }
}

/** What the detail pane shows on wide until a session is chosen. */
@Composable
private fun BoardDetailPlaceholder() {
    Box(
        Modifier.fillMaxSize().testTag("board_detail_placeholder"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Select a session",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
