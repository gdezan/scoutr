package dev.scoutr.app.state

import dev.scoutr.app.data.SessionEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the chat scroll crash: pi message ids are random hex
 * strings, so a misbehaving cursor could make a poll re-deliver already-loaded
 * entries. Appending them produced duplicate LazyColumn keys, which Compose
 * rejects with "Key ... was already used" while scrolling. mergeSessionEntries
 * guarantees an incremental poll never duplicates an existing entry id.
 */
class ChatMergeTest {

    private fun entry(id: String) = SessionEntry(entryId = id)

    @Test
    fun fullSnapshotReplacesExistingList() {
        val existing = listOf(entry("a1"))
        val fresh = listOf(entry("b2"), entry("c3"))
        assertEquals(fresh, mergeSessionEntries(existing, fresh, incremental = false))
    }

    @Test
    fun incrementalPollAppendsNewEntries() {
        val existing = listOf(entry("a1"), entry("b2"))
        val incoming = listOf(entry("c3"))
        assertEquals(listOf(entry("a1"), entry("b2"), entry("c3")), mergeSessionEntries(existing, incoming, incremental = true))
    }

    /** The crash case: incoming re-delivers entries that are already present. */
    @Test
    fun incrementalPollDropsAlreadyLoadedEntries() {
        val existing = listOf(entry("a1"), entry("b2"), entry("c3"))
        val incoming = listOf(entry("a1"), entry("b2"), entry("c3"), entry("d4"))
        val merged = mergeSessionEntries(existing, incoming, incremental = true)
        assertEquals(4, merged.size)
        assertEquals(listOf("a1", "b2", "c3", "d4"), merged.map { it.entryId })
        // LazyColumn keys must be unique — the exact property that crashed.
        assertEquals(merged.size, merged.map { it.entryId }.toSet().size)
    }

    @Test
    fun incrementalPollWithEmptyIncomingAppendsNothing() {
        val existing = listOf(entry("a1"))
        assertEquals(existing, mergeSessionEntries(existing, emptyList(), incremental = true))
    }
}
