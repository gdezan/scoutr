package dev.scoutr.app.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherSettingsStoreTest {
    private lateinit var context: Context
    private lateinit var store: SharedPreferencesLauncherSettingsStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("scoutr_launcher", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = SharedPreferencesLauncherSettingsStore(context)
    }

    @Test
    fun roundTripsLauncherPreferencesAndPresets() {
        val expected = LauncherSettings(
            defaultModelKey = "openai-codex/gpt-5.4",
            favoriteModelKeys = setOf("anthropic/claude-sonnet-4-6"),
            recentModelKeys = listOf("openai-codex/gpt-5.4"),
            recentFolders = listOf("/home/dev/project"),
            thinkingByModel = mapOf("openai-codex/gpt-5.4" to "high"),
            presets = listOf(
                SessionLauncherPreset(
                    id = "review",
                    title = "Review this repo",
                    cwd = "/home/dev/project",
                    modelKey = "openai-codex/gpt-5.4",
                    thinkingLevel = "high",
                    sessionName = "review",
                    initialPrompt = "Review the current changes.",
                ),
            ),
        )

        store.saveLauncherSettings(expected)

        assertEquals(expected, store.loadLauncherSettings())
    }

    @Test
    fun boundsRecentsAndPresetsBeforePersisting() {
        store.saveLauncherSettings(
            LauncherSettings(
                recentModelKeys = (1..20).map { "provider/model-$it" },
                recentFolders = (1..20).map { "/folder/$it" },
                presets = (1..20).map {
                    SessionLauncherPreset("$it", "Preset $it", "/folder/$it", "provider/model-$it", initialPrompt = "Task $it")
                },
            ),
        )

        val loaded = store.loadLauncherSettings()
        assertEquals(8, loaded.recentModelKeys.size)
        assertEquals(6, loaded.recentFolders.size)
        assertEquals(12, loaded.presets.size)
    }

    @Test
    fun hostSettingsAreIndependentAndLegacySettingsAdoptOnlyToFirstHost() {
        val legacy = LauncherSettings(recentFolders = listOf("/legacy"))
        store.saveLauncherSettings(legacy)

        store.adoptLegacySettings("host-a")
        val hostA = store.forHost("host-a")
        val hostB = store.forHost("host-b")
        assertEquals(legacy, hostA.loadLauncherSettings())
        assertEquals(LauncherSettings(), hostB.loadLauncherSettings())
        assertEquals(LauncherSettings(), store.loadLauncherSettings())

        hostB.saveLauncherSettings(LauncherSettings(recentFolders = listOf("/other")))
        assertEquals(listOf("/legacy"), hostA.loadLauncherSettings().recentFolders)
        assertEquals(listOf("/other"), hostB.loadLauncherSettings().recentFolders)
    }

    @Test
    fun retiredHostCannotRepopulateClearedLauncherState() {
        val guarded = SharedPreferencesLauncherSettingsStore(
            context,
            writeIfRegistered = { _, _ -> false },
        )
        guarded.forHost("host-a").saveLauncherSettings(
            LauncherSettings(recentFolders = listOf("/stale")),
        )

        assertEquals(LauncherSettings(), guarded.forHost("host-a").loadLauncherSettings())
    }

    @Test
    fun clearingOneHostDoesNotClearAnotherHost() {
        store.forHost("host-a").saveLauncherSettings(LauncherSettings(recentFolders = listOf("/a")))
        store.forHost("host-b").saveLauncherSettings(LauncherSettings(recentFolders = listOf("/b")))

        store.clearHost("host-a")

        assertEquals(LauncherSettings(), store.forHost("host-a").loadLauncherSettings())
        assertEquals(listOf("/b"), store.forHost("host-b").loadLauncherSettings().recentFolders)
    }
}
