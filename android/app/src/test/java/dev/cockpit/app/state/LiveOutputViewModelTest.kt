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
class LiveOutputViewModelTest {
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

        viewModel.refresh()

        assertEquals("compile\nall tests passed", viewModel.ui.value.text)
        assertEquals(9, viewModel.ui.value.revision)
        assertTrue(viewModel.ui.value.truncated)

        outputFails = true
        viewModel.refresh()

        // A failed poll must not blank the last snapshot; the screen shows the
        // frozen tail under a STALE marker instead of an empty panel.
        assertEquals("compile\nall tests passed", viewModel.ui.value.text)
        assertNotNull(viewModel.ui.value.error)
    }

    @Test
    fun renderedLinesSkipTerminalChrome() {
        val state = LiveOutputUiState(
            text = "Useful verification result\nTook 0.1s\nElapsed 6.0s\n────────\n.: Working...\n~/repo │ anthropic/claude-sonnet │ high\n7d:39% Pursuing goal cache R/W 63M/0\n~/Dev/agents-mobile (main) │ 101k/1.0M ↑576.",
        )

        assertEquals(listOf("Useful verification result"), state.lines)
    }

    @Test
    fun pollingRunsWhileStartedAndStopsOnStop() = runBlocking {
        val viewModel = viewModel()
        viewModel.startPolling()
        var deadline = System.currentTimeMillis() + 1_000
        while (outputReads.get() == 0 && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            delay(25)
        }
        assertTrue("polling starts with the screen", outputReads.get() > 0)

        // Keeps ticking while the screen is open…
        val atStart = outputReads.get()
        deadline = System.currentTimeMillis() + 3_000
        while (outputReads.get() <= atStart && System.currentTimeMillis() < deadline) {
            // Advance the PAUSED main looper's clock past the poll delay so
            // the next 900ms tick actually fires.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(900, TimeUnit.MILLISECONDS)
            delay(25)
        }
        assertTrue("polling repeats while the screen is open", outputReads.get() > atStart)

        // …and stops dead when the screen goes away. Zero ambient cost is the
        // whole point of moving the poll off the chat screen.
        viewModel.stopPolling()
        delay(100)
        val atStop = outputReads.get()
        repeat(4) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(900, TimeUnit.MILLISECONDS)
            delay(25)
        }
        assertEquals("no reads after the screen closes", atStop, outputReads.get())
    }

    private fun viewModel(): LiveOutputViewModel {
        val store = ConnectionStore(RuntimeEnvironment.getApplication())
        store.save(server.url("/").toString().trimEnd('/'), "test-token", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        return LiveOutputViewModel(bridge, "w1:p1")
    }
}
