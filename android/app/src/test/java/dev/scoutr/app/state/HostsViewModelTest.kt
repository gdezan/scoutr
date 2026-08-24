package dev.scoutr.app.state

import dev.scoutr.app.data.UpdateHostDisposition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings Hosts management: rename/default/update mutations stay local and
 * ordered, forget requires an update disposition only when the host is a live
 * update source with survivors, and identity-change classification surfaces
 * the reported id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostsViewModelTest {

    private fun twoHostHarness(): BoardHarness {
        val h = jvmBoardHarness()
        h.addHost("host-a", alias = "alpha")
        h.addHost("host-b", alias = "beta")
        return h
    }

    @Test
    fun rowsCarryAliasesBadgesAndStatus() {
        val h = twoHostHarness()
        h.registry.setDefaultHost("host-a")
        h.registry.confirmUpdateHost("host-b")

        val vm = h.hostsViewModel()
        waitForRows(vm)
        val rows = vm.ui.value.rows
        assertEquals(2, rows.size)
        assertTrue(rows.first { it.hostId == "host-a" }.isDefault)
        assertTrue(rows.first { it.hostId == "host-b" }.isUpdateHost)
    }

    @Test
    fun renameIsLocalTrimmedAndImmediate() {
        val h = twoHostHarness()
        val vm = h.hostsViewModel()
        waitForRows(vm)

        vm.rename("host-a", "  alpha prime  ")
        waitForRows(vm)

        val row = vm.ui.value.rows.first { it.hostId == "host-a" }
        assertEquals("alpha prime", row.alias)
        // A registry-only change: no refetch happened.
        assertEquals(0, h.apiFor("host-a").calls.count { it.name == "health" })
    }

    @Test
    fun setDefaultMovesTheBadgeWithoutRefetching() {
        val h = twoHostHarness()
        h.registry.setDefaultHost("host-a")
        val vm = h.hostsViewModel()
        waitForRows(vm)

        vm.setDefault("host-b")
        waitForRows(vm)

        assertTrue(vm.ui.value.rows.first { it.hostId == "host-b" }.isDefault)
        assertFalse(vm.ui.value.rows.first { it.hostId == "host-a" }.isDefault)
    }

    @Test
    fun forgetOfAPlainHostNeedsNoDispositionAndLeavesTheOtherHost() = runBlocking {
        val h = twoHostHarness()
        h.registry.setDefaultHost("host-a")
        val vm = h.hostsViewModel()
        waitForRows(vm)

        assertFalse(vm.forgetRequiresUpdateDisposition("host-b"))
        val registryEmpty = vm.forget("host-b", null)

        assertFalse(registryEmpty)
        waitForRows(vm)
        assertEquals(listOf("host-a"), vm.ui.value.rows.map { it.hostId })
        Unit
    }

    @Test
    fun forgetOfTheEnabledUpdateHostRequiresADisposition() = runBlocking {
        val h = twoHostHarness()
        h.registry.setDefaultHost("host-a")
        h.registry.confirmUpdateHost("host-b")
        val vm = h.hostsViewModel()
        waitForRows(vm)

        assertTrue(vm.forgetRequiresUpdateDisposition("host-b"))
        // The replacement choice list names exactly the surviving hosts.
        assertEquals(setOf("host-a"), vm.otherHostAliases("host-b").keys)
        Unit
    }

    @Test
    fun forgetOfTheLastHostReportsEmptyRegistry() = runBlocking {
        val h = jvmBoardHarness()
        h.addHost("host-a")
        val vm = h.hostsViewModel()
        waitForRows(vm)

        assertFalse(vm.forgetRequiresUpdateDisposition("host-a"))
        val registryEmpty = vm.forget("host-a", null)
        assertTrue(registryEmpty)
        Unit
    }

    @Test
    fun identityChangeSurfacesTheReportedIdOnTheRow() = runBlocking {
        val h = jvmBoardHarness()
        h.addHost("host-a", alias = "alpha")
        h.apiFor("host-a").healthResult =
            Result.failure(dev.scoutr.app.net.HostIdentityChangedException("host-a", "host-rogue"))
        val vm = h.hostsViewModel()
        waitForRows(vm)

        vm.refresh("host-a")
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.reportedHostId("host-a") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals("host-rogue", vm.reportedHostId("host-a"))
        assertFalse(vm.reportedIdIsPaired("host-a"))
        Unit
    }

    private fun waitForRows(vm: HostsViewModel) {
        // viewModelScope runs on the paused Robolectric main looper; pump it.
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.ui.value.rows.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            try {
                shadowLoop()
            } catch (_: IllegalStateException) {
                // loop() throws when the looper quits; ignore and keep waiting.
            }
        }
    }

    private fun shadowLoop() {
        org.robolectric.Robolectric.flushForegroundThreadScheduler()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }
}
