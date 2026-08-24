package dev.scoutr.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/**
 * Durable terminal choices scoped by bridge installation id.
 *
 * URL and bearer token are transport credentials, not durable identity. A
 * credential refresh therefore keeps the same terminal state, while two
 * bridges with identical URLs or tokens cannot share it. The selected pane is
 * host-scoped and [clearHost] removes it during retired-host cleanup.
 */
class TerminalPreferencesStore(
    context: Context,
    private val writeIfRegistered: (String, () -> Unit) -> Boolean = { _, write ->
        write()
        true
    },
) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _viewPreferencesRevision = MutableStateFlow(0)
    val viewPreferencesRevision: StateFlow<Int> = _viewPreferencesRevision.asStateFlow()

    /** Preferences belonging to one host id. */
    class ConnectionPreferences internal constructor(
        private val prefs: SharedPreferences,
        private val key: String,
        private val onViewPreferenceWritten: () -> Unit,
        private val writeIfRegistered: ((() -> Unit) -> Boolean),
    ) {
        var lastPaneId: String?
            get() = prefs.getString(keyFor("lastPaneId"), null)
            set(value) {
                writeIfRegistered {
                    val edit = prefs.edit()
                    if (value == null) edit.remove(keyFor("lastPaneId"))
                    else edit.putString(keyFor("lastPaneId"), value)
                    edit.apply()
                }
            }

        var fontSizeSp: Float
            get() = prefs.getFloat(keyFor("fontSizeSp"), DEFAULT_FONT_SIZE_SP)
            set(value) {
                val written = writeIfRegistered {
                    prefs.edit()
                        .putFloat(keyFor("fontSizeSp"), value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
                        .apply()
                }
                if (written) onViewPreferenceWritten()
            }

        var extraKeysVisible: Boolean
            get() = prefs.getBoolean(keyFor("extraKeysVisible"), DEFAULT_EXTRA_KEYS_VISIBLE)
            set(value) {
                val written = writeIfRegistered {
                    prefs.edit().putBoolean(keyFor("extraKeysVisible"), value).apply()
                }
                if (written) onViewPreferenceWritten()
            }

        private fun keyFor(name: String) = "$key.$name"

        companion object {
            const val DEFAULT_FONT_SIZE_SP = 12f
            const val DEFAULT_EXTRA_KEYS_VISIBLE = true
            const val MIN_FONT_SIZE_SP = 8f
            const val MAX_FONT_SIZE_SP = 24f
        }
    }

    private val byHost = HashMap<String, ConnectionPreferences>()

    @Synchronized
    fun forHost(hostId: String): ConnectionPreferences {
        val host = requireHostId(hostId)
        return byHost.getOrPut(host) {
            ConnectionPreferences(
                prefs = prefs,
                key = hostKey(host),
                onViewPreferenceWritten = { _viewPreferencesRevision.update { it + 1 } },
                writeIfRegistered = { write -> writeIfRegistered(host, write) },
            )
        }
    }

    /**
     * Moves the old URL/token-derived terminal record to the first host.
     * This is intentionally explicit and is only for singleton migration; no
     * normal read attempts to infer a host from credentials.
     */
    @Synchronized
    fun adoptLegacyPreferences(hostId: String, legacyHost: String, legacyToken: String) {
        val host = requireHostId(hostId)
        val adoptedHost = prefs.getString(KEY_LEGACY_HOST_ID, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        require(adoptedHost == null || adoptedHost == host) {
            "Legacy terminal preferences already belong to $adoptedHost"
        }

        val oldKey = legacyConnectionKey(legacyHost, legacyToken)
        val newKey = hostKey(host)
        val editor = prefs.edit().putString(KEY_LEGACY_HOST_ID, host)
        prefs.getString("$oldKey.lastPaneId", null)?.let {
            editor.putString("$newKey.lastPaneId", it)
        }
        if (prefs.contains("$oldKey.fontSizeSp")) {
            editor.putFloat(
                "$newKey.fontSizeSp",
                prefs.getFloat("$oldKey.fontSizeSp", ConnectionPreferences.DEFAULT_FONT_SIZE_SP),
            )
        }
        if (prefs.contains("$oldKey.extraKeysVisible")) {
            editor.putBoolean(
                "$newKey.extraKeysVisible",
                prefs.getBoolean("$oldKey.extraKeysVisible", ConnectionPreferences.DEFAULT_EXTRA_KEYS_VISIBLE),
            )
        }
        editor.remove("$oldKey.lastPaneId")
            .remove("$oldKey.fontSizeSp")
            .remove("$oldKey.extraKeysVisible")
        check(editor.commit()) { "Could not adopt legacy terminal preferences" }
    }

    /** Removes all host-derived terminal state, including selected pane. */
    @Synchronized
    fun clearHost(hostId: String): Boolean {
        val host = requireHostId(hostId)
        val prefix = "${hostKey(host)}."
        val editor = prefs.edit()
        var changed = false
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach {
            editor.remove(it)
            changed = true
        }
        byHost.remove(host)
        return !changed || editor.commit()
    }

    private fun hostKey(hostId: String): String =
        "$HOST_KEY_PREFIX${Base64.encodeToString(hostId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)}"

    private fun requireHostId(value: String): String =
        value.trim().takeIf(String::isNotEmpty) ?: error("Host id must be nonblank")

    companion object {
        const val FILE = "scoutr_terminal"

        private const val HOST_KEY_PREFIX = "host."
        private const val KEY_LEGACY_HOST_ID = "legacyHostId"
        private const val HEX = "0123456789abcdef"

        /** Canonicalized URL/token digest used only to locate old singleton data. */
        internal fun legacyConnectionKey(host: String, token: String): String =
            digestKey("${canonicalize(host)}\n$token")

        /** Canonical host-id key is intentionally independent of URL/token. */
        internal fun hostPreferenceKey(hostId: String): String =
            "$HOST_KEY_PREFIX${Base64.encodeToString(hostId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)}"

        private fun digestKey(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return out.toString()
        }

        /**
         * Canonicalize a saved bridge URL for locating old singleton keys.
         * It is not part of the new durable identity.
         */
        internal fun canonicalize(host: String): String {
            val trimmed = host.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            val url = runCatching { trimmed.toHttpUrl() }.getOrNull()
                ?: return trimmed.lowercase()
            if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null
            ) return trimmed.lowercase()
            val scheme = url.scheme.lowercase()
            val hostPart = url.host.lowercase()
            val port = if (url.port != defaultPort(url.scheme)) ":${url.port}" else ""
            val path = url.encodedPath.trimEnd('/')
            return "$scheme://$hostPart$port$path"
        }
    }
}
