package dev.cockpit.app.ui

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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.cockpit.app.data.FileListing
import dev.cockpit.app.state.FailureKind
import dev.cockpit.app.state.Loadable
import dev.cockpit.app.ui.screens.ChatComposer
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class FileMentionMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private val listing = FileListing(
        path = "/work/project",
        files = listOf(
            "README.md",
            "android/app/ChatScreen.kt",
            "android/app/Screener.kt",
            "bridge/src/files.ts",
        ),
    )

    @Test
    fun browsesDrillsDownAndInsertsAPath() {
        var input by mutableStateOf(TextFieldValue())
        var opens = 0
        var sends = 0
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    files = Loadable.Ready(listing),
                    onOpenMention = { opens += 1 },
                    onSend = { sends += 1 },
                )
            }
        }

        // A bare `@` browses the top level: directories first, then files.
        compose.onNodeWithTag("chat_input").performTextReplacement("look at @")
        compose.onNodeWithTag("file_mention_menu").assertIsDisplayed()
        compose.onNodeWithTag("file_mention_android/").assertIsDisplayed()
        compose.onNodeWithTag("file_mention_README.md").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, opens) }

        // Tapping a directory drills in without closing the menu or refetching.
        compose.onNodeWithTag("file_mention_android/").performClick()
        compose.onNodeWithTag("chat_input").assertTextEquals("look at @android/")
        compose.onNodeWithTag("file_mention_menu").assertIsDisplayed()
        compose.onNodeWithTag("file_mention_android/app/").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opens) }

        // Enter completes the highlighted candidate instead of sending.
        compose.onNodeWithTag("chat_input").performTextReplacement("look at @android/app/chatscr")
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("look at @android/app/ChatScreen.kt ", input.text)
            assertEquals(0, sends)
        }

        // The completion closed the mention, so the menu is gone.
        compose.onNodeWithTag("file_mention_menu").assertDoesNotExist()
    }

    @Test
    fun mentionsMidSentenceDoNotBlockEnterOrSending() {
        var input by mutableStateOf(TextFieldValue())
        var sends = 0
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    files = Loadable.Ready(listing),
                    onSend = { sends += 1 },
                )
            }
        }

        // The composer contract: with no menu open Enter inserts a newline.
        compose.onNodeWithTag("chat_input").performTextReplacement("look at @README.md and")
        compose.onNodeWithTag("chat_input").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertTrue("Enter must insert a newline, not send", input.text.endsWith("\n"))
            assertEquals(0, sends)
        }
    }

    @Test
    fun showsLoadingErrorAndNoMatchStates() {
        var input by mutableStateOf(TextFieldValue("@", TextRange(1)))
        var files by mutableStateOf<Loadable<FileListing>>(Loadable.Idle)
        var retried = false
        compose.setContent {
            CockpitTheme {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "Steer the agent…",
                    enabled = true,
                    files = files,
                    onRetryFiles = { retried = true },
                    onSend = {},
                )
            }
        }

        compose.onNodeWithText("Loading files…").assertIsDisplayed()
        compose.runOnIdle { files = Loadable.Failed("Files unavailable", FailureKind.Server) }
        compose.onNodeWithText("Files unavailable").assertIsDisplayed()
        compose.onNodeWithTag("file_mention_retry").performClick()
        assertTrue(retried)

        compose.runOnIdle { files = Loadable.Ready(listing) }
        compose.onNodeWithTag("chat_input").performTextReplacement("@zzzz")
        compose.onNodeWithText("No files match “zzzz”").assertIsDisplayed()
    }
}
