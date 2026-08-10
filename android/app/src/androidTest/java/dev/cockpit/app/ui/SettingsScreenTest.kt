package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cockpit.app.ui.screens.SettingsScreen
import dev.cockpit.app.ui.theme.CockpitTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings surface: the background-monitoring opt-in toggle renders and flips. */
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun monitoringToggleRendersAndFlips() {
        var lastToggle: Boolean? = null
        compose.setContent {
            CockpitTheme {
                SettingsScreen(
                    onBack = {},
                    onMonitoringChanged = { lastToggle = it },
                )
            }
        }
        compose.onNodeWithTag("settings_monitoring_switch").assertIsOff().performClick()
        compose.onNodeWithTag("settings_monitoring_switch").assertIsOn()
        compose.onNodeWithText("Background monitoring").assertIsDisplayed()
        assertTrue(lastToggle == true)
    }
}
