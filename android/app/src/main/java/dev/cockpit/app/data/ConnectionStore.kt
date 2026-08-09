package dev.cockpit.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the bridge host + pairing token, plus the ntfy push topic the
 * bridge revealed during the health handshake (layer 5).
 */
class ConnectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cockpit_connection", Context.MODE_PRIVATE)

    data class Saved(
        val host: String,
        val token: String,
        val ntfyUrl: String? = null,
        val ntfyTopic: String? = null,
    )

    val saved: Saved?
        get() {
            val host = prefs.getString(KEY_HOST, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            if (host.isBlank() || token.isBlank()) return null
            return Saved(
                host = host.trim(),
                token = token.trim(),
                ntfyUrl = prefs.getString(KEY_NTFY_URL, null)?.takeIf { it.isNotBlank() },
                ntfyTopic = prefs.getString(KEY_NTFY_TOPIC, null)?.takeIf { it.isNotBlank() },
            )
        }

    fun save(host: String, token: String, ntfyUrl: String? = null, ntfyTopic: String? = null) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_NTFY_URL, ntfyUrl?.trim())
            .putString(KEY_NTFY_TOPIC, ntfyTopic?.trim())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_TOKEN = "token"
        const val KEY_NTFY_URL = "ntfyUrl"
        const val KEY_NTFY_TOPIC = "ntfyTopic"
    }
}
