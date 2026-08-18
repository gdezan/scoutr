package dev.scoutr.app.net

import android.content.Context
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wire contract for the one-shot session commands (`commands.http.v1`): each
 * typed method is one authenticated POST to its own route with a typed JSON
 * body, non-2xx becomes BridgeException(status, bridge reason), and no command
 * opens a WebSocket. The behaviour above this surface is covered with
 * FakeScoutrApi; the bridge side by session-commands-http.test.ts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeClientCommandTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("scoutr_connection", Context.MODE_PRIVATE).edit()
            .putString("host", server.url("/").toString().trimEnd('/'))
            .putString("token", "test-token")
            .apply()
        client = BridgeClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            ConnectionStore(app, FakeConnectionCipher()),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueOk() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"paneId":"w1:p1"}"""))
    }

    private fun body(request: RecordedRequest) = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun steer_postsTextToTheSteerRoute() {
        enqueueOk()
        val response = runBlocking { client.steer("w1:p1", "fix it") }

        assertTrue(response.ok)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        // The pane id is percent-encoded; the bridge decodes it back to "w1:p1".
        assertEquals("/api/sessions/w1%3Ap1/steer", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("fix it", body(request)["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun runSlashCommand_postsToTheSlashCommandRoute() {
        enqueueOk()
        runBlocking { client.runSlashCommand("w1:p1", "/compact") }

        val request = server.takeRequest()
        assertEquals("/api/sessions/w1%3Ap1/slash-command", request.path)
        assertEquals("/compact", body(request)["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun answerAsk_postsTheRoundToTheAskItAnswers() {
        enqueueOk()
        runBlocking {
            client.answerAsk(
                "w1:p1",
                callId = "toolu_1",
                answers = listOf(
                    AskAnswer("toolu_1#0", selectedLabels = listOf("Yes", "No")),
                    AskAnswer("toolu_1#1", text = "something else"),
                ),
            )
        }

        val request = server.takeRequest()
        assertEquals("/api/sessions/w1%3Ap1/asks/toolu_1/answer", request.path)
        val answers = body(request)["answers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("toolu_1#0", "toolu_1#1"), answers.map { it["questionId"]!!.jsonPrimitive.content })
        assertEquals(listOf("Yes", "No"), answers[0]["selectedLabels"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Empty lists are omitted rather than sent as [].
        assertNull(answers[1]["selectedLabels"])
        assertEquals("something else", answers[1]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun answerAsk_withoutACallId_usesTheAskLessRoute() {
        enqueueOk()
        runBlocking { client.answerAsk("w1:p1", text = "yes") }

        val request = server.takeRequest()
        assertEquals("/api/sessions/w1%3Ap1/asks/answer", request.path)
        val sent = body(request)
        assertEquals("yes", sent["text"]!!.jsonPrimitive.content)
        assertNull(sent["answers"])
        assertNull(sent["callId"])
    }

    @Test
    fun dismissAsk_postsToTheDismissRoute() {
        enqueueOk()
        runBlocking { client.dismissAsk("w1:p1") }

        val request = server.takeRequest()
        assertEquals("/api/sessions/w1%3Ap1/asks/dismiss", request.path)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun rejectedCommand_surfacesTheBridgeStatusAndReason() {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"ok":false,"error":"no open ask toolu_1 in this session"}"""),
        )
        try {
            runBlocking { client.answerAsk("w1:p1", callId = "toolu_1", answers = listOf(AskAnswer("toolu_1#0", text = "Yes"))) }
            fail("expected BridgeException")
        } catch (expected: BridgeException) {
            assertEquals(409, expected.status)
            assertTrue(expected.message!!.contains("no open ask toolu_1"))
        }

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"ok":false,"error":"invalid slash command text"}"""))
        try {
            runBlocking { client.runSlashCommand("w1:p1", "nope") }
            fail("expected BridgeException")
        } catch (expected: BridgeException) {
            assertEquals(400, expected.status)
            assertTrue(expected.message!!.contains("invalid slash command"))
        }
    }

    @Test
    fun cancellingTheCaller_cancelsTheRequest() {
        val arrived = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.complete(Unit)
                // Never answered: only cancellation can end the call.
                return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
            }
        }

        runBlocking {
            val steering = async(Dispatchers.IO) { client.steer("w1:p1", "fix it") }
            arrived.await()
            steering.cancel()
            try {
                steering.await()
                fail("expected the steer to be cancelled")
            } catch (expected: CancellationException) {
                // The OkHttp call is cancelled with the coroutine; nothing leaks.
            }
        }
    }
}
