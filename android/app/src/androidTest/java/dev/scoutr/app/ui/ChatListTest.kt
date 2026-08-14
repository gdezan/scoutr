package dev.scoutr.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.ui.motion.LocalReduceMotion
import dev.scoutr.app.state.MessageDeliveryState
import dev.scoutr.app.state.PendingUserMessage
import dev.scoutr.app.ui.screens.ChatList
import dev.scoutr.app.ui.theme.ScoutrTheme
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
            ScoutrTheme { ChatList(entries = list) }
        }
        // The last entry must be visible without any user scroll.
        composeRule.onNodeWithText("last message").assertIsDisplayed()
        composeRule.onNodeWithText("message 0").assertIsNotDisplayed()
    }

    @Test
    fun scrolledUpShowsFabAndTapReturnsToEnd() {
        val list = tallList()
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = list) }
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
            ScoutrTheme { ChatList(entries = list) }
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
    fun opensAtTrueBottomNotJustLastMessage() {
        // A tall final message: showing it at all would pass the old "last
        // message visible" check even while scrolled up inside it. The FAB
        // must stay hidden, which only happens at the true bottom.
        val tail = (0 until 120).joinToString("\n") { "tail line $it" }
        val list = tallList(30) + SessionEntry(
            entryId = "id-tail",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = tail)),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = list) }
        }
        // The open-at-bottom scroll converges across several frames (lazy
        // measurement); waitUntil polls past the coroutine's retries.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("scroll_to_end_fab").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("tail line 119", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("scroll_to_end_fab").assertIsNotDisplayed()
        composeRule.onNodeWithText("tail line 119", substring = true).assertIsDisplayed()
    }

    @Test
    fun fabTapReachesTrueBottom() {
        val tail = (0 until 120).joinToString("\n") { "tail line $it" }
        val list = tallList(30) + SessionEntry(
            entryId = "id-tail",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = tail)),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = list) }
        }
        // Wait for the open-at-bottom scroll to settle before interacting.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("scroll_to_end_fab").fetchSemanticsNodes().isEmpty()
        }
        // Scroll up inside the tall tail, then tap the FAB: it must land on
        // the true bottom (FAB hidden again), not merely show the last entry.
        composeRule.onNodeWithTag("chat_list").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_list").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("scroll_to_end_fab").assertIsDisplayed()
        composeRule.onNodeWithTag("scroll_to_end_fab").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("scroll_to_end_fab").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("scroll_to_end_fab").assertIsNotDisplayed()
        composeRule.onNodeWithText("tail line 119", substring = true).assertIsDisplayed()
    }

    @Test
    fun answeredQuestionShowsAsBubbleNotCard() {
        val entry = SessionEntry(
            entryId = "qa-1",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "asking…")),
        )
        val answered = QuestionEntry(
            id = "call1#0",
            question = "Proceed?",
            header = "Confirm",
            options = emptyList(),
            multiSelect = false,
            answered = true,
            answerText = "Yes",
            selected = emptyList(),
            timestamp = "2026-08-10T10:00:00.000Z",
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(entry), questions = listOf(answered)) }
        }
        composeRule.onNodeWithTag("question_answer_call1#0").assertIsDisplayed()
        composeRule.onNodeWithText("Yes").assertIsDisplayed()
        composeRule.onNodeWithTag("question_card_call1#0").assertDoesNotExist()
    }


    @Test
    fun startingIndicatorShowsForBrandNewSessionWithPendingMessage() {
        // A new session whose first message is queued shows an explicit
        // "starting" stage under the pending bubble instead of a bare empty
        // chat, so a slow first response never reads as broken.
        composeRule.setContent {
            ScoutrTheme {
                ChatList(
                    entries = emptyList(),
                    pendingMessages = listOf(PendingUserMessage("local-1", "Steer the agent", MessageDeliveryState.QUEUED)),
                    showThinking = true,
                    starting = true,
                    agentStatus = "working",
                )
            }
        }
        composeRule.onNodeWithText("Steer the agent").assertIsDisplayed()
        composeRule.onNodeWithTag("working_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("Starting session…").assertIsDisplayed()
    }

    @Test
    fun workingIndicatorShowsWorkingAndTheElapsedTimer() {
        composeRule.setContent {
            ScoutrTheme {
                ChatList(
                    entries = emptyList(),
                    showThinking = true,
                    agentStatus = "working",
                    statusSinceMs = System.currentTimeMillis() - 72_000,
                )
            }
        }
        composeRule.onNodeWithTag("working_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("Working…").assertIsDisplayed()
        composeRule.onNodeWithText("1m 12s").assertIsDisplayed()
    }

    @Test
    fun workingIndicatorOmitsTheTimerWithoutAStatusStamp() {
        // No stamp, no fabricated "0s" — the label sits alone.
        composeRule.setContent {
            ScoutrTheme {
                ChatList(
                    entries = emptyList(),
                    showThinking = true,
                    agentStatus = "working",
                    statusSinceMs = null,
                )
            }
        }
        composeRule.onNodeWithTag("working_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("Working…").assertIsDisplayed()
        composeRule.onNodeWithText("0s").assertDoesNotExist()
    }

    @Test
    fun blockedWithoutAQuestionAsksTheUser() {
        composeRule.setContent {
            ScoutrTheme {
                ChatList(
                    entries = emptyList(),
                    showThinking = true,
                    agentStatus = "blocked",
                    statusSinceMs = System.currentTimeMillis() - 5_000,
                )
            }
        }
        composeRule.onNodeWithTag("working_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for you").assertIsDisplayed()
    }

    @Test
    fun blockedWithAQuestionCardShowsNoIndicator() {
        val question = QuestionEntry(
            id = "call-1#0",
            question = "Which database?",
            options = listOf(QuestionOption(label = "Postgres")),
        )
        composeRule.setContent {
            ScoutrTheme {
                ChatList(
                    entries = emptyList(),
                    questions = listOf(question),
                    showThinking = true,
                    agentStatus = "blocked",
                    hasPendingQuestion = true,
                    statusSinceMs = System.currentTimeMillis() - 5_000,
                )
            }
        }
        // The card already states the need and carries the buttons.
        composeRule.onNodeWithText("Which database?").assertIsDisplayed()
        composeRule.onNodeWithTag("working_indicator").assertDoesNotExist()
    }

    @Test
    fun idleAgentShowsNoIndicator() {
        val entry = SessionEntry(
            entryId = "id-1",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "first reply")),
        )
        composeRule.setContent {
            ScoutrTheme {
                ChatList(
                    entries = listOf(entry),
                    pendingMessages = listOf(PendingUserMessage("local-1", "Steer the agent", MessageDeliveryState.QUEUED)),
                    showThinking = true,
                    starting = false,
                    agentStatus = "idle",
                )
            }
        }
        composeRule.onNodeWithText("first reply").assertIsDisplayed()
        composeRule.onNodeWithTag("working_indicator").assertDoesNotExist()
    }

    @Test
    fun reduceMotionStillRendersTheIndicatorAndItsTimer() {
        // Reduce motion drops the ripple animation, not the surface: the
        // static ring, the label, and the timer all stay.
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                ScoutrTheme {
                    ChatList(
                        entries = emptyList(),
                        showThinking = true,
                        agentStatus = "working",
                        statusSinceMs = System.currentTimeMillis() - 3_000,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("working_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("Working…").assertIsDisplayed()
        composeRule.onNodeWithText("3s").assertIsDisplayed()
    }

    @Test
    fun pendingMessageShowsDeliveryStateAndRetriesFailure() {
        var pending by mutableStateOf(
            listOf(PendingUserMessage("local-1", "Do it now", MessageDeliveryState.QUEUED)),
        )
        var retried = false
        composeRule.setContent {
            ScoutrTheme {
                ChatList(
                    entries = emptyList(),
                    pendingMessages = pending,
                    showThinking = true,
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
            ScoutrTheme { ChatList(entries = listOf(entry)) }
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
            ScoutrTheme { ChatList(entries = listOf(entry)) }
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
            ScoutrTheme { ChatList(entries = listOf(entry)) }
        }
        // Collapsed by default even with details off: one-line no-fill row.
        composeRule.onNodeWithText("bash", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Expand bash").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Collapse bash").assertIsDisplayed()
    }

    @Test
    fun toolsToggleExpandsToolCallChip() {
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
            ScoutrTheme { ChatList(entries = listOf(entry), expandTools = true) }
        }
        // The tools toggle force-expands tool calls (thinking stays on by
        // default — the two toggles are independent).
        composeRule.onNodeWithContentDescription("Collapse read").assertIsDisplayed()
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
            ScoutrTheme { ChatList(entries = listOf(entry)) }
        }
        // This result has no tool call in the transcript to expand it from, so it
        // shows unasked rather than becoming unreachable; tapping expands it to
        // the full output (§7a gives collapsed calls "no tile of their own", but
        // that rule only applies where a chevron exists).
        composeRule.onNodeWithTag("tool_result").assertIsDisplayed()
        composeRule.onNodeWithTag("tool_result").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("line5", substring = true).assertIsDisplayed()
    }

    @Test
    fun linkedToolResultStaysHiddenUntilItsCallIsExpanded() {
        val assistant = SessionEntry(
            entryId = "assistant-collapsed",
            role = "assistant",
            content = listOf(ContentBlock(type = "toolCall", name = "bash")),
        )
        val result = SessionEntry(
            entryId = "result-collapsed",
            parentId = "assistant-collapsed",
            role = "toolResult",
            toolName = "bash",
            content = listOf(ContentBlock(type = "text", text = "quiet output")),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(assistant, result)) }
        }
        // §7a: a collapsed call is one mono line on the spine with no tile of its
        // own. The chevron is what reveals the evidence.
        composeRule.onNodeWithTag("tool_result").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Expand bash").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_result").assertIsDisplayed()
    }

    @Test
    fun failedToolResultShowsWithoutExpanding() {
        val assistant = SessionEntry(
            entryId = "assistant-failed",
            role = "assistant",
            content = listOf(ContentBlock(type = "toolCall", name = "bash")),
        )
        val result = SessionEntry(
            entryId = "result-failed",
            parentId = "assistant-failed",
            role = "toolResult",
            toolName = "bash",
            isError = true,
            content = listOf(ContentBlock(type = "text", text = "2 tests failed")),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(assistant, result)) }
        }
        // A failure is the one result worth interrupting the scan for, so it
        // breaks the collapsed pattern loudly and on its own (§7a).
        composeRule.onNodeWithTag("tool_result").assertIsDisplayed()
        composeRule.onNodeWithText("2 tests failed", substring = true).assertIsDisplayed()
    }

    @Test
    fun toolCallExpandsResultWhenTranscriptOmitsIds() {
        val assistant = SessionEntry(
            entryId = "assistant-no-id",
            role = "assistant",
            content = listOf(ContentBlock(type = "toolCall", name = "bash")),
        )
        val result = SessionEntry(
            entryId = "result-no-id",
            parentId = "assistant-no-id",
            role = "toolResult",
            toolName = "bash",
            content = listOf(ContentBlock(type = "text", text = "line1\nline2\nline3\nline4\nline5")),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(assistant, result)) }
        }
        composeRule.onNodeWithContentDescription("Expand bash").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Collapse bash").assertIsDisplayed()
        composeRule.onNodeWithText("line5", substring = true).assertIsDisplayed()
    }

    @Test
    fun thinkingVisibleByDefault() {
        val entry = SessionEntry(
            entryId = "as-1",
            role = "assistant",
            content = listOf(
                ContentBlock(type = "thinking", thinking = "hidden reasoning"),
                ContentBlock(type = "text", text = "visible answer"),
            ),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(entry)) }
        }
        // Thinking is part of the story: on by default, no toggle needed.
        composeRule.onNodeWithTag("thinking_block").assertIsDisplayed()
        composeRule.onNodeWithText("hidden reasoning").assertIsDisplayed()
        composeRule.onNodeWithText("visible answer").assertIsDisplayed()
    }

    @Test
    fun thinkingHiddenWhenToggledOff() {
        val entry = SessionEntry(
            entryId = "as-2",
            role = "assistant",
            content = listOf(
                ContentBlock(type = "thinking", thinking = "hidden reasoning"),
                ContentBlock(type = "text", text = "visible answer"),
            ),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(entry), showThinking = false) }
        }
        composeRule.onNodeWithTag("thinking_block").assertDoesNotExist()
        composeRule.onNodeWithText("visible answer").assertIsDisplayed()
    }

    @Test
    fun togglesAreIndependent() {
        val entry = SessionEntry(
            entryId = "as-3",
            role = "assistant",
            content = listOf(
                ContentBlock(type = "thinking", thinking = "hidden reasoning"),
                ContentBlock(
                    type = "toolCall",
                    id = "call-3",
                    name = "bash",
                    arguments = buildJsonObject { put("command", JsonPrimitive("npm test")) },
                ),
            ),
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(entry), showThinking = false, expandTools = true) }
        }
        // Tools expanded, thinking hidden: one toggle never affects the other.
        composeRule.onNodeWithContentDescription("Collapse bash").assertIsDisplayed()
        composeRule.onNodeWithTag("thinking_block").assertDoesNotExist()
    }
    @Test
    fun answeredQuestionStaysInTranscriptPosition() {
        // The ask happened at the top of the transcript; many turns followed.
        // The answer bubble must render next to the ask, not at the bottom.
        val askEntry = SessionEntry(
            entryId = "ask-1",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "Which fruit?")),
        )
        val later = entries(30)
        val answered = QuestionEntry(
            id = "call1#0",
            callId = "call1",
            entryId = "ask-1",
            question = "Which fruit?",
            header = "Pick",
            options = listOf(QuestionOption(label = "Apple", description = ""), QuestionOption(label = "Cherry", description = "")),
            multiSelect = false,
            answered = true,
            answerText = "Cherry",
            selected = emptyList(),
            timestamp = "2026-08-10T10:00:00.000Z",
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(askEntry) + later, questions = listOf(answered)) }
        }
        // The list opens on the transcript tail, not on the answer: the
        // bubble must NOT be pinned at the bottom next to the newest turn.
        composeRule.onNodeWithTag("chat_list").performScrollToNode(
            androidx.compose.ui.test.hasText("last message"),
        )
        composeRule.onNodeWithText("last message").assertIsDisplayed()
        composeRule.onNodeWithTag("question_answer_call1#0").assertIsNotDisplayed()
        // Scroll up to the ask: the answer bubble renders right below it in
        // transcript order, far above the tail.
        composeRule.onNodeWithTag("chat_list").performScrollToNode(
            androidx.compose.ui.test.hasText("message 0"),
        )
        composeRule.waitForIdle()
        val bubble = composeRule.onNodeWithTag("question_answer_call1#0").getBoundsInRoot()
        val askText = composeRule.onNodeWithText("Which fruit?").getBoundsInRoot()
        assertTrue("bubble below ask (bubble top ${bubble.top}, ask bottom ${askText.bottom})", bubble.top >= askText.bottom)
    }

    @Test
    fun pendingQuestionRendersNextToItsAsk() {
        val askEntry = SessionEntry(
            entryId = "ask-1",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "Ask me which fruit")),
        )
        val pending = QuestionEntry(
            id = "call1#0",
            callId = "call1",
            entryId = "ask-1",
            question = "Which fruit?",
            header = "Pick",
            options = listOf(QuestionOption(label = "Apple", description = ""), QuestionOption(label = "Cherry", description = "")),
            multiSelect = false,
            answered = false,
            timestamp = "2026-08-10T10:00:00.000Z",
        )
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = listOf(askEntry), questions = listOf(pending)) }
        }
        val card = composeRule.onNodeWithTag("question_card_call1#0").getBoundsInRoot()
        val askText = composeRule.onNodeWithText("Ask me which fruit").getBoundsInRoot()
        assertTrue("card below ask (card top ${card.top}, ask bottom ${askText.bottom})", card.top >= askText.bottom)
    }

}
