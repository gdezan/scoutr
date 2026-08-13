package dev.scoutr.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
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
import org.junit.Assert.assertTrue
import dev.scoutr.app.data.SlashCommandInfo
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.ui.screens.ChatComposer
import dev.scoutr.app.ui.theme.ScoutrTheme
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
        val input = mutableStateOf(TextFieldValue())
        var sends = 0
        compose.setContent {
            ScoutrTheme {
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
            assertEquals("abc\ndef", input.value.text)
            assertEquals(0, sends)
        }
        compose.onNodeWithTag("chat_input").assertTextEquals("abc\ndef")
    }

    @Test
    fun sendButtonStillSends() {
        val input = mutableStateOf(TextFieldValue())
        var sends = 0
        compose.setContent {
            ScoutrTheme {
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
        val input = mutableStateOf(TextFieldValue())
        var sends = 0
        compose.setContent {
            ScoutrTheme {
                ChatComposer(
                    value = input.value,
                    onValueChange = { input.value = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    commands = Loadable.Ready(
                        listOf(
                            SlashCommandInfo(
                                name = "compact",
                                description = "Compact the context",
                                source = "builtin",
                            ),
                        ),
                    ),
                    onSend = { sends += 1 },
                )
            }
        }

        compose.onNodeWithTag("chat_input").performTextInput("/comp")
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("/compact", input.value.text)
            assertEquals(0, sends)
        }
    }
    @Test
    fun attachAndSendIconsNeverOverlap() {
        compose.setContent {
            ScoutrTheme {
                ChatComposer(
                    value = TextFieldValue("hello"),
                    onValueChange = {},
                    placeholder = "Steer the agent…",
                    enabled = true,
                    onSend = {},
                )
            }
        }

        val attach = compose.onNodeWithContentDescription("Attach image")
            .fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Send")
            .fetchSemanticsNode().boundsInRoot

        // The two actions must sit side by side, never stacked on top of each other
        // (S24 feedback: the send icon overlapped the attach icon).
        assertTrue("attach right edge (${attach.right}) must not pass send left edge (${send.left})", attach.right <= send.left + 1f)
        assertTrue("attach must be to the left of send", attach.left < send.left)
        assertTrue("icons must share the same vertical band", attach.top < send.bottom && send.top < attach.bottom)
    }
}
