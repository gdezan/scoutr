package dev.scoutr.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Outcome of validating a session catalog before any key access or cache write. */
sealed interface CatalogValidation {
    data class Valid(val items: List<SessionCatalogItem>) : CatalogValidation

    data class Invalid(val reason: String) : CatalogValidation
}

/**
 * The one gate every catalog payload — live HTTP response or persisted
 * snapshot — passes before anything reads [SessionCatalogItem.key], merges
 * rows, or writes a cache. Each item must carry a nonnull canonical key whose
 * agentKind and path are nonblank and which round-trips through the codec.
 */
fun validateSessionCatalogResponse(items: List<SessionCatalogItem>): CatalogValidation {
    items.forEachIndexed { index, item ->
        val descriptor = item.session
        val key = descriptor.key
            ?: return CatalogValidation.Invalid(
                "Session $index (${descriptor.displayName}) has no canonical key",
            )
        if (key.agentKind.isBlank()) {
            return CatalogValidation.Invalid("Session $index has a blank agentKind")
        }
        if (key.path.isBlank()) {
            return CatalogValidation.Invalid("Session $index has a blank path")
        }
        val decoded = decodeSessionKey(key.encode())
        if (decoded != key) {
            return CatalogValidation.Invalid("Session $index key does not round-trip: ${key.path}")
        }
    }
    return CatalogValidation.Valid(items)
}

/** One host's durable Sessions cache: the last successful unfiltered catalog fetch. */
@Serializable
data class SessionSnapshotRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val hostId: String,
    val fetchedAtMs: Long,
    val truncated: Boolean = false,
    val sessions: List<SessionCatalogItem> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Persistent per-host Session snapshots under `filesDir/scoutr/session-snapshots`.
 *
 * `filesDir` (not `cacheDir`, which Android may evict independently of process
 * death) keeps cached rows visible after restart while their host is offline.
 * File names derive from a SHA-256 digest of [hostId] so raw host input never
 * becomes a path. Writes go through a temporary file and an atomic rename;
 * a record that fails validation on read is deleted and treated as a miss.
 */
class SessionSnapshotStore(
    private val filesDir: File,
    private val json: Json = DEFAULT_JSON,
) {
    private val directory: File get() = File(filesDir, DIRECTORY)

    /** Reads one host's snapshot; null on miss. Corrupt/foreign records are deleted. */
    fun read(hostId: String): SessionSnapshotRecord? {
        val file = fileFor(hostId)
        if (!file.isFile) return null
        val record = try {
            json.decodeFromString<SessionSnapshotRecord>(file.readText())
        } catch (_: Exception) {
            file.delete()
            return null
        }
        if (record.schemaVersion != SessionSnapshotRecord.SCHEMA_VERSION || record.hostId != hostId) {
            file.delete()
            return null
        }
        when (val validation = validateSessionCatalogResponse(record.sessions)) {
            is CatalogValidation.Valid -> return record
            is CatalogValidation.Invalid -> {
                file.delete()
                return null
            }
        }
    }

    /**
     * Validates then atomically persists one successful unfiltered fetch.
     * Invalid payloads are rejected without touching the previous snapshot.
     */
    fun write(hostId: String, fetchedAtMs: Long, truncated: Boolean, items: List<SessionCatalogItem>) {
        when (validateSessionCatalogResponse(items)) {
            is CatalogValidation.Invalid -> return
            is CatalogValidation.Valid -> Unit
        }
        val directory = directory
        directory.mkdirs()
        val record = SessionSnapshotRecord(
            hostId = hostId,
            fetchedAtMs = fetchedAtMs,
            truncated = truncated,
            sessions = items,
        )
        val target = fileFor(hostId)
        val temp = File(directory, "${target.name}.tmp")
        temp.writeText(json.encodeToString(record))
        if (!temp.renameTo(target)) {
            temp.delete()
            // Best effort only; the next successful fetch rewrites the cache.
        }
    }

    /** Removes one host's snapshot (forget and identity replacement). */
    fun clear(hostId: String) {
        fileFor(hostId).delete()
    }

    private fun fileFor(hostId: String): File =
        File(directory, digest(hostId) + ".json")

    private companion object {
        const val DIRECTORY = "scoutr/session-snapshots"

        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        fun digest(hostId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(hostId.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
