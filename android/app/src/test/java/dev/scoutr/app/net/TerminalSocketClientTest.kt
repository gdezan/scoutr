package dev.scoutr.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Real-socket contract tests for /ws/terminal: auth header (never a query
 * token), hello-first ordering, input/resize gating on writable state,
 * binary vs text classification, release, and end-of-stream semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalSocketClientTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: TerminalSocketClient
    private val listener = RecordingListener()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = TerminalSocketClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Server side: captures hello (text) and binary input, then optionally sends frames. */
    private class ServerSide : WebSocketListener() {
        val hello = CompletableFuture<String>()
        val binaryInput = CompletableFuture<ByteString>()
        val resizes = CompletableFuture<String>()
        val releases = CompletableFuture<String>()
        var webSocket: WebSocket? = null
        var onHello: (WebSocket) -> Unit = {}
        var sentReady = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this.webSocket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when {
                !hello.isDone -> {
                    hello.complete(text)
                    onHello(webSocket)
                }
                text.contains("\"resize\"") && !resizes.isDone -> resizes.complete(text)
                text.contains("\"release\"") && !releases.isDone -> releases.complete(text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            binaryInput.complete(bytes)
        }

        fun sendReady(generation: Long = 1, mode: String = "control") {
            webSocket!!.send(
                """{"type":"ready","version":1,"generation":$generation,"paneId":"w1:p1","mode":"$mode","cols":80,"rows":24,"reset":true}""",
            )
        }

        fun sendBinary(bytes: ByteArray) = webSocket!!.send(ByteString.of(*bytes))
    }

    private fun openSocket(serverSide: ServerSide): TerminalSocket {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
        return transport.open(
            TerminalOpenRequest(
                host = server.url("/").toString().trimEnd('/'),
                token = "test-token",
                paneId = "w1:p1",
                cols = 80,
                rows = 24,
                intent = TerminalIntent.AUTO,
            ),
            listener,
        )
    }

    private class RecordingListener : TerminalTransportListener {
        val ready = CompletableFuture<TerminalServerMessage.Ready>()
        val ownership = CompletableFuture<TerminalServerMessage.Ownership>()
        val closed = CompletableFuture<TerminalServerMessage.Closed>()
        val error = CompletableFuture<TerminalServerMessage.Error>()
        val bytes = CompletableFuture<ByteArray>()
        val failure = CompletableFuture<IOException>()

        override fun onReady(message: TerminalServerMessage.Ready) { ready.complete(message) }
        override fun onOwnership(message: TerminalServerMessage.Ownership) { ownership.complete(message) }
        override fun onClosed(message: TerminalServerMessage.Closed) { closed.complete(message) }
        override fun onError(message: TerminalServerMessage.Error) { error.complete(message) }
        override fun onBytes(bytes: ByteArray) { this.bytes.complete(bytes) }
        override fun onFailure(error: IOException) { failure.complete(error) }

        fun awaitReady(): TerminalServerMessage.Ready = ready.get(5, TimeUnit.SECONDS)
    }

    private fun await(): Nothing = throw AssertionError("unreachable")

    @Test
    fun a_refused_upgrade_reports_the_bridge_verdict_not_a_bare_socket_failure() {
        // server.ts rejectUpgrade: a settled `unsupported` capability answers
        // the upgrade with a 503 and the reason, never a 101. Reported as a
        // plain IOException it looks like an abrupt EOF, and the route
        // reconnects forever against a bridge that already refused.
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":"observer handshake failed",
                       "terminal":{"capability":{"status":"unsupported","installedVersion":"0.8.0",
                       "required":"0.8.0 or newer","reason":"observer handshake failed"}}}""",
                ),
        )
        transport.open(
            TerminalOpenRequest(
                host = server.url("/").toString().removeSuffix("/"),
                token = "test-token",
                paneId = "w1:p1",
                cols = 80,
                rows = 24,
                intent = TerminalIntent.AUTO,
            ),
            listener,
        )
        val error = listener.error.get(5, TimeUnit.SECONDS)
        assertEquals(TerminalProtocol.ERROR_UNSUPPORTED, error.code)
        assertEquals("observer handshake failed", error.message)
        assertFalse(error.retryable)
        assertFalse("a rejected upgrade is a verdict, not a transport failure", listener.failure.isDone)
    }

    @Test
    fun upgrade_uses_bearer_header_and_never_query_token() {
        val serverSide = ServerSide()
        openSocket(serverSide)
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/ws/terminal", upgrade!!.path)
        assertEquals("Bearer test-token", upgrade.getHeader("Authorization"))
        assertNull("query token must never be sent", upgrade.requestUrl!!.queryParameter("token"))
        // paneId travels in the hello frame, never in the URL.
    }

    @Test
    fun hello_is_the_first_frame_with_intent_and_grid() {
        val serverSide = ServerSide()
        openSocket(serverSide)
        val hello = runBlocking {
            val text = serverSide.hello.get(5, TimeUnit.SECONDS)
            protocolJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        }
        assertEquals("hello", hello["type"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("w1:p1", hello["paneId"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("auto", hello["intent"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(80, hello["cols"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }.toInt())
        assertEquals(1, hello["version"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }.toInt())
    }

    @Test
    fun input_is_rejected_before_ready_and_flows_after() {
        val serverSide = ServerSide()
        val socket = openSocket(serverSide)
        runBlocking { serverSide.hello.get(5, TimeUnit.SECONDS) }

        // Not writable until ready.
        assertFalse(socket.sendInput(byteArrayOf(1, 2, 3)))

        serverSide.sendReady()
        listener.awaitReady()

        assertTrue(socket.sendInput("abc".toByteArray()))
        val sent = runBlocking { serverSide.binaryInput.get(5, TimeUnit.SECONDS) }
        assertEquals("abc", sent.utf8())
    }

    @Test
    fun binary_frames_arrive_as_bytes() {
        val serverSide = ServerSide()
        val socket = openSocket(serverSide)
        runBlocking { serverSide.hello.get(5, TimeUnit.SECONDS) }
        serverSide.sendReady()
        listener.awaitReady()
        serverSide.sendBinary(byteArrayOf(0x1b, 0x5b, 0x33, 0x31, 0x6d))
        val bytes = runBlocking { listener.bytes.get(5, TimeUnit.SECONDS) }
        assertEquals(5, bytes.size)
        assertEquals(0x1b.toByte(), bytes[0])
    }

    @Test
    fun resize_and_release_are_control_frames() {
        val serverSide = ServerSide()
        val socket = openSocket(serverSide)
        runBlocking { serverSide.hello.get(5, TimeUnit.SECONDS) }
        serverSide.sendReady()
        listener.awaitReady()

        assertTrue(socket.resize(100, 40))
        val resize = runBlocking { serverSide.resizes.get(5, TimeUnit.SECONDS) }
        assertTrue(resize.contains("\"cols\":100"))

        socket.release()
        val release = runBlocking { serverSide.releases.get(5, TimeUnit.SECONDS) }
        assertTrue(release.contains("\"type\":\"release\""))
        // The socket is ended locally; nothing more may be sent.
        assertFalse(socket.sendInput(byteArrayOf(1)))
    }

    @Test
    fun observe_mode_gates_input_and_resize() {
        val serverSide = ServerSide()
        val socket = openSocket(serverSide)
        runBlocking { serverSide.hello.get(5, TimeUnit.SECONDS) }
        serverSide.sendReady(mode = "observe")
        listener.awaitReady()

        assertFalse(socket.sendInput(byteArrayOf(1)))
        assertFalse(socket.resize(80, 24))

        // Ownership announces takeover possibility.
        serverSide.webSocket!!.send(
            """{"type":"ownership","generation":1,"mode":"observe","canTakeover":true}""",
        )
        assertTrue(runBlocking { listener.ownership.get(5, TimeUnit.SECONDS) }.canTakeover)
    }

    @Test
    fun malformed_server_frame_reports_protocol_error_and_ends_socket() {
        val serverSide = ServerSide()
        val socket = openSocket(serverSide)
        runBlocking { serverSide.hello.get(5, TimeUnit.SECONDS) }
        serverSide.webSocket!!.send("this is not json")
        val error = runBlocking { listener.error.get(5, TimeUnit.SECONDS) }
        assertEquals(TerminalProtocol.ERROR_PROTOCOL, error.code)
        assertFalse(error.retryable)
        assertFalse(socket.sendInput(byteArrayOf(1)))
    }

    @Test
    fun closed_frame_suppresses_failure_on_server_close() {
        val serverSide = ServerSide()
        val socket = openSocket(serverSide)
        runBlocking { serverSide.hello.get(5, TimeUnit.SECONDS) }
        serverSide.webSocket!!.send("""{"type":"closed","generation":1,"reason":"released"}""")
        val closed = runBlocking { listener.closed.get(5, TimeUnit.SECONDS) }
        assertEquals(TerminalProtocol.CLOSED_RELEASED, closed.reason)
        // No onFailure should surface (server close after a closed frame is expected).
        Thread.sleep(200)
        assertFalse(listener.failure.isDone)
    }

    @Test
    fun close_without_frame_is_an_abrupt_failure() {
        val serverSide = ServerSide()
        openSocket(serverSide)
        runBlocking { serverSide.hello.get(5, TimeUnit.SECONDS) }
        serverSide.webSocket!!.close(1001, "going away")
        val failure = runBlocking { listener.failure.get(5, TimeUnit.SECONDS) }
        assertTrue(failure.message!!.contains("1001"))
    }

    @Test
    fun wsUrl_maps_http_and_https() {
        assertEquals("ws://host:8080/ws/terminal", TerminalSocketClient.wsUrl("http://host:8080") + "/ws/terminal")
        assertEquals("wss://host/ws/terminal", TerminalSocketClient.wsUrl("https://host/") + "/ws/terminal")
        assertEquals("ws://host/ws/terminal", TerminalSocketClient.wsUrl("host") + "/ws/terminal")
    }
}
