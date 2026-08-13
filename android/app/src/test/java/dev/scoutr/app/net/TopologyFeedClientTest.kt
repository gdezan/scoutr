package dev.scoutr.app.net

import android.content.Context
import dev.scoutr.app.data.ConnectionStore
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Route-scoped topology feed: subscribe filter, kind classification,
 * snapshot resync, rejected-upgrade typing, and backoff reconnect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TopologyFeedClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TopologyFeedClient
    private val events = CompletableFuture<String>()
    private val snapshots = CompletableFuture<Boolean>()
    private val failures = CompletableFuture<IOException>()
    private val listener = object : TopologyFeed.Listener {
        override fun onTopologyEvent(kind: String) { events.complete(kind) }
        override fun onSnapshot() { snapshots.complete(true) }
        override fun onFeedFailure(error: IOException) { failures.complete(error) }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("scoutr_connection", Context.MODE_PRIVATE).edit()
            .putString("host", server.url("/").toString().trimEnd('/'))
            .putString("token", "test-token")
            .apply()
        client = TopologyFeedClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            ConnectionStore(app),
            listener,
            backoffBaseMs = 50L,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun subscribe_filter_uses_bearer_and_topology_kinds() {
        val serverSide = FeedServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        assertTrue(client.start())

        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/ws", upgrade!!.path)
        assertEquals("Bearer test-token", upgrade.getHeader("Authorization"))
        assertNull(upgrade.requestUrl!!.queryParameter("token"))

        val subscribe = serverSide.subscribe.get(5, TimeUnit.SECONDS)
        assertTrue(subscribe.contains("\"subscribe\""))
        assertTrue(subscribe.contains("pane.updated"))
        assertTrue(subscribe.contains("pane_updated"))
        assertTrue(subscribe.contains("workspace.created"))
        assertTrue(subscribe.contains("layout.updated"))
    }

    @Test
    fun topology_events_and_snapshot_resync_are_forwarded() {
        val serverSide = FeedServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        client.start()
        server.takeRequest(5, TimeUnit.SECONDS)
        serverSide.opened.get(5, TimeUnit.SECONDS)
        serverSide.webSocket!!.send("""{"type":"feed","payload":{"kind":"pane.updated","data":{}}}""")
        serverSide.webSocket!!.send("""{"type":"feed","payload":{"kind":"pane.created","data":{}}}""")
        serverSide.webSocket!!.send("""{"type":"feed","payload":{"type":"snapshot","snapshot":{}}}""")

        assertEquals("pane.updated", events.get(5, TimeUnit.SECONDS))
        assertTrue(snapshots.get(5, TimeUnit.SECONDS))
        // Only the first event slot is captured; both arrived on the wire.
    }

    @Test
    fun feed_error_and_non_feed_frames_are_ignored() {
        val serverSide = FeedServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        client.start()
        server.takeRequest(5, TimeUnit.SECONDS)
        serverSide.opened.get(5, TimeUnit.SECONDS)
        serverSide.webSocket!!.send("""{"type":"feed","payload":{"kind":"feed_error","error":"nope"}}""")
        serverSide.webSocket!!.send("""{"type":"ack"}""")
        serverSide.webSocket!!.send("""{"payload":{"kind":"pane.updated"}}""")
        Thread.sleep(200)
        assertTrue(!events.isDone && !snapshots.isDone)
    }

    @Test
    fun rejected_upgrade_surfaces_typed_failure_without_retry() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        client.start()
        val failure = failures.get(5, TimeUnit.SECONDS)
        assertTrue(failure.message!!.contains("401"))
        // No reconnect: only one request ever hit the server.
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun abrupt_close_reconnects_and_resyncs() {
        val first = FeedServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(first))
        val second = FeedServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(second))
        client.start()
        server.takeRequest(5, TimeUnit.SECONDS)
        first.opened.get(5, TimeUnit.SECONDS)
        first.webSocket!!.close(1001, "gone")

        // Backoff (50 ms base) re-opens the socket; the resync snapshot flows.
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/ws", upgrade!!.path)
        second.subscribe.get(5, TimeUnit.SECONDS)
        second.opened.get(5, TimeUnit.SECONDS)
        second.webSocket!!.send("""{"type":"feed","payload":{"type":"snapshot","snapshot":{}}}""")
        assertTrue(snapshots.get(5, TimeUnit.SECONDS))
    }

    private class FeedServer : WebSocketListener() {
        val subscribe = CompletableFuture<String>()
        val opened = CompletableFuture<Boolean>()
        var webSocket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket = webSocket
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!subscribe.isDone && text.contains("\"subscribe\"")) subscribe.complete(text)
        }
    }
}
