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
        val registryState by container.hostRegistry.states.collectAsState()
        val profile = registryState.defaultHostId?.let { id ->
            registryState.profiles.firstOrNull { it.hostId == id }
        }?.let { HostProfileKey(it.hostId, it.profileGeneration) }
        val connectionRevision = registryState.connectionRevision(profile)
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        if (profile == null || api == null) {
            HostUnavailableScreen(if (container.hostRegistry.snapshot().pendingLegacyConnection) {
                "Checking the saved bridge…"
            } else hostUnavailableReason(profile))
            return@composable
        }
        val historyViewModel: SessionHistoryViewModel = viewModel(
            factory = viewModelFactory<SessionHistoryViewModel> {
                SessionHistoryViewModel(
                    bridge = api,
                    profile = profile,
                    store = it.container.sessionCatalogStore,
                    adoptLegacyMetadata = it.container.migration::adoptPendingMetadata,
                )
            },
            key = "sessions_${profile.encode()}_$connectionRevision",
        )
        TabScaffold(
            title = "Sessions",
            onSearch = openPalette,
            ownsBottomInset = isWide,
        ) { innerSessions ->
            HistoryScreen(
                onOpenSession = { resumed ->
                    val target = resumed.profile ?: profile
                    markHostUsed(target)
                    navController.navigateToChat(
                        target,
                        resumed.key,
                        resumed.bootstrapPaneId,
                        AppRoutes.ChatArgs.DEFAULT_STATUS,
                    )
                },
                onReview = { item -> openReview(profile, item.session.cwd) },
                viewModel = historyViewModel,
                modifier = Modifier.padding(innerSessions),
            )
        }
    }
}
