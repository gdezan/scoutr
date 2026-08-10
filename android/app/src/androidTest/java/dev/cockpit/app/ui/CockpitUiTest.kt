package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.BoardState
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.BoardUiState
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.ui.screens.ConnectScreen
import dev.cockpit.app.ui.screens.BoardScreen
import dev.cockpit.app.CockpitBottomBar
import dev.cockpit.app.ui.theme.CockpitTheme
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

class CockpitUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * BoardViewModel pinned to a fixed UI state; never touches the network.
     * The store is wiped first so a connection saved on the device (e.g. from an
     * earlier live session) cannot trigger a real connect() from init.
     */
    private fun fakeBoard(state: BoardUiState): BoardViewModel {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connectionStore = ConnectionStore(context).also { it.clear() }
        return BoardViewModel(
            bridge = BridgeClient(OkHttpClient(), connectionStore),
            connectionStore = connectionStore,
            initialState = state,
        )
    }
    @Test
    fun connectScreen_disablesButtonUntilFieldsFilled() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connectionStore = ConnectionStore(context).also { it.clear() }
        val vm = dev.cockpit.app.state.ConnectViewModel(
            BridgeClient(OkHttpClient(), connectionStore),
            connectionStore,
        )
        composeRule.setContent {
            CockpitTheme {
                ConnectScreen(onConnected = {}, viewModel = vm)
            }
        }
        val button = composeRule.onNodeWithTag("connect_button")
        button.assertIsNotEnabled() // disabled initially

        composeRule.onNodeWithTag("connect_host").performTextInput("https://artemis.example.ts.net")
        composeRule.onNodeWithTag("connect_token").performTextInput("cockpit_test")
        button.assertIsEnabled() // enabled after fields filled
    }

    @Test
    fun connectScreen_showsScanQrButton() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connectionStore = ConnectionStore(context).also { it.clear() }
        val vm = dev.cockpit.app.state.ConnectViewModel(
            BridgeClient(OkHttpClient(), connectionStore),
            connectionStore,
        )
        composeRule.setContent {
            CockpitTheme {
                ConnectScreen(onConnected = {}, viewModel = vm)
            }
        }
        composeRule.onNodeWithTag("connect_scan").assertExists()
        composeRule.onNodeWithText("Scan QR code").assertExists()
    }

    @Test
    fun boardScreen_rendersNeedsYouSectionFirst() {
        val state = BoardUiState(
            connected = true,
            loading = false,
            board = BoardState.group(
                listOf(
                    AgentCard(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        agent = "pi",
                        status = "blocked",
                        title = "hestia",
                        cwd = "/home/gdezan/Dev/hestia",
                    ),
                    AgentCard(
                        paneId = "w2:p1",
                        workspaceId = "w2",
                        tabId = "w2:t1",
                        agent = "pi",
                        status = "working",
                        title = "agents-mobile",
                    ),
                ),
            ),
        )

        // Render the BoardScreen with a fixed state via a fake view model.
        val fakeVm = fakeBoard(state)
        composeRule.setContent {
            CockpitTheme {
                BoardScreen(onOpenAgent = {}, viewModel = fakeVm)
            }
        }

        composeRule.onNodeWithText("Needs you").assertExists()
        composeRule.onNodeWithText("Working").assertExists()
        composeRule.onNodeWithText("hestia").assertExists()
        composeRule.onNodeWithText("agents-mobile").assertExists()
    }

    @Test
    fun bottomBar_badgeWithNeedsYouCountRendersWithoutCrashing() {
        // Regression: the needs-you badge used Modifier.padding with negative
        // values, which Compose rejects at composition — any session needing
        // the user crashed the app on startup. The badge must render.
        composeRule.setContent {
            CockpitTheme {
                CockpitBottomBar(currentRoute = "board", needsYouCount = 3, onSelect = {})
            }
        }
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("9+").assertDoesNotExist()
    }

    @Test
    fun boardScreen_showsEmptyState() {
        val fakeVm = fakeBoard(BoardUiState(connected = true, loading = false))
        composeRule.setContent {
            CockpitTheme {
                BoardScreen(onOpenAgent = {}, viewModel = fakeVm)
            }
        }
        composeRule.onNodeWithText("No agents running").assertExists()
    }
}
