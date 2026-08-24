package dev.scoutr.app.state

import android.content.Context
import dev.scoutr.app.data.HostPaneKey
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.decodeHostPaneKey
import dev.scoutr.app.data.encode
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Panes the user has told Scoutr to stop interrupting for.
 *
 * New entries use the generation-qualified [HostPaneKey]. This prevents a
 * mute for host A's pane from silencing host B, and prevents a same-id
 * re-pair from inheriting a decision made for the old profile generation.
 * The string-only methods remain as a compatibility seam for pre-host tests.
 */
class MuteStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isMuted(key: HostPaneKey): Boolean = key.encode() in muted()

    fun mute(key: HostPaneKey) {
        prefs.edit().putStringSet(KEY_PANES, muted() + key.encode()).commit()
    }

    /** Clears stale entries for one exact profile generation. */
    fun prune(profile: HostProfileKey, livePaneIds: Set<String>) {
        val current = muted()
        val kept = current.filterTo(mutableSetOf()) { value ->
            val key = decodeHostPaneKey(value)
            if (key == null) {
                // Host-qualified compatibility entries are pruned by the
                // host-wide overload below; unrelated legacy entries survive.
                true
            } else {
                key.profile != profile || key.paneId in livePaneIds
            }
        }
        if (kept.size != current.size) prefs.edit().putStringSet(KEY_PANES, kept).commit()
    }

    /** Removes host-owned mutes plus obsolete singleton values during cleanup. */
    fun clearHost(hostId: String) {
        val current = muted()
        val kept = current.filterTo(mutableSetOf()) { value ->
            !isLegacyPaneValue(value) &&
                decodeHostPaneKey(value)?.profile?.hostId != hostId &&
                decodeHostScopedKey(value)?.first != hostId
        }
        if (kept.size != current.size) prefs.edit().putStringSet(KEY_PANES, kept).commit()
    }

    /**
     * Compatibility host-scoped methods for callers that only have a host id.
     * They deliberately use a separate host-qualified spelling rather than
     * putting a bare pane id back into the global set.
     */
    fun isMuted(hostId: String, paneId: String): Boolean {
        val current = muted()
        return hostScopedKey(hostId, paneId) in current || current.any { value ->
            val key = decodeHostPaneKey(value)
            key?.profile?.hostId == hostId && key.paneId == paneId
        }
    }

    fun mute(hostId: String, paneId: String) {
        prefs.edit().putStringSet(KEY_PANES, muted() + hostScopedKey(hostId, paneId)).commit()
    }

    /** Prunes all generations of one host when only pane ids are available. */
    fun prune(hostId: String, livePaneIds: Set<String>) {
        val current = muted()
        val kept = current.filterTo(mutableSetOf()) { value ->
            val key = decodeHostPaneKey(value)
            val hostScoped = decodeHostScopedKey(value)
            when {
                key != null && key.profile.hostId == hostId -> key.paneId in livePaneIds
                hostScoped?.first == hostId -> hostScoped.second in livePaneIds
                else -> true
            }
        }
        if (kept.size != current.size) prefs.edit().putStringSet(KEY_PANES, kept).commit()
    }

    /** Adopts old singleton pane mutes into the first migrated host generation. */
    fun adoptLegacyMutes(profile: HostProfileKey) {
        val current = muted()
        val adopted = current.mapTo(mutableSetOf()) { value ->
            if (isLegacyPaneValue(value)) HostPaneKey(profile, value).encode() else value
        }
        if (adopted != current) prefs.edit().putStringSet(KEY_PANES, adopted).commit()
    }

    // Legacy singleton methods. These read/write only old bare pane values so
    // an old test or migrated install cannot collide with a host-qualified key.
    fun isMuted(paneId: String): Boolean = paneId in muted()

    fun mute(paneId: String) {
        prefs.edit().putStringSet(KEY_PANES, muted() + paneId).commit()
    }

    /** Forget mutes for legacy panes the bridge no longer reports. */
    fun prune(livePaneIds: Set<String>) {
        val current = muted()
        val kept = current.filterTo(mutableSetOf()) { value ->
            isLegacyPaneValue(value) && value in livePaneIds || !isLegacyPaneValue(value)
        }
        if (kept.size != current.size) prefs.edit().putStringSet(KEY_PANES, kept).commit()
    }

    // Defensive copy: SharedPreferences hands back its own instance, and
    // mutating it would corrupt the cached value without ever persisting.
    private fun muted(): Set<String> = prefs.getStringSet(KEY_PANES, emptySet())?.toSet().orEmpty()

    private fun isLegacyPaneValue(value: String): Boolean =
        !value.startsWith(HOST_MUTE_PREFIX) && decodeHostPaneKey(value) == null

    private fun hostScopedKey(hostId: String, paneId: String): String =
        listOf(HOST_MUTE_PREFIX, encode(hostId), encode(paneId)).joinToString(".")

    private fun decodeHostScopedKey(value: String): Pair<String, String>? {
        val parts = value.split('.')
        if (parts.size != 3 || parts[0] != HOST_MUTE_PREFIX) return null
        return runCatching { decode(parts[1]) to decode(parts[2]) }.getOrNull()
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    internal companion object {
        const val FILE = "scoutr_mutes"
        const val KEY_PANES = "mutedPaneIds"
        private const val HOST_MUTE_PREFIX = "hmp1"
    }
}
