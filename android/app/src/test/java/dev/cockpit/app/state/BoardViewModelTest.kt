package dev.cockpit.app.state

import android.content.Context
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import android.os.Looper

/** Board swipe-bar Close: posts the control action and surfaces failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: BoardViewModel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = RuntimeEnvironment.getApplication()
        val connectionStore = ConnectionStore(app)
        // Unsaved at construction: the VM init never connects, so no health
        // probe and no poll loop interfere with the control POST below.
        connectionStore.clear()
        viewModel = BoardViewModel(
            bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connectionStore),
            connectionStore = connectionStore,
            initialState = BoardUiState(connected = true),
        )
        connectionStore.save(server.url("/").toString().trimEnd('/'), "test-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun closeAgentPostsControlActionAndStaysQuiet() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
        runBlocking { viewModel.closeAgent("p1") }
        val control = awaitControlRequest()
        // The response resumption is posted to the (paused) main looper;
        // idle it so the coroutine actually processes the response.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("POST", control.method)
        assertEquals("/api/sessions/p1/control", control.path)
        assertTrue(control.body.readUtf8().contains("\"close\""))
        assertTrue("no error on success", viewModel.ui.value.error == null)
    }

    /** Drains queued requests until the control POST arrives (or times out). */
    private fun awaitControlRequest(): okhttp3.mockwebserver.RecordedRequest {
        val deadline = System.currentTimeMillis() + 5_000
        var consumed = 0
        while (System.currentTimeMillis() < deadline) {
            while (server.requestCount > consumed) {
                val request = server.takeRequest()
                consumed++
                if (request.path?.startsWith("/api/sessions/") == true && request.method == "POST") {
                    return request
                }
            }
            Thread.sleep(20)
        }
        throw AssertionError("control POST never arrived (requests seen: $consumed)")
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun closeAgentSurfacesBridgeError() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":false,"error":"pane not found"}""")
        )
        runBlocking { viewModel.closeAgent("p1") }
        awaitControlRequest()
        // The 500 resumption is posted to the (paused) main looper; idle it so
        // closeAgent's catch + reportError actually run.
        shadowOf(Looper.getMainLooper()).idle()
        waitUntil { viewModel.ui.value.error?.contains("pane not found") == true }
        assertTrue(
            "error surfaced, was: ${viewModel.ui.value.error}",
            viewModel.ui.value.error?.contains("pane not found") == true,
        )
    }

}
