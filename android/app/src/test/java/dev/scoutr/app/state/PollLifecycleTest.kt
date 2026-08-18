package dev.scoutr.app.state

import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper.idleMainLooper

/**
 * ChatViewModel's poll runs only while the chat screen is STARTED.
 * startPolling() must be a no-op when already polling (Poller's immediate first
 * tick doubles as the first paint, so there is no init refresh), stopPolling()
 * must halt further requests, and a cancelled refresh must never be rendered as
 * a failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PollLifecycleTest {

    private lateinit var fake: FakeScoutrApi

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = listOf(
                    dev.scoutr.app.data.liveSessionFixture(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        agentKind = "pi",
                        status = "working",
                        cwd = "/repo",
                        key = dev.scoutr.app.data.SessionKey("pi", "/repo/sessions/s.jsonl"),
                    ),
                ),
            ),
        )
    }

    private fun sessionReads(): Int = fake.calls.count { it.name == "session" }

    @Test
    fun noPollingUntilStartedThenNoOpRestartAndStop() {
        val vm = ChatViewModel(fake, dev.scoutr.app.data.SessionKey("pi", "/repo/sessions/s.jsonl"), "w1:p1", "working")
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
    fun stopPollingCancelsTheInFlightRefreshWithoutWritingFailure() = runBlocking {
        // Hold the transcript response so the first poll tick suspends
        // mid-flight at the network seam.
        val gate = CompletableDeferred<Unit>()
        fake.gates["session"] = gate
        val vm = ChatViewModel(fake, dev.scoutr.app.data.SessionKey("pi", "/repo/sessions/s.jsonl"), "w1:p1", "working")
        vm.startPolling()

        repeat(200) {
            if (fake.calls.count { it.name == "session" } >= 1) return@repeat
            idleMainLooper()
            kotlinx.coroutines.delay(10)
        }
        assertEquals("the tick reached the suspended session call", 1, fake.calls.count { it.name == "session" })

        // STOPPED cancels the in-flight refresh; releasing the gate leaves
        // the cancelled read without any work to do.
        vm.stopPolling()
        gate.complete(Unit)
        idleMainLooper()
        assertTrue("a cancelled in-flight refresh must not write a transcript failure", vm.ui.value.transcript !is Loadable.Failed)

        // A trigger arriving after STOPPED must not start a new read.
        assertFalse("a trigger after STOPPED is a no-op", vm.refresh(RefreshSource.PollTick))
        assertEquals(1, fake.calls.count { it.name == "session" })
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
