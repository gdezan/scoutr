package dev.scoutr.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.scoutr.app.data.BoardState
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.encode
import dev.scoutr.app.data.liveSessionFixture
import dev.scoutr.app.state.BoardUiState
import dev.scoutr.app.state.BoardViewModel
import dev.scoutr.app.ui.screens.PanelSelection
import dev.scoutr.app.ui.screens.SessionPanel
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The wide window's session panel: the live board list in 320dp, compact rows
 * with no swipe-to-reveal, and a highlight derived from the open chat route.
 */
class SessionPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun agent(paneId: String, title: String, status: String = "blocked") = liveSessionFixture(
        paneId = paneId,
        workspaceId = "ws_$paneId",
        tabId = "tab_$paneId",
        agentKind = "pi",
        status = status,
        cwd = "/repo/$paneId",
        title = title,
        key = SessionKey("pi", "/sessions/$paneId.jsonl"),
        statusSinceMs = (System.currentTimeMillis() - 90_000).toDouble(),
        model = "openai-codex/gpt-5.4",
        latestActivity = "Found the rounding error in the tax module",
        updatedAtMs = (System.currentTimeMillis() - 30_000).toDouble(),
    )

    private fun showPanel(
        agents: List<SessionDescriptor>,
        selection: PanelSelection? = null,
        onOpenSession: (SessionDescriptor) -> Unit = {},
        onCloseAgent: (SessionDescriptor) -> Unit = {},
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = ConnectionStore(context).apply { clear() }
        // Unsaved connection: the VM init never polls, so the UI stays static.
        val bridge = dev.scoutr.app.net.BridgeClient(okhttp3.OkHttpClient(), connection)
        val viewModel = BoardViewModel(
            bridge,
            connection,
            initialState = BoardUiState(
                board = BoardState.group(agents),
                connected = true,
                apiCompatibility = ScoutrApiCompatibility.Compatible,
            ),
        )
        compose.setContent {
            ScoutrTheme {
                Box(Modifier.width(320.dp)) {
                    SessionPanel(
                        viewModel = viewModel,
                        selection = selection,
                        onOpenSession = onOpenSession,
                        onReviewAgent = {},
                        onCloseAgent = onCloseAgent,
                        onQuickAnswer = { _, _ -> },
                        onNewSession = {},
                        onSettings = {},
                        onTerminal = {},
                        onResolveCompatibility = {},
                    )
                }
            }
        }
    }

    @Test
    fun panelShowsItsOwnHeaderSectionsAndRows() {
        showPanel(listOf(agent("p1", "Fix billing bug")))

        compose.onNodeWithTag("board_session_panel").assertIsDisplayed()
        compose.onNodeWithText("Board").assertIsDisplayed()
        compose.onNodeWithContentDescription("NEEDS YOU 1").assertIsDisplayed()
        compose.onNodeWithTag("panel_agent_card_p1").assertIsDisplayed()
        compose.onNodeWithTag("panel_new_session").assertIsDisplayed()
    }

    @Test
    fun compactRowsCarryNoSwipeRevealBar() {
        // 156dp of reveal does not fit a 320dp column, so the reveal actions
        // exist only in the overflow menu here.
        showPanel(listOf(agent("p1", "Fix billing bug")))

        compose.onNodeWithTag("board_action_review_p1").assertDoesNotExist()
        compose.onNodeWithTag("board_action_copy_p1").assertDoesNotExist()
        compose.onNodeWithTag("board_action_close_p1").assertDoesNotExist()
    }

    @Test
    fun overflowMenuKeepsReviewCopyAndClose() {
        showPanel(listOf(agent("p1", "Fix billing bug")))

        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_review_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_menu_copy_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_menu_close_p1").assertIsDisplayed()
    }

    @Test
    fun theOpenChatsRowReportsSelected() {
        val open = agent("p1", "Fix billing bug")
        showPanel(
            listOf(open, agent("p2", "Docs refresh", status = "working")),
            selection = PanelSelection(sessionKey = open.key?.encode()),
        )

        compose.onNodeWithTag("panel_agent_card_p1").assertIsSelected()
        compose.onNodeWithTag("panel_agent_card_p2").assertIsNotSelected()
    }

    @Test
    fun aBootstrapChatHighlightsByPaneIdBeforeItsRouteConverges() {
        showPanel(
            listOf(agent("p1", "Fix billing bug")),
            selection = PanelSelection(paneId = "p1"),
        )

        compose.onNodeWithTag("panel_agent_card_p1").assertIsSelected()
    }

    @Test
    fun offChatNoRowIsHighlighted() {
        showPanel(listOf(agent("p1", "Fix billing bug")), selection = null)

        compose.onNodeWithTag("panel_agent_card_p1").assertIsNotSelected()
    }

    @Test
    fun tappingARowOpensThatSession() {
        var opened: String? = null
        showPanel(listOf(agent("p1", "Fix billing bug")), onOpenSession = { opened = it.live?.paneId })

        compose.onNodeWithTag("panel_agent_card_p1").performClick()
        compose.waitForIdle()
        assertEquals("p1", opened)
    }

    @Test
    fun closeFromThePanelStaysConfirmationGated() {
        var closed: String? = null
        showPanel(listOf(agent("p1", "Fix billing bug")), onCloseAgent = { closed = it.live?.paneId })

        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_close_p1").performClick()
        compose.waitForIdle()
        assertEquals("close must not fire before the confirm", null, closed)
        compose.onNodeWithText("Close agent?").assertIsDisplayed()
    }

    @Test
    fun emptyBoardKeepsItsEmptyStateInThePanel() {
        showPanel(emptyList())

        compose.onNodeWithText("No agents running").assertIsDisplayed()
    }
}
