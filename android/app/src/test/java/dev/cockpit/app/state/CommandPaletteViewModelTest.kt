package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
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
class CommandPaletteViewModelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val agentsBody = """
        {"ok":true,"agents":[
          {"paneId":"pane1","workspaceId":"ws1","tabId":"t1","agent":"pi","status":"blocked","cwd":"/repo/a","title":"Fix billing bug","sessionPath":"/root/sessions/abc.jsonl","statusSinceMs":${System.currentTimeMillis() - 90_000}.0},
          {"paneId":"pane2","workspaceId":"ws2","tabId":"t2","agent":"pi","status":"working","cwd":"/repo/b","title":"Docs refresh","sessionPath":"/root/sessions/def.jsonl","statusSinceMs":${System.currentTimeMillis()}.0}
        ]}
    """.trimIndent()

    private val catalogBody = """
        {"ok":true,"truncated":false,"sessions":[
          {"path":"/root/sessions/abc.jsonl","sessionId":"abc","title":"Fix billing bug","cwd":"/repo/a","model":"openai-codex/gpt-5.4","updatedAt":${System.currentTimeMillis()}.0,"messageCount":12,"preview":"","active":true,"paneId":"pane1","workspaceId":"ws1","agentStatus":"blocked"},
          {"path":"/root/sessions/ghi.jsonl","sessionId":"ghi","title":"Old migration","cwd":"/repo/c","model":"anthropic/claude-sonnet-4-6","updatedAt":${System.currentTimeMillis() - 3_600_000}.0,"messageCount":3,"preview":"","active":false}
        ]}
    """.trimIndent()

    private fun stubApi() {
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/session-catalog/resume") ->
                    MockResponse().setResponseCode(201).setBody("""{"ok":true,"workspaceId":"ws9","paneId":"pane9"}""")
                request.path!!.contains("/control") ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                request.path!!.startsWith("/api/session-catalog") ->
                    MockResponse().setResponseCode(200).setBody(catalogBody)
                request.path!!.startsWith("/api/agents") ->
                    MockResponse().setResponseCode(200).setBody(agentsBody)
                else -> MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"not found"}""")
            }
        }
        server.dispatcher = dispatcher
    }

    @Test
    fun openWithoutQueryLoadsRunningAgents() = runBlocking {
        stubApi()
        val viewModel = CommandPaletteViewModel(bridge(), savedConnection())
        viewModel.open()
        viewModel.waitForSettled()

        val ui = viewModel.ui.value
        assertTrue(ui.open)
        assertEquals(2, ui.results.size)
        assertEquals(PaletteResultKind.Agent, ui.results[0].kind)
        assertEquals("Fix billing bug", ui.results[0].title)
    }

    @Test
    fun queryMergesAgentAndSessionResults() = runBlocking {
        stubApi()
        val viewModel = CommandPaletteViewModel(bridge(), savedConnection())
        viewModel.open()
        viewModel.waitForSettled()
        viewModel.setQuery("billing")
        viewModel.waitForSettled()

        val titles = viewModel.ui.value.results.map { it.title }
        assertTrue("expected the billing agent and session, saw $titles", titles.contains("Fix billing bug"))
        assertTrue(titles.contains("Old migration") || titles.contains("Fix billing bug"))
        assertEquals(2, titles.size)
    }

    @Test
    fun resumePostsToCatalogResume() = runBlocking {
        stubApi()
        val viewModel = CommandPaletteViewModel(bridge(), savedConnection())
        viewModel.resume("/root/sessions/ghi.jsonl")
        viewModel.waitForIdle()
        assertNull(viewModel.ui.value.busyPath)
        assertNull(viewModel.ui.value.error)
    }

    @Test
    fun controlPostsToPaneControl() = runBlocking {
        stubApi()
        val viewModel = CommandPaletteViewModel(bridge(), savedConnection())
        viewModel.open()
        viewModel.waitForSettled()
        viewModel.control("pane1", "abort")
        viewModel.waitForIdle()

        assertNull(viewModel.ui.value.busyPaneId)
        assertNull(viewModel.ui.value.error)
    }

    @Test
    fun openWithoutConnectionShowsError() = runBlocking {
        // bridge() re-saves the shared prefs, so clear only after it is built.
        val bridgeClient = bridge()
        val connection = ConnectionStore(RuntimeEnvironment.getApplication()).apply { clear() }
        val viewModel = CommandPaletteViewModel(bridgeClient, connection)
        viewModel.open()
        assertTrue(viewModel.ui.value.open)
        assertTrue(viewModel.ui.value.results.isEmpty())
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

    private fun CommandPaletteViewModel.waitForSettled() = runBlocking {
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (!ui.value.loading && (ui.value.results.isNotEmpty() || ui.value.error != null)) return@runBlocking
            delay(25)
        }
        assertTrue("Palette did not settle", !ui.value.loading)
    }

    private fun CommandPaletteViewModel.waitForIdle() = runBlocking {
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (ui.value.busyPath == null && ui.value.busyPaneId == null && ui.value.error == null) return@runBlocking
            delay(25)
        }
    }
}
