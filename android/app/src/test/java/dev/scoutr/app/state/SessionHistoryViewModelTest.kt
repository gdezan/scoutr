package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.CreatedSessionResponse
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.SessionCatalogItem
import dev.scoutr.app.data.SessionCatalogResponse
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.SessionLiveAttachment
import dev.scoutr.app.data.catalogSessionFixture
import dev.scoutr.app.net.BridgeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Multi-host Sessions workers: cache-first rows, per-host fetch isolation,
 * generation-guarded cross-host search, and host-qualified mutations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionHistoryViewModelTest {

    private fun item(
        agent: String = "pi",
        path: String = "/root/sessions/abc.jsonl",
        title: String = "Fix billing bug",
        cwd: String = "/repo/a",
        updatedAtMs: Double = System.currentTimeMillis().toDouble(),
        paneId: String? = null,
    ) = catalogSessionFixture(
        key = SessionKey(agent, path),
        title = title,
        cwd = cwd,
        model = "openai-codex/gpt-5.4",
        updatedAtMs = updatedAtMs,
        latestActivity = "latest",
        live = paneId?.let {
            SessionLiveAttachment(paneId = it, workspaceId = "ws1", tabId = "t1", status = "blocked")
        },
    )

    private fun response(vararg items: SessionCatalogItem, truncated: Boolean = false) =
        Result.success(SessionCatalogResponse(ok = true, truncated = truncated, sessions = items.toList()))

    private val store = RecordingSessionCatalogStore()

    private fun harness(vararg hostIds: String): BoardHarness {
        val h = jvmBoardHarness()
        hostIds.forEach { h.addHost(it) }
        return h
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
            idle()
        }
        assertTrue("condition did not become true before timeout", condition())
    }

    /** Starts the poll and waits until the first live cycle has landed. */
    private fun seededViewModel(h: BoardHarness): SessionHistoryViewModel {
        val vm = h.historyViewModel(catalogStore = store)
        vm.startPolling()
        waitUntil { !vm.ui.value.loading && vm.ui.value.hostCatalogs.isNotEmpty() }
        return vm
    }

    @Test
    fun loadsCatalogWithPinAndArchiveFlags() {
        val h = harness("host-a")
        store.pinned.add(SessionKey("pi", "/root/sessions/abc.jsonl"))
        h.apiFor("host-a").sessionCatalogResult = response(item())
        val vm = seededViewModel(h)

        val rows = vm.items()
        assertEquals(1, rows.size)
        assertTrue(rows[0].pinned)
        assertFalse(rows[0].archived)
        assertEquals("Fix billing bug", rows[0].session.title)
        assertEquals("host-a", rows[0].hostId)
        assertNull(vm.ui.value.busySessionKey)
        vm.stopPolling()
    }

    @Test
    fun successfulCatalogFetchSuppliesKeysForLegacyMetadataAdoption() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult = response(item())
        var adopted = emptyList<SessionKey>()
        val vm = h.historyViewModel(catalogStore = store, adoptLegacyMetadata = { adopted = it.toList() })
        vm.startPolling()
        waitUntil { adopted.isNotEmpty() }
        vm.stopPolling()
        assertEquals(listOf(SessionKey("pi", "/root/sessions/abc.jsonl")), adopted)
    }

    @Test
    fun mergeCombinesRowsFromBothHostsUnderAllScope() {
        val h = harness("host-a", "host-b")
        h.apiFor("host-a").sessionCatalogResult = response(item(path = "/s/a.jsonl"))
        h.apiFor("host-b").sessionCatalogResult =
            response(item(path = "/s/b.jsonl", title = "Docs refresh"))
        val vm = seededViewModel(h)

        waitUntil { vm.items().size == 2 }
        assertEquals(
            setOf("host-a", "host-b"),
            vm.items().map { it.hostId }.toSet(),
        )
        // The shared filter narrows membership without touching other hosts' data.
        vm.selectFilter("host-b")
        waitUntil { vm.items().size == 1 }
        assertEquals("host-b", vm.items().single().hostId)
        vm.stopPolling()
    }

    @Test
    fun offlineFailureKeepsTheHostSnapshotAndMarksItStale() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult = response(item())
        val vm = seededViewModel(h)
        waitUntil { vm.ui.value.connected }

        h.apiFor("host-a").sessionCatalogResult =
            Result.failure(IOException("bridge unreachable"))
        vm.retry()
        waitUntil { !vm.ui.value.connected }
        // Cached/current snapshot survives so rows remain visible with a
        // stale marker instead of vanishing mid-read.
        assertEquals(1, vm.items().size)
        assertTrue(vm.ui.value.statuses["host-a"] is HostAvailability.Offline)
        vm.stopPolling()
    }

    @Test
    fun cacheFirstRendersPersistedSnapshotWhenTheHostIsOffline() {
        val h = harness("host-a")
        val persisted = listOf(item(path = "/s/cached.jsonl", title = "Cached row"))
        runBlocking { h.snapshots.write("host-a", fetchedAtMs = 5_000L, truncated = false, items = persisted) }
        h.apiFor("host-a").sessionCatalogResult =
            Result.failure(IOException("bridge unreachable"))

        val vm = h.historyViewModel(catalogStore = store)
        vm.startPolling()
        waitUntil { !vm.ui.value.loading && vm.ui.value.hostCatalogs.isNotEmpty() }
        Thread.sleep(100) // let any (failing) network leg settle
        idle()

        assertEquals("Cached row", vm.items().single().session.title)
        assertTrue(vm.ui.value.statuses["host-a"] is HostAvailability.Offline)
        assertFalse(vm.remoteActionsEnabled(vm.items().single()))
        vm.stopPolling()
    }

    @Test
    fun queryIsSentToTheBridgeAndFiltersAcrossHosts() {
        val h = harness("host-a", "host-b")
        // The bridge filters server-side, so the fakes answer per query.
        h.apiFor("host-a").sessionCatalogResult = response(item(title = "Billing fix"))
        h.apiFor("host-a").onCall = { name, args ->
            if (name == "sessionCatalog" && args["query"] == "billing") {
                Result.success(
                    SessionCatalogResponse(ok = true, sessions = listOf(item(title = "Billing fix"))),
                )
            } else null
        }
        h.apiFor("host-b").sessionCatalogResult =
            response(item(path = "/s/b.jsonl", title = "Docs refresh"))
        h.apiFor("host-b").onCall = { name, args ->
            if (name == "sessionCatalog" && args["query"] == "billing") {
                Result.success(SessionCatalogResponse(ok = true, sessions = emptyList()))
            } else null
        }
        // Zero debounce: Robolectric's paused main-looper clock never elapses
        // the production 300ms window.
        val vm = h.historyViewModel(catalogStore = store, searchDebounceMs = 0L)
        vm.startPolling()
        waitUntil { !vm.ui.value.loading && vm.ui.value.hostCatalogs.size == 2 }

        vm.setQuery("billing")
        waitUntil { vm.ui.value.query == "billing" }
        waitUntil {
            h.apiFor("host-a").calls.any {
                it.name == "sessionCatalog" && it.args["query"] == "billing"
            }
        }
        // Merged search rows keep only matches; host-b's non-matching cached
        // row is not smuggled in by its base snapshot.
        waitUntil {
            val rows = vm.items()
            rows.size == 1 && rows.single().hostId == "host-a"
        }
        vm.stopPolling()
    }

    @Test
    fun blankQueryCancelsSearchesAndFallsBackToBaseSnapshots() {
        val h = harness("host-a")
        val gate = CompletableDeferred<Unit>()
        // Park the very first base cycle so nothing else can land early.
        h.apiFor("host-a").gates["sessionCatalog"] = gate
        h.apiFor("host-a").sessionCatalogResult = response(item(title = "Base row"))
        val vm = h.historyViewModel(catalogStore = store)
        vm.startPolling()

        vm.setQuery("narrow")
        vm.setQuery("")
        gate.complete(Unit)
        waitUntil { vm.items().size == 1 }

        assertTrue(
            "a cancelled search must never reach its bridge",
            h.apiFor("host-a").calls.none {
                it.name == "sessionCatalog" && it.args["query"] == "narrow"
            },
        )
        assertEquals("Base row", vm.items().single().session.title)
        vm.stopPolling()
    }

    @Test
    fun resumeReturnsTheOriginalCanonicalKeyWithItsOwnHostProfile() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult = response(item(paneId = "pane1"))
        h.apiFor("host-a").catalogActionResult =
            Result.success(CreatedSessionResponse(ok = true, workspaceId = "ws9", paneId = "pane9"))
        val vm = seededViewModel(h)

        val resumed = runBlocking { vm.resume(vm.items().first()) }
        assertNotNull(resumed)
        assertEquals(SessionKey("pi", "/root/sessions/abc.jsonl"), resumed!!.key)
        assertEquals("host-a", resumed.profile?.hostId)
        assertNull(vm.ui.value.busySessionKey)
        vm.stopPolling()
    }

    @Test
    fun forkUsesFreshPaneBootstrapInsteadOfReusingTheOriginalKey() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult = response(item(paneId = "pane1"))
        h.apiFor("host-a").catalogActionResult =
            Result.success(CreatedSessionResponse(ok = true, workspaceId = "ws9", paneId = "pane9"))
        val vm = seededViewModel(h)

        val forked = runBlocking { vm.fork(vm.items().first()) }
        assertNotNull(forked)
        assertNull(forked!!.key)
        assertEquals("pane9", forked.bootstrapPaneId)
        val action = h.apiFor("host-a").calls.last { it.name == "sessionCatalogAction" }
        assertEquals(CatalogAction.Fork, action.args["action"])
        vm.stopPolling()
    }

    @Test
    fun renameSendsTextToTheRowOwnHost() {
        val h = harness("host-a", "host-b")
        h.apiFor("host-a").sessionCatalogResult = response(item(paneId = "pane1"))
        h.apiFor("host-b").sessionCatalogResult =
            response(item(path = "/s/b.jsonl", title = "Other host row"))
        val vm = seededViewModel(h)

        val ok = runBlocking { vm.rename(vm.items().first(), "New title") }
        assertTrue(ok)
        assertNull(vm.ui.value.busySessionKey)
        // The mutation lands on the row's own bridge and nowhere else.
        assertEquals(
            "New title",
            h.apiFor("host-a").calls.last { it.name == "sessionCatalogAction" }.args["text"],
        )
        assertEquals(0, h.apiFor("host-b").calls.count { it.name == "sessionCatalogAction" })
        vm.stopPolling()
    }

    @Test
    fun deleteClearsLocalFlagsOnSuccess() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult = response(item(paneId = null))
        val key = SessionKey("pi", "/root/sessions/abc.jsonl")
        store.pinned.add(key)
        store.archived.add(key)
        val vm = seededViewModel(h)

        val ok = runBlocking { vm.delete(vm.items().first()) }
        assertTrue(ok)
        assertFalse(store.pinned.contains(key))
        assertFalse(store.archived.contains(key))
        vm.stopPolling()
    }

    @Test
    fun closeRoutesToPaneControlOnTheOwningHost() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult = response(item(paneId = "pane1"))
        val vm = seededViewModel(h)

        val ok = runBlocking { vm.close(vm.items().first()) }
        assertTrue(ok)
        val control = h.apiFor("host-a").calls.last { it.name == "controlSession" }
        assertEquals("pane1", control.args["paneId"])
        assertEquals(SessionAction.Close, control.args["action"])
        vm.stopPolling()
    }

    @Test
    fun bridgeErrorSurfacesErrorStateWithoutRows() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult =
            Result.failure(BridgeException(503, "no herdr snapshot yet"))

        val vm = h.historyViewModel(catalogStore = store)
        vm.startPolling()
        waitUntil { !vm.ui.value.loading && vm.ui.value.transientError != null }

        assertFalse(vm.ui.value.connected)
        assertNotNull(vm.ui.value.transientError)
        vm.stopPolling()
    }

    @Test
    fun successfulFetchPersistsTheSnapshotForTheNextColdStart() {
        val h = harness("host-a")
        h.apiFor("host-a").sessionCatalogResult = response(item(), truncated = true)
        val vm = seededViewModel(h)

        waitUntil {
            runBlocking { h.snapshots.read("host-a") } != null
        }
        val record = runBlocking { h.snapshots.read("host-a") }!!
        assertEquals("host-a", record.hostId)
        assertTrue(record.truncated)
        assertEquals(1, record.sessions.size)
        vm.stopPolling()
    }
}
