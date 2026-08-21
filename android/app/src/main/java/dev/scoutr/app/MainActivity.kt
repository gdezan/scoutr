package dev.scoutr.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.decodeSessionKey
import dev.scoutr.app.data.encode
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.NewSessionViewModel
import dev.scoutr.app.state.ChatViewModel
import dev.scoutr.app.state.FileBrowserViewModel
import dev.scoutr.app.state.FileViewerViewModel
import dev.scoutr.app.state.CommandPaletteViewModel
import dev.scoutr.app.state.SessionHistoryViewModel
import dev.scoutr.app.state.UsageViewModel
import dev.scoutr.app.state.ReviewViewModel
import dev.scoutr.app.state.ReduceMotionStore
import dev.scoutr.app.state.savedStateViewModelFactory
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.screens.BoardScreen
import dev.scoutr.app.ui.screens.NewSessionSheet
import dev.scoutr.app.ui.screens.PanelSelection
import dev.scoutr.app.ui.screens.SessionPanel
import dev.scoutr.app.ui.screens.ChatScreen
import dev.scoutr.app.ui.screens.FileBrowserScreen
import dev.scoutr.app.ui.screens.FileViewerScreen
import dev.scoutr.app.ui.screens.CommandPalette
import dev.scoutr.app.ui.screens.ConnectScreen
import dev.scoutr.app.ui.screens.HistoryScreen
import dev.scoutr.app.ui.screens.UsageScreen
import dev.scoutr.app.ui.screens.ReviewScreen
import dev.scoutr.app.service.parseScoutrUri
import dev.scoutr.app.ui.screens.SettingsScreen
import dev.scoutr.app.state.TerminalViewModel
import dev.scoutr.app.ui.screens.terminal.TerminalScreen

import dev.scoutr.app.ui.nav.CHAT_ROUTE
import dev.scoutr.app.ui.nav.Destination
import dev.scoutr.app.ui.nav.TabScaffold
import dev.scoutr.app.ui.nav.isShellRoute
import dev.scoutr.app.ui.components.AppTopBar
import dev.scoutr.app.ui.motion.ScoutrMotion
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.OverlayPresence
import dev.scoutr.app.ui.motion.rememberHaptic
import dev.scoutr.app.ui.motion.useReduceMotion
import dev.scoutr.app.ui.theme.ScoutrTheme

/** Non-tab routes; the tab routes live in [Destination]. */
private object Routes {
    const val CONNECT = "connect"
    /** One source with the shell predicate; see ui/nav/ShellRoute.kt. */
    const val CHAT = CHAT_ROUTE
    const val FILE_BROWSER = "files?cwd={cwd}"
    const val FILE_VIEWER = "file-viewer?cwd={cwd}&file={file}"
    const val SETTINGS = "settings"
    const val TERMINAL = "terminal?paneId={paneId}"

    fun chat(key: SessionKey, status: String): String =
        "chat?sessionKey=${key.encode()}&status=$status"

    fun bootstrapChat(paneId: String, status: String): String =
        "chat?bootstrapPaneId=${java.net.URLEncoder.encode(paneId, "UTF-8")}&status=$status"

    fun fileBrowser(cwd: String): String =
        "$FILE_BROWSER_BASE${java.net.URLEncoder.encode(cwd, "UTF-8")}"

    fun fileViewer(cwd: String, file: String): String =
        "$FILE_VIEWER_BASE${java.net.URLEncoder.encode(cwd, "UTF-8")}&file=${java.net.URLEncoder.encode(file, "UTF-8")}"

    private const val FILE_BROWSER_BASE = "files?cwd="
    private const val FILE_VIEWER_BASE = "file-viewer?cwd="

    /**
     * Full-screen terminal. A null [paneId] lets the ViewModel resolve the
     * pane (saved pane, then herdr's focused pane, then the first one), so the
     * global top-bar action and the per-session "Open terminal" share a route.
     */
    fun terminal(paneId: String? = null): String =
        "terminal?paneId=${paneId?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""}"
}

/** A session's chat route: by canonical key, or by its pane until a fresh launch converges. */
private fun chatRoute(key: SessionKey?, bootstrapPaneId: String?, status: String): String? =
    key?.let { Routes.chat(it, status) } ?: bootstrapPaneId?.let { Routes.bootstrapChat(it, status) }

/** Open a session by canonical key, or by its pane until a fresh launch converges. */
private fun NavController.navigateToChat(key: SessionKey?, bootstrapPaneId: String?, status: String) {
    navigate(chatRoute(key, bootstrapPaneId, status) ?: return)
}

/**
 * Open a session from the wide window's session panel. Switching rows replaces
 * the detail pane rather than stacking chats, so repeated selection cannot grow
 * the back stack or leak per-session ChatViewModels.
 */
private fun NavController.navigateToChatFromPanel(key: SessionKey?, bootstrapPaneId: String?, status: String) {
    navigate(chatRoute(key, bootstrapPaneId, status) ?: return) {
        popUpTo(Destination.Board.route) { inclusive = false }
        launchSingleTop = true
    }
}

class MainActivity : ComponentActivity() {

    /** Consumed by the NavHost: scoutr://chat/<paneId> links from notifications. */
    private val deepLink = mutableStateOf<dev.scoutr.app.service.ScoutrDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deepLink.value = parseScoutrUri(intent.dataString)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            // Mirror the system "Remove animations" setting into the theme so
            // every motion helper collapses to zero duration when asked.
            val context = LocalContext.current
            val motionStore = remember { ReduceMotionStore(context) }
            DisposableEffect(motionStore) {
                onDispose { motionStore.close() }
            }
            val reduceMotion by motionStore.reduceMotion.collectAsState()
            ScoutrTheme(reduceMotion = reduceMotion) {
                ScoutrAppNav(deepLink = deepLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = parseScoutrUri(intent.dataString)
    }
}

@Composable
private fun ScoutrAppNav(
    deepLink: androidx.compose.runtime.MutableState<dev.scoutr.app.service.ScoutrDeepLink?>,
) {
    val app = LocalContext.current.applicationContext as ScoutrApp
    val navController = rememberNavController()
    val container = app.container
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    var startDestination by remember {
        mutableStateOf(if (container.connectionStore.saved != null) Destination.Board.route else Routes.CONNECT)
    }

    // One activity-scoped board VM: the bottom-bar badge and the Board screen
    // share the same live snapshot, so there is no duplicate polling.
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
                        session.live?.status ?: pendingDeepLink.status ?: "working",
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

    val openSettings = { navController.navigate(Routes.SETTINGS) }

    // Single-top so repeated taps on the top-bar terminal action reuse the live
    // route (and its one socket) instead of stacking a second attached pane.
    val openTerminal = {
        navController.navigate(Routes.terminal()) { launchSingleTop = true }
    }

    // The new-session sheet is hoisted so the wide window's panel FAB opens the
    // same sheet from any destination, not only from the Board route.
    val newSessionViewModel: NewSessionViewModel = viewModel(
        factory = NewSessionViewModel.factory(container.bridge, container.launcherSettingsStore),
    )
    var showNewSession by remember { mutableStateOf(false) }

    BoxWithConstraints {
        val compatible = boardUi.apiCompatibility == ScoutrApiCompatibility.Compatible
        // Read straight off the window, as ReadableContentColumn already does;
        // a plain Boolean threaded from the shell is also directly testable.
        val isWide = maxWidth >= WIDE_WINDOW_BREAKPOINT
        val showPanel = isWide && isShellRoute(currentRoute) && compatible
        val showBottomBar = compatible &&
            (currentRoute in Destination.routes || (isWide && currentRoute == CHAT_ROUTE))
        // The one case where the shell, not the screen, owns the bottom inset:
        // Chat under a wide window's bottom bar.
        val shellOwnsBottomInset = isWide && showBottomBar && currentRoute == CHAT_ROUTE
        // Derived from the back stack, never stored. Both arguments matter: a
        // chat entered by bootstrap has no sessionKey until the route rewrites.
        val selection = if (currentRoute == CHAT_ROUTE) {
            val args = backStack?.arguments
            PanelSelection(
                sessionKey = args?.getString("sessionKey")?.takeIf(String::isNotBlank),
                paneId = args?.getString("bootstrapPaneId")?.takeIf(String::isNotBlank),
            )
        } else {
            null
        }

        Scaffold(
        // Status/side bars only. Bottom is either the tab bar or each
        // screen's ime.union(navigationBars) pad — including nav bars here
        // stacks a nav-bar-tall gap above the keyboard.
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        // Panel, detail pane and bar ride above the keyboard together. The
        // shell takes the larger of the IME and the nav bar, and the bar drops
        // its own nav-bar pad below — exactly one consumer, as fix 25df24f
        // requires, and the same rule imeOrNavigationBarsPadding applies for
        // a screen that owns its own bottom.
        modifier = if (shellOwnsBottomInset) {
            Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        } else {
            Modifier
        },
        bottomBar = {
            if (showBottomBar) {
                ScoutrBottomBar(
                    currentRoute = currentRoute,
                    needsYouCount = rememberNeedsYouCount(boardViewModel),
                    onSelect = onTab,
                    insetOwnedByShell = shellOwnsBottomInset,
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
                    agent.live?.let { navController.navigateToChatFromPanel(agent.key, it.paneId, it.status) }
                },
                onReviewAgent = { agent ->
                    agent.cwd?.let { cwd ->
                        reviewViewModel.selectRepo(cwd)
                        onTab(Destination.Review.route)
                    }
                },
                onCloseAgent = { agent -> agent.live?.let { boardViewModel.closeAgent(it.paneId) } },
                onQuickAnswer = { agent, label -> boardViewModel.quickAnswer(agent, label) },
                onNewSession = { showNewSession = true },
                onSettings = openSettings,
                onTerminal = openTerminal,
                onResolveCompatibility = openSettings,
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
            composable(Routes.CONNECT) {
                ConnectScreen(
                    onConnected = {
                        container.registerCachedFcmToken()
                        startDestination = Destination.Board.route
                        navController.navigate(Destination.Board.route) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                        }
                    },
                )
            }
            composable(Destination.Board.route) {
                if (showPanel) {
                    // The panel header already names the surface, so the detail
                    // pane carries no second "Board" bar — Board on wide simply
                    // means no session is selected. Without the panel (compact,
                    // or an incompatible bridge) this route stays the board
                    // itself, which is what polls and what shows the banner.
                    BoardDetailPlaceholder()
                    return@composable
                }
                TabScaffold(
                    // Header composition is per-screen in the reference: the board
                    // carries terminal + settings, and search belongs to Sessions
                    // (§8b, §9c) — not a uniform action row on every tab.
                    title = "Board",
                    onSettings = openSettings,
                    onTerminal = openTerminal.takeIf { compatible },
                    showLockup = true,
                    floatingActionButton = {
                        if (compatible) {
                            FloatingActionButton(
                                onClick = { showNewSession = true },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "New session")
                            }
                        }
                    },
                ) { innerBoard ->
                    BoardScreen(
                        onOpenAgent = { agent ->
                            agent.live?.let { navController.navigateToChat(agent.key, it.paneId, it.status) }
                        },
                        onReviewAgent = { agent ->
                            val cwd = agent.cwd
                            if (cwd != null) {
                                reviewViewModel.selectRepo(cwd)
                                onTab(Destination.Review.route)
                            }
                        },
                        onCloseAgent = { agent -> agent.live?.let { boardViewModel.closeAgent(it.paneId) } },
                        onQuickAnswer = { agent, label -> boardViewModel.quickAnswer(agent, label) },
                        onResolveCompatibility = openSettings,
                        viewModel = boardViewModel,
                        modifier = Modifier.padding(innerBoard),
                    )
                }
            }
            composable(Destination.Sessions.route) {
                val historyViewModel: SessionHistoryViewModel = viewModel(
                    factory = viewModelFactory<SessionHistoryViewModel> { app ->
                        SessionHistoryViewModel(
                            app.container.bridge,
                            app.container.connectionStore,
                            app.container.sessionCatalogStore,
                        )
                    },
                )
                TabScaffold(
                    title = "Sessions",
                    onSearch = openPalette,
                ) { innerSessions ->
                    HistoryScreen(
                        onOpenSession = { resumed ->
                            navController.navigateToChat(resumed.key, resumed.bootstrapPaneId, "working")
                        },
                        onReview = { item ->
                            reviewViewModel.selectRepo(item.session.cwd)
                            onTab(Destination.Review.route)
                        },
                        viewModel = historyViewModel,
                        modifier = Modifier.padding(innerSessions),
                    )
                }
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(
                    androidx.navigation.navArgument("sessionKey") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                    androidx.navigation.navArgument("bootstrapPaneId") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                    androidx.navigation.navArgument("status") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = "working"
                    },
                ),
            ) { backStackEntry ->
                val initialKey = backStackEntry.arguments?.getString("sessionKey")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::decodeSessionKey)
                val bootstrapPaneId = backStackEntry.arguments?.getString("bootstrapPaneId")
                    ?.takeIf(String::isNotBlank)
                val agentStatus = backStackEntry.arguments?.getString("status") ?: "working"
                val chatViewModel: ChatViewModel = viewModel(
                    // Saved state, not just the view model scope: a half-filled
                    // ask round has to survive process death, not only rotation.
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
                        navController.navigate(Routes.chat(converged, chatUi.agentStatus)) {
                            popUpTo(backStackEntry.destination.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                ChatScreen(
                    viewModel = chatViewModel,
                    onBack = { navController.popBackStack() },
                    bottomInsetOwnedByShell = shellOwnsBottomInset,
                    onOpenTerminal = {
                        chatUi.livePaneId?.let { navController.navigate(Routes.terminal(it)) }
                    },
                    onOpenFiles = { cwd -> navController.navigate(Routes.fileBrowser(cwd)) },
                    onOpenReview = { cwd ->
                        reviewViewModel.selectRepo(cwd)
                        onTab(Destination.Review.route)
                    },
                )
            }
            composable(
                route = Routes.FILE_BROWSER,
                arguments = listOf(
                    androidx.navigation.navArgument("cwd") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val cwd = backStackEntry.arguments?.getString("cwd") ?: ""
                val browserViewModel: FileBrowserViewModel = viewModel(
                    factory = viewModelFactory<FileBrowserViewModel> { app ->
                        FileBrowserViewModel(app.container.bridge, cwd)
                    },
                    key = "file_browser_$cwd",
                )
                FileBrowserScreen(
                    viewModel = browserViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenFile = { file -> navController.navigate(Routes.fileViewer(cwd, file)) },
                )
            }
            composable(
                route = Routes.FILE_VIEWER,
                arguments = listOf(
                    androidx.navigation.navArgument("cwd") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                    androidx.navigation.navArgument("file") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val cwd = backStackEntry.arguments?.getString("cwd") ?: ""
                val file = backStackEntry.arguments?.getString("file") ?: ""
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
            composable(Destination.Usage.route) {
                val usageViewModel: UsageViewModel = viewModel(
                    factory = viewModelFactory<UsageViewModel> { app ->
                        UsageViewModel(app.container.bridge)
                    },
                )
                TabScaffold(
                    title = "Usage",
                ) { innerUsage ->
                    UsageScreen(
                        viewModel = usageViewModel,
                        modifier = Modifier.padding(innerUsage),
                    )
                }
            }
            composable(Destination.Review.route) {
                // Shared with the Sessions swipe action; see the hoisted instance
                // above. Review owns its own TabScaffold: the header carries the
                // repo's mono facts and the commit/overflow actions (§9c).
                ReviewScreen(viewModel = reviewViewModel)
            }
            composable(
                route = Routes.TERMINAL,
                arguments = listOf(
                    androidx.navigation.navArgument("paneId") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                val requestedPaneId = backStackEntry.arguments?.getString("paneId")?.takeIf { it.isNotBlank() }
                // Scoped to the back-stack entry (the default), so leaving the
                // route clears the ViewModel and its single pane socket; the
                // key keeps a per-pane request from reusing another pane's VM.
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
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    // Read once per visit, so re-pairing shows the new host
                    // without Settings holding a stale copy.
                    saved = remember { container.connectionStore.saved },
                    terminalPreferences = container.terminalPreferences,
                    api = container.bridge,
                    onForget = {
                        // One user-visible step: the pairing teardown (and its
                        // ordering) belongs to the container; the activity-scoped
                        // board VM is told to let go before the graph is rebuilt
                        // Connect-only.
                        container.forgetConnection()
                        boardViewModel.disconnect()
                        startDestination = Routes.CONNECT
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    },
                )
            }
        }
        }
        }

        if (showNewSession) {
            NewSessionSheet(
                viewModel = newSessionViewModel,
                onDismiss = { showNewSession = false },
                onCreated = { paneId ->
                    showNewSession = false
                    navController.navigate(Routes.bootstrapChat(paneId, "working"))
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
                        navController.navigateToChat(key, bootstrapPaneId, "working")
                    },
                )
            }
        }
    }
}

/** Above this window width the shell shows the session panel beside the detail pane. */
private val WIDE_WINDOW_BREAKPOINT = 840.dp

/** The session panel's fixed width; the detail pane takes the remainder. */
private val SESSION_PANEL_WIDTH = 320.dp

/** What the detail pane shows on wide until a session is chosen. */
@Composable
private fun BoardDetailPlaceholder() {
    Box(
        Modifier.fillMaxSize().testTag("board_detail_placeholder"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Select a session",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Number of agents that currently need the user, for the Board tab badge. */
@Composable
private fun rememberNeedsYouCount(boardViewModel: BoardViewModel): Int =
    boardViewModel.ui.collectAsState().value.board.needsYou.size

/**
 * Compact premium phone bar: three destinations, strong selected state,
 * needs-you badge, and safe-area padding. Selection is interruption-safe
 * (single-top, no queued back stacks).
 */
@Composable
fun ScoutrBottomBar(
    currentRoute: String?,
    needsYouCount: Int,
    onSelect: (String) -> Unit,
    // True when the shell already consumed the bottom inset for this route;
    // padding again would stack a nav-bar-tall band under the bar.
    insetOwnedByShell: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .then(if (insetOwnedByShell) Modifier else Modifier.navigationBarsPadding()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Destination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                val badge = if (destination == Destination.Board && needsYouCount > 0) needsYouCount else 0
                ScoutrTab(
                    destination = destination,
                    selected = selected,
                    badge = badge,
                    onClick = { onSelect(destination.route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ScoutrTab(
    destination: Destination,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,

) {
    val haptic = rememberHaptic()
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                haptic(HapticEvent.Select)
                onClick()
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            if (badge > 0) {
                Box(
                    // Offset (not padding) so the badge can straddle the icon's
                    // corner: Compose padding rejects negative values and would
                    // crash the app at startup whenever the badge renders.
                    Modifier
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badge > 9) "9+" else badge.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
