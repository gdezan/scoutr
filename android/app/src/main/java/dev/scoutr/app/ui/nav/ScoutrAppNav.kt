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
import dev.scoutr.app.service.ScoutrSubagentLink
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.CommandPaletteViewModel
import dev.scoutr.app.state.NewSessionViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.update.PendingUpdateAction
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.ScoutrMotion
import dev.scoutr.app.ui.motion.OverlayPresence
import dev.scoutr.app.ui.motion.rememberHaptic
import dev.scoutr.app.ui.motion.useReduceMotion
import dev.scoutr.app.ui.screens.CommandPalette
import dev.scoutr.app.ui.screens.NewSessionSheet
import dev.scoutr.app.ui.screens.TerminalTargetSheet
import dev.scoutr.app.ui.screens.PanelSelection
import dev.scoutr.app.ui.screens.SessionPanel

private val WIDE_WINDOW_BREAKPOINT = 840.dp
private val SESSION_PANEL_WIDTH = 320.dp

/** Root shell. Hostless Board/Sessions choose a default; remote routes carry the key. */
@Composable
fun ScoutrAppNav(
    deepLink: MutableState<ScoutrDeepLink?>,
    /** Set by an update notification: land on Settings and act on it there. */
    updateAction: MutableState<PendingUpdateAction?>,
    subagentLink: MutableState<ScoutrSubagentLink?>,
) {
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
                work = appInstance.container.hostWorkCoordinator,
                hostStatus = appInstance.container.hostStatus,
                hostFilter = appInstance.container.hostFilter,
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



    val pendingSubagentLink = subagentLink.value
    LaunchedEffect(pendingSubagentLink, registryState) {
        val link = pendingSubagentLink ?: return@LaunchedEffect
        if (container.currentHostProfile(link.profile) == null) {
            subagentLink.value = null
            return@LaunchedEffect
        }
        runCatching { container.hostRegistry.markUsed(link.profile.hostId) }
        navController.navigate(AppRoutes.subagentProgress(link.profile, link.runId))
        subagentLink.value = null
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

    // Global terminal: with several hosts, make the target explicit before any
    // WebSocket or hierarchy request goes out. One host opens directly.
    var terminalTargetOpen by remember { mutableStateOf(false) }
    var terminalTargetSelection by remember { mutableStateOf<HostProfileKey?>(null) }
    val openTerminal: () -> Unit = {
        val target = defaultProfileKey
        if (target != null) {
            if (registryState.profiles.size > 1) {
                terminalTargetSelection = target
                terminalTargetOpen = true
            } else {
                markExplicitTarget(target)
                navController.navigate(AppRoutes.terminal(target)) { launchSingleTop = true }
            }
        }
        Unit
    }

    /** Session-originated actions retain their source profile and never consult a new default. */
    val openReview: (HostProfileKey, String) -> Unit = { profile, cwd ->
        markExplicitTarget(profile)
        navController.navigate(AppRoutes.review(profile, cwd)) { launchSingleTop = true }
    }

    var showNewSession by remember { mutableStateOf(false) }
    // The sheet's selected host starts on the persistent default; switching the
    // selector swaps in that host's own view model, so delayed loads from a
    // previous host cannot land in the new host's form.
    var newSessionProfile by remember { mutableStateOf<HostProfileKey?>(null) }
    val openNewSession: () -> Unit = {
        defaultProfileKey?.let {
            markExplicitTarget(it)
            newSessionProfile = it
            showNewSession = true
        }
        Unit
    }

    val onPaired = {
        container.registerCachedFcmToken()
        startDestination = Destination.Board.route
        navController.navigate(Destination.Board.route) {
            popUpTo(AppRoutes.CONNECT) { inclusive = true }
        }
    }
    BoxWithConstraints {
        val compatible = boardUi.hasCompatibleHost
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
                            val session = agent.session
                            session.live?.let {
                                markExplicitTarget(agent.profile)
                                navController.navigateToChatFromPanel(agent.profile, session.key, it.paneId, it.status)
                            }
                        },
                        onOpenSubagent = { agent, runId ->
                            markExplicitTarget(agent.profile)
                            navController.navigateToSubagentProgress(agent.profile, runId)
                        },
                        onReviewAgent = { agent ->
                            agent.session.cwd?.let { cwd -> openReview(agent.profile, cwd) }
                        },
                        onCloseAgent = { agent ->
                            markExplicitTarget(agent.profile)
                            agent.session.live?.let {
                                boardViewModel.closeAgent(agent.profile, it.paneId)
                            }
                        },
                        onQuickAnswer = { agent, label ->
                            markExplicitTarget(agent.profile)
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
                    subagentDestination(navController, container)
                    usageDestination(navController, container, isWide)
                    reviewDestination(navController, container, isWide)
                    terminalDestination(navController, container)
                    settingsDestination(navController, container, updateAction) {
                        // HostsViewModel did the forgetting; here we only clear
                        // host-bound back stack entries and land on Connect.
                        navController.navigate(AppRoutes.CONNECT) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                }
            }
        }

        val sessionTarget = newSessionProfile
        if (showNewSession && sessionTarget != null) {
            val selectionRevision = registryState.connectionRevision(sessionTarget)
            val selectionApi = container.routeApi(sessionTarget, selectionRevision)
            if (selectionApi == null) {
                // The selected host vanished or lost its binding while the
                // sheet was open; fall back to the default on next open.
                showNewSession = false
            } else {
                // Only composes while the sheet is shown.
                val newSessionViewModel: NewSessionViewModel = viewModel(
                    factory = viewModelFactory<NewSessionViewModel> { appInstance ->
                        NewSessionViewModel(
                            selectionApi,
                            appInstance.container.launcherSettingsStore.forHost(sessionTarget.hostId),
                        )
                    },
                    key = "new_session_${sessionTarget.encode()}_$selectionRevision",
                )
                val hostStatuses = container.hostStatus.all.collectAsState().value
                val hostOptions = registryState.profiles.map { entry ->
                    dev.scoutr.app.ui.screens.NewSessionHostOption(
                        profile = HostProfileKey(entry.hostId, entry.profileGeneration),
                        alias = entry.alias,
                        usable = when (hostStatuses[entry.hostId]) {
                            is dev.scoutr.app.state.HostAvailability.Online -> true
                            else -> false
                        },
                    )
                }
                NewSessionSheet(
                    viewModel = newSessionViewModel,
                    onDismiss = { showNewSession = false },
                    selectedProfile = sessionTarget,
                    hosts = hostOptions,
                    onSelectHost = { newSessionProfile = it },
                    onCreated = { profile, paneId ->
                        // Route with the captured profile only — never a later
                        // selection made after Create was tapped.
                        markExplicitTarget(profile)
                        showNewSession = false
                        navController.navigate(
                            AppRoutes.bootstrapChat(profile, paneId, AppRoutes.ChatArgs.DEFAULT_STATUS),
                        )
                    },
                )
            }
        }

        if (terminalTargetOpen) {
            TerminalTargetSheet(
                registryState = registryState,
                statuses = boardUi.statuses,
                initialSelection = terminalTargetSelection,
                onSelect = { terminalTargetSelection = it },
                onConfirm = { target: HostProfileKey ->
                    terminalTargetOpen = false
                    markExplicitTarget(target)
                    navController.navigate(AppRoutes.terminal(target)) { launchSingleTop = true }
                },
                onDismiss = { terminalTargetOpen = false },
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
                    hostAlias = registryState.profiles
                        .firstOrNull { it.hostId == defaultProfileKey?.hostId }?.alias,
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
