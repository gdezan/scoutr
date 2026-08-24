package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.AttentionQuestion
import dev.scoutr.app.data.AttentionSummary
import dev.scoutr.app.data.BoardState
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionLiveAttachment
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Board quick answer: one option tap submits the whole open ask through the
 * same `answerAsk` Chat uses, cannot be submitted twice, and refreshes the
 * board whatever the bridge says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardQuickAnswerTest {

    private lateinit var fake: FakeScoutrApi
    private lateinit var viewModel: BoardViewModel

    private val longLabel = "Yes, and remember this choice for the rest of the session"

    private fun card(attention: AttentionSummary? = ask()): SessionDescriptor = SessionDescriptor(
        agentKind = "claude",
        displayName = "scoutr",
        title = "Refactor the board cache",
        latestActivity = "waiting for you",
        attention = attention,
        live = SessionLiveAttachment(
            paneId = "w1:p1",
            workspaceId = "w1",
            tabId = "t1",
            status = "blocked",
        ),
    )

    private fun ask(
        callId: String = "toolu_1",
        questionId: String = "toolu_1#0",
        options: List<String> = listOf("Yes", longLabel),
        questionCount: Int = 1,
        canQuickAnswer: Boolean = true,
    ) = AttentionSummary(
        kind = "ask",
        callId = callId,
        questionCount = questionCount,
        canQuickAnswer = canQuickAnswer,
        currentQuestion = AttentionQuestion(
            id = questionId,
            header = "Approach",
            question = "Rewrite the cache key?",
            options = options.map { QuestionOption(label = it) },
        ),
    )

    private fun start(board: BoardState) {
        val store = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher())
        // Unsaved at construction so the VM's init never probes health or
        // starts a poll that would race the tap under test.
        store.clear()
        viewModel = legacyBoardViewModel(
            bridge = fake,
            connectionStore = store,
            initialState = BoardUiState(
                board = board,
                connected = true,
                apiCompatibility = ScoutrApiCompatibility.Compatible,
            ),
        )
        store.save("http://test-bridge", "test-token")
    }

    private fun answers() = fake.sentCommands.filter { it["type"].toString().contains("answer_ask") }

    private fun refreshes() = fake.calls.count { it.name == "agents" }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
            idle()
        }
        assertTrue("condition did not become true before timeout", condition())
    }

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        start(BoardState(needsYou = listOf(card())))
    }

    @Test
    fun quickAnswerSubmitsTheServersOwnIdsAndLabel() {
        viewModel.quickAnswer(card(), longLabel)
        idle()

        val command = answers().single()
        assertEquals("\"w1:p1\"", command["paneId"].toString())
        assertEquals("\"toolu_1\"", command["callId"].toString())
        // Exactly one answer: this is a complete one-question round.
        assertTrue(command["answers"].toString().contains("\"toolu_1#0\""))
        // The exact server label travels, not the truncated button text.
        assertTrue(
            "sent the full label, was ${command["answers"]}",
            command["answers"].toString().contains(longLabel),
        )
        assertEquals(null, viewModel.ui.value.error)
        assertEquals("board refreshed after a successful answer", 1, refreshes())
        assertTrue(viewModel.ui.value.quickAnswering.isEmpty())
    }

    @Test
    fun secondTapWhileTheFirstIsInFlightSubmitsNothing() {
        val gate = CompletableDeferred<Unit>()
        fake.gates["answerAsk"] = gate

        viewModel.quickAnswer(card(), "Yes")
        idle()
        assertTrue("the card is visibly submitting", viewModel.ui.value.quickAnswering.contains("w1:p1"))

        viewModel.quickAnswer(card(), "Yes")
        idle()
        assertEquals("a double tap must not answer twice", 1, answers().size)

        gate.complete(Unit)
        waitUntil { viewModel.ui.value.quickAnswering.isEmpty() && refreshes() == 1 }
        assertEquals(1, answers().size)
    }

    @Test
    fun answeredElsewhereSurfacesTheStaleErrorAndRefreshes() {
        fake.commandFailure = BridgeException(409, "ask no longer open")

        viewModel.quickAnswer(card(), "Yes")
        idle()
        waitUntil { viewModel.ui.value.error != null }

        assertEquals(1, answers().size)
        assertEquals("That question is no longer open", viewModel.ui.value.error)
        assertEquals("a rejected answer still refreshes the board", 1, refreshes())
        assertTrue(viewModel.ui.value.quickAnswering.isEmpty())
    }

    @Test
    fun transportFailureSurfacesItsMessageAndRefreshes() {
        fake.commandFailure = IOException("bridge unreachable")

        viewModel.quickAnswer(card(), "Yes")
        idle()
        waitUntil { viewModel.ui.value.error != null }

        assertEquals(1, answers().size)
        assertTrue(
            "error surfaced, was: ${viewModel.ui.value.error}",
            viewModel.ui.value.error?.contains("bridge unreachable") == true,
        )
        assertEquals("a failed answer still refreshes the board", 1, refreshes())
        assertTrue(viewModel.ui.value.quickAnswering.isEmpty())
    }

    @Test
    fun anAskThatMovedOnSincePollIsNeverSubmitted() {
        // The tapped card is re-checked against the board the VM holds now,
        // where the ask has already been answered away.
        start(BoardState(needsYou = listOf(card(attention = null))))

        viewModel.quickAnswer(card(), "Yes")
        idle()
        waitUntil { viewModel.ui.value.error != null }

        assertTrue("nothing may be sent for an ask that is gone", answers().isEmpty())
        assertEquals("That question is no longer open", viewModel.ui.value.error)
        assertEquals(1, refreshes())
    }

    @Test
    fun anAskTheBridgeWillNotLetTheBoardSubmitWholeIsNeverSubmitted() {
        start(BoardState(needsYou = listOf(card(attention = ask(questionCount = 3)))))

        viewModel.quickAnswer(card(), "Yes")
        idle()
        waitUntil { viewModel.ui.value.error != null }

        assertTrue(answers().isEmpty())
        assertEquals(1, refreshes())
    }

    @Test
    fun anOptionTheCardNoLongerOffersIsNeverSubmitted() {
        viewModel.quickAnswer(card(), "Maybe")
        idle()
        waitUntil { viewModel.ui.value.error != null }

        assertTrue(answers().isEmpty())
        assertEquals(1, refreshes())
    }
}
