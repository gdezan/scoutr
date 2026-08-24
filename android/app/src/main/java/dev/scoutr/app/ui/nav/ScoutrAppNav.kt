package dev.scoutr.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.encode
import dev.scoutr.app.service.ScoutrDeepLink
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.CommandPaletteViewModel
import dev.scoutr.app.state.NewSessionViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.ScoutrMotion
import dev.scoutr.app.ui.motion.OverlayPresence
import dev.scoutr.app.ui.motion.rememberHaptic
import dev.scoutr.app.ui.motion.useReduceMotion
import dev.scoutr.app.ui.screens.CommandPalette
import dev.scoutr.app.ui.screens.NewSessionSheet
import dev.scoutr.app.ui.screens.PanelSelection
import dev.scoutr.app.ui.screens.SessionPanel

private val WIDE_WINDOW_BREAKPOINT = 840.dp
private val SESSION_PANEL_WIDTH = 320.dp

/** Root shell. Hostless Board/Sessions choose a default; remote routes carry the key. */
@Composable
fun ScoutrAppNav(deepLink: MutableState<ScoutrDeepLink?>) {
    val app = LocalContext.current.applicationContext as ScoutrApp
    val navController = rememberNavController()
    val container = app.container
    val registryState by container.hostRegistry.states.collectAsState()
    val removingHostIds by container.removingHostIds.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val installed = registryState.profiles.any { it.hostId !in removingHostIds } ||
        registryState.pendingLegacyConnection
    var startDestination by remember {
        mutableStateOf(initialStartDestination(registryState.profiles.isNotEmpty(), registryState.pendingLegacyConnection))
    }

    // Migration and pairing change the graph entry without consulting the old singleton store.
    LaunchedEffect(installed, currentRoute) {
        if (installed && currentRoute == AppRoutes.CONNECT) {
            startDestination = Destination.Board.route
            navController.navigate(Destination.Board.route) {
                popUpTo(AppRoutes.CONNECT) { inclusive = true }
            }
        } else if (!installed && currentRoute != null && currentRoute != AppRoutes.CONNECT) {
            startDestination = AppRoutes.CONNECT
            navController.navigate(AppRoutes.CONNECT) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    val boardViewModel: BoardViewModel = viewModel(
        factory = viewModelFactory<BoardViewModel> { appInstance ->
            BoardViewModel(
                appInstance.container.hostClients,
                appInstance.container.hostRegistry,
                currentBinding = appInstance.container::currentHostBinding,
                migrationState = appInstance.container.migration.state,
                adoptLegacyMetadata = appInstance.container.migration::adoptPendingMetadata,
            )
        },
        key = "activity_board",
    )
    val boardUi by boardViewModel.ui.collectAsState()
    val defaultProfile = registryState.defaultHostId?.let { id ->
        registryState.profiles.firstOrNull { it.hostId == id }
    }
    val defaultProfileKey = defaultProfile?.let { HostProfileKey(it.hostId, it.profileGeneration) }
    val defaultConnectionRevision = defaultProfile?.connectionRevision

    // Notification links are already validated by MainActivity. Keep their host identity intact;
    // Chat bootstrapping can resolve the pane directly on that bridge, even when it is not default.
    val pendingDeepLink = deepLink.value
    LaunchedEffect(pendingDeepLink, registryState) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        val profile = link.profile
        if (profile == null || container.currentHostProfile(profile) == null) {
            deepLink.value = null
            return@LaunchedEffect
        }
        runCatching { container.hostRegistry.markUsed(profile.hostId) }
        navController.navigate(
            AppRoutes.bootstrapChat(profile, link.paneId, link.status ?: AppRoutes.ChatArgs.DEFAULT_STATUS),
        )
        deepLink.value = null
    }

    fun markExplicitTarget(profile: HostProfileKey) {
        if (container.currentHostProfile(profile) != null) {
            runCatching { container.hostRegistry.markUsed(profile.hostId) }
        }
    }

    val onTab: (String) -> Unit = { route ->
        val destination = Destination.forRoute(route)
        val concrete = when (destination) {
            Destination.Board, Destination.Sessions -> route
            Destination.Usage -> defaultProfileKey?.also(::markExplicitTarget)?.let(AppRoutes::usage)
            Destination.Review -> defaultProfileKey?.also(::markExplicitTarget)?.let(AppRoutes::review)
            null -> route
        }
        if (concrete != null) {
            navController.navigate(concrete) {
                popUpTo(Destination.Board.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    val paletteViewModel: CommandPaletteViewModel? = defaultProfileKey?.let { profile ->
        val api = container.routeApi(profile, defaultConnectionRevision)
        if (api == null) null else viewModel(
            factory = viewModelFactory<CommandPaletteViewModel> {
                CommandPaletteViewModel(api, profile)
            },
            key = "palette_${profile.encode()}_$defaultConnectionRevision",
        )
    }

    var paletteOpen by remember { mutableStateOf(false) }
    val openPalette: () -> Unit = {
        defaultProfileKey?.let {
            markExplicitTarget(it)
            paletteOpen = true
        }
        Unit
    }
    val openSettings: () -> Unit = { navController.navigate(AppRoutes.SETTINGS) }

    val openTerminal: () -> Unit = {
        defaultProfileKey?.let { profile ->
            markExplicitTarget(profile)
            navController.navigate(AppRoutes.terminal(profile)) { launchSingleTop = true }
        }
        Unit
    }

    /** Session-originated actions retain their source profile and never consult a new default. */
    val openReview: (HostProfileKey, String) -> Unit = { profile, cwd ->
        markExplicitTarget(profile)
        navController.navigate(AppRoutes.review(profile, cwd)) { launchSingleTop = true }
    }

    var showNewSession by remember { mutableStateOf(false) }
    var newSessionProfile by remember { mutableStateOf<HostProfileKey?>(null) }
    val openNewSession: () -> Unit = {
        defaultProfileKey?.let {
            markExplicitTarget(it)
            newSessionProfile = it
            showNewSession = true
        }
        Unit
    }
    val newSessionViewModel: NewSessionViewModel? = defaultProfileKey?.let { profile ->
        val api = container.routeApi(profile, defaultConnectionRevision)
        if (api == null) null else viewModel(
            factory = viewModelFactory<NewSessionViewModel> {
                NewSessionViewModel(api, it.container.launcherSettingsStore.forHost(profile.hostId))
            },
            key = "new_session_${profile.encode()}_$defaultConnectionRevision",
        )
    }

    val onPaired = {
        container.registerCachedFcmToken()
        startDestination = Destination.Board.route
        navController.navigate(Destination.Board.route) {
            popUpTo(AppRoutes.CONNECT) { inclusive = true }
        }
    }
    val onPairingForgotten = {
        container.forgetConnection()
    }

    BoxWithConstraints {
        val compatible = boardUi.apiCompatibility == ScoutrApiCompatibility.Compatible
        val isWide = maxWidth >= WIDE_WINDOW_BREAKPOINT
        val showPanel = isWide && isShellRoute(currentRoute) && compatible
        val showBottomBar = !isWide && compatible && Destination.isDestinationRoute(currentRoute)
        val selection = if (currentRoute == CHAT_ROUTE) {
            val args = backStack?.arguments
            PanelSelection(
                sessionKey = args?.getString(AppRoutes.ChatArgs.SESSION_KEY)?.takeIf(String::isNotBlank),
                paneId = args?.getString(AppRoutes.ChatArgs.BOOTSTRAP_PANE_ID)?.takeIf(String::isNotBlank),
            )
        } else null

        Scaffold(
            contentWindowInsets = WindowInsets.systemBars.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
            ),
            bottomBar = {
                if (showBottomBar) {
                    ScoutrBottomBar(
                        currentRoute = currentRoute,
                        needsYouCount = rememberNeedsYouCount(boardViewModel),
                        onSelect = onTab,
                    )
                }
            },
        ) { inner ->
            val motion = useReduceMotion()
            Row(Modifier.fillMaxSize().padding(inner)) {
                if (showPanel) {
                    SessionPanel(
                        viewModel = boardViewModel,
                        selection = selection,
                        onOpenSession = { agent ->
                            val profile = boardUi.hostProfile
                            agent.live?.let {
                                if (profile != null) {
                                    markExplicitTarget(profile)
                                    navController.navigateToChatFromPanel(profile, agent.key, it.paneId, it.status)
                                }
                            }
                        },
                        onReviewAgent = { agent ->
                            val profile = boardUi.hostProfile
                            agent.cwd?.let { cwd -> if (profile != null) openReview(profile, cwd) }
                        },
                        onCloseAgent = { agent ->
                            boardUi.hostProfile?.let(::markExplicitTarget)
                            agent.live?.let { boardViewModel.closeAgent(it.paneId) }
                        },
                        onQuickAnswer = { agent, label ->
                            boardUi.hostProfile?.let(::markExplicitTarget)
                            boardViewModel.quickAnswer(agent, label)
                        },
                        onNewSession = openNewSession,
                        onSettings = openSettings,
                        onTerminal = openTerminal,
                        onResolveCompatibility = openSettings,
                        onRetryMigration = { container.migration.retry() },
                        currentRoute = currentRoute,
                        onSelectDestination = onTab,
                        modifier = Modifier.width(SESSION_PANEL_WIDTH).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.weight(1f),
                    enterTransition = {
                        if (motion) fadeIn(animationSpec = tween(0))
                        else fadeIn(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE))
                    },
                    exitTransition = {
                        if (motion) fadeOut(animationSpec = tween(0))
                        else fadeOut(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE))
                    },
                    popEnterTransition = {
                        if (motion) fadeIn(animationSpec = tween(0))
                        else fadeIn(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE))
                    },
                    popExitTransition = {
                        if (motion) fadeOut(animationSpec = tween(0))
                        else fadeOut(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE))
                    },
                ) {
                    connectDestination(navController, container, onPaired)
                    boardDestination(
                        navController = navController,
                        boardViewModel = boardViewModel,
                        compatible = compatible,
                        showWidePlaceholder = showPanel,
                        onNewSession = openNewSession,
                        onSettings = openSettings,
                        onTerminal = openTerminal,
                        openReview = openReview,
                        markHostUsed = ::markExplicitTarget,
                        onRetryMigration = { container.migration.retry() },
                    )
                    sessionsDestination(
                        navController = navController,
                        container = container,
                        isWide = isWide,
                        openPalette = openPalette,
                        openReview = openReview,
                        markHostUsed = ::markExplicitTarget,
                    )
                    chatDestination(navController, container, openReview, ::markExplicitTarget)
                    fileDestinations(navController, container)
                    usageDestination(container, isWide)
                    reviewDestination(navController, container, isWide)
                    terminalDestination(navController, container)
                    settingsDestination(navController, container, onPairingForgotten)
                }
            }
        }

        if (showNewSession && newSessionViewModel != null) {
            NewSessionSheet(
                viewModel = newSessionViewModel,
                onDismiss = { showNewSession = false },
                onCreated = { paneId ->
                    (newSessionProfile ?: defaultProfileKey)?.let { profile ->
                        markExplicitTarget(profile)
                        showNewSession = false
                        navController.navigate(AppRoutes.bootstrapChat(profile, paneId, AppRoutes.ChatArgs.DEFAULT_STATUS))
                    }
                },
            )
        }

        if (paletteOpen && paletteViewModel != null) {
            val haptic = rememberHaptic()
            LaunchedEffect(Unit) {
                haptic(HapticEvent.Select)
                paletteViewModel.open()
            }
            OverlayPresence(reduceMotion = useReduceMotion()) {
                CommandPalette(
                    onDismiss = {
                        paletteViewModel.close()
                        paletteOpen = false
                    },
                    viewModel = paletteViewModel,
                    onOpen = { key, bootstrapPaneId ->
                        val profile = paletteViewModel.profile
                        if (profile != null) {
                            markExplicitTarget(profile)
                            navController.navigateToChat(profile, key, bootstrapPaneId, AppRoutes.ChatArgs.DEFAULT_STATUS)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberNeedsYouCount(boardViewModel: BoardViewModel): Int =
    boardViewModel.ui.collectAsState().value.board.needsYou.size

@Composable
fun ScoutrBottomBar(
    currentRoute: String?,
    needsYouCount: Int,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        DestinationNavRow(currentRoute, needsYouCount, onSelect)
    }
}
