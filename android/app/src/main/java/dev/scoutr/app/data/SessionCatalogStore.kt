package dev.scoutr.app.data

import android.content.Context

/** On-device pin/archive membership, keyed by canonical backend-qualified session identity. */
interface SessionCatalogStore {
    fun pinnedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey>
    fun archivedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey>
    fun setPinned(key: SessionKey, pinned: Boolean)
    fun setArchived(key: SessionKey, archived: Boolean)
}

/**
 * SharedPreferences-backed pin/archive store.
 *
 * Entries are persisted under a host-qualified identity
 * ([HostSessionKey], `hsk1.…`) so metadata follows one bridge installation:
 * two bridges exposing the same (agentKind, path) never share entries, and
 * token or URL changes do not orphan them. Callers still speak plain
 * [SessionKey]s; the store qualifies them with [currentHostId].
 *
 * Migration (idempotent, on read):
 * - hsk1 entries are kept; only those matching the current host surface.
 * - sk1 entries are rewritten to hsk1 when a current host is known; without
 *   one they remain sk1 and stay visible (a pre-pairing device).
 * - legacy raw paths resolve against the catalog when exactly one key owns
 *   the path, then qualify like any other entry.
 */
class SharedPreferencesSessionCatalogStore(
    context: Context,
    private val currentHostId: () -> String? = { null },
) : SessionCatalogStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun pinnedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey> =
        readAndMigrate(KEY_PINNED, catalogKeys)

    override fun archivedKeys(catalogKeys: Collection<SessionKey>): Set<SessionKey> =
        readAndMigrate(KEY_ARCHIVED, catalogKeys)

    override fun setPinned(key: SessionKey, pinned: Boolean) = mutate(KEY_PINNED, key, pinned)

    override fun setArchived(key: SessionKey, archived: Boolean) = mutate(KEY_ARCHIVED, key, archived)

    /**
     * Returns the stored identities that belong to this bridge as plain
     * [SessionKey]s, migrating legacy spellings in place. Identities recorded
     * for other hosts stay in storage but are never surfaced here — they would
     * come back if the device re-pairs with that bridge.
     */
    private fun readAndMigrate(preference: String, catalogKeys: Collection<SessionKey>): Set<SessionKey> {
        val host = currentHostId()
        val stored = prefs.getStringSet(preference, emptySet()).orEmpty().toMutableSet()
        var changed = false

        // Rewrite sk1 entries straight to the current host's namespace.
        if (host != null) {
            for (entry in stored.filter { decodeHostSessionKey(it)?.hostId?.isEmpty() == true }) {
                val decoded = decodeHostSessionKey(entry)?.session ?: continue
                val qualified = HostSessionKey(hostId = host, session = decoded).encode()
                stored.remove(entry)
                stored.add(qualified)
                changed = true
            }
        }

        // Legacy raw paths: qualify only when exactly one catalog key owns the path.
        for (legacyPath in stored.filter { decodeHostSessionKey(it) == null }) {
            val matches = catalogKeys.filter { it.path == legacyPath }.distinct()
            if (matches.size != 1) continue
            val qualified = matches.single().withHostOrNull(host) ?: continue
            stored.remove(legacyPath)
            stored.add(qualified)
            changed = true
        }

        if (changed) prefs.edit().putStringSet(preference, stored).apply()

        return stored.mapNotNullTo(mutableSetOf()) { entry ->
            decodeHostSessionKey(entry)?.let { identity ->
                when {
                    identity.hostId.isEmpty() && host == null -> identity.session
                    identity.hostId.isNotEmpty() && identity.hostId == host -> identity.session
                    else -> null
                }
            }
        }
    }

    private fun mutate(preference: String, key: SessionKey, add: Boolean) {
        val encoded = key.withHostOrNull(currentHostId())
        val current = prefs.getStringSet(preference, emptySet()).orEmpty()
        val updated = if (add) current + encoded else current - encoded
        prefs.edit().putStringSet(preference, updated.toSet()).apply()
    }

    /** Host-qualified persisted spelling; falls back to sk1 while no host is known. */
    private fun SessionKey.withHostOrNull(host: String?): String =
        if (host != null) HostSessionKey(hostId = host, session = this).encode() else encode()

    private companion object {
        const val PREFS_NAME = "scoutr_session_catalog"
        const val KEY_PINNED = "pinned"
        const val KEY_ARCHIVED = "archived"
    }
}
