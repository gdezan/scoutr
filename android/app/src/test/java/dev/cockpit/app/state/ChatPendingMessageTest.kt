package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.ContentBlock
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.entryText
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
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
    private val commandCatalogCwds = CopyOnWriteArrayList<String>()
    @Volatile private var confirmMessage = false
    @Volatile private var failWebSocket = false
    @Volatile private var agentStatus = "working"
    @Volatile private var agentCwd: String? = "/repo"
    @Volatile private var agentPresent = true
    @Volatile private var answerRecorded = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return when (path) {
                    "/api/models" -> json("""{"ok":true,"catalog":{"providers":[]}}""")
                    "/api/commands" -> {
                        val cwd = request.requestUrl?.queryParameter("cwd") ?: "<global>"
                        commandCatalogCwds += cwd
                        if (cwd == "/slow") Thread.sleep(200)
                        val name = when (cwd) {
                            "/slow" -> "slow-command"
                            "/fast" -> "fast-command"
                            else -> "compact"
                        }
                        json("""{"ok":true,"catalog":{"commands":[{"name":"$name","description":"Command","source":"builtin","argumentHint":null}]}}""")
                    }
                    "/api/agents" -> {
                        if (!agentPresent) {
                            json("""{"ok":true,"agents":[]}""")
                        } else {
                            val cwd = agentCwd?.let { "\"$it\"" } ?: "null"
                            json("""{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","status":"$agentStatus","cwd":$cwd,"sessionPath":"/tmp/session.jsonl"}]}""")
                        }
                    }
                    "/api/sessions" -> {
                        val entries = if (confirmMessage) {
                            """[{"entryId":"server-1","role":"user","content":[{"type":"text","text":"Fix it"}]}]"""
                        } else "[]"
                        // pi records an ask_user_question answer only in the
                        // toolResult's details — never as a user entry — so
                        // the question flips to answered with no new entry.
                        val questions = """[{"id":"q1","question":"Proceed?","header":"Confirm","options":[],"multiSelect":false,"answered":$answerRecorded,"answerText":"Proceed","selected":[],"timestamp":"2026-08-10T10:00:00.000Z"}]"""
                        json("""{"ok":true,"exists":true,"entries":$entries,"since":null,"questions":$questions}""")
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
    fun blockedSessionRoutesSlashCommandsBeforeQuestionAnswers() = runBlocking {
        agentStatus = "blocked"
        val viewModel = viewModel()
        waitUntil("cwd") { viewModel.ui.value.cwd == "/repo" }
        waitUntil("catalog") { viewModel.ui.value.commands.isNotEmpty() && commandCatalogCwds.contains("/repo") }

        viewModel.send("/compact")

        waitUntil("slash command") { commands.isNotEmpty() }
        assertTrue(commands.single().contains("\"type\":\"slash_command\""))
        assertTrue(commands.single().contains("\"text\":\"/compact\""))
        assertTrue(viewModel.ui.value.pendingMessages.isEmpty())

        commands.clear()
        viewModel.send("Proceed")
        waitUntil("question answer") { commands.isNotEmpty() }
        assertTrue(commands.single().contains("\"type\":\"answer_question\""))
        assertTrue(commands.single().contains("\"text\":\"Proceed\""))
    }

    @Test
    fun composerAnswerConfirmsWhenQuestionFlipsToAnswered() = runBlocking {
        agentStatus = "blocked"
        val viewModel = viewModel()
        waitUntil("pending question") {
            viewModel.ui.value.questions.singleOrNull()?.answered == false
        }

        viewModel.send("Proceed")
        assertEquals("Proceed", viewModel.ui.value.pendingMessages.single().text)

        // The agent records the answer — as a toolResult, never as a user
        // entry. The pending bubble must still confirm once the question is
        // answered.
        answerRecorded = true
        waitUntil("answer confirmed") { viewModel.ui.value.pendingMessages.isEmpty() }
        assertTrue(viewModel.ui.value.questions.single().answered)
        // pi records the answer only in the toolResult — no user entry for
        // "Proceed" appears in the transcript (the harness injects "Fix it"
        // after a WS send, which is unrelated).
        assertTrue(viewModel.ui.value.entries.none { entryText(it.content) == "Proceed" })
    }

    @Test
    fun staleCommandResponseCannotReplaceANewerCatalog() = runBlocking {
        val viewModel = viewModel()
        waitUntil("initial catalog") { viewModel.ui.value.commands.isNotEmpty() }

        val slow = async { viewModel.refreshCommands("/slow") }
        delay(25)
        val fast = async { viewModel.refreshCommands("/fast") }
        slow.await()
        fast.await()

        assertEquals(listOf("fast-command"), viewModel.ui.value.commands.map { it.name })
    }

    @Test
    fun clearsProjectCommandsWhenAgentLosesItsCwd() = runBlocking {
        val viewModel = viewModel()
        waitUntil("project catalog") { viewModel.ui.value.cwd == "/repo" && commandCatalogCwds.contains("/repo") }

        agentCwd = null
        viewModel.refresh()

        waitUntil("global catalog") { viewModel.ui.value.cwd == null && commandCatalogCwds.lastOrNull() == "<global>" }
    }

    @Test
    fun clearsProjectCommandsWhenAgentDisappears() = runBlocking {
        val viewModel = viewModel()
        waitUntil("project catalog") { viewModel.ui.value.cwd == "/repo" && commandCatalogCwds.contains("/repo") }

        agentPresent = false
        viewModel.refresh()

        waitUntil("global catalog") { viewModel.ui.value.cwd == null && commandCatalogCwds.lastOrNull() == "<global>" }
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
    fun multiSpaceAndNewlineMessagesStillReconcile() {
        val message = PendingUserMessage(
            localId = "local-1",
            text = "hello  world\nsecond line",
            state = MessageDeliveryState.QUEUED,
        )
        // pi records the user message with normalized spacing; entryText
        // collapses runs, so the typed text must be normalized the same way.
        val confirmation = listOf(
            SessionEntry(
                entryId = "server-1",
                role = "user",
                content = listOf(ContentBlock(type = "text", text = "hello world second line")),
            ),
        )

        assertTrue(dropConfirmedMessages(listOf(message), confirmation).isEmpty())
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

    private suspend fun waitUntil(description: String = "condition", condition: () -> Boolean) {
        repeat(200) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            delay(25)
        }
        error("$description did not become true")
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody(body)
}
