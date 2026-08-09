package dev.cockpit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.state.ConnectViewModel
import dev.cockpit.app.ui.screens.BoardScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CockpitTheme {
                CockpitAppNav()
            }
        }
    }
}

@Composable
private fun CockpitAppNav() {
    val app = (androidx.compose.ui.platform.LocalContext.current.applicationContext as CockpitApp)
    val navController = rememberNavController()
    val container = app.container

    // Decide the start destination once, based on saved credentials.
    var startDestination by remember {
        mutableStateOf(if (container.connectionStore.saved != null) Routes.BOARD else Routes.CONNECT)
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.CONNECT) {
            Scaffold(topBar = { AppTopBar("Cockpit") }) { inner ->
                ConnectScreen(
                    onConnected = {
                        startDestination = Routes.BOARD
                        navController.navigate(Routes.BOARD) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                        }
                    },
                    modifier = Modifier.padding(inner),
                )
            }
        }
        composable(Routes.BOARD) {
            val boardViewModel: BoardViewModel = viewModel(
                factory = BoardViewModel.factory(container.bridge, container.connectionStore),
            )
            Scaffold(
                topBar = { AppTopBar("Board") },
            ) { inner ->
                BoardScreen(
                    onOpenAgent = { agent ->
                        navController.navigate(Routes.chat(agent.paneId, agent.sessionPath, agent.status))
                    },
                    viewModel = boardViewModel,
                    modifier = Modifier.padding(inner),
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
            val chatViewModel: dev.cockpit.app.state.ChatViewModel = viewModel(
                factory = dev.cockpit.app.state.ChatViewModel.factory(
                    container.bridge,
                    paneId,
                    sessionPath,
                    agentStatus,
                ),
                key = "chat_$paneId",
            )
            Scaffold(topBar = { AppTopBar("Session") }) { inner ->
                ChatScreen(
                    viewModel = chatViewModel,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(inner),
                )
            }
        }
        composable(Routes.USAGE) {
            val usageViewModel: dev.cockpit.app.state.UsageViewModel = viewModel(
                factory = dev.cockpit.app.state.UsageViewModel.factory(container.bridge),
            )
            Scaffold(topBar = { AppTopBar("Usage") }) { inner ->
                UsageScreen(
                    viewModel = usageViewModel,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(inner),
                )
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
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ),
    )
}
