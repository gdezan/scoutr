package dev.scoutr.app.ui.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.scoutr.app.data.decodeSessionKey
import dev.scoutr.app.data.encode
import dev.scoutr.app.state.ChatViewModel
import dev.scoutr.app.state.savedStateViewModelFactory
import dev.scoutr.app.ui.screens.ChatScreen

/**
 * The chat destination. A session enters by canonical key, or by its live
 * pane's id when only that is known (fresh launches, new sessions, legacy
 * notification links); once the ViewModel converges on the canonical key the
 * back-stack entry is rewritten in place.
 */
internal fun NavGraphBuilder.chatDestination(
    navController: NavHostController,
    openReview: (cwd: String) -> Unit,
) {
    composable(
        route = AppRoutes.CHAT,
        arguments = listOf(
            navArgument(AppRoutes.ChatArgs.SESSION_KEY) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(AppRoutes.ChatArgs.BOOTSTRAP_PANE_ID) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(AppRoutes.ChatArgs.STATUS) {
                type = NavType.StringType
                defaultValue = AppRoutes.ChatArgs.DEFAULT_STATUS
            },
        ),
    ) { backStackEntry ->
        val initialKey = decodedChatSessionKey(
            backStackEntry.arguments?.getString(AppRoutes.ChatArgs.SESSION_KEY),
        )
        val bootstrapPaneId = backStackEntry.arguments?.getString(AppRoutes.ChatArgs.BOOTSTRAP_PANE_ID)
            ?.takeIf(String::isNotBlank)
        val agentStatus = backStackEntry.arguments?.getString(AppRoutes.ChatArgs.STATUS)
            ?: AppRoutes.ChatArgs.DEFAULT_STATUS
        val chatViewModel: ChatViewModel = viewModel(
            // Saved state, not just the view model scope: a half-filled ask
            // round has to survive process death, not only rotation.
            factory = savedStateViewModelFactory<ChatViewModel> { app, savedState ->
                ChatViewModel(
                    app.container.bridge,
                    initialKey,
                    bootstrapPaneId,
                    agentStatus,
                    app.container.performanceCounters,
                    savedState,
                )
            },
            key = "chat_${initialKey?.encode() ?: "bootstrap_$bootstrapPaneId"}",
        )
        val chatUi by chatViewModel.ui.collectAsState()
        LaunchedEffect(initialKey, chatUi.sessionKey) {
            val converged = chatUi.sessionKey
            if (initialKey == null && converged != null) {
                navController.navigate(AppRoutes.chat(converged, chatUi.agentStatus)) {
                    popUpTo(backStackEntry.destination.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        ChatScreen(
            viewModel = chatViewModel,
            onBack = { navController.popBackStack() },
            onOpenTerminal = {
                chatUi.livePaneId?.let { navController.navigate(AppRoutes.terminal(it)) }
            },
            onOpenFiles = { cwd -> navController.navigate(AppRoutes.fileBrowser(cwd)) },
            onOpenReview = openReview,
        )
    }
}
