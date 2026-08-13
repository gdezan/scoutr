package dev.scoutr.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.scoutr.app.data.SlashCommandInfo
import dev.scoutr.app.state.FailureKind
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.ui.screens.ChatComposer
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SlashCommandMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val commands = listOf(
        SlashCommandInfo("compact", "Compact the session", "builtin"),
        SlashCommandInfo("copy", "Copy the last message", "builtin"),
        SlashCommandInfo("skill:research", "Research a topic", "skill", "<request>"),
    )

    @Test
    fun filtersAndSelectsWithTouchOrKeyboard() {
        var input by mutableStateOf(TextFieldValue("/", TextRange(1)))
        var sends = 0
        compose.setContent {
            ScoutrTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = Loadable.Ready(commands),
                    onSend = { sends += 1 },
                )
            }
        }

        compose.onNodeWithTag("slash_command_menu").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").performTextReplacement("/comp")
        compose.onNodeWithText("BUILT-IN").assertIsDisplayed()
        compose.onNodeWithTag("slash_command_compact").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat_input").assertTextEquals("/compact")
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, sends) }

        compose.onNodeWithTag("chat_input").performTextReplacement("/")
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("chat_input").assertTextEquals("/copy")

        compose.onNodeWithTag("chat_input").performTextReplacement("/skill:r")
        compose.onNodeWithText("SKILL").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("chat_input").assertTextEquals("/skill:research ")
    }

    @Test
    fun showsLoadingErrorEmptyAndNoMatchStates() {
        var input by mutableStateOf(TextFieldValue("/", TextRange(1)))
        var currentCommands by mutableStateOf<Loadable<List<SlashCommandInfo>>>(Loadable.Idle)
        var retried = false
        compose.setContent {
            ScoutrTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = currentCommands,
                    onRetryCommands = { retried = true },
                    onSend = {},
                )
            }
        }

        compose.onNodeWithText("Loading commands…").assertIsDisplayed()
        compose.runOnIdle { currentCommands = Loadable.Failed("Commands unavailable", FailureKind.Server) }
        compose.onNodeWithText("Commands unavailable").assertIsDisplayed()
        compose.onNodeWithTag("slash_command_retry").performClick()
        assertTrue(retried)

        compose.runOnIdle { currentCommands = Loadable.Ready(emptyList()) }
        compose.onNodeWithText("No commands available").assertIsDisplayed()
        compose.runOnIdle { currentCommands = Loadable.Ready(commands); input = TextFieldValue("/zzz", TextRange(4)) }
        compose.onNodeWithText("No commands match “zzz”").assertIsDisplayed()
    }

    @Test
    fun longCommandListsScroll() {
        var input by mutableStateOf(TextFieldValue("/", TextRange(1)))
        val many = (1..30).map { SlashCommandInfo("skill:item-$it", "Skill $it", "skill", "<request>") }
        compose.setContent {
            ScoutrTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = Loadable.Ready(many),
                    onSend = {},
                )
            }
        }

        compose.onNodeWithTag("slash_command_list").performScrollToIndex(29)
        compose.onNodeWithTag("slash_command_skill:item-30").assertIsDisplayed()
    }
}
