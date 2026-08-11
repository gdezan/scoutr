package dev.cockpit.app

import android.os.Bundle
import android.os.Build

import android.content.Intent
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.state.NewSessionViewModel
import dev.cockpit.app.state.ChatViewModel
import dev.cockpit.app.state.CommandPaletteViewModel
import dev.cockpit.app.state.SessionHistoryViewModel
import dev.cockpit.app.state.UsageViewModel
import dev.cockpit.app.state.ReviewViewModel
import dev.cockpit.app.state.ReduceMotionStore
import dev.cockpit.app.ui.screens.BoardScreen
import dev.cockpit.app.ui.screens.NewSessionSheet
import dev.cockpit.app.ui.screens.ChatScreen
import dev.cockpit.app.ui.screens.CommandPalette
import dev.cockpit.app.ui.screens.ConnectScreen
import dev.cockpit.app.ui.screens.HistoryScreen
import dev.cockpit.app.ui.screens.UsageScreen
import dev.cockpit.app.ui.screens.ReviewScreen
import dev.cockpit.app.service.parseCockpitUri
import dev.cockpit.app.ui.screens.SettingsScreen

import dev.cockpit.app.ui.motion.CockpitMotion
import dev.cockpit.app.ui.motion.HapticEvent
import dev.cockpit.app.ui.motion.OverlayPresence
import dev.cockpit.app.ui.motion.rememberHaptic
import dev.cockpit.app.ui.motion.useReduceMotion
import dev.cockpit.app.ui.theme.CockpitTheme

private object Routes {
    const val CONNECT = "connect"
    const val BOARD = "board"
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{paneId}?sessionPath={sessionPath}&status={status}"
    const val USAGE = "usage"

    const val REVIEW = "review"

    const val SETTINGS = "settings"

    fun chat(paneId: String, sessionPath: String?, status: String): String =
        "chat/$paneId?sessionPath=${sessionPath?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""}&status=$status"
}

/** The three top-level destinations on the phone bar. */
private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Board(Routes.BOARD, "Board", Icons.Default.GridView),
    Sessions(Routes.SESSIONS, "Sessions", Icons.Default.History),
    Usage(Routes.USAGE, "Usage", Icons.Default.BarChart),
    Review(Routes.REVIEW, "Review", Icons.Default.Code),
}

class MainActivity : ComponentActivity() {

    /** Consumed by the NavHost: cockpit://chat/<paneId> links from notifications. */
    private val deepLink = mutableStateOf<dev.cockpit.app.service.CockpitDeepLink?>(null)

    // Android 13+ requires a runtime opt-in before notifications can show.
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deepLink.value = parseCockpitUri(intent.dataString)
        // Resume background monitoring when the app starts if the user opted in.
        val monitor = dev.cockpit.app.state.MonitoringStore(this)
        if (monitor.enabled) {
            ContextCompat.startForegroundService(this, Intent(this, dev.cockpit.app.service.CockpitMonitorService::class.java))
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
            CockpitTheme(reduceMotion = reduceMotion) {
                CockpitAppNav(deepLink = deepLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = parseCockpitUri(intent.dataString)
    }
}

@Composable
private fun CockpitAppNav(
    deepLink: androidx.compose.runtime.MutableState<dev.cockpit.app.service.CockpitDeepLink?>,
) {
    val app = LocalContext.current.applicationContext as CockpitApp
    val navController = rememberNavController()
    val container = app.container
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    var startDestination by remember {
        mutableStateOf(if (container.connectionStore.saved != null) Routes.BOARD else Routes.CONNECT)
    }

    // One activity-scoped board VM: the bottom-bar badge and the Board screen
    // share the same live snapshot, so there is no duplicate polling.
    val boardViewModel: BoardViewModel = viewModel(
        factory = BoardViewModel.factory(
            container.bridge,
            container.connectionStore,
            container.ntfy,
            container::showAgentNotification,
        ),
        key = "activity_board",
    )

    val onTab = { route: String ->

        navController.navigate(route) {
            popUpTo(Routes.BOARD) { inclusive = false }
            launchSingleTop = true
        }
    }

    // A cockpit://chat/<paneId> deep link (notification tap) jumps straight to
    // the session once the nav graph is ready.
    val pendingDeepLink = deepLink.value
    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null) {
            if (container.connectionStore.saved != null) {
                navController.navigate(Routes.chat(pendingDeepLink.paneId, null, pendingDeepLink.status ?: "working"))
            }
            deepLink.value = null
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

    Box {
        Scaffold(
        bottomBar = {
            if (currentRoute in setOf(Routes.BOARD, Routes.SESSIONS, Routes.USAGE, Routes.REVIEW)) {
                CockpitBottomBar(
                    currentRoute = currentRoute,
                    needsYouCount = rememberNeedsYouCount(boardViewModel),
                    onSelect = onTab,
                )
            }
        },
    ) { inner ->
        val motion = useReduceMotion()
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(inner),
            // Shared-axis-style navigation: the incoming destination slides in
            // softly while the outgoing one fades; reduced motion collapses to
            // a straight swap.
            enterTransition = {
                if (motion) {
                    fadeIn(animationSpec = tween(0))
                } else {
                    slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn(
                        animationSpec = tween(CockpitMotion.DURATION_EMPHASIZED),
                    )
                }
            },
            exitTransition = {
                if (motion) fadeOut(animationSpec = tween(0))
                else fadeOut(animationSpec = tween(CockpitMotion.DURATION_STANDARD))
            },
            popEnterTransition = {
                if (motion) fadeIn(animationSpec = tween(0))
                else fadeIn(animationSpec = tween(CockpitMotion.DURATION_STANDARD))
            },
            popExitTransition = {
                if (motion) {
                    fadeOut(animationSpec = tween(0))
                } else {
                    slideOutHorizontally(targetOffsetX = { it / 4 }) + fadeOut(
                        animationSpec = tween(CockpitMotion.DURATION_EMPHASIZED),
                    )
                }
            },
        ) {
            composable(Routes.CONNECT) {
                ConnectScreen(
                    onConnected = {
                        startDestination = Routes.BOARD
                        navController.navigate(Routes.BOARD) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.BOARD) {
                val newSessionViewModel: NewSessionViewModel = viewModel(
                    factory = NewSessionViewModel.factory(
                        container.bridge,
                        container.launcherSettingsStore,
                    ),
                )
                var showNewSession by remember { mutableStateOf(false) }
                Scaffold(
                    contentWindowInsets = WindowInsets(0.dp),
                    topBar = { AppTopBar("Board", onSearch = openPalette, onSettings = openSettings) },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { showNewSession = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New session")
                        }
                    },
                ) { innerBoard ->
                    BoardScreen(
                        onOpenAgent = { agent ->
                            navController.navigate(Routes.chat(agent.paneId, agent.sessionPath, agent.status))
                        },
                        onReviewAgent = { agent ->
                            val cwd = agent.cwd
                            if (cwd != null) {
                                reviewViewModel.selectRepo(cwd)
                                onTab(Routes.REVIEW)
                            }
                        },
                        onCloseAgent = { agent -> boardViewModel.closeAgent(agent.paneId) },
                        viewModel = boardViewModel,
                        modifier = Modifier.padding(innerBoard),
                    )
                }
                if (showNewSession) {
                    NewSessionSheet(
                        viewModel = newSessionViewModel,
                        onDismiss = { showNewSession = false },
                        onCreated = { paneId ->
                            showNewSession = false
                            navController.navigate(Routes.chat(paneId, null, "working"))
                        },
                    )
                }
            }
            composable(Routes.SESSIONS) {
                val historyViewModel: SessionHistoryViewModel = viewModel(
                    factory = SessionHistoryViewModel.factory(
                        container.bridge,
                        container.connectionStore,
                        container.sessionCatalogStore,
                    ),
                )
                Scaffold(
                    contentWindowInsets = WindowInsets(0.dp),
                    topBar = { AppTopBar("Sessions", onSearch = openPalette, onSettings = openSettings) },
                ) { innerSessions ->
                    HistoryScreen(
                        onOpenSession = { resumed ->
                            navController.navigate(Routes.chat(resumed.paneId, null, "working"))
                        },
                        onReview = { item ->
                            reviewViewModel.selectRepo(item.session.cwd)
                            onTab(Routes.REVIEW)
                        },
                        viewModel = historyViewModel,
                        modifier = Modifier.padding(innerSessions),
                    )
                }
            }
            composable(
                route = Routes.CHAT,
                arguments = listOf(
                    androidx.navigation.navArgument("paneId") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("sessionPath") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                    androidx.navigation.navArgument("status") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = "working"
                    },
                ),
            ) { backStackEntry ->
                val paneId = backStackEntry.arguments?.getString("paneId") ?: ""
                val sessionPath = backStackEntry.arguments?.getString("sessionPath")?.takeIf { it.isNotBlank() }
                val agentStatus = backStackEntry.arguments?.getString("status") ?: "working"
                val chatViewModel: ChatViewModel = viewModel(
                    factory = ChatViewModel.factory(container.bridge, paneId, sessionPath, agentStatus),
                    key = "chat_$paneId",
                )
                ChatScreen(
                    viewModel = chatViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.USAGE) {
                val usageViewModel: UsageViewModel = viewModel(
                    factory = UsageViewModel.factory(container.bridge),
                )
                Scaffold(
                    contentWindowInsets = WindowInsets(0.dp),
                    topBar = { AppTopBar("Usage", onSearch = openPalette, onSettings = openSettings) },
                ) { innerUsage ->
                    UsageScreen(
                        viewModel = usageViewModel,
                        modifier = Modifier.padding(innerUsage),
                    )
                }
            }
            composable(Routes.REVIEW) {
                // Shared with the Sessions swipe action; see the hoisted instance above.
                Scaffold(
                    contentWindowInsets = WindowInsets(0.dp),
                    topBar = { AppTopBar("Review", onSearch = openPalette, onSettings = openSettings) },
                ) { innerReview ->
                    ReviewScreen(
                        viewModel = reviewViewModel,
                        modifier = Modifier.padding(innerReview),
                    )
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
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
                    onOpenAgent = { paneId, sessionPath ->
                        navController.navigate(Routes.chat(paneId, sessionPath, "working"))
                    },
                    onOpenSession = { paneId, sessionPath ->
                        navController.navigate(Routes.chat(paneId, sessionPath, "working"))
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
 * Compact premium phone bar: three destinations, strong selected state,
 * needs-you badge, and safe-area padding. Selection is interruption-safe
 * (single-top, no queued back stacks).
 */
@Composable
fun CockpitBottomBar(
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Destination.entries.forEach { destination ->
                val selected = currentRoute == destination.route
                val badge = if (destination == Destination.Board && needsYouCount > 0) needsYouCount else 0
                CockpitTab(
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
private fun CockpitTab(
    destination: Destination,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,

) {
    val haptic = rememberHaptic()
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
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
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50)),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    title: String,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    TopAppBar(
        title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        actions = {
            if (onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search agents and sessions")
                }
            }
            if (onSettings != null) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        },
    )
}
