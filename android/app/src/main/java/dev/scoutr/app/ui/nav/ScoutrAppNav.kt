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
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.service.ScoutrDeepLink
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.CommandPaletteViewModel
import dev.scoutr.app.state.NewSessionViewModel
import dev.scoutr.app.state.ReviewViewModel
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

/** Above this window width the shell shows the session panel beside the detail pane. */
private val WIDE_WINDOW_BREAKPOINT = 840.dp

/** The session panel's fixed width; the detail pane takes the remainder. */
private val SESSION_PANEL_WIDTH = 320.dp

/**
 * The app shell: one root NavController, the wide-window panel/detail split,
 * the compact bottom bar, and the two overlays (new-session sheet, command
 * palette). Feature destinations register themselves through the graph
 * modules in this package; everything they need from the shell arrives as an
 * explicit parameter.
 */
@Composable
fun ScoutrAppNav(
    deepLink: MutableState<ScoutrDeepLink?>,
) {
    val app = LocalContext.current.applicationContext as ScoutrApp
    val navController = rememberNavController()
    val container = app.container
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    var startDestination by remember {
        mutableStateOf(initialStartDestination(container.connectionStore.saved != null))
    }

    // One activity-scoped board VM: the bottom-bar badge, the session panel
    // and the Board screen share the same live snapshot, so there is no
    // duplicate polling.
    val boardViewModel: BoardViewModel = viewModel(
        factory = viewModelFactory<BoardViewModel> { app ->
            BoardViewModel(app.container.bridge, app.container.connectionStore)
        },
        key = "activity_board",
    )
    val boardUi by boardViewModel.ui.collectAsState()

    val onTab = { route: String ->
        navController.navigate(route) {
            popUpTo(Destination.Board.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    // Legacy notification links carry a live pane id. Resolve that attachment
    // through the bridge-owned Board descriptor before navigation so an
    // existing session still enters Chat by canonical key.
    val pendingDeepLink = deepLink.value
    LaunchedEffect(pendingDeepLink, boardUi.apiCompatibility, boardUi.board) {
        if (pendingDeepLink != null) {
            if (container.connectionStore.saved == null) {
                deepLink.value = null
            } else if (boardUi.apiCompatibility == ScoutrApiCompatibility.Compatible) {
                val session = boardUi.board.sessions.firstOrNull {
                    it.live?.paneId == pendingDeepLink.paneId
                }
                if (session != null) {
                    navController.navigateToChat(
                        session.key,
                        pendingDeepLink.paneId,
                        session.live?.status ?: pendingDeepLink.status ?: AppRoutes.ChatArgs.DEFAULT_STATUS,
                    )
                    deepLink.value = null
                }
            }
        }
    }

    val paletteViewModel: CommandPaletteViewModel = viewModel(
        factory = CommandPaletteViewModel.factory(container.bridge, container.connectionStore),
    )
    // Hoisted to the activity so session rows can steer it (fix 5: review the
    // workspace the agent runs in from the Sessions list). Demand-driven, no
    // polling, so keeping it alive across tabs is harmless.
    val reviewViewModel: ReviewViewModel = viewModel(
        factory = ReviewViewModel.factory(container.bridge, container.connectionStore),
    )

    var paletteOpen by remember { mutableStateOf(false) }
    val openPalette = { paletteOpen = true }
    val openSettings = { navController.navigate(AppRoutes.SETTINGS) }

    // Single-top so repeated taps on the top-bar terminal action reuse the live
    // route (and its one socket) instead of stacking a second attached pane.
    val openTerminal = {
        navController.navigate(AppRoutes.terminal()) { launchSingleTop = true }
    }

    /** Pre-select the repo and land on Review; shared by Board, the session panel, Sessions and Chat. */
    val openReview: (String) -> Unit = { cwd ->
        reviewViewModel.selectRepo(cwd)
        onTab(Destination.Review.route)
    }

    // The new-session sheet is hoisted so the wide window's panel FAB opens the
    // same sheet from any destination, not only from the Board route.
    val newSessionViewModel: NewSessionViewModel = viewModel(
        factory = NewSessionViewModel.factory(container.bridge, container.launcherSettingsStore),
    )
    var showNewSession by remember { mutableStateOf(false) }

    // Pairing lifecycle lives at the shell because both ends rebuild the
    // graph root: pairing swaps Connect for Board, forgetting does the reverse.
    val onPaired = {
        container.registerCachedFcmToken()
        startDestination = Destination.Board.route
        navController.navigate(Destination.Board.route) {
            popUpTo(AppRoutes.CONNECT) { inclusive = true }
        }
    }
    // One user-visible step: the pairing teardown (and its ordering) belongs
    // to the container; the activity-scoped board VM is told to let go before
    // the graph is rebuilt Connect-only.
    val onPairingForgotten = {
        container.forgetConnection()
        boardViewModel.disconnect()
        startDestination = AppRoutes.CONNECT
        navController.navigate(AppRoutes.CONNECT) {
            popUpTo(navController.graph.id) { inclusive = true }
        }
    }

    BoxWithConstraints {
        val compatible = boardUi.apiCompatibility == ScoutrApiCompatibility.Compatible
        // Read straight off the window, as ReadableContentColumn already does;
        // a plain Boolean threaded from the shell is also directly testable.
        val isWide = maxWidth >= WIDE_WINDOW_BREAKPOINT
        val showPanel = isWide && isShellRoute(currentRoute) && compatible
        // Compact windows only. Wide navigation lives in the session panel's
        // destination row, so nothing sits beneath the panes.
        val showBottomBar = !isWide && compatible && currentRoute in Destination.routes
        // Derived from the back stack, never stored. Both arguments matter: a
        // chat entered by bootstrap has no sessionKey until the route rewrites.
        val selection = if (currentRoute == CHAT_ROUTE) {
            val args = backStack?.arguments
            PanelSelection(
                sessionKey = args?.getString(AppRoutes.ChatArgs.SESSION_KEY)?.takeIf(String::isNotBlank),
                paneId = args?.getString(AppRoutes.ChatArgs.BOOTSTRAP_PANE_ID)?.takeIf(String::isNotBlank),
            )
        } else {
            null
        }

        Scaffold(
            // Status/side bars only. Every screen owns its own bottom inset via
            // imeOrNavigationBarsPadding — including nav bars here stacks a
            // nav-bar-tall gap above the keyboard.
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
                            agent.live?.let {
                                navController.navigateToChatFromPanel(agent.key, it.paneId, it.status)
                            }
                        },
                        onReviewAgent = { agent -> agent.cwd?.let(openReview) },
                        onCloseAgent = { agent -> agent.live?.let { boardViewModel.closeAgent(it.paneId) } },
                        onQuickAnswer = { agent, label -> boardViewModel.quickAnswer(agent, label) },
                        onNewSession = { showNewSession = true },
                        onSettings = openSettings,
                        onTerminal = openTerminal,
                        onResolveCompatibility = openSettings,
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
                    // Destination changes are a short fade only; rows own their own
                    // 140ms arrival and never shift the list with placement animation.
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
                    connectDestination(onPaired)
                    boardDestination(
                        navController = navController,
                        boardViewModel = boardViewModel,
                        compatible = compatible,
                        showWidePlaceholder = showPanel,
                        onNewSession = { showNewSession = true },
                        onSettings = openSettings,
                        onTerminal = openTerminal,
                        openReview = openReview,
                    )
                    sessionsDestination(
                        navController = navController,
                        container = container,
                        isWide = isWide,
                        openPalette = openPalette,
                        openReview = openReview,
                    )
                    chatDestination(navController, openReview)
                    fileDestinations(navController, container)
                    usageDestination(container, isWide)
                    reviewDestination(reviewViewModel, isWide)
                    terminalDestination(navController, container)
                    settingsDestination(navController, container, onPairingForgotten)
                }
            }
        }

        if (showNewSession) {
            NewSessionSheet(
                viewModel = newSessionViewModel,
                onDismiss = { showNewSession = false },
                onCreated = { paneId ->
                    showNewSession = false
                    navController.navigate(AppRoutes.bootstrapChat(paneId, AppRoutes.ChatArgs.DEFAULT_STATUS))
                },
            )
        }

        if (paletteOpen) {

            // The palette's own back-dismiss closes the ViewModel; mirror it so
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
                        navController.navigateToChat(key, bootstrapPaneId, AppRoutes.ChatArgs.DEFAULT_STATUS)
                    },
                )
            }
        }
    }
}

/** Number of agents that currently need the user, for the Board tab badge. */
@Composable
private fun rememberNeedsYouCount(boardViewModel: BoardViewModel): Int =
    boardViewModel.ui.collectAsState().value.board.needsYou.size

/**
 * Compact phone bar: the shared [DestinationNavRow] over a surface sheet with
 * a top hairline and safe-area padding. Wide windows render the same row
 * inside the session panel instead, so this bar never shows beside it.
 * Selection is interruption-safe (single-top, no queued back stacks).
 */
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
