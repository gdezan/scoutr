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
import dev.scoutr.app.state.FileBrowserViewModel
import dev.scoutr.app.state.FileViewerViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.FileBrowserScreen
import dev.scoutr.app.ui.screens.FileViewerScreen
import dev.scoutr.app.ui.screens.HostUnavailableScreen

/** Browser and viewer routes are immutable host destinations. */
internal fun NavGraphBuilder.fileDestinations(
    navController: NavHostController,
    container: AppContainer,
) {
    composable(
        route = AppRoutes.FILE_BROWSER,
        arguments = listOf(
            navArgument(AppRoutes.FileArgs.CWD) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(AppRoutes.FileArgs.HOST_PROFILE) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val profile = backStackEntry.routeProfile(AppRoutes.FileArgs.HOST_PROFILE)
        val registryState by container.hostRegistry.states.collectAsState()
        val connectionRevision = registryState.connectionRevision(profile)
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        val cwd = backStackEntry.arguments?.getString(AppRoutes.FileArgs.CWD) ?: ""
        if (profile == null || api == null) {
            HostUnavailableScreen(hostUnavailableReason(profile))
            return@composable
        }
        val browserViewModel: FileBrowserViewModel = viewModel(
            factory = viewModelFactory<FileBrowserViewModel> { FileBrowserViewModel(api, cwd) },
            key = "file_browser_${profile.encode()}_${connectionRevision}_$cwd",
        )
        FileBrowserScreen(
            viewModel = browserViewModel,
            onBack = { navController.popBackStack() },
            onOpenFile = { file -> navController.navigate(AppRoutes.fileViewer(profile, cwd, file)) },
        )
    }

    composable(
        route = AppRoutes.FILE_VIEWER,
        arguments = listOf(
            navArgument(AppRoutes.FileArgs.CWD) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(AppRoutes.FileArgs.FILE) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(AppRoutes.FileArgs.HOST_PROFILE) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val profile = backStackEntry.routeProfile(AppRoutes.FileArgs.HOST_PROFILE)
        val registryState by container.hostRegistry.states.collectAsState()
        val connectionRevision = registryState.connectionRevision(profile)
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        val cwd = backStackEntry.arguments?.getString(AppRoutes.FileArgs.CWD) ?: ""
        val file = backStackEntry.arguments?.getString(AppRoutes.FileArgs.FILE) ?: ""
        if (profile == null || api == null) {
            HostUnavailableScreen(hostUnavailableReason(profile))
            return@composable
        }
        val viewerViewModel: FileViewerViewModel = viewModel(
            factory = viewModelFactory<FileViewerViewModel> {
                FileViewerViewModel(api, cwd, file, container.viewerImageCacheDir, profile.encode())
            },
            key = "file_viewer_${profile.encode()}_${connectionRevision}_$cwd/$file",
        )
        FileViewerScreen(
            viewModel = viewerViewModel,
            onBack = { navController.popBackStack() },
        )
    }
}
