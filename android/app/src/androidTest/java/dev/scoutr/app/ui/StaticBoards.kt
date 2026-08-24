package dev.scoutr.app.ui

import androidx.test.platform.app.InstrumentationRegistry
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.net.HostIdentityChangedException
import dev.scoutr.app.net.HostIncompatibleException
import dev.scoutr.app.state.BoardHarness
import dev.scoutr.app.state.BoardViewModel
import java.io.IOException

/**
 * Static Board view models for UI tests: a real harness-backed VM pinned to a
 * seeded snapshot. Exactly one host is registered, its first (and only, at a
 * 60s interval) poll cycle lands before composition, and polling then stops —
 * the UI under test renders a frozen board and never touches the network.
 */
object StaticBoards {

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }
        check(condition()) { "static board seed did not settle" }
    }

    /** One host, online, showing [sessions]; polling stopped after the seed. */
    fun boardViewModel(sessions: List<SessionDescriptor>): BoardViewModel {
        val harness = BoardHarness(InstrumentationRegistry.getInstrumentation().targetContext)
        harness.addHost("host-a", alias = "bridge")
        harness.apiFor("host-a").agentsResult =
            Result.success(AgentsResponse(agents = sessions))
        val vm = harness.viewModel()
        vm.startPolling()
        waitUntil { vm.ui.value.hostedSessions.size == sessions.size }
        vm.stopPolling()
        return vm
    }

    /** No registered hosts: the loading spinner branch. */
    fun emptyHostsViewModel(): BoardViewModel {
        val harness = BoardHarness(InstrumentationRegistry.getInstrumentation().targetContext)
        val vm = harness.viewModel()
        vm.startPolling()
        vm.stopPolling()
        return vm
    }

    /**
     * One paired host whose bridge answers with an empty agents list while
     * reporting Online — the standing "no agents running" board.
     */
    fun connectedEmptyViewModel(): BoardViewModel {
        val harness = BoardHarness(InstrumentationRegistry.getInstrumentation().targetContext)
        harness.addHost("host-a", alias = "bridge")
        val vm = harness.viewModel()
        vm.startPolling()
        waitUntil { vm.ui.value.connected }
        vm.stopPolling()
        return vm
    }

    /** One host classified incompatible: the issue-card branch, no rows ever. */
    fun incompatibleViewModel(): BoardViewModel {
        val harness = BoardHarness(InstrumentationRegistry.getInstrumentation().targetContext)
        harness.addHost("host-a", alias = "bridge")
        harness.apiFor("host-a").agentsResult =
            Result.failure(HostIncompatibleException("host-a"))
        val vm = harness.viewModel()
        vm.startPolling()
        waitUntil { vm.ui.value.hostIssues.isNotEmpty() }
        vm.stopPolling()
        return vm
    }

    /** One host whose bridge now reports a foreign identity. */
    fun identityChangedViewModel(reportedHostId: String = "host-rogue"): BoardViewModel {
        val harness = BoardHarness(InstrumentationRegistry.getInstrumentation().targetContext)
        harness.addHost("host-a", alias = "bridge")
        harness.apiFor("host-a").agentsResult =
            Result.failure(HostIdentityChangedException("host-a", reportedHostId))
        val vm = harness.viewModel()
        vm.startPolling()
        waitUntil { vm.ui.value.hostIssues.isNotEmpty() }
        vm.stopPolling()
        return vm
    }

    /** One host offline since startup: stale-marker rendering. */
    fun offlineViewModel(sessions: List<SessionDescriptor>, message: String = "bridge unreachable"): BoardViewModel {
        val harness = BoardHarness(InstrumentationRegistry.getInstrumentation().targetContext)
        harness.addHost("host-a", alias = "bridge")
        val fake = harness.apiFor("host-a")
        fake.agentsResult = Result.success(AgentsResponse(agents = sessions))
        val vm = harness.viewModel()
        vm.startPolling()
        waitUntil { vm.ui.value.hostedSessions.size == sessions.size }
        fake.agentsResult = Result.failure(IOException(message))
        vm.retryHost("host-a")
        waitUntil { vm.ui.value.statuses["host-a"] is dev.scoutr.app.state.HostAvailability.Offline }
        vm.stopPolling()
        return vm
    }
}
