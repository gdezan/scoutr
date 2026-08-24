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
    container: dev.scoutr.app.AppContainer,
    openReview: (profile: dev.scoutr.app.data.HostProfileKey, cwd: String) -> Unit,
    markHostUsed: (dev.scoutr.app.data.HostProfileKey) -> Unit = {},
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
            navArgument(AppRoutes.ChatArgs.HOST_PROFILE) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val profile = backStackEntry.routeProfile(AppRoutes.ChatArgs.HOST_PROFILE)
        val registryState by container.hostRegistry.states.collectAsState()
        val connectionRevision = registryState.connectionRevision(profile)
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        if (profile == null || api == null) {
            dev.scoutr.app.ui.screens.HostUnavailableScreen(hostUnavailableReason(profile))
            return@composable
        }
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
                    bridge = api,
                    initialKey = initialKey,
                    bootstrapPaneId = bootstrapPaneId,
                    agentStatus = agentStatus,
                    performanceCounters = app.container.performanceCounters,
                    savedState = savedState,
                    hostProfileKey = profile,
                )
            },
            key = "chat_${profile.encode()}_${connectionRevision}_${initialKey?.encode() ?: "bootstrap_$bootstrapPaneId"}",
        )
        val chatUi by chatViewModel.ui.collectAsState()
        LaunchedEffect(initialKey, chatUi.sessionKey) {
            val converged = chatUi.sessionKey
            if (initialKey == null && converged != null) {
                navController.navigate(AppRoutes.chat(profile, converged, chatUi.agentStatus)) {
                    popUpTo(backStackEntry.destination.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        ChatScreen(
            viewModel = chatViewModel,
            onBack = { navController.popBackStack() },
            onOpenTerminal = {
                markHostUsed(profile)
                chatUi.livePaneId?.let { navController.navigate(AppRoutes.terminal(profile, it)) }
            },
            onOpenFiles = {
                markHostUsed(profile)
                navController.navigate(AppRoutes.fileBrowser(profile, it))
            },
            onOpenReview = { cwd -> openReview(profile, cwd) },
        )
    }
}
