package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.BoardState
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.state.BoardUiState
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.ui.screens.BoardScreen
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test

/** Board card composition: phase, model, latest activity, and needs-you emphasis. */
class BoardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun blockedAgent(
        paneId: String,
        title: String,
        cwd: String,
        model: String?,
        activity: String?,
    ) = AgentCard(
        paneId = paneId,
        workspaceId = "ws_$paneId",
        tabId = "tab_$paneId",
        agent = "pi",
        status = "blocked",
        cwd = cwd,
        title = title,
        sessionPath = "/sessions/$paneId.jsonl",
        statusSinceMs = (System.currentTimeMillis() - 90_000).toDouble(),
        model = model,
        latestActivity = activity,
        latestActivityAtMs = (System.currentTimeMillis() - 30_000).toDouble(),
    )

    private fun workingAgent(
        paneId: String,
        title: String,
        cwd: String,
        model: String?,
        activity: String?,
    ) = blockedAgent(paneId, title, cwd, model, activity).copy(status = "working")

    @Test
    fun cardsShowPhaseSectionModelActivityAndTime() {
        val ui = BoardUiState(
            board = BoardState.group(listOf(
                blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found the rounding error in the tax module"),
                workingAgent("p2", "Docs refresh", "/repo/b", "anthropic/claude-sonnet-4-6", "Updating the README with the new flags"),
            )),
            connected = true,
        )
        compose.setContent {
            CockpitTheme {
                BoardScreen(onOpenAgent = {}, viewModel = staticBoardViewModel(ui))
            }
        }

        compose.onNodeWithText("Needs you").assertIsDisplayed()
        compose.onNodeWithText("Working").assertIsDisplayed()
        compose.onNodeWithTag("agent_card_p1").assertIsDisplayed()
        compose.onNodeWithText("Found the rounding error in the tax module").assertIsDisplayed()
        compose.onNodeWithText("gpt-5.4").assertIsDisplayed()
        compose.onNodeWithText("needs you").assertIsDisplayed()
    }

    @Test
    fun skeletonShownWhileLoadingWithNoAgents() {
        compose.setContent {
            CockpitTheme {
                BoardScreen(onOpenAgent = {}, viewModel = staticBoardViewModel(BoardUiState(loading = true)))
            }
        }
        // Stable skeleton rows render instead of a centered spinner.
        compose.onNodeWithTag("board_skeleton", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun emptyStateWhenNoAgents() {
        compose.setContent {
            CockpitTheme {
                BoardScreen(onOpenAgent = {}, viewModel = staticBoardViewModel(BoardUiState(connected = true)))
            }
        }
        compose.onNodeWithText("No agents running").assertIsDisplayed()
    }

    private fun staticBoardViewModel(ui: BoardUiState): BoardViewModel {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connection = ConnectionStore(context)
        // Unsaved connection: the VM init never polls, so the UI stays static.
        val bridge = dev.cockpit.app.net.BridgeClient(okhttp3.OkHttpClient(), connection)
        return BoardViewModel(bridge, connection, initialState = ui)
    }
}
