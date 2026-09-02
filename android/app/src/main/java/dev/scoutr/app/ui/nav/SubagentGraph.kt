package dev.scoutr.app.ui.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.scoutr.app.AppContainer
import dev.scoutr.app.data.encode
import dev.scoutr.app.state.SubagentProgressViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.HostUnavailableScreen
import dev.scoutr.app.ui.screens.SubagentProgressScreen

/** Full-window PI-workflow progress; not a shell route. */
internal fun NavGraphBuilder.subagentDestination(
    navController: NavHostController,
    container: AppContainer,
) {
    composable(
        route = AppRoutes.SUBAGENT_PROGRESS,
        arguments = listOf(
            navArgument(AppRoutes.SubagentArgs.RUN_ID) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(AppRoutes.SubagentArgs.HOST_PROFILE) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val profile = backStackEntry.routeProfile(AppRoutes.SubagentArgs.HOST_PROFILE)
        val registryState by container.hostRegistry.states.collectAsState()
        val connectionRevision = registryState.connectionRevision(profile)
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        val runId = backStackEntry.arguments?.getString(AppRoutes.SubagentArgs.RUN_ID) ?: ""
        if (profile == null || api == null) {
            HostUnavailableScreen(hostUnavailableReason(profile))
            return@composable
        }
        val progressViewModel: SubagentProgressViewModel = viewModel(
            factory = viewModelFactory<SubagentProgressViewModel> {
                SubagentProgressViewModel(api, runId)
            },
            key = "subagent_progress_${profile.encode()}_${connectionRevision}_$runId",
        )
        SubagentProgressScreen(
            viewModel = progressViewModel,
            onBack = { navController.popBackStack() },
        )
    }
}
