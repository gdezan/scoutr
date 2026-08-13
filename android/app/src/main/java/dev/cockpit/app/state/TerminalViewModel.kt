package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.SnapshotResponse
import dev.cockpit.app.data.TerminalPreferencesStore
import dev.cockpit.app.data.TerminalSnapshot
import dev.cockpit.app.data.toTerminalSnapshot
import dev.cockpit.app.net.CockpitApi
import dev.cockpit.app.net.TerminalMode
import dev.cockpit.app.net.TerminalOpenRequest
import dev.cockpit.app.net.TerminalIntent
import dev.cockpit.app.net.TerminalProtocol
import dev.cockpit.app.net.TerminalServerMessage
import dev.cockpit.app.net.TerminalSocket
import dev.cockpit.app.net.TerminalTransport
import dev.cockpit.app.net.TerminalTransportListener
import dev.cockpit.app.net.TopologyFeed
import dev.cockpit.app.terminal.RemoteTerminalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.Executors

/**
 * Slice 6: terminal state for exactly one active pane (plan
 * "TerminalViewModel / state"). No full-screen UI yet — this is the
 * attach/reconnect/detach lifecycle the future TerminalView binds to.
 *
 * Contract highlights:
 *  - exactly one active socket at a time; callbacks are tagged by socket
 *    instance and ignored once the socket is no longer current (generation
 *    safety at the client boundary; the bridge emits exactly one generation
 *    per socket);
 *  - no input queue: bytes produced by the terminal are forwarded only while
 *    Ready(writable=true) and dropped otherwise (no replay after reconnect);
 *  - every new generation resets the emulator ([RemoteTerminalSession.resetForGeneration])
 *    before any byte of that generation is fed; bytes are fed on the
 *    injectable single-thread [terminalIo] dispatcher so session access is
 *    serialized off the main thread;
 *  - reconnect uses bounded exponential backoff (500 ms base, ×2, 8 s
 *    ceiling, ±20% jitter) while the route is started; onCleared cancels the
 *    socket WITHOUT release (bridge grace) whereas [release] sends `release`.
 */
class TerminalViewModel(
    private val api: CockpitApi,
    private val transport: TerminalTransport,
    private val feedFactory: TopologyFeed.Factory,
    private val connectionStore: ConnectionStore,
    private val preferencesStore: TerminalPreferencesStore,
    injectedIo: CoroutineDispatcher? = null,
) : ViewModel() {

    private val _ui = MutableStateFlow(TerminalUiState())
    val ui: StateFlow<TerminalUiState> = _ui.asStateFlow()

    /** Emulator state for the active pane. Owned here, mutated only on [terminalIo]. */
    internal val session: RemoteTerminalSession = RemoteTerminalSession(transcriptRows = TRANSCRIPT_ROWS)

    private val terminalIo: CoroutineDispatcher
    private val ownsIo: Boolean
    private lateinit var terminalScope: CoroutineScope

    init {
        if (injectedIo != null) {
            terminalIo = injectedIo
            ownsIo = false
        } else {
            terminalIo = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "cockpit-terminal-io").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            ownsIo = true
        }
        terminalScope = CoroutineScope(SupervisorJob() + terminalIo)
        session.inputSink = { bytes -> forwardInput(bytes) }
        session.callbacks.onTitleChanged = { _ui.update { it.copy(title = session.getTitle()) } }
    }

    @Volatile private var started = false
    @Volatile private var activeSocket: TerminalSocket? = null
    private var reconnectJob: Job? = null
    private var snapshotDebounce: Job? = null
    private var reconnectAttempt = 0
    private var feed: TopologyFeed? = null

    /** The current generation, used to gate input (thread-safe via StateFlow). */
    private val currentConnection: TerminalConnectionState get() = _ui.value.connection

    /** Route entry: capability gate, then snapshot + attach. Idempotent. */
    fun start() {
        if (started) return
        val saved = connectionStore.saved ?: run {
            _ui.update { it.copy(connection = TerminalConnectionState.Failed("No connection configured", retryable = false)) }
            return
        }
        started = true
        feed = feedFactory.create(feedListener)
        feed?.start()
        viewModelScope.launch { checkHealthAndAttach() }
    }

    private val feedListener = object : TopologyFeed.Listener {
        override fun onTopologyEvent(kind: String) = scheduleSnapshotRefresh()

        override fun onSnapshot() = scheduleSnapshotRefresh()

        override fun onFeedFailure(error: java.io.IOException) {
            _ui.update { it.copy(snapshotError = error.message ?: "terminal feed lost") }
        }
    }

    private suspend fun checkHealthAndAttach() {
        val saved = connectionStore.saved ?: return
        try {
            val health = api.health(host = saved.host, token = saved.token)
            if (health.terminal?.isSupported != true) {
                _ui.update {
                    it.copy(
                        connection = TerminalConnectionState.Unsupported(
                            health.terminal?.reason ?: "this bridge does not support the terminal route",
                        ),
                    )
                }
                return
            }
            refreshSnapshot()
            openSocket()
        } catch (e: Exception) {
            _ui.update {
                it.copy(connection = TerminalConnectionState.Failed(e.message ?: "bridge unreachable", retryable = true))
            }
            scheduleHealthRetry()
        }
    }

    /** Retry the whole capability gate + attach sequence after a health failure. */
    private fun scheduleHealthRetry() {
        if (!started) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(backoffDelayMs(reconnectAttempt++))
            if (started && currentConnection is TerminalConnectionState.Failed) {
                checkHealthAndAttach()
            }
        }
    }

    /**
     * Explicit user attach to a specific pane (hierarchy drawer, slice 7).
     * The previous stream is released explicitly — a switch is a user
     * decision, not a transport fault.
     */
    fun attach(paneId: String) {
        if (!started) return
        val saved = connectionStore.saved ?: return
        reconnectJob?.cancel()
        activeSocket?.release()
        activeSocket = null
        preferencesStore.forConnection(saved.host, saved.token).lastPaneId = paneId
        _ui.update { it.copy(paneClosedNotice = false) }
        openSocket(paneId)
    }

    /** Explicit detach: send `release` and settle to [TerminalConnectionState.Closed]. */
    fun release() {
        reconnectJob?.cancel()
        activeSocket?.release()
        activeSocket = null
        _ui.update { it.copy(connection = TerminalConnectionState.Closed) }
    }

    /** Controller resize; a no-op when not writable (observe/connecting). */
    fun resize(cols: Int, rows: Int) {
        val socket = activeSocket ?: return
        if (currentConnection !is TerminalConnectionState.Ready) return
        socket.resize(cols, rows)
        terminalScope.launch { session.updateSize(cols, rows, NOMINAL_CELL_WIDTH, NOMINAL_CELL_HEIGHT) }
    }

    /** Force a snapshot refresh (e.g. hierarchy action result). Debounced callers use [scheduleSnapshotRefresh]. */
    fun refreshNow() {
        snapshotDebounce?.cancel()
        viewModelScope.launch {
            refreshSnapshot()
            // After a pane-closed notice, re-attach to the resolved pane if any pane remains.
            val state = _ui.value
            if (state.paneClosedNotice &&
                state.connection == TerminalConnectionState.Closed &&
                state.snapshot?.panes?.isNotEmpty() == true
            ) {
                openSocket()
            }
        }
    }

    fun scheduleSnapshotRefresh() {
        snapshotDebounce?.cancel()
        snapshotDebounce = viewModelScope.launch {
            delay(SNAPSHOT_DEBOUNCE_MS)
            refreshSnapshot()
        }
    }

    private suspend fun refreshSnapshot() {
        val response: SnapshotResponse = try {
            api.snapshot()
        } catch (e: Exception) {
            _ui.update { it.copy(snapshotError = e.message ?: "snapshot unavailable") }
            return
        }
        val snapshot = response.snapshot?.let { runCatching { it.toTerminalSnapshot() }.getOrNull() }
        _ui.update {
            it.copy(
                // Keep the last good catalog when the response carries no snapshot.
                snapshot = snapshot ?: it.snapshot,
                snapshotError = if (response.ok) null else response.error,
                paneName = it.paneId?.let { id -> snapshot?.pane(id)?.displayName } ?: it.paneName,
            )
        }
    }

    private fun openSocket(paneId: String? = null, intent: TerminalIntent = TerminalIntent.AUTO) {
        val saved = connectionStore.saved ?: return
        val targetPaneId = paneId ?: resolvePaneId()
        if (targetPaneId == null) {
            _ui.update {
                it.copy(
                    connection = TerminalConnectionState.Closed,
                    paneClosedNotice = it.snapshot?.panes?.isEmpty() != false,
                )
            }
            return
        }
        _ui.update {
            it.copy(
                connection = TerminalConnectionState.Connecting,
                paneId = targetPaneId,
                paneName = it.snapshot?.pane(targetPaneId)?.displayName ?: it.paneName,
            )
        }
        preferencesStore.forConnection(saved.host, saved.token).lastPaneId = targetPaneId
        val ref = SocketRef()
        val socket = transport.open(
            TerminalOpenRequest(
                host = saved.host,
                token = saved.token,
                paneId = targetPaneId,
                cols = GRID_COLS,
                rows = GRID_ROWS,
                intent = intent,
            ),
            createListener(ref),
        )
        ref.socket = socket
        activeSocket = socket
    }

    /** Last valid pane for this connection, then herdr's focused pane, then the first pane. */
    private fun resolvePaneId(): String? {
        val state = _ui.value
        val prefsPane = connectionStore.saved?.let { preferencesStore.forConnection(it.host, it.token).lastPaneId }
        return when {
            prefsPane != null && state.snapshot?.pane(prefsPane) != null -> prefsPane
            state.snapshot?.focusedPane() != null -> state.snapshot.focusedPane()!!.paneId
            state.snapshot?.panes?.isNotEmpty() == true -> state.snapshot.panes.first().paneId
            else -> null
        }
    }

    private class SocketRef {
        var socket: TerminalSocket? = null
    }

    private fun createListener(ref: SocketRef): TerminalTransportListener =
        object : TerminalTransportListener {
            override fun onReady(message: TerminalServerMessage.Ready) {
                if (!isCurrent(ref)) return
                reconnectAttempt = 0
                _ui.update {
                    it.copy(
                        connection = TerminalConnectionState.Ready(message.generation, writable = message.modeEnum == TerminalMode.CONTROL),
                        cols = message.cols,
                        rows = message.rows,
                        canTakeover = false,
                        paneClosedNotice = false,
                    )
                }
                terminalScope.launch {
                    session.resetForGeneration(message.cols, message.rows, NOMINAL_CELL_WIDTH, NOMINAL_CELL_HEIGHT)
                }
            }

            override fun onOwnership(message: TerminalServerMessage.Ownership) {
                if (!isCurrent(ref)) return
                _ui.update { it.copy(canTakeover = message.canTakeover) }
            }

            override fun onBytes(bytes: ByteArray) {
                if (!isCurrent(ref)) return
                terminalScope.launch { session.appendOutput(bytes, 0, bytes.size) }
            }

            override fun onClosed(message: TerminalServerMessage.Closed) {
                if (!isCurrent(ref)) return
                when (message.reason) {
                    TerminalProtocol.CLOSED_RELEASED -> {
                        _ui.update { it.copy(connection = TerminalConnectionState.Closed) }
                    }
                    TerminalProtocol.CLOSED_PANE_CLOSED -> {
                        _ui.update {
                            it.copy(connection = TerminalConnectionState.Closed, paneClosedNotice = true)
                        }
                        refreshNow()
                    }
                    else -> {
                        // replaced / taken_over / shutdown: retryable, same pane, fresh generation.
                        scheduleReconnect()
                    }
                }
            }

            override fun onError(message: TerminalServerMessage.Error) {
                if (!isCurrent(ref)) return
                when (message.code) {
                    TerminalProtocol.ERROR_UNSUPPORTED -> _ui.update {
                        it.copy(connection = TerminalConnectionState.Unsupported(message.message))
                    }
                    TerminalProtocol.ERROR_PROTOCOL -> _ui.update {
                        it.copy(connection = TerminalConnectionState.Failed(message.message, retryable = false))
                    }
                    "pane_not_found" -> {
                        refreshNow()
                        scheduleReconnect()
                    }
                    else -> {
                        // child_failed / startup_error / slow_client / input_backpressure / replaced / shutdown
                        val frozen = (currentConnection as? TerminalConnectionState.Ready)?.generation
                        _ui.update {
                            it.copy(connection = TerminalConnectionState.Failed(message.message, retryable = message.retryable))
                        }
                        if (message.retryable) scheduleReconnect(frozen)
                    }
                }
            }

            override fun onFailure(error: java.io.IOException) {
                if (!isCurrent(ref)) return
                scheduleReconnect()
            }
        }

    private fun isCurrent(ref: SocketRef): Boolean =
        ref.socket != null && ref.socket === activeSocket

    /** Forward terminal-produced bytes; gated on Ready(writable). Never queued or replayed. */
    private fun forwardInput(bytes: ByteArray) {
        val socket = activeSocket ?: return
        val connection = currentConnection
        if (connection is TerminalConnectionState.Ready && connection.writable) {
            socket.sendInput(bytes)
        }
        // Otherwise dropped by design: no keystroke/paste queue exists (plan "Input").
    }

    private fun scheduleReconnect(frozen: Long? = (currentConnection as? TerminalConnectionState.Ready)?.generation) {
        if (!started) return
        _ui.update { it.copy(connection = TerminalConnectionState.Reconnecting(frozenGeneration = frozen)) }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(backoffDelayMs(reconnectAttempt++))
            if (started) openSocket()
        }
    }

    private fun backoffDelayMs(attempt: Int): Long {
        val exp = RECONNECT_BASE_MS * (1L shl attempt.coerceAtMost(4))
        val jitter = 0.8 + ThreadLocalRandom.current().nextDouble(0.4)
        return (exp.coerceAtMost(RECONNECT_MAX_MS) * jitter).toLong().coerceAtLeast(1L)
    }

    override fun onCleared() {
        // Grace: cancel without release so the bridge keeps the child in grace
        // and the pane is not killed while the user might come back.
        activeSocket?.cancel()
        activeSocket = null
        reconnectJob?.cancel()
        feed?.stop()
        terminalScope.cancel()
        if (ownsIo) (terminalIo as? kotlinx.coroutines.ExecutorCoroutineDispatcher)?.close()
    }

    companion object {
        /** Plan "TerminalViewModel": ~10k transcript rows. */
        const val TRANSCRIPT_ROWS = 10_000

        /** Slice 6 uses the last known grid with nominal monospace cell dims; real pixels arrive with the view. */
        const val GRID_COLS = 80
        const val GRID_ROWS = 24
        const val NOMINAL_CELL_WIDTH = 10
        const val NOMINAL_CELL_HEIGHT = 20

        const val SNAPSHOT_DEBOUNCE_MS = 250L
        const val RECONNECT_BASE_MS = 500L
        const val RECONNECT_MAX_MS = 8_000L
    }
}

/** Terminal connection lifecycle (plan "State model"). */
sealed interface TerminalConnectionState {
    data object Idle : TerminalConnectionState
    data object Connecting : TerminalConnectionState

    /** Writable = control mode; input is gated on this flag. */
    data class Ready(val generation: Long, val writable: Boolean) : TerminalConnectionState

    /** Transport-level reconnect in progress; the frozen generation may be null (never ready). */
    data class Reconnecting(val frozenGeneration: Long?) : TerminalConnectionState

    /** Capability gate failed; retrying will not help (unless the bridge upgrades). */
    data class Unsupported(val explanation: String) : TerminalConnectionState

    /** Stable or retryable failure (health, protocol_error, server error with retryable=false). */
    data class Failed(val message: String, val retryable: Boolean) : TerminalConnectionState

    /** Explicit release or no pane remains. */
    data object Closed : TerminalConnectionState
}

/** Slice 6 state surface; the future TerminalView binds to this. */
data class TerminalUiState(
    val connection: TerminalConnectionState = TerminalConnectionState.Idle,
    val paneId: String? = null,
    val paneName: String? = null,
    val snapshot: TerminalSnapshot? = null,
    val snapshotError: String? = null,
    val canTakeover: Boolean = false,
    val cols: Int = 0,
    val rows: Int = 0,
    val title: String? = null,
    val paneClosedNotice: Boolean = false,
) {
    val generation: Long?
        get() = when (val c = connection) {
            is TerminalConnectionState.Ready -> c.generation
            is TerminalConnectionState.Reconnecting -> c.frozenGeneration
            else -> null
        }
}
