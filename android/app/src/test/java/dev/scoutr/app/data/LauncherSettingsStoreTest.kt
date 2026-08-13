package dev.scoutr.app.data

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
    private lateinit var store: SharedPreferencesLauncherSettingsStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
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
}
