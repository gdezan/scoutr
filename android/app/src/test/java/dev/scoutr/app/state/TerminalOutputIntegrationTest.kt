package dev.scoutr.app.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.SnapshotResponse
import dev.scoutr.app.data.TerminalCapabilityInfo
import dev.scoutr.app.data.TerminalHealthInfo
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.FakeTerminalTransport
import dev.scoutr.app.net.PerformanceCounters
import dev.scoutr.app.net.TopologyFeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The ViewModel↔pump seam: transport frames in, batched emulator work out.
 *
 * The terminal dispatcher is a [StandardTestDispatcher] the test advances by hand, so frames
 * delivered between advances model a burst arriving faster than the emulator drains it — the case
 * batching exists for. Under `Dispatchers.Unconfined` (what `TerminalViewModelTest` uses) every
 * frame would drain immediately and no coalescing could be observed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalOutputIntegrationTest {

    private lateinit var api: FakeScoutrApi
    private lateinit var transport: FakeTerminalTransport
    private lateinit var connectionStore: ConnectionStore
    private lateinit var preferencesStore: TerminalPreferencesStore
    private lateinit var counters: PerformanceCounters
    private val scheduler = TestCoroutineScheduler()

    private val feed = object : TopologyFeed {
        override fun start(): Boolean = true
        override fun stop() {}
    }
    private val feedFactory = TopologyFeed.Factory { feed }

    @Before
    fun setUp() {
        api = FakeScoutrApi()
        transport = FakeTerminalTransport()
        counters = PerformanceCounters()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("scoutr_connection", Context.MODE_PRIVATE).edit()
            .putString("host", "http://bridge")
            .putString("token", "test-token")
            .apply()
        connectionStore = ConnectionStore(app, FakeConnectionCipher())
        preferencesStore = TerminalPreferencesStore(app)
        api.healthResult = Result.success(
            HealthResponse(ok = true, terminal = TerminalHealthInfo(TerminalCapabilityInfo(status = "supported", protocol = 1))),
        )
        api.snapshotResult = Result.success(snapshotWithPane("w1:p1"))
    }

    private fun snapshotWithPane(paneId: String): SnapshotResponse = SnapshotResponse(
        ok = true,
        snapshot = buildJsonObject {
            put("focused_pane_id", paneId)
            putJsonArray("panes") {
                add(
                    buildJsonObject {
                        put("pane_id", paneId)
                        put("workspace_id", "w1")
                        put("tab_id", "t1")
                        put("focused", true)
                    },
                )
            }
        },
    )

    private fun vm(): TerminalViewModel = TerminalViewModel(
        api = api,
        transport = transport,
        feedFactory = feedFactory,
        connectionStore = connectionStore,
        preferencesStore = preferencesStore,
        injectedIo = StandardTestDispatcher(scheduler),
        performanceCounters = counters,
    )

    private fun transcript(vm: TerminalViewModel): String =
        vm.session.emulator!!.screen.getTranscriptTextWithoutJoinedLines()

    @Test
    fun aThousandFramesBecomeTheExactByteStreamInFarFewerBatches() {
        val vm = vm()
        vm.start()
        val socket = transport.lastSocket
        socket.ready(generation = 1)

        val lines = (1..1_000).map { "line $it" }
        // Every frame arrives before the consumer runs: one burst, as on a fast LAN.
        lines.forEach { socket.bytes("$it\r\n".toByteArray()) }
        scheduler.advanceUntilIdle()

        // 1,000 rows through a 24-row screen with a 10k transcript: nothing scrolls out.
        assertEquals(lines, transcript(vm).split("\n").filter { it.isNotEmpty() })

        val terminal = counters.snapshot().terminal
        assertTrue(
            "expected coalescing, got ${terminal.outputBatches} batches for 1000 frames",
            terminal.outputBatches < 100,
        )
        assertEquals(terminal.outputBatches, terminal.emulatorAppends)
        assertTrue(terminal.maxBatchBytes <= 64L * 1024)
        assertEquals(0L, terminal.queueOverflows)
        // One screen update per batch plus the generation reset's own notify.
        assertEquals(terminal.outputBatches + 1, terminal.screenUpdates)
    }

    @Test
    fun staleSocketCannotEnqueueIntoTheReplacementGeneration() {
        val vm = vm()
        vm.start()
        val old = transport.lastSocket
        old.ready(generation = 1)
        old.bytes("stale ".toByteArray())

        // A pane switch replaces the socket before the old bytes were drained.
        vm.attach("w1:p1")
        val fresh = transport.lastSocket
        fresh.ready(generation = 2)
        fresh.bytes("fresh".toByteArray())
        // The old socket keeps talking after being replaced.
        old.bytes("late".toByteArray())
        scheduler.advanceUntilIdle()

        assertEquals("fresh", transcript(vm).trimEnd())
    }

    @Test
    fun reconnectStartsWithACleanOutputQueue() {
        val vm = vm()
        vm.start()
        val first = transport.lastSocket
        first.ready(generation = 1)
        first.bytes("never rendered".toByteArray())
        // Transport failure before the queue drained; the route reconnects.
        first.failure()
        assertTrue(vm.ui.value.connection is TerminalConnectionState.Reconnecting)

        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            5,
            java.util.concurrent.TimeUnit.SECONDS,
        )
        val second = transport.lastSocket
        assertFalse(second === first)
        second.ready(generation = 2)
        second.bytes("after reconnect".toByteArray())
        scheduler.advanceUntilIdle()

        assertEquals("after reconnect", transcript(vm).trimEnd())
    }

    @Test
    fun releaseAndClearStopTheConsumer() {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, TestVmFactory()).get(TerminalViewModel::class.java)
        vm.start()
        val socket = transport.lastSocket
        socket.ready(generation = 1)
        socket.bytes("visible".toByteArray())
        scheduler.advanceUntilIdle()
        assertEquals("visible", transcript(vm).trimEnd())

        vm.release()
        socket.bytes(" after release".toByteArray())
        scheduler.advanceUntilIdle()
        assertEquals("visible", transcript(vm).trimEnd())

        val batchesBefore = counters.snapshot().terminal.outputBatches
        store.clear()
        socket.bytes(" after clear".toByteArray())
        scheduler.advanceUntilIdle()
        assertEquals(batchesBefore, counters.snapshot().terminal.outputBatches)
    }

    @Test
    fun repeatedDeliveryFailuresStopRetryingInsteadOfLooping() {
        val vm = vm()
        vm.start()

        // A delivery failure that is deterministic in the pane's content would otherwise reconnect
        // forever: every cycle reaches Ready, which resets the backoff. Each cycle here renders a
        // batch successfully first — the bridge's replay frame always does — so a cap that reset
        // on a rendered batch would never trip.
        repeat(TerminalViewModel.MAX_DELIVERY_FAILURES + 1) {
            val socket = transport.lastSocket
            socket.ready(generation = 1L + it)
            socket.bytes("replay".toByteArray())
            scheduler.advanceUntilIdle()

            // Then the poison: make this append throw.
            vm.session.callbacks.onScreenUpdated = { throw IllegalStateException("render failed") }
            socket.bytes("boom".toByteArray())
            scheduler.advanceUntilIdle()
            vm.session.callbacks.onScreenUpdated = {}

            org.robolectric.shadows.ShadowLooper.idleMainLooper(
                2,
                java.util.concurrent.TimeUnit.SECONDS,
            )
        }

        val connection = vm.ui.value.connection
        assertTrue("expected a settled failure, got $connection", connection is TerminalConnectionState.Failed)
        assertFalse((connection as TerminalConnectionState.Failed).retryable)
        val opensAtGiveUp = transport.openedRequests.size

        // No further reconnect is scheduled once it has given up.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(opensAtGiveUp, transport.openedRequests.size)
        assertTrue(counters.snapshot().terminal.deliveryFailures > 0)
    }

    private inner class TestVmFactory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = vm() as T
    }
}
