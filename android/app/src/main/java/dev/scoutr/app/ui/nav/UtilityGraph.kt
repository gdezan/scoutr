package dev.scoutr.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.scoutr.app.AppContainer
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.decodeHostProfileKey
import dev.scoutr.app.data.encode
import dev.scoutr.app.state.ReviewViewModel
import dev.scoutr.app.state.TerminalViewModel
import dev.scoutr.app.state.UsageViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.ConnectScreen
import dev.scoutr.app.ui.screens.HostUnavailableScreen
import dev.scoutr.app.ui.screens.ReviewScreen
import dev.scoutr.app.ui.screens.SettingsConnection
import dev.scoutr.app.ui.screens.SettingsScreen
import dev.scoutr.app.ui.screens.UsageScreen
import dev.scoutr.app.ui.screens.terminal.TerminalScreen

/** Pairing, host-qualified utility destinations, and settings. */
internal fun NavGraphBuilder.connectDestination(
    navController: NavHostController,
    container: AppContainer,
    onPaired: () -> Unit,
) {
    composable(AppRoutes.CONNECT) {
        ConnectScreen(onConnected = onPaired)
    }
    composable(
        AppRoutes.CONNECT_REFRESH,
        arguments = listOf(
            navArgument("hostProfile") { type = NavType.StringType },
        ),
    ) { entry ->
        val key = entry.arguments?.getString("hostProfile")?.let(::decodeHostProfileKey)
        val profile = key?.let(container::currentHostProfile)
        if (key == null || profile == null) {
            HostUnavailableScreen()
        } else {
            ConnectScreen(
                onConnected = { navController.popBackStack() },
                initialHost = profile.baseUrl,
                refreshingHostId = profile.hostId,
            )
        }
    }
}

/** Usage resolves the default only when the hostless shell entry is opened. */
internal fun NavGraphBuilder.usageDestination(container: AppContainer, isWide: Boolean) {
    composable(
        route = Destination.Usage.pattern,
        arguments = listOf(
            navArgument(DestinationArgs.HOST_PROFILE) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val profile = backStackEntry.routeProfile()
        val registryState by container.hostRegistry.states.collectAsState()
        val connectionRevision = registryState.connectionRevision(profile)
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        if (profile == null || api == null) {
            HostUnavailableScreen(hostUnavailableReason(profile))
            return@composable
        }
        val usageViewModel: UsageViewModel = viewModel(
            factory = viewModelFactory<UsageViewModel> { UsageViewModel(api) },
            key = "usage_${profile.encode()}_$connectionRevision",
        )
        TabScaffold(title = "Usage", ownsBottomInset = isWide) { innerUsage ->
            UsageScreen(viewModel = usageViewModel, modifier = Modifier.padding(innerUsage))
        }
    }
}

/** Review is route-scoped so a repository opened from Chat retains its host key. */
internal fun NavGraphBuilder.reviewDestination(
    navController: NavHostController,
    container: AppContainer,
    isWide: Boolean,
) {
    composable(
        route = Destination.Review.pattern,
        arguments = listOf(
            navArgument(DestinationArgs.HOST_PROFILE) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(DestinationArgs.REPO_PATH) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val profile = backStackEntry.routeProfile()
        val registryState by container.hostRegistry.states.collectAsState()
        val connectionRevision = registryState.connectionRevision(profile)
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        val repoPath = backStackEntry.arguments?.getString(DestinationArgs.REPO_PATH)
            ?.takeIf(String::isNotBlank)
        if (profile == null || api == null) {
            HostUnavailableScreen(hostUnavailableReason(profile))
            return@composable
        }
        val reviewViewModel: ReviewViewModel = viewModel(
            factory = viewModelFactory<ReviewViewModel> {
                ReviewViewModel(api, container.reviewStoreForHost(profile.hostId))
            },
            key = "review_${profile.encode()}_$connectionRevision",
        )
        LaunchedEffect(repoPath) {
            if (repoPath != null) reviewViewModel.selectRepo(repoPath)
            else if (reviewViewModel.ui.value.repoPath == null) reviewViewModel.openPicker()
        }
        ReviewScreen(viewModel = reviewViewModel, ownsBottomInset = isWide)
    }
}

/** Terminal is fully host-bound: both socket and topology feed come from the captured host id. */
internal fun NavGraphBuilder.terminalDestination(navController: NavHostController, container: AppContainer) {
    composable(
        route = AppRoutes.TERMINAL,
        arguments = listOf(
            navArgument(AppRoutes.TerminalArgs.PANE_ID) {
                type = NavType.StringType
                defaultValue = ""
            },
            navArgument(AppRoutes.TerminalArgs.HOST_PROFILE) {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { backStackEntry ->
        val profile = backStackEntry.routeProfile(AppRoutes.TerminalArgs.HOST_PROFILE)
        val registryState by container.hostRegistry.states.collectAsState()
        val connectionRevision = registryState.connectionRevision(profile)
        val binding = profile?.let { container.routeBinding(it, connectionRevision) }
        val api = profile?.let { container.routeApi(it, connectionRevision) }
        val requestedPaneId = backStackEntry.arguments
            ?.getString(AppRoutes.TerminalArgs.PANE_ID)
            ?.takeIf(String::isNotBlank)
        if (profile == null || binding == null || api == null) {
            HostUnavailableScreen(hostUnavailableReason(profile))
            return@composable
        }
        val terminalViewModel: TerminalViewModel = viewModel(
            factory = viewModelFactory<TerminalViewModel> { app ->
                TerminalViewModel(
                    api = api,
                    transport = app.container.hostClients.terminal(binding),
                    feedFactory = app.container.hostClients.topologyFeedFactory(binding),
                    hostPreferences = app.container.terminalPreferencesForHost(profile.hostId),
                    preferencesStore = app.container.terminalPreferences,
                    initialPaneId = requestedPaneId,
                    performanceCounters = app.container.performanceCounters,
                    hostIsCurrent = { app.container.currentHostProfile(profile) != null },
                )
            },
            key = "terminal_${profile.encode()}_${connectionRevision}_${requestedPaneId ?: "resolved"}",
        )
        DisposableEffect(terminalViewModel) {
            onDispose(terminalViewModel::dispose)
        }
        TerminalScreen(viewModel = terminalViewModel, onBack = { navController.popBackStack() })
    }
}

/** Settings is device-level, but update transport is bound only to the selected update host. */
internal fun NavGraphBuilder.settingsDestination(
    navController: NavHostController,
    container: AppContainer,
    onForget: () -> Unit,
) {
    composable(AppRoutes.SETTINGS) {
        val registryState by container.hostRegistry.states.collectAsState()
        val current = registryState.defaultHostId?.let { id ->
            registryState.profiles.firstOrNull { it.hostId == id }
        }
        val updateHost = registryState.updateHostId?.let { id ->
            registryState.profiles.firstOrNull { it.hostId == id }
        }
        val updateBinding = updateHost
            ?.takeIf { registryState.inAppUpdatesEnabled }
            ?.let { container.currentHostBinding(it.hostId) }
        val replacementUpdateHost = current
            ?.takeIf { it.hostId == registryState.updateHostId }
            ?.let { selected -> registryState.profiles.firstOrNull { it.hostId != selected.hostId } }
        val saved = current?.let { profile ->
            SettingsConnection(
                host = profile.baseUrl,
                hostId = profile.hostId,
            )
        }
        SettingsScreen(
            onBack = { navController.popBackStack() },
            saved = saved,
            terminalPreferences = container.terminalPreferences,
            api = updateBinding?.let(container.hostClients::api),
            trackUpdateWork = { work ->
                checkNotNull(updateBinding) { "Update host binding is unavailable" }
                container.hostWorkCoordinator.track(updateBinding, work)
            },
            updateHostId = updateHost?.hostId,
            updateHostAlias = updateHost?.alias,
            updateHostOptions = registryState.profiles.associate { it.hostId to it.alias },
            onSelectUpdateHost = { hostId -> container.hostRegistry.confirmUpdateHost(hostId) },
            onDisableUpdates = container.hostRegistry::disableUpdates,
            onRefreshConnection = current?.let { profile ->
                {
                    navController.navigate(
                        AppRoutes.refreshConnection(HostProfileKey(profile.hostId, profile.profileGeneration)),
                    )
                }
            },
            forgetUpdateHostAlias = replacementUpdateHost?.alias,
            onForget = onForget,
        )
    }
}
