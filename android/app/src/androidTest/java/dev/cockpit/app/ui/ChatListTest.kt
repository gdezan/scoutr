package dev.cockpit.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.cockpit.app.data.ContentBlock
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.ui.screens.ChatList
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Rule
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

    private fun tallList(n: Int = 40): List<SessionEntry> = entries(n)

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
        // Collapsed: the chip shows the collapsed caret; tap expands it, the
        // caret flips and the full output becomes visible.
        composeRule.onNodeWithText("▸ bash", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("tool_result").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("▾ bash", substring = true).assertIsDisplayed()
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
