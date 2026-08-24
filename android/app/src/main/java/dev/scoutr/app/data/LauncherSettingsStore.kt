package dev.scoutr.app.data

import android.content.Context
import android.util.Base64
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

/** SharedPreferences-backed launcher settings, scoped to one bridge installation. */
class SharedPreferencesLauncherSettingsStore(
    context: Context,
    private val hostId: String? = null,
    private val writeIfRegistered: (String, () -> Unit) -> Boolean = { _, write ->
        write()
        true
    },
) : LauncherSettingsStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val settingsKey = hostId?.let(::hostSettingsKey) ?: KEY_SETTINGS
    override fun loadLauncherSettings(): LauncherSettings {
        val raw = prefs.getString(settingsKey, null) ?: return LauncherSettings()
        return runCatching { json.decodeFromString(LauncherSettings.serializer(), raw) }
            .getOrDefault(LauncherSettings())
    }

    override fun saveLauncherSettings(settings: LauncherSettings) {
        val bounded = settings.copy(
            recentModelKeys = settings.recentModelKeys.distinct().take(MAX_RECENT_MODELS),
            recentFolders = settings.recentFolders.distinct().take(MAX_RECENT_FOLDERS),
            presets = settings.presets.take(MAX_PRESETS),
        )
        val write = {
            prefs.edit()
                .putString(settingsKey, json.encodeToString(LauncherSettings.serializer(), bounded))
                .apply()
        }
        val host = hostId
        if (host == null) write() else writeIfRegistered(host, write)
    }

    fun forHost(hostId: String): LauncherSettingsStore =
        SharedPreferencesLauncherSettingsStore(
            appContext,
            requireHostId(hostId),
            writeIfRegistered,
        )

    /** Moves the singleton launch state to the first imported host exactly once. */
    @Synchronized
    fun adoptLegacySettings(hostId: String) {
        check(this.hostId == null) { "Legacy launcher migration requires the root store" }
        val host = requireHostId(hostId)
        val adoptedHost = prefs.getString(KEY_LEGACY_HOST_ID, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        require(adoptedHost == null || adoptedHost == host) {
            "Legacy launcher state already belongs to $adoptedHost"
        }
        val destination = hostSettingsKey(host)
        val editor = prefs.edit().putString(KEY_LEGACY_HOST_ID, host)
        prefs.getString(KEY_SETTINGS, null)?.let { legacy ->
            if (!prefs.contains(destination)) editor.putString(destination, legacy)
        }
        check(editor.remove(KEY_SETTINGS).commit()) { "Could not adopt legacy launcher state" }
    }

    @Synchronized
    fun clearHost(hostId: String): Boolean = prefs.edit().remove(hostSettingsKey(requireHostId(hostId))).commit()

    private fun hostSettingsKey(hostId: String): String =
        "$KEY_HOST_SETTINGS_PREFIX${Base64.encodeToString(hostId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)}"

    private fun requireHostId(value: String): String =
        value.trim().takeIf(String::isNotEmpty) ?: error("Host id must be nonblank")

    private companion object {
        const val PREFS_NAME = "scoutr_launcher"
        const val KEY_SETTINGS = "settings"
        const val KEY_HOST_SETTINGS_PREFIX = "settings.host."
        const val KEY_LEGACY_HOST_ID = "legacyHostId"
        const val MAX_RECENT_MODELS = 8
        const val MAX_RECENT_FOLDERS = 6
        const val MAX_PRESETS = 12
    }
}
