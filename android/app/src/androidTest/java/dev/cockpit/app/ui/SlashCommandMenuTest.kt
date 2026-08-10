package dev.cockpit.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
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
import dev.cockpit.app.data.SlashCommandInfo
import dev.cockpit.app.ui.screens.ChatComposer
import dev.cockpit.app.ui.theme.CockpitTheme
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
        var input by mutableStateOf("/")
        var sends = 0
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = commands,
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
        var input by mutableStateOf("/")
        var currentCommands by mutableStateOf(emptyList<SlashCommandInfo>())
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var retried = false
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = currentCommands,
                    commandsLoading = loading,
                    commandsError = error,
                    onRetryCommands = { retried = true },
                    onSend = {},
                )
            }
        }

        compose.onNodeWithText("Loading commands…").assertIsDisplayed()
        compose.runOnIdle { loading = false; error = "Commands unavailable" }
        compose.onNodeWithText("Commands unavailable").assertIsDisplayed()
        compose.onNodeWithTag("slash_command_retry").performClick()
        assertTrue(retried)

        compose.runOnIdle { error = null }
        compose.onNodeWithText("No commands available").assertIsDisplayed()
        compose.runOnIdle { currentCommands = commands; input = "/zzz" }
        compose.onNodeWithText("No commands match “zzz”").assertIsDisplayed()
    }

    @Test
    fun longCommandListsScroll() {
        var input by mutableStateOf("/")
        val many = (1..30).map { SlashCommandInfo("skill:item-$it", "Skill $it", "skill", "<request>") }
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = many,
                    onSend = {},
                )
            }
        }

        compose.onNodeWithTag("slash_command_list").performScrollToIndex(29)
        compose.onNodeWithTag("slash_command_skill:item-30").assertIsDisplayed()
    }
}
