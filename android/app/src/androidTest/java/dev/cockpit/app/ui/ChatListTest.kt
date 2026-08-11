package dev.cockpit.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.cockpit.app.data.ContentBlock
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.state.MessageDeliveryState
import dev.cockpit.app.state.PendingUserMessage
import dev.cockpit.app.ui.screens.ChatList
import dev.cockpit.app.ui.theme.CockpitTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat stream behaviors: opens at the last message, follows appends while at
 * the bottom, never crashes on concurrent growth, surfaces the scroll-to-end
 * button when scrolled up, tool chips expand on tap, and the details toggle
 * reveals thinking blocks.
 */
class ChatListTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entries(n: Int, lastText: String = "last message"): List<SessionEntry> =
        (0 until n).map { i ->
            SessionEntry(
                entryId = "id-$i",
                role = if (i == n - 1) "assistant" else "user",
                content = listOf(ContentBlock(type = "text", text = if (i == n - 1) lastText else "message $i")),
            )
        }

    @Test
    fun opensAtLastMessage() {
        val list = tallList()
        composeRule.setContent {
            CockpitTheme { ChatList(entries = list, detailsVisible = false) }
        }
        // The last entry must be visible without any user scroll.
        composeRule.onNodeWithText("last message").assertIsDisplayed()
        composeRule.onNodeWithText("message 0").assertIsNotDisplayed()
    }

    @Test
    fun scrolledUpShowsFabAndTapReturnsToEnd() {
        val list = tallList()
        composeRule.setContent {
            CockpitTheme { ChatList(entries = list, detailsVisible = false) }
        }
        // Scroll the list up so the end is off-screen.
        composeRule.onNodeWithTag("chat_list").performScrollToNode(
            androidx.compose.ui.test.hasText("message 5"),
        )
        composeRule.waitForIdle()
        // FAB is now visible; tapping it returns to the last message.
        composeRule.onNodeWithTag("scroll_to_end_fab").assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_end_fab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("last message").assertIsDisplayed()
    }

    @Test
    fun appendingEntriesWhileAtBottomKeepsFollowing() {
        val initial = tallList(10)
        var list by mutableStateOf(initial)
        composeRule.setContent {
            CockpitTheme { ChatList(entries = list, detailsVisible = false) }
        }
        composeRule.waitForIdle()
        // Append more entries while the user is at the bottom; the new last
        // message must be followed automatically.
        list = initial + SessionEntry(
            entryId = "id-extra",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "new tail")),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("new tail").assertIsDisplayed()
    }


    @Test
    fun inlineLiveOutputRendersTailWithTrimmedMarker() {
        composeRule.setContent {
            CockpitTheme {
                ChatList(
                    entries = emptyList(),
                    detailsVisible = false,
                    liveOutputVisible = true,
                    liveOutputLines = listOf("line a", "line b", "line c", "line d", "line e", "line f"),
                    liveOutputTruncated = true,
                )
            }
        }
        composeRule.onNodeWithTag("inline_live_output").assertIsDisplayed()
        // The card renders the tail (last 5 lines) as one joined mono text,
        // never the whole buffer.
        composeRule.onNodeWithText("line b\nline c\nline d\nline e\nline f").assertIsDisplayed()
        composeRule.onAllNodesWithText("line a", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("earlier output trimmed").assertIsDisplayed()
    }


    @Test
    fun inlineLiveOutputShowsStaleStateOnError() {
        composeRule.setContent {
            CockpitTheme {
                ChatList(
                    entries = emptyList(),
                    detailsVisible = false,
                    liveOutputVisible = true,
                    liveOutputLines = listOf("frozen line"),
                    liveOutputError = "read timed out",
                )
            }
        }
        composeRule.onNodeWithTag("inline_live_output").assertIsDisplayed()
        composeRule.onNodeWithText("STALE · RECONNECTING").assertIsDisplayed()
        composeRule.onNodeWithText("LIVE").assertDoesNotExist()
    }
    @Test
    fun inlineLiveOutputAbsentWhenNotVisible() {
        composeRule.setContent {
            CockpitTheme {
                ChatList(
                    entries = emptyList(),
                    detailsVisible = false,
                    liveOutputVisible = false,
                    liveOutputLines = emptyList(),
                )
            }
        }
        composeRule.onNodeWithTag("inline_live_output").assertDoesNotExist()
    }
    @Test
    fun startingIndicatorShowsForBrandNewSessionWithPendingMessage() {
        // Fix 8: a new session whose first message is queued shows an explicit
        // "starting" stage (spinner + label) under the pending bubble instead of
        // a bare empty chat, so a slow first response never reads as broken.
        composeRule.setContent {
            CockpitTheme {
                ChatList(
                    entries = emptyList(),
                    pendingMessages = listOf(PendingUserMessage("local-1", "Steer the agent", MessageDeliveryState.QUEUED)),
                    detailsVisible = false,
                    starting = true,
                )
            }
        }
        composeRule.onNodeWithText("Steer the agent").assertIsDisplayed()
        composeRule.onNodeWithTag("starting_session").assertIsDisplayed()
        composeRule.onNodeWithText("Starting session… waiting for the agent").assertIsDisplayed()
    }

    @Test
    fun noStartingIndicatorOnceEntriesExist() {
        // The stage is only for the first response window: once any entry lands,
        // the indicator must be gone even if a pending message remains.
        val entry = SessionEntry(
            entryId = "id-1",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "first reply")),
        )
        composeRule.setContent {
            CockpitTheme {
                ChatList(
                    entries = listOf(entry),
                    pendingMessages = listOf(PendingUserMessage("local-1", "Steer the agent", MessageDeliveryState.QUEUED)),
                    detailsVisible = false,
                    starting = false,
                )
            }
        }
        composeRule.onNodeWithText("first reply").assertIsDisplayed()
        composeRule.onNodeWithTag("starting_session").assertDoesNotExist()
    }

    @Test
    fun pendingMessageShowsDeliveryStateAndRetriesFailure() {
        var pending by mutableStateOf(
            listOf(PendingUserMessage("local-1", "Do it now", MessageDeliveryState.QUEUED)),
        )
        var retried = false
        composeRule.setContent {
            CockpitTheme {
                ChatList(
                    entries = emptyList(),
                    pendingMessages = pending,
                    detailsVisible = false,
                    onRetryPending = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("Do it now").assertIsDisplayed()
        composeRule.onNodeWithTag("pending_message_queued").assertIsDisplayed()

        pending = listOf(PendingUserMessage("local-1", "Do it now", MessageDeliveryState.FAILED))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pending_message_failed").assertIsDisplayed().performClick()
        assertTrue(retried)
    }

    /**
     * Compose puts a start and an end selection handle on screen (each in its own
     * popup) once text inside a SelectionContainer is selected. Counting them is a
     * real check on the affordance: a plain, unwrapped Text yields zero no matter
     * how long you press it. Copy itself is the platform toolbar's job from there.
     */
    private fun selectionHandleCount(): Int =
        composeRule.onAllNodes(isSelectionHandle(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    /**
     * Each handle popup carries Compose's SelectionHandleInfo semantics. The key
     * itself is internal to the foundation module, so match it by name.
     */
    private fun isSelectionHandle() = SemanticsMatcher("is a selection handle") { node ->
        node.config.any { it.key.name == "SelectionHandleInfo" }
    }

    private fun longPressText(entry: SessionEntry, message: String) {
        composeRule.setContent {
            CockpitTheme { ChatList(entries = listOf(entry), detailsVisible = false) }
        }
        val text = composeRule.onNodeWithText(message)
        text.assertIsDisplayed()
        text.performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    @Test
    fun longPressSelectsAssistantTextForCopy() {
        val entry = SessionEntry(
            entryId = "copy-a",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "a paragraph worth copying")),
        )
        longPressText(entry, "a paragraph worth copying")
        assertEquals("long press should raise both selection handles", 2, selectionHandleCount())
    }

    @Test
    fun longPressSelectsUserBubbleTextForCopy() {
        val entry = SessionEntry(
            entryId = "copy-u",
            role = "user",
            content = listOf(ContentBlock(type = "text", text = "my side of the conversation")),
        )
        longPressText(entry, "my side of the conversation")
        assertEquals("long press should raise both selection handles", 2, selectionHandleCount())
    }

    /**
     * The tool-call chip keeps its tap-to-expand gesture and is deliberately not
     * selectable, so a long press there must not start a selection. This is the
     * guard against someone "helpfully" wrapping the whole assistant bubble.
     */
    @Test
    fun longPressOnToolChipStartsNoSelection() {
        val entry = SessionEntry(
            entryId = "copy-t",
            role = "assistant",
            content = listOf(
                ContentBlock(
                    type = "toolCall",
                    id = "t1",
                    name = "bash",
                    arguments = buildJsonObject { put("command", JsonPrimitive("ls /tmp")) },
                ),
            ),
        )
        composeRule.setContent {
            CockpitTheme { ChatList(entries = listOf(entry), detailsVisible = false) }
        }
        val chip = composeRule.onNodeWithTag("tool_chip")
        chip.assertIsDisplayed()
        chip.performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals("tool chips must stay unselectable", 0, selectionHandleCount())
    }

    private fun tallList(n: Int = 40): List<SessionEntry> = entries(n)

    @Test
    fun toolCallChipShowsCollapsedByDefaultAndExpandsOnTap() {
        val entry = SessionEntry(
            entryId = "tc-1",
            role = "assistant",
            content = listOf(
                ContentBlock(
                    type = "toolCall",
                    id = "call-1",
                    name = "bash",
                    arguments = buildJsonObject { put("command", JsonPrimitive("npm test")) },
                ),
            ),
        )
        composeRule.setContent {
            CockpitTheme { ChatList(entries = listOf(entry), detailsVisible = false) }
        }
        // Collapsed by default even with details off: one-line no-fill row.
        composeRule.onNodeWithText("▸ bash", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("tool_chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("▾ bash", substring = true).assertIsDisplayed()
    }

    @Test
    fun detailsToggleExpandsToolCallChip() {
        val entry = SessionEntry(
            entryId = "tc-2",
            role = "assistant",
            content = listOf(
                ContentBlock(
                    type = "toolCall",
                    id = "call-2",
                    name = "read",
                ),
            ),
        )
        composeRule.setContent {
            CockpitTheme { ChatList(entries = listOf(entry), detailsVisible = true) }
        }
        // The details toggle force-expands tool calls.
        composeRule.onNodeWithText("▾ read", substring = true).assertIsDisplayed()
    }
    @Test
    fun toolResultChipExpandsOnTap() {
        val entry = SessionEntry(
            entryId = "tr-1",
            role = "toolResult",
            toolName = "bash",
            content = listOf(ContentBlock(type = "text", text = "line1\nline2\nline3\nline4\nline5")),
        )
        composeRule.setContent {
            CockpitTheme { ChatList(entries = listOf(entry), detailsVisible = false) }
        }
        // Collapsed: the indented faint-fill result card shows the 2-line
        // preview; tapping expands it (full output, no crash).
        composeRule.onNodeWithTag("tool_result").assertIsDisplayed()
        composeRule.onNodeWithTag("tool_result").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("line5", substring = true).assertIsDisplayed()
    }

    @Test
    fun detailsToggleRevealsThinkingBlock() {
        val entry = SessionEntry(
            entryId = "as-1",
            role = "assistant",
            content = listOf(
                ContentBlock(type = "thinking", thinking = "hidden reasoning"),
                ContentBlock(type = "text", text = "visible answer"),
            ),
        )
        composeRule.setContent {
            CockpitTheme { ChatList(entries = listOf(entry), detailsVisible = true) }
        }
        composeRule.onNodeWithTag("thinking_block").assertIsDisplayed()
        composeRule.onNodeWithText("hidden reasoning").assertIsDisplayed()
        composeRule.onNodeWithText("visible answer").assertIsDisplayed()
    }
}
