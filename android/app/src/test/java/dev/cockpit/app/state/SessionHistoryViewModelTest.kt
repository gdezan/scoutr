package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.SessionCatalogStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionHistoryViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var store: RecordingSessionCatalogStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = RecordingSessionCatalogStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val catalogBody = """
        {"ok":true,"truncated":false,"sessions":[
          {"path":"/root/sessions/abc.jsonl","sessionId":"abc","title":"Fix billing bug","cwd":"/repo/a","model":"openai-codex/gpt-5.4","updatedAt":${System.currentTimeMillis()}.0,"messageCount":12,"preview":"User asked to fix the billing math","active":true,"paneId":"pane1","workspaceId":"ws1","agentStatus":"blocked"},
          {"path":"/root/sessions/def.jsonl","sessionId":"def","title":"Docs refresh","cwd":"/repo/b","model":"anthropic/claude-sonnet-4-6","updatedAt":${System.currentTimeMillis() - 3_600_000}.0,"messageCount":3,"preview":"Update the README","active":false}
        ]}
    """.trimIndent()

    private fun stubCatalog() {
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/session-catalog/resume") ->
                    MockResponse().setResponseCode(201).setBody("""{"ok":true,"workspaceId":"ws9","paneId":"pane9"}""")
                request.path!!.startsWith("/api/session-catalog/fork") ->
                    MockResponse().setResponseCode(201).setBody("""{"ok":true,"workspaceId":"ws9","paneId":"pane9"}""")
                request.path!!.startsWith("/api/session-catalog/rename") ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                request.path!!.startsWith("/api/session-catalog/delete") ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                request.path!!.contains("/control") ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                request.path!!.startsWith("/api/session-catalog") ->
                    MockResponse().setResponseCode(200).setBody(catalogBody)
                else -> MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"not found"}""")
            }
        }
        server.dispatcher = dispatcher
    }

    @Test
    fun loadsCatalogWithPinAndArchiveFlags() = runBlocking {
        stubCatalog()
        store.pinned.add("/root/sessions/abc.jsonl")
        val connection = savedConnection()
        val viewModel = SessionHistoryViewModel(bridge(), connection, store)
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
        val queries = mutableListOf<String?>()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/api/session-catalog")) {
                    queries += request.requestUrl?.queryParameter("q")
                    return MockResponse().setResponseCode(200).setBody(catalogBody)
                }
                return MockResponse().setResponseCode(404).setBody("""{"ok":false}""")
            }
        }
        server.dispatcher = dispatcher
        val viewModel = SessionHistoryViewModel(bridge(), savedConnection(), store)
        viewModel.setQuery("billing")
        viewModel.waitForLoaded()

        val deadline = System.currentTimeMillis() + 2_000
        while ("billing" !in queries && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            delay(25)
        }
        assertTrue("expected a search with q=billing, saw $queries", "billing" in queries)
    }

    @Test
    fun resumeReturnsPaneToOpen() = runBlocking {
        stubCatalog()
        val viewModel = SessionHistoryViewModel(bridge(), savedConnection(), store)
        viewModel.waitForLoaded()

        val resumed = viewModel.resume(viewModel.ui.value.items[0])
        assertNotNull(resumed)
        assertEquals("pane9", resumed!!.paneId)
        assertNull(viewModel.ui.value.busyPath)
    }

    @Test
    fun renameSendsTextAndSucceeds() = runBlocking {
        stubCatalog()
        val viewModel = SessionHistoryViewModel(bridge(), savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.rename(viewModel.ui.value.items[0], "New title")
        assertTrue(ok)
        assertNull(viewModel.ui.value.busyPath)
    }

    @Test
    fun deleteClearsLocalFlagsOnSuccess() = runBlocking {
        stubCatalog()
        store.pinned.add("/root/sessions/def.jsonl")
        store.archived.add("/root/sessions/def.jsonl")
        val viewModel = SessionHistoryViewModel(bridge(), savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.delete(viewModel.ui.value.items[1])
        assertTrue(ok)
        assertFalse(store.pinned.contains("/root/sessions/def.jsonl"))
        assertFalse(store.archived.contains("/root/sessions/def.jsonl"))
    }

    @Test
    fun closeRoutesToPaneControl() = runBlocking {
        stubCatalog()
        val viewModel = SessionHistoryViewModel(bridge(), savedConnection(), store)
        viewModel.waitForLoaded()

        val ok = viewModel.close(viewModel.ui.value.items[0])
        assertTrue(ok)
    }

    @Test
    fun bridgeErrorSurfacesErrorState() = runBlocking {
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503).setBody("""{"ok":false,"error":"no herdr snapshot yet"}""")
        }
        server.dispatcher = dispatcher
        val viewModel = SessionHistoryViewModel(bridge(), savedConnection(), store)
        viewModel.waitForLoaded()

        assertFalse(viewModel.ui.value.connected)
        assertNotNull(viewModel.ui.value.error)
    }

    private fun savedConnection(): ConnectionStore =
        ConnectionStore(RuntimeEnvironment.getApplication()).apply {
            save(server.url("/").toString().trimEnd('/'), "test-token", null, null)
        }

    private fun bridge(): BridgeClient {
        val connection = ConnectionStore(RuntimeEnvironment.getApplication())
        connection.save(server.url("/").toString().trimEnd('/'), "test-token", null, null)
        return BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connection)
    }

    private fun SessionHistoryViewModel.waitForLoaded() = runBlocking {
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
