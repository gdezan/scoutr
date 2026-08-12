package dev.cockpit.app.state

import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.data.CommandsCatalog
import dev.cockpit.app.data.CommandsCatalogResponse
import dev.cockpit.app.data.ContentBlock
import dev.cockpit.app.data.QuestionEntry
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.SessionReadResponse
import dev.cockpit.app.data.entryText
import dev.cockpit.app.net.FakeCockpitApi
import dev.cockpit.app.state.Loadable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatPendingMessageTest {
    private lateinit var fake: FakeCockpitApi
    @Volatile private var agentStatus = "working"
    @Volatile private var agentCwd: String? = "/repo"
    @Volatile private var agentPresent = true
    @Volatile private var answerRecorded = false

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
        fake.commandsResult = Result.success(
            CommandsCatalogResponse(
                ok = true,
                catalog = CommandsCatalog(
                    commands = listOf(
                        dev.cockpit.app.data.SlashCommandInfo(
                            name = "compact",
                            description = "Command",
                            source = "builtin",
                        ),
                    ),
                ),
            ),
        )
        fake.onCall = { name, args ->
            if (name == "commands") {
                val cwd = args["cwd"] as String?
                val name = when (cwd) {
                    "/slow" -> "slow-command"
                    "/fast" -> "fast-command"
                    else -> "compact"
                }
                Result.success(
                    CommandsCatalogResponse(
                        ok = true,
                        catalog = CommandsCatalog(
                            commands = listOf(
                                dev.cockpit.app.data.SlashCommandInfo(
                                    name = name,
                                    description = "Command",
                                    source = "builtin",
                                ),
                            ),
                        ),
                    ),
                )
            } else null
        }
    }

    private fun stubAgents() {
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = if (agentPresent) {
                    listOf(
                        AgentCard(
                            paneId = "w1:p1",
                            workspaceId = "w1",
                            tabId = "t1",
                            agent = "pi",
                            status = agentStatus,
                            cwd = agentCwd,
                            sessionPath = "/tmp/session.jsonl",
                        ),
                    )
                } else emptyList(),
            ),
        )
    }

    private fun stubSession(entries: List<SessionEntry> = emptyList(), answered: Boolean = false) {
        fake.sessionResult = Result.success(
            SessionReadResponse(
                ok = true,
                exists = true,
                path = "/tmp/session.jsonl",
                entries = entries,
                questions = listOf(
                    QuestionEntry(
                        id = "q1",
                        question = "Proceed?",
                        header = "Confirm",
                        options = emptyList(),
                        multiSelect = false,
                        answered = answered,
                        answerText = if (answered) "Proceed" else null,
                        timestamp = "2026-08-10T10:00:00.000Z",
                    ),
                ),
            ),
        )
    }

    private fun commandsCwds(): List<String?> =
        fake.calls.filter { it.name == "commands" }.map { it.args["cwd"] as String? }

    private fun ChatViewModel.readyCommands(): List<dev.cockpit.app.data.SlashCommandInfo> =
        (ui.value.commands as? Loadable.Ready)?.value.orEmpty()

    @Test
    fun sendAppearsQueuedImmediatelyThenReconcilesWithTranscript() = runBlocking {
        stubAgents()
        stubSession()
        val viewModel = viewModel()

        viewModel.send("Fix it")

        assertEquals("Fix it", viewModel.ui.value.pendingMessages.single().text)
        assertEquals(MessageDeliveryState.QUEUED, viewModel.ui.value.pendingMessages.single().state)
        // The agent records the message only after the WS send lands; the
        // pending bubble confirms once the transcript shows the entry.
        stubSession(
            entries = listOf(
                SessionEntry(entryId = "server-1", role = "user", content = listOf(ContentBlock(type = "text", text = "Fix it"))),
            ),
        )
        waitUntil { viewModel.ui.value.pendingMessages.isEmpty() }
        assertTrue(fake.sentCommands.single().toString().contains("\"text\":\"Fix it\""))
        assertEquals(listOf("server-1"), viewModel.ui.value.entries.map { it.entryId })
    }

    @Test
    fun failedSendStaysVisibleAndCanBeRetried() = runBlocking {
        stubAgents()
        stubSession()
        fake.wsFailure = java.io.IOException("websocket rejected")
        val viewModel = viewModel()
        viewModel.send("Fix it")

        waitUntil { viewModel.ui.value.pendingMessages.singleOrNull()?.state == MessageDeliveryState.FAILED }
        val failed = viewModel.ui.value.pendingMessages.single()

        fake.wsFailure = null
        viewModel.retryPendingMessage(failed.localId)
        assertEquals(MessageDeliveryState.QUEUED, viewModel.ui.value.pendingMessages.single().state)
        // The agent records the message only once the retried WS send lands;
        // the pending bubble confirms once the transcript shows the entry.
        stubSession(
            entries = listOf(
                SessionEntry(entryId = "server-1", role = "user", content = listOf(ContentBlock(type = "text", text = "Fix it"))),
            ),
        )
        waitUntil { viewModel.ui.value.pendingMessages.isEmpty() }
    }

    @Test
    fun blockedSessionRoutesSlashCommandsBeforeQuestionAnswers() = runBlocking {
        agentStatus = "blocked"
        stubAgents()
        stubSession()
        val viewModel = viewModel()
        waitUntil("cwd") { viewModel.ui.value.cwd == "/repo" }
        waitUntil("catalog") { viewModel.readyCommands().isNotEmpty() && commandsCwds().contains("/repo") }

        viewModel.send("/compact")

        waitUntil("slash command") { fake.sentCommands.isNotEmpty() }
        assertTrue(fake.sentCommands.single().toString().contains("\"type\":\"slash_command\""))
        assertTrue(fake.sentCommands.single().toString().contains("\"text\":\"/compact\""))
        assertTrue(viewModel.ui.value.pendingMessages.isEmpty())

        fake.sentCommands.clear()
        viewModel.send("Proceed")
        waitUntil("question answer") { fake.sentCommands.isNotEmpty() }
        assertTrue(fake.sentCommands.single().toString().contains("\"type\":\"answer_question\""))
        assertTrue(fake.sentCommands.single().toString().contains("\"text\":\"Proceed\""))
    }

    @Test
    fun composerAnswerConfirmsWhenQuestionFlipsToAnswered() = runBlocking {
        agentStatus = "blocked"
        stubAgents()
        stubSession()
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
        stubSession(answered = true)
        waitUntil("answer confirmed") { viewModel.ui.value.pendingMessages.isEmpty() }
        assertTrue(viewModel.ui.value.questions.single().answered)
        // pi records the answer only in the toolResult — no user entry for
        // "Proceed" appears in the transcript (the harness injects "Fix it"
        // after a WS send, which is unrelated).
        assertTrue(viewModel.ui.value.entries.none { entryText(it.content) == "Proceed" })
    }

    @Test
    fun staleCommandResponseCannotReplaceANewerCatalog() = runBlocking {
        stubAgents()
        stubSession()
        val viewModel = viewModel()
        waitUntil("initial catalog") { viewModel.readyCommands().isNotEmpty() }

        fake.callDelays["commands"] = 200
        val slow = async { viewModel.refreshCommands("/slow") }
        delay(25)
        fake.callDelays["commands"] = 0
        val fast = async { viewModel.refreshCommands("/fast") }
        slow.await()
        fast.await()

        assertEquals(listOf("fast-command"), viewModel.readyCommands().map { it.name })
    }

    @Test
    fun clearsProjectCommandsWhenAgentLosesItsCwd() = runBlocking {
        stubAgents()
        stubSession()
        val viewModel = viewModel()
        waitUntil("project catalog") { viewModel.ui.value.cwd == "/repo" && commandsCwds().contains("/repo") }

        agentCwd = null
        stubAgents()
        viewModel.refresh()

        waitUntil("global catalog") { viewModel.ui.value.cwd == null && commandsCwds().lastOrNull() == null }
    }

    @Test
    fun clearsProjectCommandsWhenAgentDisappears() = runBlocking {
        stubAgents()
        stubSession()
        val viewModel = viewModel()
        waitUntil("project catalog") { viewModel.ui.value.cwd == "/repo" && commandsCwds().contains("/repo") }

        agentPresent = false
        stubAgents()
        viewModel.refresh()

        waitUntil("global catalog") { viewModel.ui.value.cwd == null && commandsCwds().lastOrNull() == null }
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

    private fun viewModel(): ChatViewModel =
        ChatViewModel(fake, "w1:p1", "/tmp/session.jsonl", "working")

    private suspend fun waitUntil(description: String = "condition", condition: () -> Boolean) {
        repeat(200) {
            // Advance the paused main looper's clock so post-ack refreshes
            // (parked on their 750ms retry delay) actually fire.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) return
            delay(25)
        }
        error("$description did not become true")
    }
}