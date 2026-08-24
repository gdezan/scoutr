package dev.scoutr.app.ui

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.BoardState
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.net.BridgeClient
import dev.scoutr.app.state.BoardUiState
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.state.legacyBoardViewModel
import dev.scoutr.app.ui.screens.ConnectScreen
import dev.scoutr.app.ui.screens.BoardScreen
import dev.scoutr.app.ui.nav.ScoutrBottomBar
import dev.scoutr.app.ui.theme.ScoutrTheme
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

class ScoutrUiTest {

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
        return legacyBoardViewModel(
            bridge = BridgeClient(OkHttpClient(), connectionStore),
            connectionStore = connectionStore,
            initialState = state,
        )
    }
    @Test
    fun connectScreen_disablesButtonUntilFieldsFilled() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connectionStore = ConnectionStore(context).also { it.clear() }
        val vm = dev.scoutr.app.state.ConnectViewModel(
            BridgeClient(OkHttpClient(), connectionStore),
            connectionStore,
        )
        composeRule.setContent {
            ScoutrTheme {
                ConnectScreen(onConnected = {}, viewModel = vm)
            }
        }
        val button = composeRule.onNodeWithTag("connect_button")
        button.assertIsNotEnabled() // disabled initially

        composeRule.onNodeWithTag("connect_host").performTextInput("https://artemis.example.ts.net")
        composeRule.onNodeWithTag("connect_token").performTextInput("scoutr_test")
        button.assertIsEnabled() // enabled after fields filled
    }

    @Test
    fun connectScreen_showsScanQrButton() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connectionStore = ConnectionStore(context).also { it.clear() }
        val vm = dev.scoutr.app.state.ConnectViewModel(
            BridgeClient(OkHttpClient(), connectionStore),
            connectionStore,
        )
        composeRule.setContent {
            ScoutrTheme {
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
                    dev.scoutr.app.data.liveSessionFixture(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        agentKind = "pi",
                        status = "blocked",
                        title = "hestia",
                        cwd = "/home/gdezan/Dev/hestia",
                    ),
                    dev.scoutr.app.data.liveSessionFixture(
                        paneId = "w2:p1",
                        workspaceId = "w2",
                        tabId = "w2:t1",
                        agentKind = "pi",
                        status = "working",
                        title = "agents-mobile",
                    ),
                ),
            ),
        )

        // Render the BoardScreen with a fixed state via a fake view model.
        val fakeVm = fakeBoard(state)
        composeRule.setContent {
            ScoutrTheme {
                BoardScreen(onOpenAgent = {}, viewModel = fakeVm)
            }
        }

        composeRule.onNodeWithContentDescription("NEEDS YOU 1").assertExists()
        composeRule.onNodeWithContentDescription("WORKING 1").assertExists()
        composeRule.onNodeWithText("hestia").assertExists()
        composeRule.onNodeWithText("agents-mobile").assertExists()
    }

    @Test
    fun bottomBar_badgeWithNeedsYouCountRendersWithoutCrashing() {
        // Regression: the needs-you badge used Modifier.padding with negative
        // values, which Compose rejects at composition — any session needing
        // the user crashed the app on startup. The badge must render.
        composeRule.setContent {
            ScoutrTheme {
                ScoutrBottomBar(currentRoute = "board", needsYouCount = 3, onSelect = {})
            }
        }
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("9+").assertDoesNotExist()
    }

    @Test
    fun boardScreen_showsEmptyState() {
        val fakeVm = fakeBoard(BoardUiState(connected = true, loading = false))
        composeRule.setContent {
            ScoutrTheme {
                BoardScreen(onOpenAgent = {}, viewModel = fakeVm)
            }
        }
        composeRule.onNodeWithText("No agents running").assertExists()
    }
}
