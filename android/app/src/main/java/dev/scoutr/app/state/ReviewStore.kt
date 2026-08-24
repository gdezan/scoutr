package dev.scoutr.app.state

import android.content.Context
import android.util.Base64

/**
 * Remembers the last reviewed repository per bridge installation.
 *
 * A repository path is only meaningful to the bridge that exposed it. The
 * store therefore has no unqualified read/write API: every durable value is
 * keyed by the supplied host id. [adoptLegacyPath] is the sole migration seam
 * for the old singleton value.
 */
class ReviewStore(
    context: Context,
    hostId: String,
    private val writeIfRegistered: (String, () -> Unit) -> Boolean = { _, write ->
        write()
        true
    },
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val scopedHostId = requireHostId(hostId)

    /** Host-scoped view used by ReviewViewModel. */
    var lastRepoPath: String?
        get() = read(scopedHostId)
        set(value) = write(scopedHostId, value)

    /** Explicit host-qualified access for cleanup coordinators. */
    fun lastRepoPath(hostId: String): String? = read(requireHostId(hostId))

    fun setLastRepoPath(hostId: String, value: String?) =
        write(requireHostId(hostId), value)

    /**
     * Adopts the old singleton path for the first host. This must be called by
     * migration, not when a review screen happens to open.
     */
    @Synchronized
    fun adoptLegacyPath(hostId: String) {
        val host = requireHostId(hostId)
        val adoptedHost = prefs.getString(KEY_LEGACY_HOST_ID, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        require(adoptedHost == null || adoptedHost == host) {
            "Legacy review state already belongs to $adoptedHost"
        }

        val legacy = prefs.getString(KEY_LAST_REPO, null)?.takeIf(String::isNotBlank)
        val editor = prefs.edit().putString(KEY_LEGACY_HOST_ID, host)
        if (legacy != null && read(host) == null) editor.putString(key(host), legacy)
        editor.remove(KEY_LAST_REPO)
        check(editor.commit()) { "Could not adopt legacy review state" }
    }

    /** Clears only this host's transient review selection. */
    @Synchronized
    fun clearHost(hostId: String): Boolean {
        val key = key(requireHostId(hostId))
        if (!prefs.contains(key)) return true
        return prefs.edit().remove(key).commit()
    }

    private fun read(hostId: String): String? =
        prefs.getString(key(hostId), null)?.takeIf(String::isNotBlank)

    private fun write(hostId: String, value: String?) {
        writeIfRegistered(hostId) {
            val editor = prefs.edit()
            val key = key(hostId)
            if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
            check(editor.commit()) { "Could not persist review state" }
        }
    }

    private fun key(hostId: String): String =
        "$KEY_HOST_PREFIX${Base64.encodeToString(hostId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)}"

    private fun requireHostId(value: String): String =
        value.trim().takeIf(String::isNotEmpty) ?: error("Host id must be nonblank")

    private companion object {
        const val FILE = "scoutr_review"
        const val KEY_LAST_REPO = "lastRepoPath"
        const val KEY_HOST_PREFIX = "lastRepoPath.host."
        const val KEY_LEGACY_HOST_ID = "legacyHostId"
    }
}
