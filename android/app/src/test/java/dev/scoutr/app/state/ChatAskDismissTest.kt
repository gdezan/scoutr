package dev.scoutr.app.state

import dev.scoutr.app.data.AgentCard
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.SessionReadResponse
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.time.Duration

/**
 * Dismissing an ask. The case that matters is drift: the question was already
 * closed in the terminal, so it can never be answered from the app, and the
 * bridge keeps reporting it as open. Dismiss has to work anyway, and has to
 * stick across every later poll — otherwise the composer stays locked for the
 * rest of the session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatAskDismissTest {

    private lateinit var fake: FakeScoutrApi

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = listOf(
                    AgentCard(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        agent = "claude",
                        status = "blocked",
                        cwd = "/repo",
                        sessionPath = "/repo/sessions/s.jsonl",
                    ),
                ),
            ),
        )
        // The bridge keeps serving the same open ask on every read — which is
        // exactly what a stale sidecar looks like from the app.
        fake.sessionResult = Result.success(
            SessionReadResponse(
                ok = true,
                exists = true,
                path = "/repo/sessions/s.jsonl",
                entries = emptyList(),
                questions = listOf(
                    QuestionEntry(
                        id = "q1",
                        callId = "call1",
                        question = "Which one?",
                        timestamp = "2026-08-10T10:00:00.000Z",
                    ),
                ),
            ),
        )
    }

    /** Test clock, so the slow-submit mark is reachable without waiting 15s. */
    private var now = 0L

    /** A live screen: polling started and the first read settled. */
    private fun startedViewModel(): ChatViewModel {
        val vm = ChatViewModel(
            fake,
            "w1:p1",
            "/repo/sessions/s.jsonl",
            "blocked",
            nowMs = { now },
        )
        vm.startPolling()
        pumpUntil("the ask arrived") { vm.ui.value.questions.isNotEmpty() }
        return vm
    }

    private fun pumpUntil(description: String, condition: () -> Boolean) {
        runBlocking {
            repeat(500) {
                ShadowLooper.idleMainLooper()
                if (condition()) return@runBlocking
                delay(10)
            }
        }
        assertTrue("$description did not become true", condition())
    }

    private fun ChatViewModel.refreshBlocking() {
        runBlocking {
            val job = launch { refresh(RefreshSource.PollTick) }
            repeat(500) {
                ShadowLooper.idleMainLooper()
                if (job.isCompleted) return@runBlocking
                delay(10)
            }
            job.join()
        }
    }

    @Test
    fun dismissUnlocksTheComposerAndKeepsItUnlockedAcrossPolls() {
        val vm = startedViewModel()
        assertTrue(vm.ui.value.hasPendingQuestion)

        vm.dismissAsk("call1")
        // Local first: the card is gone before the bridge has answered.
        assertFalse(vm.ui.value.hasPendingQuestion)
        assertTrue(vm.ui.value.questionCards.isEmpty())
        pumpUntil("dismiss reached the bridge") { fake.calls.any { it.name == "dismissAsk" } }

        // The ask is still open as far as the bridge is concerned; the app's
        // own record of the dismissal is what keeps the composer usable.
        vm.refreshBlocking()
        assertTrue(vm.ui.value.questions.any { !it.answered })
        assertFalse(vm.ui.value.hasPendingQuestion)
        assertTrue(vm.ui.value.questionCards.isEmpty())
    }

    @Test
    fun aDismissTheBridgeRejectsStillClearsTheCard() {
        val vm = startedViewModel()
        fake.wsFailure = IOException("pane is gone")

        vm.dismissAsk("call1")
        pumpUntil("the failure surfaced") { vm.ui.value.askNotice != null }
        // The point of dismiss is getting out. A bridge that could not close
        // the question in the terminal is worth saying, not worth undoing.
        assertEquals(ASK_DISMISS_FAILED, vm.ui.value.askNotice)
        assertFalse(vm.ui.value.hasPendingQuestion)
        assertTrue(vm.ui.value.questionCards.isEmpty())
    }

    @Test
    fun dismissDropsTheDraftWithTheCard() {
        val vm = startedViewModel()
        vm.setAskAnswer("call1", "q1", DraftAnswer(text = "half typed"))
        assertTrue(vm.ui.value.askDrafts.containsKey("call1"))

        vm.dismissAsk("call1")
        assertTrue(vm.ui.value.askDrafts.isEmpty())
    }

    @Test
    fun aStalledSubmitCanBeDismissedOnceItIsSlow() {
        val vm = startedViewModel()
        vm.setAskAnswer("call1", "q1", DraftAnswer(text = "yes"))
        vm.submitAsk("call1")
        pumpUntil("the round is in flight") { vm.ui.value.submittingCallId == "call1" }

        // While the keystrokes are in flight the round may still land, so
        // dismiss is ignored.
        vm.dismissAsk("call1")
        assertEquals("call1", vm.ui.value.submittingCallId)

        // Past the slow mark the card admits it has had no response, and the
        // user gets their way out rather than a permanently locked composer.
        now += ASK_SLOW_SUBMIT_MS
        // The reconciliation loop sleeps between reads, so simulated time has
        // to move for it to look at the clock again.
        runBlocking {
            repeat(50) {
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ASK_RECONCILE_INTERVAL_MS))
                if (vm.ui.value.submitIsSlow) return@runBlocking
                delay(5)
            }
        }
        assertTrue("the round went slow", vm.ui.value.submitIsSlow)
        vm.dismissAsk("call1")
        assertEquals(null, vm.ui.value.submittingCallId)
        assertFalse(vm.ui.value.hasPendingQuestion)
    }
}
