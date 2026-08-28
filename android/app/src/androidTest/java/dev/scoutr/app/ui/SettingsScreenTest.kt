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
import dev.scoutr.app.data.ApkBuild
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.HerdrInfo
import dev.scoutr.app.data.REQUIRED_SCOUTR_API_FEATURES
import dev.scoutr.app.data.ScoutrApiInfo
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.data.UpdateApkStatusResponse
import dev.scoutr.app.data.UpdateIdentity
import dev.scoutr.app.data.UpdateInstalled
import dev.scoutr.app.data.UpdateStatusResponse
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostWorkCoordinator
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.state.BoardHarness
import dev.scoutr.app.state.HostsViewModel
import dev.scoutr.app.ui.screens.SettingsScreen
import dev.scoutr.app.ui.theme.ScoutrTheme
import dev.scoutr.app.update.AppUpdateController
import dev.scoutr.app.update.StagedIdentity
import dev.scoutr.app.update.UpdateNotifier
import dev.scoutr.app.update.UpdateStaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

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
    private val hostId = "test-host"
    private val hostUrl = "http://bridge.local:8787"
    @Before
    fun clearPrefs() {
        context.getSharedPreferences(AppearancePreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(TerminalPreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun terminalStore() = TerminalPreferencesStore(context)

    private object SilentUpdateNotifier : UpdateNotifier {
        override fun showUpdateReady(identity: StagedIdentity) {}
        override fun showUpdateFailed(message: String, resumable: Boolean) {}
        override fun cancelUpdateNotifications() {}
    }

    /**
     * A controller wired to a scratch staging dir and a no-op installer, so the
     * screen has real state to observe without committing anything on-device.
     */
    private fun updateController(
        staging: UpdateStaging = UpdateStaging(File(context.cacheDir, "settings-test-update-${System.nanoTime()}")),
    ): AppUpdateController =
        AppUpdateController(
            scope = CoroutineScope(SupervisorJob()),
            work = HostWorkCoordinator(),
            notifications = SilentUpdateNotifier,
            staging = staging,
            installer = {},
        )

    private fun setSettings(
        hosts: Int = 1,
        terminalPreferences: TerminalPreferencesStore = terminalStore(),
        api: ScoutrApi = FakeScoutrApi(),
        updates: AppUpdateController = updateController(),
        onStartUpdate: () -> Unit = {},
        onForget: () -> Unit = {},
    ): HostsViewModel {
        val harness = BoardHarness(context)
        repeat(hosts) { index ->
            harness.addHost(
                if (index == 0) hostId else "$hostId-$index",
                alias = if (index == 0) "bridge" else "bridge-$index",
                baseUrl = hostUrl,
            )
        }
        harness.apiFor(hostId).healthResult = Result.success(healthy())
        val viewModel = harness.hostsViewModel()
        compose.setContent {
            ScoutrTheme {
                SettingsScreen(
                    onBack = {},
                    hostsViewModel = viewModel,
                    terminalPreferences = terminalPreferences,
                    api = api,
                    updates = updates,
                    onStartUpdate = onStartUpdate,
                    onAllHostsForgotten = onForget,
                )
            }
        }
        return viewModel
    }

    private fun healthy() = HealthResponse(
        ok = true,
        api = ScoutrApiInfo(
            protocol = 2,
            features = REQUIRED_SCOUTR_API_FEATURES,
        ),
        herdr = HerdrInfo(connected = true),
    )

    @Test
    fun hostRowShowsAliasAndUrlButNeverTheToken() {
        setSettings()
        compose.waitForIdle()
        compose.onNodeWithTag("host_row_bridge").assertExists()
        compose.onNodeWithText(hostUrl).assertExists()
        compose.onNodeWithText("bridge").assertExists()
        // The token is neither text nor a content description anywhere on the page.
        compose.onAllNodes(
            hasText(pairingToken, substring = true) or
                hasContentDescription(pairingToken, substring = true),
        ).assertCountEquals(0)
    }

    @Test
    fun forgetIsConfirmGatedThroughTheRowMenu() {
        setSettings()

        compose.onNodeWithTag("host_menu_bridge").performScrollTo().performClick()
        compose.onNodeWithText("Forget").performClick()
        // The confirm dialog gates the destructive action.
        compose.onNodeWithText(
            "You'll need to pair again. Notifications from this host will stop.",
        ).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("host_menu_bridge").performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.onNodeWithTag("settings_forget_confirm").performClick()
        compose.waitForIdle()
    }

    @Test
    fun emptyHostListShowsAddInsteadOfRows() {
        setSettings(hosts = 0)
        compose.onNodeWithTag("host_row_bridge").assertDoesNotExist()
        compose.onNodeWithText("No bridge is paired.").assertExists()
        compose.onNodeWithTag("settings_add_host").performScrollTo().assertExists()
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
        val prefs = store.forHost(hostId)

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
        assertFalse(store.forHost(hostId).extraKeysVisible)
    }

    @Test
    fun terminalSectionIsAbsentWithoutAnyPairedHost() {
        setSettings(hosts = 0)
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
        // The screen no longer owns the update job — it asks the foreground
        // service to start one — so the trigger is what this asserts.
        var started = 0
        setSettings(api = api, onStartUpdate = { started += 1 })

        compose.onNodeWithTag("settings_update_button").performScrollTo().performClick()
        compose.onNodeWithText("Update app?").assertExists()
        compose.onNodeWithText("Update now").performClick()
        compose.waitForIdle()

        assertEquals("confirming must start exactly one update", 1, started)
    }

    @Test
    fun updateConfirmNoLongerTellsTheUserToStayOnTheScreen() {
        allowUnknownSources()
        val api = FakeScoutrApi()
        api.updateStatusResult = Result.success(updateStatusResponse(available = true))
        setSettings(api = api)

        compose.onNodeWithTag("settings_update_button").performScrollTo().performClick()

        compose.onNodeWithText("Keep this screen open", substring = true).assertDoesNotExist()
        compose.onNodeWithText("keeps going", substring = true).assertExists()
    }

    @Test
    fun aStagedUpdateOffersInstallEvenBeforeAnyHostCheck() {
        allowUnknownSources()
        val staging = UpdateStaging(File(context.cacheDir, "settings-test-staged-${System.nanoTime()}"))
        val bytes = ByteArray(24)
        staging.record(
            StagedIdentity(
                commit = "abc1234",
                sha256 = "ab",
                size = bytes.size.toLong(),
                version = "0.4.0",
            ),
        )
        staging.apkFile().writeBytes(bytes)
        staging.markVerified()
        val updates = updateController(staging).apply { rehydrate() }

        val api = FakeScoutrApi()
        api.updateStatusResult = Result.success(updateStatusResponse(available = false))
        setSettings(api = api, updates = updates)

        // Bytes that already cost a build and a download stay installable even
        // though the host now reports nothing new.
        compose.onNodeWithTag("settings_update_status").performScrollTo().assertExists()
        compose.onNodeWithText("Ready to install").assertExists()
        compose.onNodeWithTag("settings_update_button").performScrollTo().assertExists()
        compose.onNodeWithText("Install now").assertExists()
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
