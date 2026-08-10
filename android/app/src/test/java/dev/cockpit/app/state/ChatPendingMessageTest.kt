package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.ContentBlock
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatPendingMessageTest {
    private lateinit var server: MockWebServer
    private val commands = CopyOnWriteArrayList<String>()
    @Volatile private var confirmMessage = false
    @Volatile private var failWebSocket = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return when (path) {
                    "/api/models" -> json("""{"ok":true,"catalog":{"providers":[]}}""")
                    "/api/agents" -> json("""{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","status":"working","sessionPath":"/tmp/session.jsonl"}]}""")
                    "/api/sessions" -> {
                        val entries = if (confirmMessage) {
                            """[{"entryId":"server-1","role":"user","content":[{"type":"text","text":"Fix it"}]}]"""
                        } else "[]"
                        json("""{"ok":true,"exists":true,"entries":$entries,"since":null}""")
                    }
                    "/ws" -> if (failWebSocket) {
                        MockResponse().setResponseCode(503)
                    } else {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                commands += text
                                confirmMessage = true
                                webSocket.send("""{"type":"ack"}""")
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(code, reason)
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendAppearsQueuedImmediatelyThenReconcilesWithTranscript() = runBlocking {
        val viewModel = viewModel()

        viewModel.send("Fix it")

        assertEquals("Fix it", viewModel.ui.value.pendingMessages.single().text)
        assertEquals(MessageDeliveryState.QUEUED, viewModel.ui.value.pendingMessages.single().state)
        waitUntil { viewModel.ui.value.pendingMessages.isEmpty() }
        assertTrue(commands.single().contains("\"text\":\"Fix it\""))
        assertEquals(listOf("server-1"), viewModel.ui.value.entries.map { it.entryId })
    }

    @Test
    fun failedSendStaysVisibleAndCanBeRetried() = runBlocking {
        failWebSocket = true
        val viewModel = viewModel()
        viewModel.send("Fix it")

        waitUntil { viewModel.ui.value.pendingMessages.singleOrNull()?.state == MessageDeliveryState.FAILED }
        val failed = viewModel.ui.value.pendingMessages.single()

        failWebSocket = false
        viewModel.retryPendingMessage(failed.localId)
        assertEquals(MessageDeliveryState.QUEUED, viewModel.ui.value.pendingMessages.single().state)
        waitUntil { viewModel.ui.value.pendingMessages.isEmpty() }
    }

    @Test
    fun duplicateTextDropsOneMessagePerEntry() {
        val pending = listOf(
            PendingUserMessage("local-1", "same", MessageDeliveryState.QUEUED),
            PendingUserMessage("local-2", "same", MessageDeliveryState.QUEUED),
        )
        val oneConfirmation = listOf(
            SessionEntry(
                entryId = "server-1",
                role = "user",
                content = listOf(ContentBlock(type = "text", text = "same")),
            ),
        )

        assertEquals(listOf("local-2"), dropConfirmedMessages(pending, oneConfirmation).map { it.localId })
    }

    @Test
    fun oldMatchingTextDoesNotDropNewMessage() {
        val message = PendingUserMessage(
            localId = "local-1",
            text = "same",
            state = MessageDeliveryState.QUEUED,
            baselineIds = setOf("server-old"),
        )
        val oldEntry = SessionEntry(
            entryId = "server-old",
            role = "user",
            content = listOf(ContentBlock(type = "text", text = "same")),
        )

        assertEquals(listOf(message), dropConfirmedMessages(listOf(message), listOf(oldEntry)))
    }

    private fun viewModel(): ChatViewModel {
        val store = ConnectionStore(RuntimeEnvironment.getApplication())
        store.save(server.url("/").toString().trimEnd('/'), "test-token")
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        return ChatViewModel(BridgeClient(client, store), "w1:p1", "/tmp/session.jsonl", "working")
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            delay(25)
        }
        error("condition did not become true")
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody(body)
}
