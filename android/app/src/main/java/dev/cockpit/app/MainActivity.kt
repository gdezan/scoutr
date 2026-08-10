package dev.cockpit.app

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.state.NewSessionViewModel
import dev.cockpit.app.state.ChatViewModel
import dev.cockpit.app.state.UsageViewModel
import dev.cockpit.app.ui.screens.BoardScreen
import dev.cockpit.app.ui.screens.NewSessionSheet
import dev.cockpit.app.ui.screens.ChatScreen
import dev.cockpit.app.ui.screens.ConnectScreen
import dev.cockpit.app.ui.screens.UsageScreen
import dev.cockpit.app.ui.theme.CockpitTheme

private object Routes {
    const val CONNECT = "connect"
    const val BOARD = "board"
    const val CHAT = "chat/{paneId}?sessionPath={sessionPath}&status={status}"
    const val USAGE = "usage"

    fun chat(paneId: String, sessionPath: String?, status: String): String =
        "chat/$paneId?sessionPath=${sessionPath?.let { java.net.URLEncoder.encode(it, "UTF-8") } ?: ""}&status=$status"
}

class MainActivity : ComponentActivity() {

    // Android 13+ requires a runtime opt-in before notifications can show.
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            CockpitTheme {
                CockpitAppNav()
            }
        }
    }
}

@Composable
private fun CockpitAppNav() {
    val app = LocalContext.current.applicationContext as CockpitApp
    val navController = rememberNavController()
    val container = app.container
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    var startDestination by remember {
        mutableStateOf(if (container.connectionStore.saved != null) Routes.BOARD else Routes.CONNECT)
    }

    val showBottomBar = currentRoute == Routes.BOARD || currentRoute == Routes.USAGE

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.BOARD,
                        onClick = {
                            navController.navigate(Routes.BOARD) {
                                popUpTo(Routes.BOARD) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.GridView, contentDescription = null) },
                        label = { Text("Board") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.USAGE,
                        onClick = {
                            navController.navigate(Routes.USAGE) {
                                popUpTo(Routes.BOARD)
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.DonutLarge, contentDescription = null) },
                        label = { Text("Usage") },
                    )
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(inner),
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
                val boardViewModel: BoardViewModel = viewModel(
                    factory = BoardViewModel.factory(
                        container.bridge,
                        container.connectionStore,
                        container.ntfy,
                        container::showAgentNotification,
                    ),
                )
                val newSessionViewModel: NewSessionViewModel = viewModel(
                    factory = NewSessionViewModel.factory(
                        container.bridge,
                        container.launcherSettingsStore,
                    ),
                )
                var showNewSession by remember { mutableStateOf(false) }
                Scaffold(
                    topBar = { AppTopBar("Board") },
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
                Scaffold(topBar = { AppTopBar("Usage") }) { innerUsage ->
                    UsageScreen(
                        viewModel = usageViewModel,
                        modifier = Modifier.padding(innerUsage),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(title: String) {
    TopAppBar(
        title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}
