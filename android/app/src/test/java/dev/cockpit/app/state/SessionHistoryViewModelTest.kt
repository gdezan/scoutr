package dev.cockpit.app.state

import dev.cockpit.app.data.CatalogAction
import dev.cockpit.app.data.SessionAction
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.CreatedSessionResponse
import dev.cockpit.app.data.SessionCatalogItem
import dev.cockpit.app.data.SessionCatalogResponse
import dev.cockpit.app.data.SessionCatalogStore
import dev.cockpit.app.net.BridgeException
import dev.cockpit.app.net.FakeCockpitApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionHistoryViewModelTest {

    private lateinit var fake: FakeCockpitApi
    private lateinit var store: RecordingSessionCatalogStore

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
        store = RecordingSessionCatalogStore()
        stubCatalog()
    }

    private fun stubCatalog() {
        fake.sessionCatalogResult = Result.success(
            SessionCatalogResponse(
                ok = true,
                truncated = false,
                sessions = listOf(
                    SessionCatalogItem(
                        id = "abc",
                        path = "/root/sessions/abc.jsonl",
                        title = "Fix billing bug",
                        cwd = "/repo/a",
                        model = "openai-codex/gpt-5.4",
                        updatedAt = System.currentTimeMillis().toDouble(),
                        preview = "User asked to fix the billing math",
                        active = true,
                        paneId = "pane1",
                        workspaceId = "ws1",
                        status = "blocked",
                    ),
                    SessionCatalogItem(
                        id = "def",
                        path = "/root/sessions/def.jsonl",
                        title = "Docs refresh",
                        cwd = "/repo/b",
                        model = "anthropic/claude-sonnet-4-6",
                        updatedAt = (System.currentTimeMillis() - 3_600_000).toDouble(),
                        preview = "Update the README",
                        active = false,
                    ),
                ),
            ),
        )
        fake.catalogActionResult = Result.success(
            CreatedSessionResponse(ok = true, workspaceId = "ws9", paneId = "pane9"),
        )
    }

    private fun catalogQueries(): List<String?> =
        fake.calls.filter { it.name == "sessionCatalog" }.map { it.args["query"] as String? }

    @Test
    fun loadsCatalogWithPinAndArchiveFlags() = runBlocking {
        store.pinned.add("/root/sessions/abc.jsonl")
        val connection = savedConnection()
        val viewModel = SessionHistoryViewModel(fake, connection, store)
        viewModel.waitForLoaded()

        val ui = viewModel.ui.value
        assertEquals(2, ui.items.size)
        assertTrue(ui.items[0].pinned)
        assertFalse(ui.items[1].pinned)
        assertEquals("Fix billing bug", ui.items[0].session.title)
        assertEquals("openai-codex/gpt-5.4", ui.items[0].session.model)
    }

    @Test
    fun queryIsSentToTheBridgeAndFilters() = runBlocking {
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.setQuery("billing")
        viewModel.waitForLoaded()

        val deadline = System.currentTimeMillis() + 2_000
        while ("billing" !in catalogQueries() && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            delay(25)
        }
        assertTrue("expected a search with q=billing, saw ${catalogQueries()}", "billing" in catalogQueries())
    }

    @Test
    fun resumeReturnsPaneToOpen() = runBlocking {
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val resumed = viewModel.resume(viewModel.ui.value.items[0])
        assertNotNull(resumed)
        assertEquals("pane9", resumed!!.paneId)
        assertNull(viewModel.ui.value.busyPath)
    }

    @Test
    fun renameSendsTextAndSucceeds() = runBlocking {
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.rename(viewModel.ui.value.items[0], "New title")
        assertTrue(ok)
        assertNull(viewModel.ui.value.busyPath)
        val rename = fake.calls.last { it.name == "sessionCatalogAction" }
        assertEquals(CatalogAction.Rename, rename.args["action"])
        assertEquals("New title", rename.args["text"])
    }

    @Test
    fun deleteClearsLocalFlagsOnSuccess() = runBlocking {
        store.pinned.add("/root/sessions/def.jsonl")
        store.archived.add("/root/sessions/def.jsonl")
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.delete(viewModel.ui.value.items[1])
        assertTrue(ok)
        assertFalse(store.pinned.contains("/root/sessions/def.jsonl"))
        assertFalse(store.archived.contains("/root/sessions/def.jsonl"))
    }

    @Test
    fun closeRoutesToPaneControl() = runBlocking {
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.close(viewModel.ui.value.items[0])
        assertTrue(ok)
        val control = fake.calls.last { it.name == "controlSession" }
        assertEquals("pane1", control.args["paneId"])
        assertEquals(SessionAction.Close, control.args["action"])
    }

    @Test
    fun bridgeErrorSurfacesErrorState() = runBlocking {
        fake.sessionCatalogResult = Result.failure(BridgeException(503, "no herdr snapshot yet"))
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        assertFalse(viewModel.ui.value.connected)
        assertNotNull(viewModel.ui.value.error)
    }

    private fun savedConnection(): ConnectionStore =
        ConnectionStore(RuntimeEnvironment.getApplication()).apply {
            save("http://test-bridge", "test-token", null, null)
        }

    private fun SessionHistoryViewModel.waitForLoaded() = runBlocking {
        // Polling is lifecycle-scoped now (plan 005): the screen starts the
        // loop, so these tests must too.
        startPolling()
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (!ui.value.loading && (ui.value.items.isNotEmpty() || ui.value.error != null)) return@runBlocking
            delay(25)
        }
        assertTrue("History did not settle", !ui.value.loading)
    }
}

/** In-memory catalog store for tests. */
class RecordingSessionCatalogStore : SessionCatalogStore {
    val pinned = mutableSetOf<String>()
    val archived = mutableSetOf<String>()

    override fun pinnedPaths(): Set<String> = pinned.toSet()
    override fun archivedPaths(): Set<String> = archived.toSet()
    override fun setPinned(path: String, pinned: Boolean) {
        if (pinned) this.pinned.add(path) else this.pinned.remove(path)
    }

    override fun setArchived(path: String, archived: Boolean) {
        if (archived) this.archived.add(path) else this.archived.remove(path)
    }
}