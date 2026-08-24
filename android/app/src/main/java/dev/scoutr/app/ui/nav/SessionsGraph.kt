package dev.scoutr.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.scoutr.app.AppContainer
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.encode
import dev.scoutr.app.state.SessionHistoryViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.HistoryScreen
import dev.scoutr.app.ui.screens.HostUnavailableScreen

/** Sessions remains hostless in the shell and binds the persistent default once on entry. */
internal fun NavGraphBuilder.sessionsDestination(
    navController: NavHostController,
    container: AppContainer,
    isWide: Boolean,
    openPalette: () -> Unit,
    openReview: (profile: HostProfileKey, cwd: String) -> Unit,
    markHostUsed: (HostProfileKey) -> Unit = {},
) {
    composable(Destination.Sessions.route) {
        // Sessions is a hostless shell entry over per-host workers: one VM for
        // the whole registry, cache-first, with the shared host filter. It no
        // longer binds a single default profile at entry.
        val hasHosts = container.hostRegistry.snapshot().profiles.isNotEmpty()
        if (!hasHosts) {
            HostUnavailableScreen(if (container.hostRegistry.snapshot().pendingLegacyConnection) {
                "Checking the saved bridge…"
            } else "No bridge is paired yet")
            return@composable
        }
        val historyViewModel: SessionHistoryViewModel = viewModel(
            factory = viewModelFactory<SessionHistoryViewModel> { appInstance ->
                SessionHistoryViewModel(
                    hostClients = appInstance.container.hostClients,
                    registry = appInstance.container.hostRegistry,
                    currentBinding = appInstance.container::currentHostBinding,
                    work = appInstance.container.hostWorkCoordinator,
                    hostStatus = appInstance.container.hostStatus,
                    snapshots = appInstance.container.sessionSnapshots,
                    catalogStore = appInstance.container.sessionCatalogStore,
                    hostFilter = appInstance.container.hostFilter,
                    adoptLegacyMetadata = appInstance.container.migration::adoptPendingMetadata,
                )
            },
            key = "sessions_history",
        )
        TabScaffold(
            title = "Sessions",
            onSearch = openPalette,
            ownsBottomInset = isWide,
        ) { innerSessions ->
            HistoryScreen(
                onOpenSession = { resumed ->
                    // The resumed session names its own host; the VM resolved
                    // it from the row, so there is no default-host fallback.
                    val target = requireNotNull(resumed.profile)
                    markHostUsed(target)
                    navController.navigateToChat(
                        target,
                        resumed.key,
                        resumed.bootstrapPaneId,
                        AppRoutes.ChatArgs.DEFAULT_STATUS,
                    )
                },
                onReview = { item ->
                    val profileKey = historyViewModel.ui.value.profiles[item.hostId]
                    if (profileKey != null) openReview(profileKey, item.session.cwd)
                },
                viewModel = historyViewModel,
                modifier = Modifier.padding(innerSessions),
            )
        }
    }
}
