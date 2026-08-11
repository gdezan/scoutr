package dev.cockpit.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One saved session launcher preset, including the first prompt and execution settings. */
@Serializable
data class SessionLauncherPreset(
    val id: String,
    val title: String,
    val cwd: String,
    val modelKey: String,
    val thinkingLevel: String? = null,
    val sessionName: String = "",
    val initialPrompt: String,
    /** Backend id the preset was saved under; null means a legacy pi preset. */
    val agent: String? = null,
)

/** Persistent model-picker and session-launcher settings stored only on this device. */
@Serializable
data class LauncherSettings(
    val defaultModelKey: String? = null,
    val favoriteModelKeys: Set<String> = emptySet(),
    val recentModelKeys: List<String> = emptyList(),
    val recentFolders: List<String> = emptyList(),
    val thinkingByModel: Map<String, String> = emptyMap(),
    val presets: List<SessionLauncherPreset> = emptyList(),
)

/** Read and write the launcher's bounded on-device settings. */
interface LauncherSettingsStore {
    fun loadLauncherSettings(): LauncherSettings
    fun saveLauncherSettings(settings: LauncherSettings)
}

/** SharedPreferences-backed launcher settings; no bridge secrets are stored here. */
class SharedPreferencesLauncherSettingsStore(context: Context) : LauncherSettingsStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun loadLauncherSettings(): LauncherSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return LauncherSettings()
        return runCatching { json.decodeFromString(LauncherSettings.serializer(), raw) }
            .getOrDefault(LauncherSettings())
    }

    override fun saveLauncherSettings(settings: LauncherSettings) {
        val bounded = settings.copy(
            recentModelKeys = settings.recentModelKeys.distinct().take(MAX_RECENT_MODELS),
            recentFolders = settings.recentFolders.distinct().take(MAX_RECENT_FOLDERS),
            presets = settings.presets.take(MAX_PRESETS),
        )
        prefs.edit()
            .putString(KEY_SETTINGS, json.encodeToString(LauncherSettings.serializer(), bounded))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cockpit_launcher"
        const val KEY_SETTINGS = "settings"
        const val MAX_RECENT_MODELS = 8
        const val MAX_RECENT_FOLDERS = 6
        const val MAX_PRESETS = 12
    }
}
