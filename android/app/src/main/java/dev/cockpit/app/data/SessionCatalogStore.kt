package dev.cockpit.app.data

import android.content.Context

/**
 * On-device session catalog preferences: pinned and archived session paths.
 *
 * The bridge catalog stays read-only; pinning only lifts a session into the
 * Pinned view, and archiving only hides it from the main views (the stored
 * session file is untouched — destructive delete stays an explicit action).
 */
interface SessionCatalogStore {
    fun pinnedPaths(): Set<String>
    fun archivedPaths(): Set<String>
    fun setPinned(path: String, pinned: Boolean)
    fun setArchived(path: String, archived: Boolean)
}

class SharedPreferencesSessionCatalogStore(context: Context) : SessionCatalogStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun pinnedPaths(): Set<String> = prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()

    override fun archivedPaths(): Set<String> = prefs.getStringSet(KEY_ARCHIVED, emptySet()) ?: emptySet()

    override fun setPinned(path: String, pinned: Boolean) {
        prefs.edit().putStringSet(KEY_PINNED, mutate(pinnedPaths(), path, pinned)).apply()
    }

    override fun setArchived(path: String, archived: Boolean) {
        prefs.edit().putStringSet(KEY_ARCHIVED, mutate(archivedPaths(), path, archived)).apply()
    }

    private fun mutate(current: Set<String>, path: String, add: Boolean): Set<String> =
        (if (add) current + path else current - path).toSet()

    private companion object {
        const val PREFS_NAME = "cockpit_session_catalog"
        const val KEY_PINNED = "pinned"
        const val KEY_ARCHIVED = "archived"
    }
}
