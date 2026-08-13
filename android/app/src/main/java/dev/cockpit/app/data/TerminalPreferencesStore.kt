package dev.cockpit.app.data

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/**
 * Per-connection terminal preferences (plan "Per-connection state").
 *
 * Keys are scoped by a SHA-256 digest of the canonicalized bridge URL plus
 * the pairing token, so two saved connections never share terminal state
 * and the raw token never appears in preference keys. "Last pane id" is the
 * route's remembered selection; font size and extra-key visibility are the
 * future view's settings, stored now so the slice 7 UI reads one store.
 */
class TerminalPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Per-connection preferences. The key is derived from the same values
     * the socket uses, so re-pairing with a new token resets the route.
     */
    class ConnectionPreferences internal constructor(
        private val prefs: SharedPreferences,
        private val key: String,
    ) {
        var lastPaneId: String?
            get() = prefs.getString(keyFor("lastPaneId"), null)
            set(value) = prefs.edit().putString(keyFor("lastPaneId"), value).apply()

        var fontSizeSp: Float
            get() = prefs.getFloat(keyFor("fontSizeSp"), DEFAULT_FONT_SIZE_SP)
            set(value) = prefs.edit().putFloat(keyFor("fontSizeSp"), value).apply()

        var extraKeysVisible: Boolean
            get() = prefs.getBoolean(keyFor("extraKeysVisible"), DEFAULT_EXTRA_KEYS_VISIBLE)
            set(value) = prefs.edit().putBoolean(keyFor("extraKeysVisible"), value).apply()

        private fun keyFor(name: String) = "$key.$name"

        companion object {
            /** Plan "TerminalViewport": default monospace size; the view can still autoscale. */
            const val DEFAULT_FONT_SIZE_SP = 12f
            const val DEFAULT_EXTRA_KEYS_VISIBLE = true
        }
    }

    fun forConnection(host: String, token: String): ConnectionPreferences =
        ConnectionPreferences(prefs, connectionKey(host, token))

    companion object {
        private const val FILE = "cockpit_terminal"

        /** SHA-256 over canonical host and token; hex digest, no raw token on disk. */
        internal fun connectionKey(host: String, token: String): String {
            val input = "${canonicalize(host)}\n$token".toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256")
                .digest(input)
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Canonicalize a saved bridge URL for keying: lower-case scheme and
         * host, drop the default port, drop a trailing path slash, keep a
         * non-default port and any non-root path. URLs carrying userinfo,
         * query, or fragment are rejected (opaque fallback: trimmed,
         * lower-cased) because they cannot be routed to reliably.
         */
        internal fun canonicalize(host: String): String {
            val trimmed = host.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            val url = runCatching { trimmed.toHttpUrl() }.getOrNull()
                ?: return trimmed.lowercase()
            if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null
            ) {
                return trimmed.lowercase()
            }
            val scheme = url.scheme.lowercase()
            val hostPart = url.host.lowercase()
            val port = if (url.port != defaultPort(url.scheme)) ":${url.port}" else ""
            val path = url.encodedPath.trimEnd('/')
            return "$scheme://$hostPart$port$path"
        }
    }
}
