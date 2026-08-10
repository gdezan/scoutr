package dev.cockpit.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cockpit.app.state.ChatUiState
import dev.cockpit.app.ui.screens.LiveOutputDrawer
import dev.cockpit.app.ui.screens.LiveOutputStrip
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveOutputPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun drawerRendersLoadingEmptyErrorAndTruncatedStates() {
        var state by mutableStateOf(ChatUiState(liveOutputExpanded = true, liveOutputLoading = true))
        compose.setContent {
            CockpitTheme { Column { LiveOutputDrawer(state) } }
        }
        compose.onNodeWithText("Waiting for output…").assertIsDisplayed()

        compose.runOnIdle {
            state = ChatUiState(
                liveOutputExpanded = true,
                liveOutputText = "   \nElapsed 2m 14s\n│ opencode-go/gpt-5.4 │",
            )
        }
        compose.onNodeWithText("No recent output").assertIsDisplayed()

        compose.runOnIdle {
            state = ChatUiState(liveOutputExpanded = true, liveOutputError = "bridge offline")
        }
        compose.onNodeWithText("Output unavailable", substring = true).assertIsDisplayed()
        compose.onNodeWithText("bridge offline", substring = true).assertIsDisplayed()

        compose.runOnIdle {
            state = ChatUiState(
                liveOutputExpanded = true,
                liveOutputText = (1..30).joinToString("\n") { "output line $it" },
                liveOutputTruncated = true,
            )
        }
        compose.onNodeWithText("EARLIER OUTPUT TRIMMED").assertIsDisplayed()
        compose.onNodeWithText("output line 30", substring = true).assertIsDisplayed()
    }

    @Test
    fun collapsedStripAnnouncesPreviewAndToggles() {
        var toggled = false
        val state = ChatUiState(liveOutputText = "latest useful work")
        compose.setContent {
            CockpitTheme {
                LiveOutputStrip(ui = state, onToggle = { toggled = true })
            }
        }

        compose.onNodeWithContentDescription("Live output, collapsed. latest useful work")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(toggled) }
    }
}
