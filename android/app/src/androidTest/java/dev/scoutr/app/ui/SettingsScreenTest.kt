package dev.scoutr.app.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.scoutr.app.data.AppearancePreferencesStore
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.ui.screens.SettingsScreen
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Settings: the five sections, the durable stores behind them, and the
 * Forget gate. The instrumented app keeps one preferences file across tests,
 * so every test clears what it asserts on. The page is one long scroll —
 * scroll to a row before touching it.
 */
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val saved = ConnectionStore.Saved(
        host = "http://bridge.local:8787",
        token = "super-secret-token-42",
        ntfyUrl = "https://ntfy.sh",
        ntfyTopic = "scoutr-abc123",
    )

    @Before
    fun clearPrefs() {
        context.getSharedPreferences(AppearancePreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(TerminalPreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun terminalStore() = TerminalPreferencesStore(context)

    private fun setSettings(
        saved: ConnectionStore.Saved? = this.saved,
        terminalPreferences: TerminalPreferencesStore = terminalStore(),
        onForget: () -> Unit = {},
        onMonitoringChanged: ((Boolean) -> Unit)? = {},
    ) {
        compose.setContent {
            ScoutrTheme {
                SettingsScreen(
                    onBack = {},
                    saved = saved,
                    terminalPreferences = terminalPreferences,
                    onForget = onForget,
                    onMonitoringChanged = onMonitoringChanged,
                )
            }
        }
    }

    @Test
    fun monitoringToggleRendersAndFlips() {
        var lastToggle: Boolean? = null
        setSettings(onMonitoringChanged = { lastToggle = it })
        compose.onNodeWithTag("settings_monitoring_switch").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("settings_monitoring_switch").assertIsOn()
        compose.onNodeWithText("Background monitoring").assertExists()
        compose.onNodeWithText(
            "Watch agents for blocked / done events while the app is closed. " +
                "Android 15+ limits data-sync monitoring to six hours in a 24-hour period.",
        ).assertExists()
        assertTrue(lastToggle == true)
    }

    @Test
    fun connectionCardShowsHostAndNtfyButNeverTheToken() {
        setSettings()
        compose.onNodeWithTag("settings_host").assertTextEquals(saved.host)
        compose.onNodeWithTag("settings_ntfy")
            .assertTextEquals("${saved.ntfyUrl}\n${saved.ntfyTopic}")
        // The token is neither text nor a content description anywhere on the page.
        compose.onAllNodes(
            hasText(saved.token, substring = true) or
                hasContentDescription(saved.token, substring = true),
        ).assertCountEquals(0)
    }

    @Test
    fun missingNtfyReadsAsNotConfigured() {
        setSettings(saved = saved.copy(ntfyUrl = null))
        compose.onNodeWithTag("settings_ntfy").assertTextEquals("Push not configured.")
    }

    @Test
    fun forgetIsConfirmGatedAndCarriesTheAgreedCopy() {
        var forgot = 0
        setSettings(onForget = { forgot++ })

        compose.onNodeWithTag("settings_forget").performScrollTo().performClick()
        assertEquals("confirm must gate the callback", 0, forgot)
        compose.onNodeWithText(
            "Forget ${saved.host}? You'll need to pair again. Background monitoring will turn off.",
        ).assertExists()

        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, forgot)

        compose.onNodeWithTag("settings_forget").performScrollTo().performClick()
        compose.onNodeWithText("Forget").performClick()
        assertEquals(1, forgot)
    }

    @Test
    fun forgetIsAbsentWithoutASavedConnection() {
        setSettings(saved = null)
        compose.onNodeWithTag("settings_forget").assertDoesNotExist()
        compose.onNodeWithTag("settings_host").assertDoesNotExist()
        // The device-global sections still stand on their own.
        compose.onNodeWithTag("settings_haptics").assertExists()
    }

    @Test
    fun chatDefaultsPersistToTheAppearanceStore() {
        setSettings()
        // Today's factory values, now durable: thinking on, tools off.
        compose.onNodeWithTag("settings_chat_thinking").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("settings_chat_tools").performScrollTo().assertIsOff().performClick()

        val stored = AppearancePreferencesStore(context)
        assertFalse(stored.showThinkingDefault)
        assertTrue(stored.expandToolsDefault)
    }

    @Test
    fun hapticsSwitchIsOnByDefaultAndPersists() {
        setSettings()
        compose.onNodeWithTag("settings_haptics").performScrollTo().assertIsOn().performClick()
        assertFalse(AppearancePreferencesStore(context).hapticsEnabled)
    }

    @Test
    fun fontStepperWritesTheSharedTerminalStoreAndStopsAtTheBounds() {
        val store = terminalStore()
        setSettings(terminalPreferences = store)
        val prefs = store.forConnection(saved.host, saved.token)

        compose.onNodeWithTag("settings_font_value").performScrollTo().assertTextEquals("12")
        compose.onNodeWithTag("settings_font_plus").performClick()
        compose.onNodeWithTag("settings_font_value").assertTextEquals("13")
        assertEquals(13f, prefs.fontSizeSp, 0.01f)

        // 13 -> 24 is 11 more taps; + then disables at the ceiling.
        repeat(11) { compose.onNodeWithTag("settings_font_plus").performClick() }
        compose.onNodeWithTag("settings_font_value").assertTextEquals("24")
        compose.onNodeWithTag("settings_font_plus").assertIsNotEnabled()
        compose.onNodeWithTag("settings_font_minus").assertIsEnabled()
        assertEquals(24f, prefs.fontSizeSp, 0.01f)

        repeat(16) { compose.onNodeWithTag("settings_font_minus").performClick() }
        compose.onNodeWithTag("settings_font_value").assertTextEquals("8")
        compose.onNodeWithTag("settings_font_minus").assertIsNotEnabled()
        assertEquals(8f, prefs.fontSizeSp, 0.01f)
    }

    @Test
    fun extraKeysSwitchWritesTheSharedTerminalStore() {
        val store = terminalStore()
        setSettings(terminalPreferences = store)
        // The strip ships visible, so Settings starts on.
        compose.onNodeWithTag("settings_extra_keys").performScrollTo().assertIsOn().performClick()
        assertFalse(store.forConnection(saved.host, saved.token).extraKeysVisible)
    }

    @Test
    fun terminalSectionIsAbsentWithoutASavedConnection() {
        setSettings(saved = null)
        compose.onNodeWithTag("settings_font_value").assertDoesNotExist()
        compose.onNodeWithTag("settings_extra_keys").assertDoesNotExist()
    }
}
