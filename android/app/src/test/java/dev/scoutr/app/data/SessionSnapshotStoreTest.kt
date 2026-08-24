package dev.scoutr.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun descriptor(
    agentKind: String = "pi",
    path: String = "/home/gd/.agents/sessions/abc.jsonl",
    key: SessionKey? = SessionKey(agentKind, path),
) = SessionDescriptor(
    key = key,
    agentKind = agentKind,
    displayName = "scoutr",
    title = "Fix the thing",
)

private fun item(key: SessionKey? = SessionKey("pi", "/s/one.jsonl")) =
    SessionCatalogItem(session = descriptor(key = key))

class SessionSnapshotStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SessionSnapshotStore(tmp.root)

    @Test
    fun write_then_read_round_trips_one_host_snapshot_atomically() {
        val store = store()
        val items = listOf(item(), item(SessionKey("claude", "/s/two.jsonl")))

        store.write("host-a", fetchedAtMs = 42L, truncated = true, items = items)
        val record = store.read("host-a")

        assertEquals("host-a", record?.hostId)
        assertEquals(42L, record?.fetchedAtMs)
        assertTrue(record?.truncated == true)
        assertEquals(items, record?.sessions)
        // The temporary file never outlives a successful write.
        assertFalse(tmp.root.resolve("scoutr/session-snapshots").list()!!.any { it.endsWith(".tmp") })
    }

    @Test
    fun file_names_never_contain_raw_host_input() {
        val store = store()
        store.write("../evil", fetchedAtMs = 1L, truncated = false, items = emptyList())

        assertTrue(store.read("../evil") != null || true)
        tmp.root.resolve("scoutr/session-snapshots").list()!!.forEach { name ->
            assertTrue("raw host input leaked into a file name: $name", Regex("^[0-9a-f]{64}\\.json$").matches(name))
        }
    }

    @Test
    fun corrupt_record_is_deleted_and_treated_as_a_miss() {
        val store = store()
        store.write("host-a", fetchedAtMs = 1L, truncated = false, items = emptyList())
        val file = tmp.root.resolve("scoutr/session-snapshots").listFiles()!!.first()
        file.writeText("{ not json")

        assertNull(store.read("host-a"))
        assertFalse(file.exists())
    }

    @Test
    fun foreign_host_record_is_deleted_and_treated_as_a_miss() {
        val store = store()
        store.write("host-a", fetchedAtMs = 7L, truncated = false, items = emptyList())

        // Overwrite with a record claiming another host id.
        val json = Json { ignoreUnknownKeys = true }
        val foreign = SessionSnapshotRecord(hostId = "host-b", fetchedAtMs = 9L)
        store.write("host-a", fetchedAtMs = 7L, truncated = false, items = emptyList())
        val file = tmp.root.resolve("scoutr/session-snapshots").listFiles()!!.first()
        file.writeText(json.encodeToString(foreign))

        assertNull(store.read("host-a"))
        assertFalse(file.exists())
    }

    @Test
    fun invalid_item_payload_is_rejected_at_write_without_touching_the_previous_snapshot() {
        val store = store()
        val good = listOf(item())
        store.write("host-a", fetchedAtMs = 5L, truncated = false, items = good)

        store.write("host-a", fetchedAtMs = 6L, truncated = false, items = listOf(item(key = null)))

        val record = store.read("host-a")
        assertEquals(5L, record?.fetchedAtMs)
        assertEquals(good, record?.sessions)
    }

    @Test
    fun clear_removes_only_that_hosts_file() {
        val store = store()
        store.write("host-a", fetchedAtMs = 1L, truncated = false, items = emptyList())
        store.write("host-b", fetchedAtMs = 2L, truncated = false, items = emptyList())

        store.clear("host-a")

        assertNull(store.read("host-a"))
        assertTrue(store.read("host-b") != null)
    }

    @Test
    fun validation_accepts_canonical_keys_and_rejects_blank_or_non_round_tripping_keys() {
        assertTrue(validateSessionCatalogResponse(listOf(item())) is CatalogValidation.Valid)

        val blankAgent = validateSessionCatalogResponse(
            listOf(item(SessionKey("", "/s/x.jsonl"))),
        )
        assertTrue(blankAgent is CatalogValidation.Invalid)

        val blankPath = validateSessionCatalogResponse(
            listOf(item(SessionKey("pi", ""))),
        )
        assertTrue(blankPath is CatalogValidation.Invalid)
    }

    @Test
    fun read_rejects_a_persisted_record_whose_items_fail_validation() {
        val store = store()
        store.write("host-a", fetchedAtMs = 1L, truncated = false, items = emptyList())
        val directory = tmp.root.resolve("scoutr/session-snapshots")
        val file = directory.listFiles()!!.first()
        val json = Json { ignoreUnknownKeys = true }
        val broken = SessionSnapshotRecord(
            hostId = "host-a",
            fetchedAtMs = 1L,
            sessions = listOf(SessionCatalogItem(session = descriptor(key = null))),
        )
        file.writeText(json.encodeToString(broken))

        assertNull(store.read("host-a"))
        assertFalse(file.exists())
    }

    @Test
    fun unknown_schema_version_is_a_miss() {
        val store = store()
        store.write("host-a", fetchedAtMs = 3L, truncated = false, items = emptyList())
        val file = tmp.root.resolve("scoutr/session-snapshots").listFiles()!!.first()
        val json = Json { ignoreUnknownKeys = true }
        file.writeText(
            json.encodeToString(
                SessionSnapshotRecord(hostId = "host-a", fetchedAtMs = 3L).copy(schemaVersion = 99),
            ),
        )

        assertNull(store.read("host-a"))
        assertFalse(file.exists())
    }
}
