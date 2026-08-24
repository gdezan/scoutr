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
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.data.ApkBuild
import dev.scoutr.app.data.UpdateApkStatusResponse
import dev.scoutr.app.data.UpdateInstalled
import dev.scoutr.app.data.UpdateIdentity
import dev.scoutr.app.data.UpdateStatusResponse
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.ui.screens.SettingsConnection
import dev.scoutr.app.ui.screens.SettingsScreen
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Settings: the seven sections, the durable stores behind them, and the
 * Forget gate. The instrumented app keeps one preferences file across tests,
 * so every test clears what it asserts on. The page is one long scroll —
 * scroll to a row before touching it.
 */
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pairingToken = "super-secret-token-42"
    private val saved = SettingsConnection(
        host = "http://bridge.local:8787",
        hostId = "test-host",
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
        saved: SettingsConnection? = this.saved,
        terminalPreferences: TerminalPreferencesStore = terminalStore(),
        api: ScoutrApi = FakeScoutrApi(),
        onForget: () -> Unit = {},
    ) {
        compose.setContent {
            ScoutrTheme {
                SettingsScreen(
                    onBack = {},
                    saved = saved,
                    terminalPreferences = terminalPreferences,
                    api = api,
                    onForget = onForget,
                )
            }
        }
    }

    @Test
    fun connectionCardShowsTheHostButNeverTheToken() {
        setSettings()
        compose.onNodeWithTag("settings_connection_status").assertExists()
        compose.onNodeWithText("Connected").assertExists()
        compose.onNodeWithTag("settings_host").assertTextEquals(saved.host)
        // The token is neither text nor a content description anywhere on the page.
        compose.onAllNodes(
            hasText(pairingToken, substring = true) or
                hasContentDescription(pairingToken, substring = true),
        ).assertCountEquals(0)
    }

    @Test
    fun forgetIsConfirmGatedAndCarriesTheAgreedCopy() {
        var forgot = 0
        setSettings(onForget = { forgot++ })

        compose.onNodeWithTag("settings_forget").performScrollTo().performClick()
        assertEquals("confirm must gate the callback", 0, forgot)
        compose.onNodeWithText(
            "Forget ${saved.host}? You'll need to pair again. Notifications will stop.",
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
    fun reduceMotionSwitchIsOffByDefaultAndPersists() {
        setSettings()
        compose.onNodeWithTag("settings_reduce_motion")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("settings_reduce_motion").assertIsOn()
        assertTrue(AppearancePreferencesStore(context).reduceMotionEnabled)
        compose.onNodeWithTag("settings_reduce_motion").performClick()
        compose.onNodeWithTag("settings_reduce_motion").assertIsOff()
        assertFalse(AppearancePreferencesStore(context).reduceMotionEnabled)
    }

    @Test
    fun typographySteppersPersistIndependentCodeSizes() {
        setSettings()
        val appearance = AppearancePreferencesStore(context)

        compose.onNodeWithTag("settings_markdown_code_value").performScrollTo().assertTextEquals("11")
        compose.onNodeWithTag("settings_markdown_code_minus").performClick()
        compose.onNodeWithTag("settings_markdown_code_value").assertTextEquals("10")
        assertEquals(10f, appearance.markdownCodeFontSizeSp, 0.01f)

        compose.onNodeWithTag("settings_review_code_value").performScrollTo().assertTextEquals("11")
        compose.onNodeWithTag("settings_review_code_plus").performClick()
        compose.onNodeWithTag("settings_review_code_value").assertTextEquals("12")
        assertEquals(12f, appearance.reviewFontSizeSp, 0.01f)

        compose.onNodeWithTag("settings_tool_output_value").performScrollTo().assertTextEquals("9.5")
        compose.onNodeWithTag("settings_tool_output_plus").performClick()
        compose.onNodeWithTag("settings_tool_output_value").assertTextEquals("10")
        assertEquals(10f, appearance.toolOutputFontSizeSp, 0.01f)
    }

    @Test
    fun fontStepperWritesTheSharedTerminalStoreAndStopsAtTheBounds() {
        val store = terminalStore()
        setSettings(terminalPreferences = store)
        val prefs = store.forHost(saved.hostId ?: "test-host")

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
        assertFalse(store.forHost(saved.hostId ?: "test-host").extraKeysVisible)
    }

    @Test
    fun terminalSectionIsAbsentWithoutASavedConnection() {
        setSettings(saved = null)
        compose.onNodeWithTag("settings_font_value").assertDoesNotExist()
        compose.onNodeWithTag("settings_extra_keys").assertDoesNotExist()
    }

    private fun updateStatusResponse(available: Boolean) = UpdateStatusResponse(
        ok = true,
        host = UpdateIdentity(version = "0.2.0", versionCode = 2000, commit = "abc1234", dirty = false),
        installed = UpdateInstalled(version = "0.1.0", commit = "def5678", dirty = false),
        updateAvailable = available,
    )

    @Test
    fun updateButtonAppearsWhenAnUpdateIsAvailable() {
        val api = FakeScoutrApi()
        api.updateStatusResult = Result.success(updateStatusResponse(available = true))
        setSettings(api = api)
        compose.onNodeWithTag("settings_update_status").performScrollTo().assertExists()
        compose.onNodeWithText("Update available").assertExists()
        compose.onNodeWithTag("settings_update_button").performScrollTo().assertExists().assertIsEnabled()
    }

    @Test
    fun updateButtonAbsentWhenUpToDate() {
        val api = FakeScoutrApi()
        api.updateStatusResult = Result.success(updateStatusResponse(available = false))
        setSettings(api = api)
        compose.onNodeWithTag("settings_update_status").performScrollTo().assertExists()
        compose.onNodeWithText("Up to date").assertExists()
        compose.onNodeWithTag("settings_update_button").assertDoesNotExist()
    }

    /**
     * The update button only offers the real flow once Android lets Scoutr
     * install apps; without the app op it routes to Settings instead. Grant it
     * through UiAutomation so the confirm path is deterministic here.
     */
    private fun allowUnknownSources() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set dev.scoutr.app REQUEST_INSTALL_PACKAGES allow",
        ).close()
    }

    @Test
    fun updateConfirmStartsAHostBuild() {
        allowUnknownSources()
        val api = FakeScoutrApi()
        api.updateStatusResult = Result.success(updateStatusResponse(available = true))
        // Leave the build in flight so the download and install never run: this
        // test owns the trigger, not the whole update.
        api.updateApkStatusResult = Result.success(
            UpdateApkStatusResponse(build = ApkBuild(state = "building", buildId = 1)),
        )
        setSettings(api = api)

        compose.onNodeWithTag("settings_update_button").performScrollTo().performClick()
        compose.onNodeWithText("Update app?").assertExists()
        compose.onNodeWithText("Update now").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { api.calls.any { it.name == "updateBuild" } }
        assertTrue("the host build must be started", api.calls.any { it.name == "updateBuild" })
    }

    @Test
    fun updateSectionNeverShowsTheDirtyFlag() {
        val api = FakeScoutrApi()
        api.updateStatusResult = Result.success(
            updateStatusResponse(available = true).copy(
                host = UpdateIdentity(
                    version = "0.2.0",
                    versionCode = 2000,
                    commit = "abc1234",
                    dirty = true,
                ),
            ),
        )
        setSettings(api = api)
        compose.onNodeWithTag("settings_update_status").performScrollTo().assertExists()
        compose.onNodeWithText("dirty", substring = true).assertDoesNotExist()
    }
}
