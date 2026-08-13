package dev.cockpit.app.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.HealthResponse
import dev.cockpit.app.data.SnapshotResponse
import dev.cockpit.app.data.TerminalCapabilityInfo
import dev.cockpit.app.data.TerminalPreferencesStore
import dev.cockpit.app.net.FakeCockpitApi
import dev.cockpit.app.net.FakeTerminalTransport
import dev.cockpit.app.net.TerminalIntent
import dev.cockpit.app.net.TerminalMode
import dev.cockpit.app.net.TerminalProtocol
import dev.cockpit.app.net.TopologyFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * TerminalViewModel lifecycle with a FakeTerminalTransport: capability gate,
 * socket tagging, input gating, closed/error policy, reconnect backoff, and
 * release-vs-onCleared teardown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalViewModelTest {

    private lateinit var api: FakeCockpitApi
    private lateinit var transport: FakeTerminalTransport
    private lateinit var connectionStore: ConnectionStore
    private lateinit var preferencesStore: TerminalPreferencesStore

    private val feed = object : TopologyFeed {
        override fun start(): Boolean = true
        override fun stop() {}
    }
    private val feedFactory = TopologyFeed.Factory { feed }

    @Before
    fun setUp() {
        api = FakeCockpitApi()
        transport = FakeTerminalTransport()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cockpit_connection", Context.MODE_PRIVATE).edit()
            .putString("host", "http://bridge")
            .putString("token", "test-token")
            .apply()
        connectionStore = ConnectionStore(app)
        preferencesStore = TerminalPreferencesStore(app)
        api.healthResult = Result.success(
            HealthResponse(
                ok = true,
                terminal = TerminalCapabilityInfo(status = "supported", protocol = 1),
            ),
        )
        api.snapshotResult = Result.success(snapshotWithPanes("w1:p1", focused = "w1:p1"))
    }

    @After
    fun tearDown() {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    private fun vm(initialPaneId: String? = null): TerminalViewModel = TerminalViewModel(
        api = api,
        transport = transport,
        feedFactory = feedFactory,
        connectionStore = connectionStore,
        preferencesStore = preferencesStore,
        initialPaneId = initialPaneId,
        injectedIo = Dispatchers.Unconfined,
    )

    private fun snapshotWithPanes(vararg panes: String, focused: String? = null): SnapshotResponse {
        val paneJson = panes.map { id ->
            buildJsonObject {
                put("pane_id", id)
                put("workspace_id", "w1")
                put("tab_id", "t1")
                put("focused", id == focused)
            }
        }
        return SnapshotResponse(
            ok = true,
            snapshot = buildJsonObject {
                put("focused_pane_id", focused)
                putJsonArray("panes") {
                    paneJson.forEach { add(it) }
                }
            },
        )
    }

    // --- Capability gate & start ---

    @Test
    fun start_without_connection_is_non_retryable_failure() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("cockpit_connection", Context.MODE_PRIVATE).edit().clear().apply()
        val vm = vm()
        vm.start()
        assertEquals(TerminalConnectionState.Failed("No connection configured", retryable = false), vm.ui.value.connection)
    }

    @Test
    fun unsupported_capability_fails_fast() {
        api.healthResult = Result.success(
            HealthResponse(ok = true, terminal = TerminalCapabilityInfo(status = "unsupported", reason = "needs herdr 0.9")),
        )
        val vm = vm()
        vm.start()
        val state = vm.ui.value.connection
        assertTrue(state is TerminalConnectionState.Unsupported)
        assertEquals("needs herdr 0.9", (state as TerminalConnectionState.Unsupported).explanation)
        assertEquals(0, transport.openedRequests.size)
    }

    @Test
    fun health_failure_is_retryable_and_reattempts() {
        api.healthResult = Result.failure(java.io.IOException("bridge down"))
        val vm = vm()
        vm.start()
        assertTrue(vm.ui.value.connection is TerminalConnectionState.Failed)
        assertTrue((vm.ui.value.connection as TerminalConnectionState.Failed).retryable)

        // Bridge comes back; the scheduled health retry re-runs the gate.
        api.healthResult = Result.success(
            HealthResponse(ok = true, terminal = TerminalCapabilityInfo(status = "supported")),
        )
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertTrue(vm.ui.value.connection is TerminalConnectionState.Ready ||
            vm.ui.value.connection is TerminalConnectionState.Connecting)
    }

    // --- Happy path: ready, bytes, input gating ---

    @Test
    fun start_attaches_resolves_pane_and_reaches_ready() {
        val vm = vm()
        vm.start()
        assertEquals(TerminalConnectionState.Connecting, vm.ui.value.connection)
        val request = transport.openedRequests.single()
        assertEquals("w1:p1", request.paneId)
        assertEquals(TerminalIntent.AUTO, request.intent)
        assertEquals(80, request.cols)

        transport.lastSocket.ready(generation = 1)
        assertEquals(TerminalConnectionState.Ready(generation = 1, writable = true), vm.ui.value.connection)
        assertEquals("w1:p1", vm.ui.value.paneId)
    }

    @Test
    fun ready_resets_emulator_and_bytes_feed_transcript() {
        val vm = vm()
        vm.start()
        val session = vm.session
        transport.lastSocket.ready(generation = 1)
        val emulatorBefore = session.emulator
        assertNotNull(emulatorBefore)
        transport.lastSocket.bytes("hello".toByteArray())
        assertEquals(
            "hello",
            session.emulator!!.screen.getTranscriptTextWithoutJoinedLines().trimEnd(),
        )
    }

    @Test
    fun input_flows_only_when_writable() {
        val vm = vm()
        vm.start()
        // Before ready: no input.
        transport.lastSocket.ready(generation = 1)
        transport.lastSocket.writable = false
        vm.session.inputSink?.invoke("x".toByteArray())
        assertEquals(0, transport.lastSocket.inputFrames.size)

        transport.lastSocket.writable = true
        vm.session.inputSink?.invoke("y".toByteArray())
        assertEquals(1, transport.lastSocket.inputFrames.size)
        assertEquals("y", transport.lastSocket.inputFrames.single().toString(Charsets.UTF_8))
    }

    @Test
    fun observe_mode_reports_can_takeover_and_gates_input() {
        val vm = vm()
        vm.start()
        transport.lastSocket.ready(generation = 1, mode = TerminalMode.OBSERVE)
        assertFalse(vm.ui.value.connection.let { it is TerminalConnectionState.Ready && it.writable })
        transport.lastSocket.ownership(canTakeover = true)
        assertTrue(vm.ui.value.canTakeover)
    }

    // --- Closed/error policy ---

    @Test
    fun pane_closed_settles_closed_with_notice_and_re_attaches() {
        api.snapshotResult = Result.success(snapshotWithPanes("w1:p1", focused = "w1:p1"))
        val vm = vm()
        vm.start()
        transport.lastSocket.ready(generation = 1)
        transport.lastSocket.closed(TerminalProtocol.CLOSED_PANE_CLOSED)
        // refreshNow re-attaches synchronously because a pane remains; the
        // notice stays visible until the next ready.
        assertTrue(vm.ui.value.connection is TerminalConnectionState.Connecting)
        assertTrue(vm.ui.value.paneClosedNotice)
        transport.lastSocket.ready(generation = 2)
        assertEquals(TerminalConnectionState.Ready(generation = 2, writable = true), vm.ui.value.connection)
        assertFalse(vm.ui.value.paneClosedNotice)
    }

    @Test
    fun released_settles_closed_without_notice() {
        val vm = vm()
        vm.start()
        transport.lastSocket.ready(generation = 1)
        vm.release()
        assertEquals(TerminalConnectionState.Closed, vm.ui.value.connection)
        assertTrue(transport.lastSocket.released)
        assertFalse(transport.lastSocket.cancelled)
    }

    @Test
    fun protocol_error_is_non_retryable() {
        val vm = vm()
        vm.start()
        transport.lastSocket.error(TerminalProtocol.ERROR_PROTOCOL, message = "bad frame", retryable = false)
        assertTrue(vm.ui.value.connection is TerminalConnectionState.Failed)
        assertFalse((vm.ui.value.connection as TerminalConnectionState.Failed).retryable)
        // No reconnect scheduled: openSocket must not run again.
        ShadowLooper.idleMainLooper()
        assertEquals(1, transport.openedRequests.size)
    }

    @Test
    fun retryable_error_reconnects_after_backoff() {
        val vm = vm()
        vm.start()
        transport.lastSocket.ready(generation = 1)
        transport.lastSocket.error("child_failed", message = "child died", retryable = true)
        assertTrue(vm.ui.value.connection is TerminalConnectionState.Reconnecting)
        assertEquals(1L, (vm.ui.value.connection as TerminalConnectionState.Reconnecting).frozenGeneration)
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertEquals(2, transport.openedRequests.size)
        transport.lastSocket.ready(generation = 2)
        assertEquals(TerminalConnectionState.Ready(generation = 2, writable = true), vm.ui.value.connection)
    }

    @Test
    fun stale_socket_callbacks_are_ignored_after_switch() {
        val vm = vm()
        vm.start()
        transport.lastSocket.ready(generation = 1)
        vm.attach("w1:p1") // releases old, opens new
        val old = transport.sockets[0]
        val fresh = transport.lastSocket
        assertNotEquals(old, fresh)
        assertTrue(old.released)
        assertEquals(TerminalConnectionState.Connecting, vm.ui.value.connection)

        // Late callbacks from the old socket must not move the state.
        old.ready(generation = 99)
        assertEquals(TerminalConnectionState.Connecting, vm.ui.value.connection)
        fresh.ready(generation = 2)
        assertEquals(TerminalConnectionState.Ready(generation = 2, writable = true), vm.ui.value.connection)
    }

    // --- Route pane targeting (slice 7 navigation) ---

    @Test
    fun route_pane_outranks_focused_and_saved_pane() {
        api.snapshotResult = Result.success(snapshotWithPanes("w1:p1", "w1:p2", focused = "w1:p1"))
        // The Chat overflow's "Open terminal" asks for this session's pane, not
        // the one herdr happens to focus.
        val vm = vm(initialPaneId = "w1:p2")
        vm.start()
        assertEquals("w1:p2", transport.openedRequests.single().paneId)
        assertEquals("w1:p2", vm.ui.value.paneId)
    }

    @Test
    fun unknown_route_pane_falls_back_to_normal_resolution() {
        api.snapshotResult = Result.success(snapshotWithPanes("w1:p1", focused = "w1:p1"))
        val vm = vm(initialPaneId = "w9:gone")
        vm.start()
        assertEquals("w1:p1", transport.openedRequests.single().paneId)
    }

    @Test
    fun route_pane_is_consumed_once_and_does_not_steer_reconnect() {
        api.snapshotResult = Result.success(snapshotWithPanes("w1:p1", "w1:p2", focused = "w1:p1"))
        val vm = vm(initialPaneId = "w1:p2")
        vm.start()
        transport.lastSocket.ready(generation = 1)

        // The user picks another pane in the hierarchy drawer.
        vm.attach("w1:p1")
        transport.lastSocket.ready(generation = 2)
        assertEquals("w1:p1", vm.ui.value.paneId)

        // A transport drop must reconnect to the attached pane, not bounce back
        // to the pane the route was originally opened for.
        transport.lastSocket.failure()
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
        assertEquals("w1:p1", transport.openedRequests.last().paneId)
    }

    // --- Ownership ---

    @Test
    fun dismiss_takeover_clears_the_offer_without_touching_the_socket() {
        val vm = vm()
        vm.start()
        transport.lastSocket.ready(generation = 1, mode = TerminalMode.OBSERVE)
        transport.lastSocket.ownership(canTakeover = true)
        assertTrue(vm.ui.value.canTakeover)

        val socket = transport.lastSocket
        vm.dismissTakeover()
        assertFalse(vm.ui.value.canTakeover)
        assertFalse(socket.released)
        assertFalse(socket.cancelled)
        // Still an observer on the same socket.
        assertEquals(1, transport.openedRequests.size)
        assertEquals(TerminalConnectionState.Ready(generation = 1, writable = false), vm.ui.value.connection)

        // A later ownership message can offer again.
        socket.ownership(canTakeover = true)
        assertTrue(vm.ui.value.canTakeover)
    }

    @Test
    fun takeover_reopens_the_same_pane_with_takeover_intent() {
        val vm = vm()
        vm.start()
        transport.lastSocket.ready(generation = 1, mode = TerminalMode.OBSERVE)
        transport.lastSocket.ownership(canTakeover = true)

        vm.takeover()
        val request = transport.openedRequests.last()
        assertEquals("w1:p1", request.paneId)
        assertEquals(TerminalIntent.TAKEOVER, request.intent)
        assertFalse(vm.ui.value.canTakeover)
    }

    @Test
    fun release_sends_release_and_settles_closed() {
        val vm = vm()
        vm.start()
        val socket = transport.lastSocket
        socket.ready(generation = 1)

        vm.release()
        assertTrue(socket.released)
        assertFalse(socket.cancelled)
        assertEquals(TerminalConnectionState.Closed, vm.ui.value.connection)
    }

    // --- Teardown ---

    @Test
    fun onCleared_cancels_without_release() {
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, TestVmFactory()).get(TerminalViewModel::class.java)
        vm.start()
        transport.lastSocket.ready(generation = 1)
        store.clear() // ViewModelStore-style teardown
        assertTrue(transport.lastSocket.cancelled)
        assertFalse(transport.lastSocket.released)
    }

    private inner class TestVmFactory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TerminalViewModel(
                api = api,
                transport = transport,
                feedFactory = feedFactory,
                connectionStore = connectionStore,
                preferencesStore = preferencesStore,
                injectedIo = Dispatchers.Unconfined,
            ) as T
    }
}
