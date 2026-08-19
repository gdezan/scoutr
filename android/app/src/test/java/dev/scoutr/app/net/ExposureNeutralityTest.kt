package dev.scoutr.app.net

import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.FakeConnectionCipher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Exposure is metadata, not transport. Whatever fronts the bridge, every
 * client must put the same method, path, and Authorization header on the wire,
 * derived only from the saved base URL and token — this is the test that fails
 * if someone ever teaches a client to branch on the provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExposureNeutralityTest {

    private lateinit var server: MockWebServer
    private lateinit var store: ConnectionStore
    private val okHttp = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher())
        store.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun everyClientBuildsTheSameRequestForEveryExposureKind() {
        val traffic = ExposureKind.entries.associateWith { kind ->
            store.save(
                host = server.url("/").toString().trimEnd('/'),
                token = "test-token",
                exposure = kind,
            )
            assertEquals(kind, store.saved?.exposure)
            listOf(httpGet(), httpPost(), topologySocket(), terminalSocket())
        }

        val tailscale = traffic.getValue(ExposureKind.Tailscale)
        for ((kind, requests) in traffic) {
            assertEquals("$kind must produce the tailscale wire traffic", tailscale, requests)
        }
        // Guard against the comparison passing on empty/degenerate captures.
        assertEquals(
            listOf(
                "GET /api/health Bearer test-token",
                "POST /api/sessions/w1%3Ap1/steer Bearer test-token",
                "GET /ws Bearer test-token",
                "GET /ws/terminal Bearer test-token",
            ),
            tailscale,
        )
    }

    private fun bridge() = BridgeClient(okHttp, store)

    private fun httpGet(): String {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        runBlocking { bridge().health(null, null) }
        return describe(takeRequest())
    }

    private fun httpPost(): String {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"paneId":"w1:p1"}"""))
        runBlocking { bridge().steer("w1:p1", "go") }
        return describe(takeRequest())
    }

    private fun topologySocket(): String {
        server.enqueue(MockResponse().withWebSocketUpgrade(SilentServer()))
        val feed = TopologyFeedClient(okHttp, store, NoopFeedListener(), backoffBaseMs = 50L)
        feed.start()
        val request = takeRequest()
        feed.stop()
        return describe(request)
    }

    private fun terminalSocket(): String {
        server.enqueue(MockResponse().withWebSocketUpgrade(SilentServer()))
        val saved = requireNotNull(store.saved)
        val socket = TerminalSocketClient(okHttp).open(
            TerminalOpenRequest(
                host = saved.host,
                token = saved.token,
                paneId = "w1:p1",
                cols = 80,
                rows = 24,
                intent = TerminalIntent.AUTO,
            ),
            NoopTerminalListener(),
        )
        val request = takeRequest()
        socket.cancel()
        return describe(request)
    }

    private fun takeRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request reached the bridge" }

    /** Everything the exposure kind must not be able to change. */
    private fun describe(request: RecordedRequest): String =
        "${request.method} ${request.path} ${request.getHeader("Authorization")}"

    private class SilentServer : WebSocketListener()

    private class NoopFeedListener : TopologyFeed.Listener {
        override fun onTopologyEvent(kind: String) = Unit
        override fun onSnapshot() = Unit
        override fun onFeedFailure(error: IOException) = Unit
    }

    private class NoopTerminalListener : TerminalTransportListener {
        override fun onReady(message: TerminalServerMessage.Ready) = Unit
        override fun onOwnership(message: TerminalServerMessage.Ownership) = Unit
        override fun onClosed(message: TerminalServerMessage.Closed) = Unit
        override fun onError(message: TerminalServerMessage.Error) = Unit
        override fun onBytes(bytes: ByteArray) = Unit
        override fun onFailure(error: IOException) = Unit
    }
}
