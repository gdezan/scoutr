package dev.scoutr.app.state

import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.AttentionQuestion
import dev.scoutr.app.data.AttentionSummary
import dev.scoutr.app.data.QuestionOption
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionLiveAttachment
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Board quick answer across hosts: one option tap submits the whole open ask
 * through the same `answerAsk` Chat uses, cannot be submitted twice — keyed by
 * host, so identical pane ids on two bridges never block each other — and
 * refreshes the owning host's board whatever the bridge says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardQuickAnswerTest {

    private val longLabel = "Yes, and remember this choice for the rest of the session"

    private fun card(
        paneId: String = "w1:p1",
        attention: AttentionSummary? = ask(),
        status: String = "blocked",
    ) = SessionDescriptor(
        agentKind = "claude",
        displayName = "scoutr",
        title = "Refactor the board cache",
        latestActivity = "waiting for you",
        attention = attention,
        live = SessionLiveAttachment(
            paneId = paneId,
            workspaceId = "w1",
            tabId = "t1",
            status = status,
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

    /** Seeds one or both hosts' boards with a needs-you card via one cycle. */
    private fun seededHarness(
        hostA: List<SessionDescriptor>,
        hostB: List<SessionDescriptor>? = null,
    ): Pair<BoardHarness, BoardViewModel> {
        val h = jvmBoardHarness()
        h.addHost("host-a")
        h.apiFor("host-a").agentsResult = Result.success(AgentsResponse(agents = hostA))
        if (hostB != null) {
            h.addHost("host-b")
            h.apiFor("host-b").agentsResult = Result.success(AgentsResponse(agents = hostB))
        }
        val vm = h.viewModel()
        vm.startPolling()
        // Wait on the merged rows, not the raw snapshots: they only appear
        // once the registry/status collectors have fed the merge too.
        BoardTestLoop.waitUntil {
            vm.ui.value.hostedSessions.any { it.session.live?.paneId == hostA.first().live?.paneId }
        }
        if (hostB != null) {
            BoardTestLoop.waitUntil {
                vm.ui.value.hostedSessions.any { it.session.live?.paneId == hostB.first().live?.paneId }
            }
        }
        return h to vm
    }

    private fun hostedRow(vm: BoardViewModel, hostId: String): HostedSession =
        vm.ui.value.hostedSessions.first { it.profile.hostId == hostId }

    private fun answers(fake: FakeScoutrApi) =
        fake.sentCommands.filter { it["type"].toString().contains("answer_ask") }

    @Test
    fun quickAnswerSubmitsTheServersOwnIdsAndLabelToTheOwningHost() {
        val (h, vm) = seededHarness(listOf(card()))
        val row = hostedRow(vm, "host-a")

        vm.quickAnswer(row, longLabel)
        BoardTestLoop.waitUntil {
            answers(h.apiFor("host-a")).size == 1 && vm.ui.value.quickAnswering.isEmpty()
        }

        val command = answers(h.apiFor("host-a")).single()
        assertEquals("\"w1:p1\"", command["paneId"].toString())
        assertEquals("\"toolu_1\"", command["callId"].toString())
        // Exactly one answer: this is a complete one-question round.
        assertTrue(command["answers"].toString().contains("\"toolu_1#0\""))
        // The exact server label travels, not the truncated button text.
        assertTrue(
            "sent the full label, was ${command["answers"]}",
            command["answers"].toString().contains(longLabel),
        )
        assertEquals(null, vm.ui.value.transientError)
        // Only the owning host's board refreshed; the other bridge is untouched.
        assertEquals(2, h.apiFor("host-a").calls.count { it.name == "agents" })
        assertEquals(0, h.apiFor("host-b").calls.count { it.name == "agents" })
        vm.stopPolling()
    }

    @Test
    fun secondTapWhileTheFirstIsInFlightSubmitsNothing() {
        val (h, vm) = seededHarness(listOf(card()))
        val gate = CompletableDeferred<Unit>()
        h.apiFor("host-a").gates["answerAsk"] = gate

        vm.quickAnswer(hostedRow(vm, "host-a"), "Yes")
        BoardTestLoop.waitUntil { vm.ui.value.quickAnswering.isNotEmpty() }
        assertTrue(
            "the busy key carries the host, not just the pane",
            vm.ui.value.quickAnswering.single().paneId == "w1:p1",
        )

        vm.quickAnswer(hostedRow(vm, "host-a"), "Yes")
        BoardTestLoop.idle()
        assertEquals("a double tap must not answer twice", 1, answers(h.apiFor("host-a")).size)

        gate.complete(Unit)
        BoardTestLoop.waitUntil { vm.ui.value.quickAnswering.isEmpty() }
        assertEquals(1, answers(h.apiFor("host-a")).size)
        vm.stopPolling()
    }

    @Test
    fun identicalPaneIdsOnTwoHostsNeverBlockEachOther() {
        // Both bridges report the very same pane id; only the owning host
        // tells the two cards apart.
        val (h, vm) = seededHarness(listOf(card()), listOf(card()))
        assertEquals(
            listOf("w1:p1", "w1:p1"),
            vm.ui.value.hostedSessions.mapNotNull { it.session.live?.paneId },
        )
        val gate = CompletableDeferred<Unit>()
        // Park only host-b's answer; host-a's identical pane must still go.
        h.apiFor("host-b").gates["answerAsk"] = gate

        vm.quickAnswer(hostedRow(vm, "host-b"), "Yes")
        BoardTestLoop.waitUntil { vm.ui.value.quickAnswering.size == 1 }

        vm.quickAnswer(hostedRow(vm, "host-a"), "Yes")
        BoardTestLoop.waitUntil { answers(h.apiFor("host-a")).size == 1 }

        assertEquals("the parked host's answer is still in flight", 1, vm.ui.value.quickAnswering.size)
        gate.complete(Unit)
        BoardTestLoop.waitUntil { vm.ui.value.quickAnswering.isEmpty() }
        vm.stopPolling()
    }

    @Test
    fun answeredElsewhereSurfacesTheStaleErrorAndRefreshes() {
        val (h, vm) = seededHarness(listOf(card()))
        h.apiFor("host-a").commandFailure = BridgeException(409, "ask no longer open")

        vm.quickAnswer(hostedRow(vm, "host-a"), "Yes")
        BoardTestLoop.waitUntil { vm.ui.value.transientError != null }

        assertEquals(1, answers(h.apiFor("host-a")).size)
        assertEquals("That question is no longer open", vm.ui.value.transientError)
        assertTrue("a rejected answer still refreshes the board", vm.ui.value.quickAnswering.isEmpty())
        vm.stopPolling()
    }

    @Test
    fun transportFailureSurfacesItsMessageAndRefreshes() {
        val (h, vm) = seededHarness(listOf(card()))
        h.apiFor("host-a").commandFailure = IOException("bridge unreachable")

        vm.quickAnswer(hostedRow(vm, "host-a"), "Yes")
        BoardTestLoop.waitUntil { vm.ui.value.transientError != null }

        assertEquals(1, answers(h.apiFor("host-a")).size)
        assertTrue(
            "error surfaced, was: ${vm.ui.value.transientError}",
            vm.ui.value.transientError?.contains("bridge unreachable") == true,
        )
        assertTrue(vm.ui.value.quickAnswering.isEmpty())
        vm.stopPolling()
    }

    @Test
    fun anAskThatMovedOnSincePollIsNeverSubmitted() {
        // The tapped card is re-checked against the board the VM holds now,
        // where the ask has already been answered away.
        val (h, vm) = seededHarness(listOf(card(attention = null)))

        vm.quickAnswer(hostedRow(vm, "host-a"), "Yes")
        BoardTestLoop.waitUntil { vm.ui.value.transientError != null }

        assertTrue("nothing may be sent for an ask that is gone", answers(h.apiFor("host-a")).isEmpty())
        assertEquals("That question is no longer open", vm.ui.value.transientError)
        vm.stopPolling()
    }

    @Test
    fun anAskTheBridgeWillNotLetTheBoardSubmitWholeIsNeverSubmitted() {
        val (h, vm) = seededHarness(listOf(card(attention = ask(questionCount = 3))))

        vm.quickAnswer(hostedRow(vm, "host-a"), "Yes")
        BoardTestLoop.waitUntil { vm.ui.value.transientError != null }

        assertTrue(answers(h.apiFor("host-a")).isEmpty())
        vm.stopPolling()
    }

    @Test
    fun anOptionTheCardNoLongerOffersIsNeverSubmitted() {
        val (h, vm) = seededHarness(listOf(card()))

        vm.quickAnswer(hostedRow(vm, "host-a"), "Maybe")
        BoardTestLoop.waitUntil { vm.ui.value.transientError != null }

        assertTrue(answers(h.apiFor("host-a")).isEmpty())
        vm.stopPolling()
    }
}
