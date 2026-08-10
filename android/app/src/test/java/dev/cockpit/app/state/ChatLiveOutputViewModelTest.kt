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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatLiveOutputViewModelTest {
    private lateinit var server: MockWebServer
    private val outputReads = AtomicInteger()
    @Volatile private var outputFails = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val body = when (path) {
                    "/api/agents" -> """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","status":"working"}]}"""
                    "/api/agents/w1:p1/read" -> {
                        outputReads.incrementAndGet()
                        if (outputFails) return MockResponse().setResponseCode(503).setBody("offline")
                        """{"ok":true,"output":{"paneId":"w1:p1","text":"compile\nall tests passed","revision":9,"truncated":true,"lineLimit":80}}"""
                    }
                    else -> """{"ok":false,"error":"unexpected $path"}"""
                }
                return MockResponse().setHeader("content-type", "application/json").setBody(body)
            }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun refreshLoadsBoundedOutputAndPreservesItOnError() = runBlocking {
        val viewModel = viewModel()

        viewModel.refreshLiveOutput()

        assertEquals("compile\nall tests passed", viewModel.ui.value.liveOutputText)
        assertEquals(9, viewModel.ui.value.liveOutputRevision)
        assertTrue(viewModel.ui.value.liveOutputTruncated)

        outputFails = true
        viewModel.refreshLiveOutput()

        assertEquals("compile\nall tests passed", viewModel.ui.value.liveOutputText)
        assertNotNull(viewModel.ui.value.liveOutputError)
    }

    @Test
    fun collapsedSummarySkipsTerminalChrome() {
        val state = ChatUiState(
            agentStatus = "working",
            liveOutputText = "Useful verification result\nTook 0.1s\nElapsed 6.0s\n────────\n.: Working...\n~/repo │ anthropic/claude-sonnet │ high\n7d:39% Pursuing goal cache R/W 63M/0",
        )

        assertEquals("Useful verification result", state.liveOutputSummary)
    }

    @Test
    fun pollingStopsWhenPanelCollapses() = runBlocking {
        val viewModel = viewModel()
        viewModel.setLiveOutputExpanded(true)
        viewModel.startLiveOutputPolling()
        val deadline = System.currentTimeMillis() + 1_000
        while (outputReads.get() == 0 && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            delay(25)
        }
        assertTrue(outputReads.get() > 0)

        viewModel.setLiveOutputExpanded(false)
        delay(100)
        val stoppedAt = outputReads.get()
        delay(1_700)

        assertEquals(stoppedAt, outputReads.get())
    }

    private fun viewModel(): ChatViewModel {
        val store = ConnectionStore(RuntimeEnvironment.getApplication())
        store.save(server.url("/").toString().trimEnd('/'), "test-token", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        return ChatViewModel(bridge, "w1:p1", null, "working")
    }
}
