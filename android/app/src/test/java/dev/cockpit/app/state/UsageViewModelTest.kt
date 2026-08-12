package dev.cockpit.app.state

import dev.cockpit.app.data.UsageResponse
import dev.cockpit.app.data.UsageSnapshot
import dev.cockpit.app.data.UsageWindow
import dev.cockpit.app.net.BridgeException
import dev.cockpit.app.net.FakeCockpitApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UsageViewModel: the quota list is a Loadable that never blanked the chart —
 * a failed poll after data keeps Ready and surfaces [UsageUiState.error].
 * The poller's immediate first tick means construction under Robolectric's
 * paused main looper runs the first refresh synchronously, so each test arms
 * the fake before building the ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageViewModelTest {

    private lateinit var fake: FakeCockpitApi

    private val snapshot = UsageSnapshot(
        provider = "openai-codex",
        label = "Codex",
        windows = listOf(UsageWindow(label = "5h", usedPercent = 42.0, amount = 10.0, limitAmount = 100.0, currency = "USD")),
    )

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
    }

    private fun viewModelOf() = UsageViewModel(fake)

    @Test
    fun immediateFirstTickLoadsIntoReady() {
        fake.usageResult = Result.success(UsageResponse(usage = listOf(snapshot)))
        val viewModel = viewModelOf()
        val providers = viewModel.ui.value.providers
        assertTrue("poller's immediate first tick serves the initial load", providers is Loadable.Ready)
        assertEquals(listOf(snapshot), (providers as Loadable.Ready).value)
        assertNull(viewModel.ui.value.error)
    }

    @Test
    fun failedFirstRefreshSurfacesFailureKind() {
        fake.usageResult = Result.failure(BridgeException(503, "bridge unreachable"))
        val viewModel = viewModelOf()
        val providers = viewModel.ui.value.providers
        assertTrue("Failed without data", providers is Loadable.Failed)
        assertEquals(FailureKind.Server, (providers as Loadable.Failed).kind)
        assertEquals("bridge 503: bridge unreachable", providers.reason)
        assertNull("no banner while Failed", viewModel.ui.value.error)
    }

    @Test
    fun unauthorizedFailureMapsToUnauthorizedKind() {
        fake.usageResult = Result.failure(BridgeException(401, "token expired"))
        val providers = viewModelOf().ui.value.providers
        assertEquals(FailureKind.Unauthorized, (providers as Loadable.Failed).kind)
    }

    @Test
    fun failedRefreshAfterDataKeepsChartAndSetsErrorBanner() {
        fake.usageResult = Result.success(UsageResponse(usage = listOf(snapshot)))
        val viewModel = viewModelOf()
        fake.usageResult = Result.failure(BridgeException(503, "bridge unreachable"))
        viewModel.refresh()
        val providers = viewModel.ui.value.providers
        assertTrue("cached chart survives the failed poll", providers is Loadable.Ready)
        assertEquals(listOf(snapshot), (providers as Loadable.Ready).value)
        assertEquals("error banner carries the failure", "bridge 503: bridge unreachable", viewModel.ui.value.error)
    }

    @Test
    fun successfulRetryClearsTheErrorBanner() {
        fake.usageResult = Result.success(UsageResponse(usage = listOf(snapshot)))
        val viewModel = viewModelOf()
        fake.usageResult = Result.failure(BridgeException(503, "bridge unreachable"))
        viewModel.refresh()
        fake.usageResult = Result.success(UsageResponse(usage = listOf(snapshot)))
        viewModel.refresh()
        assertNull("banner clears on the next success", viewModel.ui.value.error)
        assertTrue(viewModel.ui.value.providers is Loadable.Ready)
    }
}