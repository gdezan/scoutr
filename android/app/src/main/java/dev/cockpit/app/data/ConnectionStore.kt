package dev.cockpit.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the bridge host + pairing token.
 * The token is only ever stored on-device and sent as a Bearer header
 * (or WS query param) to the bridge.
 */
class ConnectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cockpit_connection", Context.MODE_PRIVATE)

    data class Saved(
        val host: String,
        val token: String,
    )

    val saved: Saved?
        get() {
            val host = prefs.getString(KEY_HOST, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            if (host.isBlank() || token.isBlank()) return null
            return Saved(host.trim(), token.trim())
        }

    fun save(host: String, token: String) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_TOKEN = "token"
    }
}
