package dev.cockpit.app.state

import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.net.FakeCockpitApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper.idleMainLooper

/**
 * Plan 005 lifecycle contract: ChatViewModel's poll runs only while the chat
 * screen is STARTED. startPolling() must be a no-op when already polling
 * (Poller's immediate first tick doubles as the first paint, so there is no
 * init refresh), stopPolling() must halt further requests, and a cancelled
 * refresh must never be rendered as a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PollLifecycleTest {

    private lateinit var fake: FakeCockpitApi

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
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
    }

    private fun sessionReads(): Int = fake.calls.count { it.name == "session" }

    @Test
    fun noPollingUntilStartedThenNoOpRestartAndStop() {
        val vm = ChatViewModel(fake, "w1:p1", "/repo/sessions/s.jsonl", "working")
        vm.awaitRefreshSettled()
        assertEquals("no init-started polling; Poller's first tick is the first paint", 0, sessionReads())

        vm.startPolling()
        vm.awaitRefreshSettled()
        val afterFirst = sessionReads()
        assertEquals("startPolling's immediate tick is the first read", 1, afterFirst)

        vm.startPolling()
        vm.awaitRefreshSettled()
        assertEquals("a second start is a no-op while already polling", afterFirst, sessionReads())

        vm.stopPolling()
        vm.awaitRefreshSettled()
        assertEquals("stopPolling halts further requests", afterFirst, sessionReads())

        // Lifecycle pause/resume: a fresh start after stop restarts the loop.
        vm.startPolling()
        vm.awaitRefreshSettled()
        assertEquals("resume restarts the loop", afterFirst + 1, sessionReads())
        vm.stopPolling()
    }

    @Test
    fun cancelledRefreshDoesNotWriteFailure() = runBlocking {
        // Hold the transcript response so the test's refresh suspends mid-
        // flight at the network seam.
        val gate = CompletableDeferred<Unit>()
        fake.gates["session"] = gate
        val vm = ChatViewModel(fake, "w1:p1", "/repo/sessions/s.jsonl", "working")

        val job = launch { vm.refresh() }
        // Wait until the refresh is actually suspended on the gate. delay()
        // suspends so the runBlocking event loop can run the launched job.
        repeat(200) {
            if (fake.calls.count { it.name == "session" } >= 1) return@repeat
            idleMainLooper()
            kotlinx.coroutines.delay(10)
        }
        assertEquals("the refresh reached the suspended session call", 1, fake.calls.count { it.name == "session" })

        job.cancel()
        job.join()
        assertTrue("cancelled refresh must not write a transcript failure", vm.ui.value.transcript !is Loadable.Failed)

        // Releasing the gate leaves no pending work; still no failure.
        gate.complete(Unit)
        idleMainLooper()
        assertTrue(vm.ui.value.transcript !is Loadable.Failed)
    }

    /** Idle the main looper until the VM's refresh has landed its state. */
    private fun ChatViewModel.awaitRefreshSettled() {
        runBlocking {
            repeat(200) {
                idleMainLooper()
                if (ui.value.transcript is Loadable.Ready) return@runBlocking
                kotlinx.coroutines.delay(25)
            }
        }
    }
}
