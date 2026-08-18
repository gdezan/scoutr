package dev.scoutr.app.data

import android.content.Context

/** On-device pin/archive membership, keyed by canonical backend-qualified session identity. */
interface SessionCatalogStore {
    fun pinnedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey>
    fun archivedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey>
    fun setPinned(key: SessionKey, pinned: Boolean)
    fun setArchived(key: SessionKey, archived: Boolean)
}

class SharedPreferencesSessionCatalogStore(context: Context) : SessionCatalogStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun pinnedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey> =
        readAndMigrate(KEY_PINNED, catalogKeys)

    override fun archivedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey> =
        readAndMigrate(KEY_ARCHIVED, catalogKeys)

    override fun setPinned(key: SessionKey, pinned: Boolean) = mutate(KEY_PINNED, key, pinned)

    override fun setArchived(key: SessionKey, archived: Boolean) = mutate(KEY_ARCHIVED, key, archived)

    /**
     * Legacy entries are raw paths in the same preference set. Replace one
     * only when exactly one catalog key owns that path; unresolved or
     * ambiguous entries remain available for a later catalog refresh.
     */
    private fun readAndMigrate(preference: String, catalogKeys: Collection<SessionKey>): Set<SessionKey> {
        val stored = prefs.getStringSet(preference, emptySet()).orEmpty().toMutableSet()
        var changed = false
        for (legacyPath in stored.filter { decodeSessionKey(it) == null }) {
            val matches = catalogKeys.filter { it.path == legacyPath }.distinct()
            if (matches.size != 1) continue
            stored.remove(legacyPath)
            stored.add(matches.single().encode())
            changed = true
        }
        if (changed) prefs.edit().putStringSet(preference, stored).apply()
        return stored.mapNotNullTo(mutableSetOf(), ::decodeSessionKey)
    }

    private fun mutate(preference: String, key: SessionKey, add: Boolean) {
        val current = prefs.getStringSet(preference, emptySet()).orEmpty()
        val encoded = key.encode()
        val updated = if (add) current + encoded else current - encoded
        prefs.edit().putStringSet(preference, updated.toSet()).apply()
    }

    private companion object {
        const val PREFS_NAME = "scoutr_session_catalog"
        const val KEY_PINNED = "pinned"
        const val KEY_ARCHIVED = "archived"
    }
}
