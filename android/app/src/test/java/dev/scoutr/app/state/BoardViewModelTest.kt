package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.HerdrInfo
import dev.scoutr.app.data.LegacyMigrationState
import dev.scoutr.app.data.REQUIRED_SCOUTR_API_FEATURES
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.ScoutrApiInfo
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/** Board swipe-bar Close: posts the control action and surfaces failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardViewModelTest {

    private lateinit var fake: FakeScoutrApi
    private lateinit var viewModel: BoardViewModel

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        val app = RuntimeEnvironment.getApplication()
        val connectionStore = ConnectionStore(app, FakeConnectionCipher())
        // Unsaved at construction: the VM init never connects, so no health
        // probe and no poll loop interfere with the control POST below.
        connectionStore.clear()
        viewModel = legacyBoardViewModel(
            bridge = fake,
            connectionStore = connectionStore,
            initialState = BoardUiState(
                connected = true,
                apiCompatibility = ScoutrApiCompatibility.Compatible,
            ),
        )
        connectionStore.save("http://test-bridge", "test-token")
    }

    private fun controls(): List<Map<String, Any?>> =
        fake.calls.filter { it.name == "controlSession" }.map { it.args }

    @Test
    fun closeAgentPostsControlActionAndStaysQuiet() {
        runBlocking { viewModel.closeAgent("p1") }
        // The response resumption is posted to the (paused) main looper;
        // idle it so the coroutine actually processes the response.
        shadowOf(Looper.getMainLooper()).idle()
        val control = controls().single()
        assertEquals("p1", control["paneId"])
        assertEquals(SessionAction.Close, control["action"])
        assertTrue("no error on success", viewModel.ui.value.error == null)
    }

    @Test
    fun closeAgentSurfacesBridgeError() {
        fake.controlResult = Result.failure(BridgeException(500, "pane not found"))
        runBlocking { viewModel.closeAgent("p1") }
        // The 500 resumption is posted to the (paused) main looper; idle it so
        // closeAgent's catch + reportError actually run.
        shadowOf(Looper.getMainLooper()).idle()
        waitUntil { viewModel.ui.value.error?.contains("pane not found") == true }
        assertTrue(
            "error surfaced, was: ${viewModel.ui.value.error}",
            viewModel.ui.value.error?.contains("pane not found") == true,
        )
    }

    @Test
    fun refreshBoardTracksProgressAndIgnoresDuplicatePulls() {
        val gate = CompletableDeferred<Unit>()
        fake.gates["agents"] = gate

        viewModel.refreshBoard()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("manual refresh should be visible while agents are loading", viewModel.ui.value.isRefreshing)

        viewModel.refreshBoard()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fake.calls.count { it.name == "agents" })

        gate.complete(Unit)
        waitUntil { !viewModel.ui.value.isRefreshing }
        assertTrue("manual refresh should settle after agents load", !viewModel.ui.value.isRefreshing)
    }

    @Test
    fun refreshBoardWaitsForInFlightPoll() {
        val gate = CompletableDeferred<Unit>()
        fake.gates["agents"] = gate
        viewModel.startPolling()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fake.calls.count { it.name == "agents" })

        viewModel.refreshBoard()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.ui.value.isRefreshing)
        assertEquals("manual refresh must not overlap the poll", 1, fake.calls.count { it.name == "agents" })

        gate.complete(Unit)
        waitUntil { fake.calls.count { it.name == "agents" } == 2 && !viewModel.ui.value.isRefreshing }
        viewModel.stopPolling()
    }

    @Test
    fun disconnectStopsPollingAndClearsTheBoard() {
        val connectionStore = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher())
        viewModel.reportError("stale")

        // Park the loop inside its first tick so "did the poll die" is provable
        // without waiting out the 3s interval.
        val gate = CompletableDeferred<Unit>()
        fake.gates["agents"] = gate
        viewModel.startPolling()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fake.calls.count { it.name == "agents" })

        // Forget's order: the pairing goes first, then the VM is told to let go.
        connectionStore.clear()
        viewModel.disconnect()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(BoardUiState(), viewModel.ui.value)
        assertTrue("hasSavedConnection must follow the cleared store", !viewModel.hasSavedConnection)

        // The parked poll was cancelled: releasing it neither repopulates the
        // board nor schedules another round against the cleared pairing.
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BoardUiState(), viewModel.ui.value)
        assertEquals(1, fake.calls.count { it.name == "agents" })
    }

    @Test
    fun incompatibleSavedPairingIsRetainedAndFeaturePollingIsGated() {
        val store = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher()).also {
            it.clear()
            it.save("https://saved-bridge.test", "saved-token")
        }
        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                api = ScoutrApiInfo(protocol = 3),
                herdr = HerdrInfo(connected = true),
            ),
        )

        val incompatibleViewModel = legacyBoardViewModel(fake, store)
        incompatibleViewModel.startPolling()
        waitUntil { incompatibleViewModel.ui.value.apiCompatibility is ScoutrApiCompatibility.Incompatible }

        assertEquals("https://saved-bridge.test", store.saved?.host)
        assertTrue(!incompatibleViewModel.ui.value.connected)
        assertTrue(incompatibleViewModel.ui.value.error!!.contains("bridge protocol 3"))
        assertEquals(0, fake.calls.count { it.name == "agents" })

        incompatibleViewModel.refreshBoard()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("manual refresh must stay gated", 0, fake.calls.count { it.name == "agents" })
        incompatibleViewModel.stopPolling()
    }

    @Test
    fun supportedHealthRetryClearsIncompatibilityAndRestartsPolling() {
        val store = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher()).also {
            it.clear()
            it.save("https://saved-bridge.test", "saved-token")
        }
        fake.healthResult = Result.success(
            HealthResponse(ok = true, api = ScoutrApiInfo(protocol = 3), herdr = HerdrInfo(connected = true)),
        )
        val recoveringViewModel = legacyBoardViewModel(fake, store)
        recoveringViewModel.startPolling()
        waitUntil { recoveringViewModel.ui.value.apiCompatibility is ScoutrApiCompatibility.Incompatible }

        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                herdr = HerdrInfo(connected = true),
            ),
        )
        recoveringViewModel.connect("", "")

        waitUntil {
            recoveringViewModel.ui.value.apiCompatibility == ScoutrApiCompatibility.Compatible &&
                fake.calls.any { it.name == "agents" }
        }
        assertTrue(recoveringViewModel.ui.value.connected)
        recoveringViewModel.stopPolling()
    }

    @Test
    fun transientInitialHealthFailureRetriesTheHandshakeBeforeFeaturePolling() {
        val store = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher()).also {
            it.clear()
            it.save("https://saved-bridge.test", "saved-token")
        }
        var healthCalls = 0
        fake.onCall = { name, _ ->
            if (name == "health") {
                healthCalls += 1
                if (healthCalls == 1) {
                    Result.failure<HealthResponse>(IOException("temporarily offline"))
                } else {
                    Result.success(
                        HealthResponse(
                            ok = true,
                            api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                            herdr = HerdrInfo(connected = true),
                        ),
                    )
                }
            } else {
                null
            }
        }

        val selfHealingViewModel = legacyBoardViewModel(fake, store, pollInterval = 10.milliseconds)
        selfHealingViewModel.startPolling()

        waitUntil {
            healthCalls >= 2 &&
                selfHealingViewModel.ui.value.apiCompatibility == ScoutrApiCompatibility.Compatible &&
                fake.calls.any { it.name == "agents" }
        }
        val firstAgentsCall = fake.calls.indexOfFirst { it.name == "agents" }
        assertTrue(fake.calls.take(firstAgentsCall).count { it.name == "health" } >= 2)
        selfHealingViewModel.stopPolling()
    }

    @Test
    fun pendingLegacyMetadataBlocksRemoteBoardActions() {
        val migrating = BoardViewModel(
            bridge = fake,
            connectionAvailable = { true },
            initialState = BoardUiState(
                connected = true,
                apiCompatibility = ScoutrApiCompatibility.Compatible,
            ),
            pollInterval = 3_000.milliseconds,
            migrationState = MutableStateFlow(LegacyMigrationState.Pending),
        )

        migrating.closeAgent("p1")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, fake.calls.count { it.name == "controlSession" })
        assertEquals("Finishing saved connection migration", migrating.ui.value.error)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("condition did not become true before timeout", condition())
    }
}
