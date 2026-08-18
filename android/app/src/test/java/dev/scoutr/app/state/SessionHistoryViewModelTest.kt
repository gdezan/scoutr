package dev.scoutr.app.state

import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.CreatedSessionResponse
import dev.scoutr.app.data.SessionCatalogItem
import dev.scoutr.app.data.SessionCatalogResponse
import dev.scoutr.app.data.SessionCatalogStore
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
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

    private lateinit var fake: FakeScoutrApi
    private lateinit var store: RecordingSessionCatalogStore

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        store = RecordingSessionCatalogStore()
        stubCatalog()
    }

    private fun stubCatalog() {
        fake.sessionCatalogResult = Result.success(
            SessionCatalogResponse(
                ok = true,
                truncated = false,
                sessions = listOf(
                    dev.scoutr.app.data.catalogSessionFixture(
                        key = dev.scoutr.app.data.SessionKey("pi", "/root/sessions/abc.jsonl"),
                        title = "Fix billing bug",
                        cwd = "/repo/a",
                        model = "openai-codex/gpt-5.4",
                        updatedAtMs = System.currentTimeMillis().toDouble(),
                        latestActivity = "User asked to fix the billing math",
                        live = dev.scoutr.app.data.SessionLiveAttachment(
                            paneId = "pane1",
                            workspaceId = "ws1",
                            tabId = "t1",
                            status = "blocked",
                        ),
                    ),
                    dev.scoutr.app.data.catalogSessionFixture(
                        key = dev.scoutr.app.data.SessionKey("claude", "/root/sessions/def.jsonl"),
                        title = "Docs refresh",
                        cwd = "/repo/b",
                        model = "anthropic/claude-sonnet-4-6",
                        updatedAtMs = (System.currentTimeMillis() - 3_600_000).toDouble(),
                        latestActivity = "Update the README",
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
        store.pinned.add(dev.scoutr.app.data.SessionKey("pi", "/root/sessions/abc.jsonl"))
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
    fun resumeReturnsTheOriginalCanonicalKey() = runBlocking {
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val resumed = viewModel.resume(viewModel.ui.value.items[0])
        assertNotNull(resumed)
        assertEquals(dev.scoutr.app.data.SessionKey("pi", "/root/sessions/abc.jsonl"), resumed!!.key)
        assertNull(resumed.bootstrapPaneId)
        assertNull(viewModel.ui.value.busySessionKey)
    }

    @Test
    fun forkUsesFreshPaneBootstrapInsteadOfReusingTheOriginalKey() = runBlocking {
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val forked = viewModel.fork(viewModel.ui.value.items[0])

        assertNotNull(forked)
        assertNull(forked!!.key)
        assertEquals("pane9", forked.bootstrapPaneId)
        val action = fake.calls.last { it.name == "sessionCatalogAction" }
        assertEquals(CatalogAction.Fork, action.args["action"])
        assertEquals(dev.scoutr.app.data.SessionKey("pi", "/root/sessions/abc.jsonl"), action.args["key"])
    }

    @Test
    fun renameSendsTextAndSucceeds() = runBlocking {
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.rename(viewModel.ui.value.items[0], "New title")
        assertTrue(ok)
        assertNull(viewModel.ui.value.busySessionKey)
        val rename = fake.calls.last { it.name == "sessionCatalogAction" }
        assertEquals(CatalogAction.Rename, rename.args["action"])
        assertEquals("New title", rename.args["text"])
    }

    @Test
    fun deleteClearsLocalFlagsOnSuccess() = runBlocking {
        val key = dev.scoutr.app.data.SessionKey("claude", "/root/sessions/def.jsonl")
        store.pinned.add(key)
        store.archived.add(key)
        val viewModel = SessionHistoryViewModel(fake, savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.delete(viewModel.ui.value.items[1])
        assertTrue(ok)
        assertFalse(store.pinned.contains(key))
        assertFalse(store.archived.contains(key))
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
        // Polling is lifecycle-scoped: the screen starts the loop, so these
        // tests must too.
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
    val pinned = mutableSetOf<dev.scoutr.app.data.SessionKey>()
    val archived = mutableSetOf<dev.scoutr.app.data.SessionKey>()

    override fun pinnedKeys(catalogKeys: Collection<dev.scoutr.app.data.SessionKey>) = pinned.toSet()
    override fun archivedKeys(catalogKeys: Collection<dev.scoutr.app.data.SessionKey>) = archived.toSet()
    override fun setPinned(key: dev.scoutr.app.data.SessionKey, pinned: Boolean) {
        if (pinned) this.pinned.add(key) else this.pinned.remove(key)
    }

    override fun setArchived(key: dev.scoutr.app.data.SessionKey, archived: Boolean) {
        if (archived) this.archived.add(key) else this.archived.remove(key)
    }
}
