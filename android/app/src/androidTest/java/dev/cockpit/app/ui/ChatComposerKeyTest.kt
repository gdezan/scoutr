package dev.cockpit.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import dev.cockpit.app.data.SlashCommandInfo
import dev.cockpit.app.ui.screens.ChatComposer
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Composer keyboard contract: Enter inserts a newline and never sends;
 * sending happens only via the send button. This pins the S24 Ultra
 * feedback ("enter should write a new line") against regressions.
 */
class ChatComposerKeyTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enterInsertsNewlineWithoutSending() {
        val input = mutableStateOf("")
        var sends = 0
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input.value,
                    onValueChange = { input.value = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    onSend = { sends += 1 },
                )
            }
        }

        compose.onNodeWithTag("chat_input").performTextInput("abc")
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("chat_input").performTextInput("def")

        compose.runOnIdle {
            assertEquals("abc\ndef", input.value)
            assertEquals(0, sends)
        }
        compose.onNodeWithTag("chat_input").assertTextEquals("abc\ndef")
    }

    @Test
    fun sendButtonStillSends() {
        val input = mutableStateOf("")
        var sends = 0
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input.value,
                    onValueChange = { input.value = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    onSend = { sends += 1 },
                )
            }
        }

        compose.onNodeWithTag("chat_input").performTextInput("hello")
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun slashCompletionEnterStillCompletes() {
        val input = mutableStateOf("")
        var sends = 0
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input.value,
                    onValueChange = { input.value = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = listOf(
                        SlashCommandInfo(
                            name = "compact",
                            description = "Compact the context",
                            source = "builtin",
                        ),
                    ),
                    onSend = { sends += 1 },
                )
            }
        }

        compose.onNodeWithTag("chat_input").performTextInput("/comp")
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("/compact", input.value)
            assertEquals(0, sends)
        }
    }
}
