package dev.scoutr.app.data

import android.content.Context

/**
 * Device-local retained session metadata. New callers must always provide the
 * bridge installation identity; the catalog key itself deliberately has no
 * profile generation so pin/archive flags survive forget and same-id repair.
 */
interface SessionCatalogStore {
    fun pinnedKeys(catalogKeys: Collection<HostSessionKey>): Set<HostSessionKey>
    fun archivedKeys(catalogKeys: Collection<HostSessionKey>): Set<HostSessionKey>
    fun setPinned(key: HostSessionKey, pinned: Boolean)
    fun setArchived(key: HostSessionKey, archived: Boolean)

    /**
     * Qualifies the old singleton entries for the first host. This is the
     * migration seam, not a read-time fallback: normal catalog reads never
     * move unqualified data between host namespaces.
     *
     * Raw path entries can only be qualified when the first-host catalog has
     * supplied their unique agent/path owner. Old sk1 entries carry that
     * information and are migrated without a catalog.
     */
    fun adoptLegacyEntries(hostId: String, catalogKeys: Collection<SessionKey> = emptyList())
    fun hasUnqualifiedLegacyEntries(): Boolean = false

    /**
     * Copies retained pin/archive membership after an explicit identity
     * replacement confirmation. Forget does not call this: retained entries
     * remain attached to their original host id.
     */
    fun copyRetainedMetadata(fromHostId: String, toHostId: String, confirmed: Boolean)
}

/**
 * SharedPreferences-backed pin/archive membership.
 *
 * Every new write is an hsk1 [HostSessionKey]. Entries for other hosts are
 * hidden by the caller's catalog query but are never removed, including after
 * forget, so aliases and retained pin/archive semantics remain intact.
 */
class SharedPreferencesSessionCatalogStore(context: Context) : SessionCatalogStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun pinnedKeys(catalogKeys: Collection<HostSessionKey>): Set<HostSessionKey> =
        readQualified(KEY_PINNED, catalogKeys)

    override fun archivedKeys(catalogKeys: Collection<HostSessionKey>): Set<HostSessionKey> =
        readQualified(KEY_ARCHIVED, catalogKeys)

    override fun setPinned(key: HostSessionKey, pinned: Boolean) =
        mutate(KEY_PINNED, key, pinned)

    override fun setArchived(key: HostSessionKey, archived: Boolean) =
        mutate(KEY_ARCHIVED, key, archived)

    @Synchronized
    override fun adoptLegacyEntries(hostId: String, catalogKeys: Collection<SessionKey>) {
        val host = requireHostId(hostId)
        val adoptedHost = prefs.getString(KEY_LEGACY_HOST_ID, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        require(adoptedHost == null || adoptedHost == host) {
            "Legacy session metadata already belongs to $adoptedHost"
        }

        val editor = prefs.edit().putString(KEY_LEGACY_HOST_ID, host)
        for (preference in listOf(KEY_PINNED, KEY_ARCHIVED)) {
            val entries = stored(preference)
            var changed = false
            for (entry in entries.toList()) {
                val decoded = decodeHostSessionKey(entry)
                val qualified = when {
                    // sk1 was the old canonical spelling and is safe to adopt.
                    decoded?.hostId?.isEmpty() == true ->
                        HostSessionKey(host, decoded.session).encode()
                    // Raw paths predate SessionKeyCodec. Only a unique catalog
                    // owner can supply the missing agent kind.
                    decoded == null -> catalogKeys
                        .asSequence()
                        .filter { it.path == entry }
                        .distinct()
                        .singleOrNull()
                        ?.let { HostSessionKey(host, it).encode() }
                    else -> null
                } ?: continue
                if (qualified != entry) {
                    entries.remove(entry)
                    entries.add(qualified)
                    changed = true
                }
            }
            if (changed) editor.putStringSet(preference, entries)
        }
        check(editor.commit()) { "Could not adopt legacy session metadata" }
    }

    override fun hasUnqualifiedLegacyEntries(): Boolean =
        listOf(KEY_PINNED, KEY_ARCHIVED).any { preference ->
            stored(preference).any { entry ->
                decodeHostSessionKey(entry)?.hostId.isNullOrEmpty()
            }
        }

    @Synchronized
    override fun copyRetainedMetadata(
        fromHostId: String,
        toHostId: String,
        confirmed: Boolean,
    ) {
        require(confirmed) { "Pin/archive migration requires explicit confirmation" }
        val from = requireHostId(fromHostId)
        val to = requireHostId(toHostId)
        require(from != to) { "Metadata copy requires two distinct hosts" }
        val editor = prefs.edit()
        for (preference in listOf(KEY_PINNED, KEY_ARCHIVED)) {
            val entries = stored(preference)
            val additions = entries.asSequence()
                .mapNotNull { decodeHostSessionKey(it) }
                .filter { it.hostId == from }
                .map { HostSessionKey(to, it.session).encode() }
                .toSet()
            if (additions.isNotEmpty()) editor.putStringSet(preference, entries + additions)
        }
        check(editor.commit()) { "Could not copy retained session metadata" }
    }

    private fun readQualified(
        preference: String,
        catalogKeys: Collection<HostSessionKey>,
    ): Set<HostSessionKey> {
        val catalog = catalogKeys.toSet()
        return stored(preference).mapNotNullTo(linkedSetOf()) { encoded ->
            val key = decodeHostSessionKey(encoded) ?: return@mapNotNullTo null
            if (key.hostId.isBlank()) return@mapNotNullTo null
            if (catalog.isEmpty() || key in catalog) key else null
        }
    }

    @Synchronized
    private fun mutate(preference: String, key: HostSessionKey, add: Boolean) {
        requireHostSessionKey(key)
        val entries = stored(preference)
        val encoded = key.encode()
        val updated = if (add) entries + encoded else entries - encoded
        check(prefs.edit().putStringSet(preference, updated).commit()) {
            "Could not persist session metadata"
        }
    }

    private fun stored(preference: String): MutableSet<String> =
        prefs.getStringSet(preference, emptySet()).orEmpty().toMutableSet()

    private fun requireHostId(value: String): String =
        value.trim().takeIf(String::isNotEmpty) ?: error("Host id must be nonblank")

    private fun requireHostSessionKey(key: HostSessionKey) {
        requireHostId(key.hostId)
        require(key.session.agentKind.isNotBlank()) { "Agent kind must be nonblank" }
        require(key.session.path.isNotBlank()) { "Session path must be nonblank" }
    }

    private companion object {
        const val PREFS_NAME = "scoutr_session_catalog"
        const val KEY_PINNED = "pinned"
        const val KEY_ARCHIVED = "archived"
        const val KEY_LEGACY_HOST_ID = "legacyHostId"
    }
}
