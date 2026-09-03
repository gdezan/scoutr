package dev.scoutr.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.ui.screens.ChatList
import dev.scoutr.app.ui.theme.ScoutrTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test

/**
 * Evidence pool acceptance: each thinking/prose block anchors its own live
 * pool where the work ran (never a turn total after the final prose), and
 * tapping a command row expands to the full command plus its output.
 */
class EvidencePillTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun bashCall(id: String, command: String) = ContentBlock(
        type = "toolCall",
        id = id,
        name = "bash",
        arguments = buildJsonObject { put("command", command) },
    )

    private fun bashResult(id: String, callId: String, output: String) = SessionEntry(
        entryId = id,
        role = "toolResult",
        content = listOf(ContentBlock(type = "text", text = output)),
        toolCallId = callId,
        toolName = "bash",
    )

    private fun turnEntries() = listOf(
        SessionEntry(
            entryId = "u1",
            role = "user",
            content = listOf(ContentBlock(type = "text", text = "check the tree")),
        ),
        SessionEntry(
            entryId = "a1",
            role = "assistant",
            content = listOf(
                ContentBlock(type = "thinking", thinking = "plan A"),
                bashCall("c1", "ls -la"),
            ),
        ),
        bashResult("r1", "c1", "total 0"),
        SessionEntry(
            entryId = "a2",
            role = "assistant",
            content = listOf(
                ContentBlock(type = "thinking", thinking = "plan B"),
                bashCall("c2", "git status --short --branch"),
            ),
        ),
        bashResult("r2", "c2", "## main"),
        SessionEntry(
            entryId = "a3",
            role = "assistant",
            content = listOf(ContentBlock(type = "text", text = "done")),
        ),
    )

    @Test
    fun eachContentSegmentAnchorsItsOwnPool() {
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = turnEntries()) }
        }

        // One pool per thinking block — not one turn total after "done".
        composeRule.onAllNodesWithTag("evidence_pill").assertCountEquals(2)
        composeRule.onAllNodesWithText("1 command", substring = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("2 commands", substring = true).assertCountEquals(0)
    }

    @Test
    fun tappingPillExpandsCommandToFullCommandAndOutput() {
        composeRule.setContent {
            ScoutrTheme { ChatList(entries = turnEntries()) }
        }

        composeRule.onAllNodesWithTag("evidence_pill")[0].performClick()
        composeRule.onNodeWithTag("evidence_sheet").assertIsDisplayed()

        // Collapsed rows show the command that was run.
        composeRule.onNodeWithText("ls -la", substring = true).assertIsDisplayed()

        // Expanding the first row reveals the full command plus its output.
        composeRule.onAllNodesWithTag("evidence_row")[0].performClick()
        composeRule.onNodeWithTag("command_detail").assertIsDisplayed()
        composeRule.onNodeWithText("total 0", substring = true).assertIsDisplayed()

        // Both pills plus the sheet header agree on the count.
        composeRule.onAllNodesWithText("1 command", substring = true).assertCountEquals(3)
    }
}
