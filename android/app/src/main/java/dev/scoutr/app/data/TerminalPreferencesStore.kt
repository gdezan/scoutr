package dev.scoutr.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _viewPreferencesRevision = MutableStateFlow(0)

    /**
     * Bumped whenever [ConnectionPreferences.fontSizeSp] or
     * [ConnectionPreferences.extraKeysVisible] is written, so a reader that is
     * already composed re-reads them. Settings and the terminal's own pinch /
     * extra-keys strip write the same setters through the same store instance,
     * which is how a Settings change reaches an open Terminal without leaving
     * the route.
     *
     * `lastPaneId` deliberately does not bump it: nothing observes it, and
     * attach writes it often enough to cause pointless recomposition.
     */
    val viewPreferencesRevision: StateFlow<Int> = _viewPreferencesRevision.asStateFlow()

    /**
     * Per-connection preferences. The key is derived from the same values
     * the socket uses, so re-pairing with a new token resets the route.
     */
    class ConnectionPreferences internal constructor(
        private val prefs: SharedPreferences,
        private val key: String,
        private val onViewPreferenceWritten: () -> Unit,
    ) {
        var lastPaneId: String?
            get() = prefs.getString(keyFor("lastPaneId"), null)
            set(value) = prefs.edit().putString(keyFor("lastPaneId"), value).apply()

        /** Clamped on write, so every writer — pinch, Settings — lands in range. */
        var fontSizeSp: Float
            get() = prefs.getFloat(keyFor("fontSizeSp"), DEFAULT_FONT_SIZE_SP)
            set(value) {
                val clamped = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
                prefs.edit().putFloat(keyFor("fontSizeSp"), clamped).apply()
                onViewPreferenceWritten()
            }

        var extraKeysVisible: Boolean
            get() = prefs.getBoolean(keyFor("extraKeysVisible"), DEFAULT_EXTRA_KEYS_VISIBLE)
            set(value) {
                prefs.edit().putBoolean(keyFor("extraKeysVisible"), value).apply()
                onViewPreferenceWritten()
            }

        private fun keyFor(name: String) = "$key.$name"

        companion object {
            /** Plan "TerminalViewport": default monospace size; the view can still autoscale. */
            const val DEFAULT_FONT_SIZE_SP = 12f
            const val DEFAULT_EXTRA_KEYS_VISIBLE = true

            /** Bounds live with the value they constrain, so both writers inherit them. */
            const val MIN_FONT_SIZE_SP = 8f
            const val MAX_FONT_SIZE_SP = 24f
        }
    }

    /**
     * Cached per connection: the key costs a SHA-256 over the canonical URL,
     * and pinch-to-zoom asks for it on every motion event (write, then the
     * revision tick re-reads font and extra-keys). Deriving it each time put
     * three digests on the frame thread per event.
     */
    private val byConnection = HashMap<String, ConnectionPreferences>()

    @Synchronized
    fun forConnection(host: String, token: String): ConnectionPreferences =
        byConnection.getOrPut("$host\n$token") {
            ConnectionPreferences(prefs, connectionKey(host, token)) {
                _viewPreferencesRevision.update { it + 1 }
            }
        }

    companion object {
        const val FILE = "scoutr_terminal"

        private const val HEX = "0123456789abcdef"

        /** SHA-256 over canonical host and token; hex digest, no raw token on disk. */
        internal fun connectionKey(host: String, token: String): String {
            val input = "${canonicalize(host)}\n$token".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            // Hand-rolled hex: String.format costs ~1-2 µs a byte, which is 64
            // of them per key on a path the terminal touches during a gesture.
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return out.toString()
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
