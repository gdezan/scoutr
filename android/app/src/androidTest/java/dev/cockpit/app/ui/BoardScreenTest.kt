package dev.cockpit.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import android.content.ClipboardManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.BoardState
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.state.BoardUiState
import dev.cockpit.app.state.BoardViewModel
import dev.cockpit.app.ui.screens.BoardScreen
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import java.io.FileOutputStream
/** Board card composition: phase, model, latest activity, and needs-you emphasis. */
class BoardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, overlay: Boolean = false) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!.resolve("$name.png")
        FileOutputStream(file).use { output ->
            if (overlay) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            } else {
                compose.onNodeWithTag("board_capture_root").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        println("BOARD_SCREENSHOT=${file.absolutePath}")
    }

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
        capture("board-card", overlay = true)
    }

    @Test
    fun visibleOverflowMenuExposesSharedActions() {
        var reviewed = false
        var closed: String? = null
        compose.setContent {
            CockpitTheme {
                BoardScreen(
                    onReviewAgent = { reviewed = true },
                    onCloseAgent = { closed = it.paneId },
                    viewModel = staticBoardViewModel(
                        BoardUiState(
                            board = BoardState.group(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                            connected = true,
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithTag("agent_actions_p1").assertIsDisplayed()
        compose.onNodeWithTag("agent_actions_p1").assertContentDescriptionContains("Agent actions for Fix billing bug")
        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_review_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_menu_copy_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_menu_close_p1").assertIsDisplayed()
        capture("board-menu", overlay = true)
        compose.onNodeWithTag("board_menu_review_p1").performClick()
        assertTrue("menu review should use the shared callback", reviewed)
        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_copy_p1").performClick()
        val clip = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("menu copy should use the shared callback", "/repo/a", clip)
        compose.onNodeWithTag("agent_actions_p1").performClick()
        compose.onNodeWithTag("board_menu_close_p1").performClick()
        compose.onNodeWithText("Close agent?").assertIsDisplayed()
        capture("board-close-confirm", overlay = true)
        assertEquals("close remains confirmation-gated", null, closed)
        compose.onAllNodesWithText("Close").filterToOne(hasClickAction()).performClick()
        assertEquals("confirmed menu close should use the shared callback", "p1", closed)
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

    @Test
    fun swipeLeftRevealsReviewAndFiresIt() {
        var reviewedCwd: String? = null
        var opened: String? = null
        compose.setContent {
            CockpitTheme {
                BoardScreen(
                    onOpenAgent = { opened = it.paneId },
                    onReviewAgent = { reviewedCwd = it.cwd },
                    viewModel = staticBoardViewModel(
                        BoardUiState(
                            board = BoardState.group(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                            connected = true,
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_review_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_action_review_p1").performClick()
        compose.waitForIdle()
        assertTrue("review callback should carry the agent cwd", reviewedCwd == "/repo/a")
        assertTrue("review must not also open the card", opened == null)
    }

    @Test
    fun swipeLeftRevealsCopyAndCopiesPath() {
        var opened: String? = null
        compose.setContent {
            CockpitTheme {
                BoardScreen(
                    onOpenAgent = { opened = it.paneId },
                    viewModel = staticBoardViewModel(
                        BoardUiState(
                            board = BoardState.group(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                            connected = true,
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_copy_p1").assertIsDisplayed()
        compose.onNodeWithTag("board_action_copy_p1").performClick()
        compose.waitForIdle()
        assertTrue("copy must not also open the card", opened == null)
        val clip = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("clipboard should hold the agent cwd", "/repo/a", clip)
    }

    @Test
    fun swipeLeftRevealsCloseAndFiresIt() {
        var closed: String? = null
        compose.setContent {
            CockpitTheme {
                BoardScreen(
                    onCloseAgent = { closed = it.paneId },
                    viewModel = staticBoardViewModel(
                        BoardUiState(
                            board = BoardState.group(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                            connected = true,
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_close_p1").performClick()
        compose.waitForIdle()
        // Close stops a live pane, so it asks first — same gate as Sessions.
        assertTrue("close must not fire before the confirm", closed == null)
        compose.onNodeWithText("Close agent?").assertIsDisplayed()
        capture("board-close-confirm", overlay = true)
        compose.onAllNodesWithText("Close").filterToOne(hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue("close callback should carry the pane id", closed == "p1")
    }

    @Test
    fun dismissingTheCloseConfirmLeavesTheAgentRunning() {
        var closed: String? = null
        compose.setContent {
            CockpitTheme {
                BoardScreen(
                    onCloseAgent = { closed = it.paneId },
                    viewModel = staticBoardViewModel(
                        BoardUiState(
                            board = BoardState.group(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                            connected = true,
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("board_action_close_p1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("cancelling must not close the agent", closed == null)
        compose.onNodeWithTag("agent_card_p1").assertIsDisplayed()
    }

    @Test
    fun coveredBoardActionIsNotTappable() {
        var reviewed = false
        compose.setContent {
            CockpitTheme {
                BoardScreen(
                    onReviewAgent = { reviewed = true },
                    viewModel = staticBoardViewModel(
                        BoardUiState(
                            board = BoardState.group(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                            connected = true,
                        ),
                    ),
                )
            }
        }
        // With the card covering the bar, a click at the action's coordinates
        // must hit the card (opening nothing here) — never the hidden action.
        compose.onNodeWithTag("board_action_review_p1").performClick()
        compose.waitForIdle()
        assertTrue("covered action must not fire", !reviewed)
    }

    @Test
    fun revealedActionBarMatchesRowHeight() {
        compose.setContent {
            CockpitTheme {
                BoardScreen(
                    viewModel = staticBoardViewModel(
                        BoardUiState(
                            board = BoardState.group(listOf(blockedAgent("p1", "Fix billing bug", "/repo/a", "openai-codex/gpt-5.4", "Found it"))),
                            connected = true,
                        ),
                    ),
                )
            }
        }
        val card = compose.onNodeWithTag("agent_card_p1").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("agent_card_p1").performTouchInput { swipeLeft() }
        val action = compose.onNodeWithTag("board_action_review_p1").getUnclippedBoundsInRoot()
        assertTrue(
            "action bar should match the card height",
            abs((card.bottom - card.top).value - (action.bottom - action.top).value) < 1f,
        )
    }

    private fun staticBoardViewModel(ui: BoardUiState): BoardViewModel {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connection = ConnectionStore(context)
        // Unsaved connection: the VM init never polls, so the UI stays static.
        val bridge = dev.cockpit.app.net.BridgeClient(okhttp3.OkHttpClient(), connection)
        return BoardViewModel(bridge, connection, initialState = ui)
    }
}
