package dev.scoutr.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.scoutr.app.AppContainer
import dev.scoutr.app.state.SessionHistoryViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.HistoryScreen

/** The Sessions tab: the resumable session catalog with its per-row review action. */
internal fun NavGraphBuilder.sessionsDestination(
    navController: NavHostController,
    container: AppContainer,
    isWide: Boolean,
    openPalette: () -> Unit,
    openReview: (cwd: String) -> Unit,
) {
    composable(Destination.Sessions.route) {
        val historyViewModel: SessionHistoryViewModel = viewModel(
            factory = viewModelFactory<SessionHistoryViewModel> { app ->
                SessionHistoryViewModel(
                    app.container.bridge,
                    app.container.connectionStore,
                    app.container.sessionCatalogStore,
                )
            },
        )
        TabScaffold(
            title = "Sessions",
            onSearch = openPalette,
            ownsBottomInset = isWide,
        ) { innerSessions ->
            HistoryScreen(
                onOpenSession = { resumed ->
                    navController.navigateToChat(resumed.key, resumed.bootstrapPaneId, AppRoutes.ChatArgs.DEFAULT_STATUS)
                },
                onReview = { item -> openReview(item.session.cwd) },
                viewModel = historyViewModel,
                modifier = Modifier.padding(innerSessions),
            )
        }
    }
}
