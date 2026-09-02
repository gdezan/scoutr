package dev.scoutr.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.NestedPiSubagent
import dev.scoutr.app.data.SessionSubagent
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.liveSessionFixture
import dev.scoutr.app.state.BoardTestLoop
import dev.scoutr.app.state.jvmBoardHarness
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nested PI-workflow rows live inside the parent card. Tapping them (or an
 * orphan stamp) must not open Chat; the parent card still does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardNestedSubagentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cardTitlePrefersSubagentLabelThenRole() {
        val labeled = liveSessionFixture(
            paneId = "orphan-1",
            workspaceId = "ws",
            tabId = "tab",
            title = "cloned parent title",
            subagent = SessionSubagent(runId = "run-a", role = "researcher", label = "Trace nest join"),
        )
        val roleOnly = labeled.copy(
            subagent = SessionSubagent(runId = "run-a", role = "researcher"),
        )
        val parent = liveSessionFixture(
            paneId = "parent-1",
            workspaceId = "ws",
            tabId = "tab",
            title = "π - parent work",
        )
        assertEquals("Trace nest join", labeled.cardTitle())
        assertEquals("researcher", roleOnly.cardTitle())
        assertEquals("parent work", parent.cardTitle())
    }

    @Test
    fun nestedRowOpensProgressNotParentChat() {
        val parent = liveSessionFixture(
            paneId = "parent-pane",
            workspaceId = "ws",
            tabId = "tab",
            title = "Parent session",
            status = "working",
            subagents = listOf(
                NestedPiSubagent(
                    runId = "run-abc",
                    paneId = "child-pane",
                    role = "scout",
                    label = "Find nest join",
                    status = "working",
                ),
            ),
        )
        val openedAgents = mutableListOf<String>()
        val openedSubagents = mutableListOf<String>()
        showBoard(listOf(parent), openedAgents, openedSubagents)

        compose.onNodeWithTag("agent_card_parent-pane").assertIsDisplayed()
        compose.onNodeWithTag("board_subagent_row_run-abc").assertIsDisplayed()
        compose.onNodeWithContentDescription("WORKING 1").assertIsDisplayed()

        compose.onNodeWithTag("board_subagent_row_run-abc").performClick()
        assertEquals(listOf("run-abc"), openedSubagents)
        assertTrue("nested row must not open Chat", openedAgents.isEmpty())

        compose.onNodeWithText("Parent session").performClick()
        assertEquals(listOf("parent-pane"), openedAgents)
    }

    @Test
    fun orphanCardOpensProgressNotChat() {
        val orphan = liveSessionFixture(
            paneId = "orphan-pane",
            workspaceId = "ws",
            tabId = "tab",
            title = "cloned parent title",
            status = "blocked",
            subagent = SessionSubagent(
                runId = "run-orphan",
                role = "worker",
                label = "Orphan run",
                orphan = true,
            ),
        )
        val openedAgents = mutableListOf<String>()
        val openedSubagents = mutableListOf<String>()
        showBoard(listOf(orphan), openedAgents, openedSubagents)

        compose.onNodeWithTag("agent_card_orphan-pane").assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithTag("board_subagent_row_run-orphan").fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithTag("agent_card_orphan-pane").performClick()
        assertEquals(listOf("run-orphan"), openedSubagents)
        assertTrue("orphan card must not open Chat", openedAgents.isEmpty())
    }

    private fun showBoard(
        sessions: List<SessionDescriptor>,
        openedAgents: MutableList<String>,
        openedSubagents: MutableList<String>,
    ) {
        val h = jvmBoardHarness()
        h.addHost("host-a")
        h.apiFor("host-a").agentsResult = Result.success(AgentsResponse(agents = sessions))
        val vm = h.viewModel()
        vm.startPolling()
        BoardTestLoop.waitUntil { vm.ui.value.hostedSessions.isNotEmpty() }
        compose.setContent {
            ScoutrTheme {
                BoardScreen(
                    onOpenAgent = { openedAgents += it.session.live?.paneId.orEmpty() },
                    onOpenSubagent = { _, runId -> openedSubagents += runId },
                    viewModel = vm,
                )
            }
        }
        compose.waitForIdle()
    }
}
