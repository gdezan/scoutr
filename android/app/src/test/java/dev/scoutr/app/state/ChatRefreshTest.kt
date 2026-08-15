package dev.scoutr.app.state

import dev.scoutr.app.data.AgentCard
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.data.SessionReadResponse
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.PerformanceCounters
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.TimeUnit

/**
 * The Chat refresh coordinator contract: every trigger (poll tick, post-action
 * reconciliation, pull) shares one authoritative read per pane; concurrent
 * triggers join the in-flight read instead of racing it; stopPolling gates new
 * triggers and cancels the in-flight read; a successful pull resets the poll
 * deadline; the pull indicator covers only the user-requested refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatRefreshTest {

    private lateinit var fake: FakeScoutrApi
    private lateinit var counters: PerformanceCounters

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
                        agent = "pi",
                        status = "working",
                        cwd = "/repo",
                        sessionPath = "/repo/sessions/s.jsonl",
                    ),
                ),
            ),
        )
        counters = PerformanceCounters()
    }

    private fun newViewModel(): ChatViewModel =
        ChatViewModel(fake, "w1:p1", "/repo/sessions/s.jsonl", "working", counters)

    private fun sessionReads(): Int = fake.calls.count { it.name == "session" }
    private fun agentReads(): Int = fake.calls.count { it.name == "agents" }

    private fun stubSession(entries: List<SessionEntry> = emptyList()) {
        fake.sessionResult = Result.success(
            SessionReadResponse(
                ok = true,
                exists = true,
                path = "/repo/sessions/s.jsonl",
                entries = entries,
                questions = emptyList(),
            ),
        )
    }

    /** Idle the main looper until [condition] holds (no clock advance). */
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

    private fun ChatViewModel.awaitRefreshSettled() =
        pumpUntil("transcript ready") { ui.value.transcript is Loadable.Ready }

    /** Run one coordinator refresh through the Main looper (a direct suspend call would deadlock). */
    private fun ChatViewModel.refreshBlocking(source: RefreshSource): Boolean {
        var result = false
        runBlocking {
            val job = launch { result = refresh(source) }
            repeat(500) {
                ShadowLooper.idleMainLooper()
                if (job.isCompleted) return@runBlocking
                delay(10)
            }
            job.join()
        }
        return result
    }

    @Test
    fun singleFlightCollapsesConcurrentTriggersIntoOneRead() {
        // Hold the agents response so the first poll tick suspends mid-read.
        val gate = CompletableDeferred<Unit>()
        fake.gates["agents"] = gate
        val vm = newViewModel()
        vm.startPolling()
        pumpUntil("tick suspended on the agents gate") { agentReads() == 1 }

        // Two more triggers arrive while that read is in flight: a post-send
        // reconciliation and an answer reconciliation. Both must join, not race.
        val results = mutableListOf<Boolean>()
        runBlocking {
            val send = launch { results += vm.refresh(RefreshSource.SendReconciliation) }
            val answer = launch { results += vm.refresh(RefreshSource.AnswerReconciliation) }
            for (attempt in 1..200) {
                ShadowLooper.idleMainLooper()
                if (counters.snapshot().chatRefresh.joined == 2L) break
                delay(10)
            }
            assertEquals("both triggers joined the in-flight read", 2, counters.snapshot().chatRefresh.joined)
            gate.complete(Unit)
            repeat(500) {
                ShadowLooper.idleMainLooper()
                if (send.isCompleted && answer.isCompleted) return@runBlocking
                delay(10)
            }
            send.join()
            answer.join()
        }
        assertEquals("joiners report the shared read's outcome", listOf(true, true), results)
        pumpUntil("shared read landed") { vm.ui.value.transcript is Loadable.Ready }

        assertEquals("one agents read for the whole race", 1, agentReads())
        assertEquals("one session read for the whole race", 1, sessionReads())
        val chat = counters.snapshot().chatRefresh
        assertEquals("one authoritative refresh started", 1L, chat.started)
        assertEquals("two triggers joined it", 2L, chat.joined)
        assertEquals(1L, chat.startedBySource["PollTick"])
        vm.stopPolling()
    }

    @Test
    fun pullJoinsTheInFlightPollAndClearsItsIndicator() {
        val gate = CompletableDeferred<Unit>()
        fake.gates["session"] = gate
        stubSession()
        val vm = newViewModel()
        vm.startPolling()
        pumpUntil("tick suspended on the session gate") { sessionReads() == 1 }

        vm.onPullRefresh()
        pumpUntil("pull indicator shown") { vm.ui.value.isRefreshing }

        gate.complete(Unit)
        pumpUntil("pull settled") { !vm.ui.value.isRefreshing && vm.ui.value.transcript is Loadable.Ready }

        assertEquals("the pull joined the in-flight poll", 1, sessionReads())
        val chat = counters.snapshot().chatRefresh
        assertEquals(1L, chat.started)
        assertEquals(1L, chat.pullsAttempted)
        assertEquals(1L, chat.pullsCompleted)
        assertEquals(1L, chat.pullsSucceeded)
        vm.stopPolling()
    }

    @Test
    fun successfulPullResetsTheNextPollDeadline() {
        stubSession()
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()
        assertEquals("first tick read the transcript", 1, sessionReads())

        // Half an interval later the user pulls; the pull's own read lands
        // and resets the deadline, so the next tick is a full interval away.
        ShadowLooper.idleMainLooper(1_250, TimeUnit.MILLISECONDS)
        vm.onPullRefresh()
        pumpUntil("pull settled") { !vm.ui.value.isRefreshing }
        assertEquals("the pull read the transcript once", 2, sessionReads())

        // The original deadline (2.5s after the first tick) must not fire.
        ShadowLooper.idleMainLooper(1_200, TimeUnit.MILLISECONDS)
        assertEquals("the old poll deadline did not fire", 2, sessionReads())

        // A full interval after the pull, the restarted loop ticks again.
        ShadowLooper.idleMainLooper(1_300, TimeUnit.MILLISECONDS)
        assertEquals("the reset deadline fired", 3, sessionReads())
        vm.stopPolling()
    }

    @Test
    fun failedPullClearsTheIndicatorAndKeepsRenderedEntries() {
        stubSession(listOf(SessionEntry(entryId = "e1", role = "user", content = listOf(ContentBlock(type = "text", text = "hello")))))
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()
        assertEquals(listOf("e1"), vm.ui.value.entries.map { it.entryId })

        // Fail the read while still polling; the pull must surface the failure
        // without losing the already-rendered entries.
        fake.sessionResult = Result.failure(IOException("bridge unreachable"))
        vm.onPullRefresh()
        pumpUntil("failed pull settled") { !vm.ui.value.isRefreshing }
        vm.stopPolling()

        assertEquals("rendered entries survive a failed pull", listOf("e1"), vm.ui.value.entries.map { it.entryId })
        assertTrue("the read failure is still surfaced in the transcript loadable", vm.ui.value.transcript is Loadable.Failed)
        val chat = counters.snapshot().chatRefresh
        assertEquals(1L, chat.pullsAttempted)
        assertEquals(1L, chat.pullsCompleted)
        assertEquals(0L, chat.pullsSucceeded)
    }

    @Test
    fun everyTriggerRecordsItsSource() {
        stubSession()
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()

        assertTrue(vm.refreshBlocking(RefreshSource.SendReconciliation))

        val chat = counters.snapshot().chatRefresh
        assertEquals(1L, chat.startedBySource["PollTick"])
        assertEquals(1L, chat.startedBySource["SendReconciliation"])
        assertEquals(2L, chat.started)
        vm.stopPolling()
    }

    @Test
    fun stopPollingGatesNewTriggers() {
        stubSession()
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()
        val reads = sessionReads()
        vm.stopPolling()

        val result = runBlocking { vm.refresh(RefreshSource.Pull) }
        assertFalse("a trigger after STOPPED must not start a read", result)
        assertEquals("no new session read after STOPPED", reads, sessionReads())
        assertEquals("no new agents read after STOPPED", reads, agentReads())
    }
}
