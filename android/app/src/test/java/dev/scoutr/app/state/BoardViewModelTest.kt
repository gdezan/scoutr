package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.LegacyMigrationState
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionLiveAttachment
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostIdentityChangedException
import dev.scoutr.app.net.HostIncompatibleException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Multi-host Board workers: per-host fetch cycles stay independent, blocked
 * hosts are classified into issue cards instead of rows, and one host's
 * failure never delays or clears another's snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardViewModelTest {

    private fun card(
        paneId: String = "w1:p1",
        title: String = "Refactor the board cache",
        updatedAtMs: Double? = 1_000.0,
    ) = SessionDescriptor(
        agentKind = "claude",
        displayName = "scoutr",
        title = title,
        updatedAtMs = updatedAtMs,
        latestActivity = "waiting for you",
        live = SessionLiveAttachment(
            paneId = paneId,
            workspaceId = "w1",
            tabId = "t1",
            status = "working",
        ),
    )

    private fun harness(vararg hostIds: String): BoardHarness {
        val h = jvmBoardHarness()
        hostIds.forEach { h.addHost(it) }
        return h
    }

    private fun controls(fake: dev.scoutr.app.net.FakeScoutrApi) =
        fake.calls.filter { it.name == "controlSession" }.map { it.args }

    @Test
    fun closeAgentPostsControlActionOnTheSessionsOwnHost() {
        val h = harness("host-a", "host-b")
        val vm = h.viewModel()
        vm.startPolling()
        val profile = vm.ui.value.profileKeyOf("host-a")!!

        runBlocking { vm.closeAgent(profile, "p1") }
        shadowOf(Looper.getMainLooper()).idle()

        // The action lands only on the named host's bridge.
        assertEquals(0, h.apiFor("host-b").calls.count { it.name == "controlSession" })
        val control = controls(h.apiFor("host-a")).single()
        assertEquals("p1", control["paneId"])
        assertEquals(SessionAction.Close, control["action"])
        assertNull("no error on success", vm.ui.value.transientError)
        vm.stopPolling()
    }

    @Test
    fun closeAgentSurfacesBridgeError() {
        val h = harness("host-a")
        val fake = h.apiFor("host-a")
        fake.controlResult = Result.failure(BridgeException(500, "pane not found"))
        val vm = h.viewModel()
        vm.startPolling()
        val profile = vm.ui.value.profileKeyOf("host-a")!!

        runBlocking { vm.closeAgent(profile, "p1") }
        shadowOf(Looper.getMainLooper()).idle()
        BoardTestLoop.waitUntil { vm.ui.value.transientError?.contains("pane not found") == true }
        vm.stopPolling()
    }

    @Test
    fun refreshTracksProgressIgnoresDuplicatePullsAndWaitsForThePollCycle() {
        val h = harness("host-a")
        val gate = CompletableDeferred<Unit>()
        h.apiFor("host-a").gates["agents"] = gate
        val vm = h.viewModel()
        vm.startPolling()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, h.apiFor("host-a").calls.count { it.name == "agents" })

        vm.refreshBoard()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("manual refresh should be visible while agents are loading", vm.ui.value.isRefreshing)

        // The duplicate pull is dropped, and the queued cycle cannot start
        // while the scheduled one still holds the per-host mutex.
        vm.refreshBoard()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, h.apiFor("host-a").calls.count { it.name == "agents" })

        gate.complete(Unit)
        BoardTestLoop.waitUntil {
            h.apiFor("host-a").calls.count { it.name == "agents" } == 2 &&
                !vm.ui.value.isRefreshing
        }
        vm.stopPolling()
    }

    @Test
    fun forgettingAHostCancelsItsWorkerAndDropsItsRowsWithoutTouchingTheOtherHost() {
        val h = harness("host-a", "host-b")
        h.apiFor("host-a").agentsResult = Result.success(AgentsResponse(agents = listOf(card())))
        h.apiFor("host-b").agentsResult = Result.success(AgentsResponse(agents = listOf(card(paneId = "w2:p9"))))
        val vm = h.viewModel()
        vm.startPolling()
        BoardTestLoop.waitUntil { vm.ui.value.sessionsFor("host-a").isNotEmpty() }
        BoardTestLoop.waitUntil { vm.ui.value.sessionsFor("host-b").isNotEmpty() }
        assertEquals("both hosts merged under All", 2, vm.ui.value.board.total)

        // Park host-b's next cycle so "did its worker die" is provable without
        // waiting out the interval, then forget it.
        val gate = CompletableDeferred<Unit>()
        h.apiFor("host-b").gates["agents"] = gate
        h.forgetHost("host-b")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("forgotten host leaves the merged rows", !vm.ui.value.hostBoards.containsKey("host-b"))
        assertEquals(1, vm.ui.value.board.total)

        // Releasing the parked call neither repopulates the board nor counts
        // as a new fetch for the removed worker.
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!vm.ui.value.hostBoards.containsKey("host-b"))
        vm.stopPolling()
    }

    @Test
    fun incompatibleHostIsClassifiedIntoAnIssueCardAndExcludedFromRows() {
        val h = harness("host-a", "host-b")
        h.apiFor("host-a").agentsResult = Result.success(AgentsResponse(agents = listOf(card())))
        h.apiFor("host-b").agentsResult =
            Result.failure(HostIncompatibleException("host-b"))
        val vm = h.viewModel()
        vm.startPolling()
        BoardTestLoop.waitUntil { vm.ui.value.hostIssues.isNotEmpty() }

        val issue = vm.ui.value.hostIssues.single()
        assertEquals("host-b", issue.hostId)
        assertTrue(vm.ui.value.statuses["host-b"] is HostAvailability.Incompatible)
        // The healthy host keeps rendering; the blocked one contributes no rows.
        BoardTestLoop.waitUntil { vm.ui.value.sessionsFor("host-a").isNotEmpty() }
        assertEquals(listOf("w1:p1"), vm.ui.value.board.sessions.mapNotNull { it.live?.paneId })
        vm.stopPolling()
    }

    @Test
    fun identityChangedHostSurfacesTheReportedIdAsAnIssue() {
        val h = harness("host-a")
        h.apiFor("host-a").agentsResult =
            Result.failure(HostIdentityChangedException("host-a", "host-rogue"))
        val vm = h.viewModel()
        vm.startPolling()
        BoardTestLoop.waitUntil { vm.ui.value.hostIssues.isNotEmpty() }

        val issue = vm.ui.value.hostIssues.single()
        assertEquals("host-rogue", issue.reportedHostId)
        assertEquals(
            HostAvailability.IdentityChanged("host-rogue"),
            vm.ui.value.statuses["host-a"],
        )
        assertTrue("a fully blocked board renders no rows", vm.ui.value.board.total == 0)
        vm.stopPolling()
    }

    @Test
    fun offlineFailureKeepsLastSnapshotAndMarksStale() {
        val h = harness("host-a")
        h.apiFor("host-a").agentsResult = Result.success(AgentsResponse(agents = listOf(card())))
        val vm = h.viewModel()
        vm.startPolling()
        BoardTestLoop.waitUntil { vm.ui.value.sessionsFor("host-a").isNotEmpty() }

        h.apiFor("host-a").agentsResult = Result.failure(IOException("bridge unreachable"))
        vm.retryHost("host-a")
        BoardTestLoop.waitUntil { vm.ui.value.statuses["host-a"] is HostAvailability.Offline }

        // The deliberately-kept snapshot survives so rows show a stale marker
        // instead of vanishing mid-read.
        assertTrue(vm.ui.value.sessionsFor("host-a").isNotEmpty())
        assertEquals("bridge unreachable", (vm.ui.value.statuses["host-a"] as HostAvailability.Offline).message)
        vm.stopPolling()
    }

    @Test
    fun mergeOrdersGloballyByRecencyAndFilterNarrowsToOneHost() {
        val h = harness("host-a", "host-b")
        h.apiFor("host-a").agentsResult = Result.success(
            AgentsResponse(agents = listOf(card(paneId = "a:old", updatedAtMs = 100.0))),
        )
        h.apiFor("host-b").agentsResult = Result.success(
            AgentsResponse(agents = listOf(card(paneId = "b:new", updatedAtMs = 900.0))),
        )
        val vm = h.viewModel()
        vm.startPolling()
        BoardTestLoop.waitUntil { vm.ui.value.board.total == 2 }

        assertEquals(
            "newest row leads regardless of host",
            listOf("b:new", "a:old"),
            vm.ui.value.hostedSessions.mapNotNull { it.session.live?.paneId },
        )

        vm.selectFilter("host-a")
        BoardTestLoop.waitUntil { vm.ui.value.filter == "host-a" }
        assertEquals(
            listOf("a:old"),
            vm.ui.value.hostedSessions.mapNotNull { it.session.live?.paneId },
        )
        vm.stopPolling()
    }

    @Test
    fun pendingLegacyMetadataBlocksRemoteBoardActions() {
        val h = harness("host-a")
        val migrating = h.viewModel(
            migrationState = MutableStateFlow(LegacyMigrationState.Pending),
        )
        migrating.startPolling()
        val profile = migrating.ui.value.profileKeyOf("host-a")!!

        migrating.closeAgent(profile, "p1")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, h.apiFor("host-a").calls.count { it.name == "controlSession" })
        assertEquals("Finishing saved connection migration", migrating.ui.value.transientError)
        migrating.stopPolling()
    }
}
