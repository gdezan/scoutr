package dev.scoutr.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.scoutr.app.AppContainer
import dev.scoutr.app.state.ReviewViewModel
import dev.scoutr.app.state.TerminalViewModel
import dev.scoutr.app.state.UsageViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.ConnectScreen
import dev.scoutr.app.ui.screens.ReviewScreen
import dev.scoutr.app.ui.screens.SettingsScreen
import dev.scoutr.app.ui.screens.UsageScreen
import dev.scoutr.app.ui.screens.terminal.TerminalScreen

/**
 * The full-window utility destinations: pairing, usage, review, the global
 * terminal, and settings. None of them keep the wide shell; each owns the
 * whole window.
 */

/** Shown until a bridge is paired. [onPaired] rebuilds the graph around Board. */
internal fun NavGraphBuilder.connectDestination(onPaired: () -> Unit) {
    composable(AppRoutes.CONNECT) {
        ConnectScreen(
            onConnected = onPaired,
        )
    }
}

/** The Usage tab polls its own retained ViewModel while visible. */
internal fun NavGraphBuilder.usageDestination(container: AppContainer, isWide: Boolean) {
    composable(Destination.Usage.route) {
        val usageViewModel: UsageViewModel = viewModel(
            factory = viewModelFactory<UsageViewModel> { app ->
                UsageViewModel(app.container.bridge)
            },
        )
        TabScaffold(
            title = "Usage",
            ownsBottomInset = isWide,
        ) { innerUsage ->
            UsageScreen(
                viewModel = usageViewModel,
                modifier = Modifier.padding(innerUsage),
            )
        }
    }
}

/**
 * The Review tab. The shared ReviewViewModel is created once at the nav root
 * so Sessions/Board/Chat can pre-select the repo before navigating here.
 */
internal fun NavGraphBuilder.reviewDestination(reviewViewModel: ReviewViewModel, isWide: Boolean) {
    composable(Destination.Review.route) {
        // Review owns its own TabScaffold: the header carries the repo's mono
        // facts and the commit/overflow actions (§9c).
        ReviewScreen(viewModel = reviewViewModel, ownsBottomInset = isWide)
    }
}

/**
 * The full-screen terminal. Scoped to the back-stack entry (the default), so
 * leaving the route clears the ViewModel and its single pane socket; the key
 * keeps a per-pane request from reusing another pane's VM.
 */
internal fun NavGraphBuilder.terminalDestination(navController: NavHostController, container: AppContainer) {
    composable(
        route = AppRoutes.TERMINAL,
        arguments = listOf(
            navArgument(AppRoutes.TerminalArgs.PANE_ID) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val requestedPaneId = backStackEntry.arguments
            ?.getString(AppRoutes.TerminalArgs.PANE_ID)
            ?.takeIf { it.isNotBlank() }
        val terminalViewModel: TerminalViewModel = viewModel(
            factory = viewModelFactory<TerminalViewModel> { app ->
                TerminalViewModel(
                    api = app.container.bridge,
                    transport = app.container.terminalTransport,
                    feedFactory = app.container.terminalTopologyFeedFactory,
                    connectionStore = app.container.connectionStore,
                    preferencesStore = app.container.terminalPreferences,
                    initialPaneId = requestedPaneId,
                    performanceCounters = app.container.performanceCounters,
                )
            },
            key = "terminal_${requestedPaneId ?: "resolved"}",
        )
        TerminalScreen(
            viewModel = terminalViewModel,
            onBack = { navController.popBackStack() },
        )
    }
}

/** Settings: terminal preferences plus the one-step forget/reset action. */
internal fun NavGraphBuilder.settingsDestination(
    navController: NavHostController,
    container: AppContainer,
    onForget: () -> Unit,
) {
    composable(AppRoutes.SETTINGS) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            // Read once per visit, so re-pairing shows the new host without
            // Settings holding a stale copy.
            saved = remember { container.connectionStore.saved },
            terminalPreferences = container.terminalPreferences,
            api = container.bridge,
            onForget = onForget,
        )
    }
}
