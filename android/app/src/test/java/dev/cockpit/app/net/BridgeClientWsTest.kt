package dev.cockpit.app.net

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import dev.cockpit.app.data.ConnectionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real-transport contract tests for the one-command-per-socket WS surface
 * (steer / runSlashCommand / sendCommandJson / answerQuestion). The HTTP side
 * is covered by BridgeClientUploadTest; the view-model behavior above this
 * surface is covered by FakeCockpitApi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeClientWsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cockpit_connection", Context.MODE_PRIVATE).edit()
            .putString("host", server.url("/").toString().trimEnd('/'))
            .putString("token", "test-token")
            .apply()
        client = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            ConnectionStore(app))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Server side: captures the one command frame, replies `body`, then closes. */
    private fun echoServer(replies: List<String>): Pair<CompletableDeferred<String>, WebSocketListener> {
        val captured = CompletableDeferred<String>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!captured.isCompleted) captured.complete(text)
                replies.forEach { webSocket.send(it) }
                webSocket.close(1000, "test done")
            }
        }
        return captured to listener
    }

    @Test
    fun steer_sendsCommandFrameAndReturnsAck() {
        val (captured, listener) = echoServer(listOf("""{"type":"ack"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        val frame = runBlocking { client.steer("w1:p1", "fix it") }

        assertEquals("ack", frame.type)
        val upgrade = server.takeRequest()
        assertEquals("/ws?token=test-token", upgrade.path)
        val command = runBlocking { Json.parseToJsonElement(captured.await()).jsonObject }
        assertEquals("steer", command["type"]!!.jsonPrimitive.content)
        assertEquals("w1:p1", command["target"]!!.jsonPrimitive.content)
        assertEquals("fix it", command["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun runSlashCommand_and_answerQuestion_areSingleCommandFrames() {
        val (slash, slashListener) = echoServer(listOf("""{"type":"ack"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(slashListener))
        runBlocking { client.runSlashCommand("w1:p1", "/compact") }
        val slashCommand = runBlocking { Json.parseToJsonElement(slash.await()).jsonObject }
        assertEquals("slash_command", slashCommand["type"]!!.jsonPrimitive.content)
        assertEquals("w1:p1", slashCommand["paneId"]!!.jsonPrimitive.content)
        assertEquals("/compact", slashCommand["text"]!!.jsonPrimitive.content)

        val (answer, answerListener) = echoServer(listOf("""{"type":"ack"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(answerListener))
        runBlocking { client.answerQuestion("w1:p1", "yes", keys = listOf("down", "\u000d"), trailingKeys = listOf("\u001b")) }
        val answerCommand = runBlocking { Json.parseToJsonElement(answer.await()).jsonObject }
        assertEquals("answer_question", answerCommand["type"]!!.jsonPrimitive.content)
        assertEquals("yes", answerCommand["text"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("down", "\u000d"),
            answerCommand["keys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("\u001b"),
            answerCommand["trailingKeys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        // Empty key lists are omitted from the wire frame rather than sent as [].
        val (omitted, omittedListener) = echoServer(listOf("""{"type":"ack"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(omittedListener))
        runBlocking { client.answerQuestion("w1:p1", "", keys = emptyList(), trailingKeys = emptyList()) }
        val omittedCommand = runBlocking { Json.parseToJsonElement(omitted.await()).jsonObject }
        assertEquals(null, omittedCommand["keys"])
        assertEquals(null, omittedCommand["trailingKeys"])
    }

    @Test
    fun errorFrame_surfacesAsIOException() {
        val (_, listener) = echoServer(listOf("""{"type":"error","error":"boom"}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        try {
            runBlocking { client.sendCommand(mapOf("type" to "steer", "target" to "w1:p1", "text" to "x")) }
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("boom"))
        }
    }

    @Test
    fun feedFrames_areSkippedUntilAck() {
        val captured = CompletableDeferred<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!captured.isCompleted) captured.complete(text)
                    webSocket.send("""{"type":"feed","payload":{"event":"working"}}""")
                    webSocket.send("""{"type":"ack"}""")
                    webSocket.close(1000, "test done")
                }
            }),
        )
        val frame = runBlocking { client.sendCommand(mapOf("type" to "steer", "target" to "w1:p1", "text" to "x")) }
        assertEquals("ack", frame.type)
    }
}