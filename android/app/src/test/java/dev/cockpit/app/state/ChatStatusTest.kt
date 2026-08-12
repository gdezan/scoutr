package dev.cockpit.app.state

import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.net.FakeCockpitApi
import dev.cockpit.app.state.Loadable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat keeps the agent status fresh from /api/agents so a session that
 * becomes blocked (ask_user_question) flips the composer to "answer" mode
 * without reopening the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatStatusTest {

    private lateinit var fake: FakeCockpitApi

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
    }

    private fun stubAgents(status: String, statusSinceMs: Long? = null) {
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = listOf(
                    AgentCard(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        agent = "pi",
                        status = status,
                        cwd = "/home/gdezan/Dev/agents-mobile",
                        sessionPath = "/home/gdezan/.pi/agent/sessions/s/s.jsonl",
                        statusSinceMs = statusSinceMs?.toDouble(),
                    ),
                ),
            ),
        )
    }

    /** Start the lifecycle poll and idle until its first tick has landed. */
    private fun ChatViewModel.awaitRefreshSettled() {
        startPolling()
        runBlocking {
            repeat(200) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (ui.value.transcript is Loadable.Ready) return@runBlocking
                delay(25)
            }
        }
    }

    @Test
    fun statusTracksBlockedFromTheBoard() {
        stubAgents("blocked")
        // Hold the first agents response so construction returns before the
        // board poll lands (the real client's network hop provided that gap).
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.gates["agents"] = gate
        val vm = ChatViewModel(fake, "w1:p1", null, "working")

        // the nav-arg status applies until the first board poll lands
        assertFalse(vm.waitingForAnswer)

        gate.complete(Unit)
        vm.awaitRefreshSettled()
        assertTrue(vm.waitingForAnswer)
        assertEquals("blocked", vm.ui.value.agentStatus)
    }

    @Test
    fun statusSinceStampFeedsTheElapsedTimer() {
        // The working indicator times the run from the bridge's stamp, so it
        // has to survive the poll into state rather than being re-derived
        // locally (a local clock restarts at 0s on every reconnect).
        stubAgents("working", statusSinceMs = 1_700_000_000_000L)
        val vm = ChatViewModel(fake, "w1:p1", null, "working")

        vm.awaitRefreshSettled()
        assertEquals(1_700_000_000_000L, vm.ui.value.statusSinceMs)
    }

    @Test
    fun unstampedCardLeavesTheTimerUnset() {
        stubAgents("working")
        val vm = ChatViewModel(fake, "w1:p1", null, "working")

        vm.awaitRefreshSettled()
        // No fabricated "0s": the indicator renders its label alone.
        assertEquals(null, vm.ui.value.statusSinceMs)
    }

    @Test
    fun statusKeepsWorkingWhenTheBoardSaysWorking() {
        stubAgents("working")
        val vm = ChatViewModel(fake, "w1:p1", null, "working")

        vm.awaitRefreshSettled()
        assertFalse(vm.waitingForAnswer)
        assertEquals("working", vm.ui.value.agentStatus)
    }
}
