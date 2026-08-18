package dev.scoutr.app.state

import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.CommandsCatalog
import dev.scoutr.app.data.CommandsCatalogResponse
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.data.SessionReadResponse
import dev.scoutr.app.data.entryText
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.state.Loadable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private lateinit var fake: FakeScoutrApi
    @Volatile private var agentStatus = "working"
    @Volatile private var agentCwd: String? = "/repo"
    @Volatile private var agentPresent = true
    @Volatile private var answerRecorded = false

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        fake.commandsResult = Result.success(
            CommandsCatalogResponse(
                ok = true,
                catalog = CommandsCatalog(
                    commands = listOf(
                        dev.scoutr.app.data.SlashCommandInfo(
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
                                dev.scoutr.app.data.SlashCommandInfo(
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
                        dev.scoutr.app.data.liveSessionFixture(
                            paneId = "w1:p1",
                            workspaceId = "w1",
                            tabId = "t1",
                            agentKind = "pi",
                            status = agentStatus,
                            cwd = agentCwd,
                            key = dev.scoutr.app.data.SessionKey("pi", "/tmp/session.jsonl"),
                        ),
                    )
                } else emptyList(),
            ),
        )
    }

    private fun stubSession(
        entries: List<SessionEntry> = emptyList(),
        answered: Boolean = false,
        withQuestion: Boolean = true,
    ) {
        fake.sessionResult = Result.success(
            SessionReadResponse(
                ok = true,
                exists = true,
                path = "/tmp/session.jsonl",
                entries = entries,
                questions = if (!withQuestion) emptyList() else listOf(
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

    private fun ChatViewModel.readyCommands(): List<dev.scoutr.app.data.SlashCommandInfo> =
        (ui.value.commands as? Loadable.Ready)?.value.orEmpty()

    @Test
    fun sendAppearsQueuedImmediatelyThenReconcilesWithTranscript() = runBlocking {
        stubAgents()
        stubSession()
        val viewModel = viewModel()

        viewModel.send("Fix it")

        assertEquals("Fix it", viewModel.ui.value.pendingMessages.single().text)
        // QUEUED only spans the request itself, so whether it has already
        // flipped to SENT is a race; what matters is that the row is in flight
        // rather than failed.
        assertTrue(viewModel.ui.value.pendingMessages.single().state != MessageDeliveryState.FAILED)
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
        fake.commandFailure = java.io.IOException("bridge rejected the steer")
        val viewModel = viewModel()
        viewModel.send("Fix it")

        waitUntil { viewModel.ui.value.pendingMessages.singleOrNull()?.state == MessageDeliveryState.FAILED }
        val failed = viewModel.ui.value.pendingMessages.single()

        fake.commandFailure = null
        viewModel.retryPendingMessage(failed.localId)
        // Retry puts it back in flight; it may already have been accepted.
        assertTrue(viewModel.ui.value.pendingMessages.single().state != MessageDeliveryState.FAILED)
        // The agent records the message only once the retried send lands;
        // the pending bubble confirms once the transcript shows the entry.
        stubSession(
            entries = listOf(
                SessionEntry(entryId = "server-1", role = "user", content = listOf(ContentBlock(type = "text", text = "Fix it"))),
            ),
        )
        waitUntil { viewModel.ui.value.pendingMessages.isEmpty() }
    }

    @Test
    fun blockedSessionRoutesSlashCommandsBeforePromptAnswers() = runBlocking {
        agentStatus = "blocked"
        stubAgents()
        // No card: the pane is blocked on a plain prompt (a permission dialog,
        // say). A pane blocked on an ask never reaches the composer path at
        // all — the card owns that answer and disables the composer.
        stubSession(withQuestion = false)
        val viewModel = viewModel()
        waitUntil("cwd") { viewModel.ui.value.cwd == "/repo" }
        waitUntil("catalog") { viewModel.readyCommands().isNotEmpty() && commandsCwds().contains("/repo") }

        viewModel.send("/compact")

        waitUntil("slash command") { fake.sentCommands.isNotEmpty() }
        assertTrue(fake.sentCommands.single().toString().contains("\"type\":\"slash_command\""))
        assertTrue(fake.sentCommands.single().toString().contains("\"text\":\"/compact\""))
        assertTrue(viewModel.ui.value.pendingMessages.isEmpty())

        // Plain text at the same blocked prompt answers it directly.
        fake.sentCommands.clear()
        viewModel.send("Proceed")
        waitUntil("prompt answer") { fake.sentCommands.isNotEmpty() }
        assertTrue(fake.sentCommands.single().toString().contains("\"type\":\"answer_ask\""))
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
        viewModel.refreshNow()

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
        viewModel.refreshNow()

        waitUntil("live controls cleared") {
            viewModel.ui.value.livePaneId == null && viewModel.readyCommands().isEmpty()
        }
    }

    /** One coordinator refresh through the Main looper (a direct suspend call would deadlock). */
    private fun ChatViewModel.refreshNow(source: RefreshSource = RefreshSource.PollTick) {
        runBlocking {
            val job = launch { refresh(source) }
            repeat(200) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (job.isCompleted) return@runBlocking
                delay(25)
            }
            job.join()
        }
    }

    private fun viewModel(): ChatViewModel =
        ChatViewModel(fake, dev.scoutr.app.data.SessionKey("pi", "/tmp/session.jsonl"), "w1:p1", "working").also { it.startPolling() }

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

    @Test
    fun sentMessageIsRetiredOnlyAfterTheEchoGracePeriod() {
        // The agent recorded what it received in a form the text match cannot
        // recognise. The row must outlive the burst of refreshes a send fires —
        // counting polls retired it seconds after sending, which read as the
        // message vanishing — and only go once the grace period is genuinely up.
        val sentAt = 1_000_000L
        val message = PendingUserMessage(
            localId = "local-1",
            text = "ship it",
            state = MessageDeliveryState.SENT,
            sentAtMs = sentAt,
        )
        val unrelated = SessionEntry(
            entryId = "u-1",
            role = "user",
            content = listOf(ContentBlock(type = "text", text = "something else entirely")),
        )

        // A burst of polls right after sending must not consume the grace period.
        repeat(10) {
            assertEquals(
                listOf(message),
                dropConfirmedMessages(listOf(message), listOf(unrelated), nowMs = sentAt + 2_000),
            )
        }
        assertEquals(
            listOf(message),
            dropConfirmedMessages(listOf(message), listOf(unrelated), nowMs = sentAt + ECHO_GRACE_MS - 1),
        )
        assertEquals(
            emptyList<PendingUserMessage>(),
            dropConfirmedMessages(listOf(message), listOf(unrelated), nowMs = sentAt + ECHO_GRACE_MS),
        )
    }

    @Test
    fun queuedMessageIsNeverRetiredOnAge() {
        // Only a message the bridge has accepted may be retired on age; one
        // still in flight must keep its row until it succeeds or fails.
        val message = PendingUserMessage(
            localId = "local-2",
            text = "ship it",
            state = MessageDeliveryState.QUEUED,
        )
        assertEquals(
            listOf(message),
            dropConfirmedMessages(listOf(message), emptyList(), nowMs = Long.MAX_VALUE / 2),
        )
    }

    @Test
    fun sentMessageStillReconcilesOnItsEcho() {
        val message = PendingUserMessage(
            localId = "local-3",
            text = "ship it",
            state = MessageDeliveryState.SENT,
            sentAtMs = 1_000_000L,
        )
        val echo = SessionEntry(
            entryId = "u-9",
            role = "user",
            content = listOf(ContentBlock(type = "text", text = "ship it")),
        )

        assertEquals(emptyList<PendingUserMessage>(), dropConfirmedMessages(listOf(message), listOf(echo)))
    }


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
