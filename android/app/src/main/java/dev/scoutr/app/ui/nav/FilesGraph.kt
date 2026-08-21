package dev.scoutr.app.ui.nav

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.scoutr.app.AppContainer
import dev.scoutr.app.state.FileBrowserViewModel
import dev.scoutr.app.state.FileViewerViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.FileBrowserScreen
import dev.scoutr.app.ui.screens.FileViewerScreen

/**
 * The file browser and file viewer, reached from Chat. Both routes carry an
 * encoded cwd; the viewer adds the encoded file name.
 */
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
        ),
    ) { backStackEntry ->
        val cwd = backStackEntry.arguments?.getString(AppRoutes.FileArgs.CWD) ?: ""
        val browserViewModel: FileBrowserViewModel = viewModel(
            factory = viewModelFactory<FileBrowserViewModel> { app ->
                FileBrowserViewModel(app.container.bridge, cwd)
            },
            key = "file_browser_$cwd",
        )
        FileBrowserScreen(
            viewModel = browserViewModel,
            onBack = { navController.popBackStack() },
            onOpenFile = { file -> navController.navigate(AppRoutes.fileViewer(cwd, file)) },
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
        ),
    ) { backStackEntry ->
        val cwd = backStackEntry.arguments?.getString(AppRoutes.FileArgs.CWD) ?: ""
        val file = backStackEntry.arguments?.getString(AppRoutes.FileArgs.FILE) ?: ""
        val viewerViewModel: FileViewerViewModel = viewModel(
            factory = viewModelFactory<FileViewerViewModel> { app ->
                FileViewerViewModel(app.container.bridge, cwd, file)
            },
            key = "file_viewer_$cwd/$file",
        )
        FileViewerScreen(
            viewModel = viewerViewModel,
            onBack = { navController.popBackStack() },
        )
    }
}
