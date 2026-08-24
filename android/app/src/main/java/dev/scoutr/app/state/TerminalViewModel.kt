package dev.scoutr.app.state

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.DirListingResponse
import dev.scoutr.app.data.SnapshotResponse
import dev.scoutr.app.data.TerminalHierarchyCommand
import dev.scoutr.app.data.TerminalHierarchyResponse
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.data.TerminalSnapshot
import dev.scoutr.app.data.toTerminalSnapshot
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.PerformanceCounters
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalMode
import dev.scoutr.app.net.TerminalOpenRequest
import dev.scoutr.app.net.TerminalIntent
import dev.scoutr.app.net.TerminalProtocol
import dev.scoutr.app.net.TerminalServerMessage
import dev.scoutr.app.net.TerminalSocket
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TerminalTransportListener
import dev.scoutr.app.net.TopologyFeed
import dev.scoutr.app.terminal.RemoteTerminalSession
import dev.scoutr.app.terminal.TerminalOutputFailure
import dev.scoutr.app.terminal.TerminalOutputPump
import kotlinx.coroutines.CancellationException
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
 * Slice 6+7: terminal state for exactly one active pane (plan
 * "TerminalViewModel / state") plus the slice 7 UI surface: measured
 * grid reporting (controller resize / observer restart), hierarchy
 * mutations, takeover, paste, and BEL/OSC-paste events.
 *
 * Contract highlights:
 *  - exactly one active socket at a time; callbacks are tagged by socket
 *    instance and ignored once the socket is no longer current (generation
 *    safety at the client boundary; the bridge emits exactly one generation
 *    per socket);
 *  - no input queue: bytes produced by the terminal are forwarded only while
 *    Ready(writable=true) and dropped otherwise (no replay after reconnect);
 *  - every new generation resets the emulator ([RemoteTerminalSession.resetForGeneration])
 *    before any byte of that generation is fed; output reaches the emulator
 *    only through [TerminalOutputPump], whose single consumer runs on the
 *    injectable single-thread [terminalIo] dispatcher, so session access is
 *    serialized off the main thread and a burst of frames costs one emulator
 *    append instead of one coroutine and one append per frame;
 *  - reconnect uses bounded exponential backoff (500 ms base, ×2, 8 s
 *    ceiling, ±20% jitter) while the route is started; onCleared cancels the
 *    socket WITHOUT release (bridge grace) whereas [release] sends `release`.
 */
class TerminalViewModel(
    private val api: ScoutrApi,
    private val transport: TerminalTransport,
    private val feedFactory: TopologyFeed.Factory,
    private val hostPreferences: TerminalPreferencesStore.ConnectionPreferences,
    private val preferencesStore: TerminalPreferencesStore,
    initialPaneId: String? = null,
    injectedIo: CoroutineDispatcher? = null,
    private val performanceCounters: PerformanceCounters? = null,
    private val hostIsCurrent: () -> Boolean = { true },
    private val hostAvailable: () -> Boolean = { true },
) : ViewModel() {
    /**
     * Pane the route was opened for (e.g. the Chat overflow's "Open terminal").
     * Consumed by the first [resolvePaneId] so it outranks the saved pane once
     * and never steers a later reconnect away from the pane the user attached
     * to from the drawer.
     */
    private var pendingInitialPaneId: String? = initialPaneId

    private val _ui = MutableStateFlow(TerminalUiState())
    val ui: StateFlow<TerminalUiState> = _ui.asStateFlow()

    /** Emulator state for the active pane. Owned here, mutated only on [terminalIo]. */
    internal val session: RemoteTerminalSession =
        RemoteTerminalSession(transcriptRows = TRANSCRIPT_ROWS, counters = performanceCounters)

    private val terminalIo: CoroutineDispatcher
    private val ownsIo: Boolean
    private lateinit var terminalScope: CoroutineScope

    /**
     * The only path from the transport to the emulator. Transport callbacks enqueue and return;
     * the pump's single consumer drains each burst into one append + one screen update. See
     * [TerminalOutputPump] for the ordering and generation guarantees this relies on.
     */
    private val outputPump: TerminalOutputPump

    init {
        if (injectedIo != null) {
            terminalIo = injectedIo
            ownsIo = false
        } else {
            terminalIo = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "scoutr-terminal-io").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            ownsIo = true
        }
        terminalScope = CoroutineScope(SupervisorJob() + terminalIo)
        outputPump = TerminalOutputPump(
            scope = terminalScope,
            counters = performanceCounters,
        ) { batch -> session.appendOutput(batch, 0, batch.size) }
        session.inputSink = { bytes -> forwardInput(bytes) }
        session.callbacks.onTitleChanged = { _ui.update { it.copy(title = session.getTitle()) } }
        session.callbacks.onBell = { _ui.update { it.copy(bellAt = it.bellAt + 1L) } }
        session.callbacks.onClipboardPasteRequest = { _ui.update { it.copy(pasteRequestAt = it.pasteRequestAt + 1L) } }
    }

    @Volatile private var started = false
    @Volatile private var disposed = false
    @Volatile private var activeSocket: TerminalSocket? = null
    private var reconnectJob: Job? = null
    private var snapshotDebounce: Job? = null
    private var gridDebounce: Job? = null
    private var reconnectAttempt = 0

    /**
     * Delivery failures inside the current [DELIVERY_FAILURE_WINDOW_MS] window, guarded by
     * [deliveryFailureLock] because the terminal and transport threads both report failures.
     *
     * Counted per window rather than "consecutive since the last rendered batch": every
     * generation starts by rendering the bridge's replay frame, and an oversized replay is split
     * across batches, so any success-based reset would be cleared on every cycle and the cap
     * below would never trip. A window also expires on its own, so failures hours apart cannot
     * accumulate into a spurious permanent failure.
     */
    private val deliveryFailureLock = Any()
    private var deliveryFailureWindowStart = 0L
    private var deliveryFailuresInWindow = 0
    private var feed: TopologyFeed? = null

    /** Last grid measured by the view (main thread); used for hello/resize/observer restart. */
    @Volatile private var measuredCols = 0
    @Volatile private var measuredRows = 0

    /** Grid the current socket's hello was opened with; observer restarts compare against it. */
    @Volatile private var socketGrid: Pair<Int, Int>? = null

    /** The current generation, used to gate input (thread-safe via StateFlow). */
    private val currentConnection: TerminalConnectionState get() = _ui.value.connection

    /** Route entry: capability gate, then snapshot + attach. Idempotent. */
    fun start() {
        if (started || disposed) return
        if (!hostAvailable()) {
            _ui.update { it.copy(connection = TerminalConnectionState.Failed("No connection configured", retryable = false)) }
            return
        }
        if (!hostIsCurrent()) {
            _ui.update { it.copy(connection = TerminalConnectionState.Failed("Host forgotten or route expired", retryable = false)) }
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
        if (!hostIsCurrent()) return
        try {
            val health = api.health()
            val capability = health.terminal?.capability
            if (capability?.isUnsupported == true) {
                _ui.update {
                    it.copy(
                        connection = TerminalConnectionState.Unsupported(
                            capability.reason ?: "this bridge does not support the terminal route",
                        ),
                    )
                }
                return
            }
            refreshSnapshot()
            openSocket()
        } catch (stale: CancellationException) {
            throw stale
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
        if (!started || !hostIsCurrent()) return
        reconnectJob?.cancel()
        retireSocket()
        hostPreferences.lastPaneId = paneId
        _ui.update { it.copy(paneClosedNotice = false, canTakeover = false) }
        openSocket(paneId)
    }

    /** Explicit detach: send `release` and settle to [TerminalConnectionState.Closed]. */
    fun release() {
        reconnectJob?.cancel()
        retireSocket()
        _ui.update { it.copy(connection = TerminalConnectionState.Closed) }
    }

    /** Controller resize; a no-op when not writable (observe/connecting). */
    fun resize(cols: Int, rows: Int) {
        val socket = activeSocket ?: return
        if (currentConnection !is TerminalConnectionState.Ready) return
        socket.resize(cols, rows)
        terminalScope.launch { session.updateSize(cols, rows, NOMINAL_CELL_WIDTH, NOMINAL_CELL_HEIGHT) }
    }

    /**
     * Take control of the active pane (ownership dialog, slice 7). A fresh
     * confirmation is required every time; the bridge answers the TAKEOVER
     * hello with control when the current owner is not connected, else with
     * an error the broker resolves.
     */
    fun takeover() {
        val paneId = _ui.value.paneId ?: return
        if (!started) return
        reconnectJob?.cancel()
        retireSocket()
        _ui.update { it.copy(paneClosedNotice = false, canTakeover = false) }
        openSocket(paneId, intent = TerminalIntent.TAKEOVER)
    }

    /**
     * Decline the takeover offer: stay a read-only observer on this pane. Only
     * the offer is cleared — the socket is untouched — so the next `ownership`
     * message (a new owner, or the owner disconnecting) can offer again.
     */
    fun dismissTakeover() {
        _ui.update { it.copy(canTakeover = false) }
    }

    /** Per-connection terminal preferences (font size, extra-key row, last pane). */
    val preferences: TerminalPreferencesStore.ConnectionPreferences
        get() = hostPreferences

    /**
     * Ticks when font size or extra-key visibility is written from anywhere —
     * pinch, the extra-keys strip, or the Settings rows, which share this
     * store. The screen re-reads [preferences] on each tick, so a Settings
     * change lands on an already-open terminal.
     */
    val viewPreferencesRevision: StateFlow<Int> = preferencesStore.viewPreferencesRevision

    fun updateFontSize(sp: Float) {
        // The store clamps; pinch can hand it any accumulated scale.
        preferences?.fontSizeSp = sp
    }

    fun updateExtraKeysVisible(visible: Boolean) {
        preferences?.extraKeysVisible = visible
    }

    /**
     * The view measured a new grid (slice 7). While writable the resize is
     * debounced to the latest cols/rows; while observing, a viewport change
     * restarts observation as a fresh generation carrying the latest grid
     * (observers cannot resize). Any other state just records the grid for
     * the next hello. May be called from any thread.
     */
    fun reportGrid(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        measuredCols = cols
        measuredRows = rows
        if (currentConnection is TerminalConnectionState.Ready) {
            gridDebounce?.cancel()
            gridDebounce = viewModelScope.launch {
                delay(GRID_DEBOUNCE_MS)
                applyGrid()
            }
        }
    }

    private suspend fun applyGrid() {
        val connection = currentConnection
        if (connection !is TerminalConnectionState.Ready) return
        val cols = measuredCols
        val rows = measuredRows
        if (cols <= 0 || rows <= 0) return
        if (connection.writable) {
            val socket = activeSocket ?: return
            // The view already resized the emulator with its real font
            // metrics; only the transport needs the latest grid here.
            socket.resize(cols, rows)
        } else {
            val openedWith = socketGrid ?: return
            val paneId = _ui.value.paneId ?: return
            if (openedWith.first != cols || openedWith.second != rows) {
                retireSocket()
                openSocket(paneId)
            }
        }
    }

    /**
     * Paste device-clipboard text chosen by the UI. Enters through the
     * emulator so bracketed-paste mode is honored, and is gated on
     * Ready(writable=true) like any other input — no paste queue exists.
     */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val connection = currentConnection
        if (connection !is TerminalConnectionState.Ready || !connection.writable) return
        terminalScope.launch {
            session.emulator?.paste(text)
        }
    }

    /**
     * Server-side folder listing for the drawer's New-workspace dialog, so it
     * browses the same allow-listed directories as Scoutr's existing picker
     * (plan "Hierarchy UX": New workspace reuses the directory picker) rather
     * than only accepting a typed path.
     */
    suspend fun browseDirs(path: String?): DirListingResponse = api.dirs(path)

    /** Hierarchy mutations (slice 7 drawer); busy/error surfaced in [TerminalUiState]. */
    fun createTab(workspaceId: String) =
        mutate("create tab") { api.terminalHierarchy(TerminalHierarchyCommand.createTab(workspaceId, selectedPaneId())) }

    fun createWorkspace(cwd: String, label: String?) =
        mutate("create workspace") { api.terminalHierarchy(TerminalHierarchyCommand.createWorkspace(cwd, label, selectedPaneId())) }

    fun renamePane(paneId: String, label: String) =
        mutate("rename pane") { api.terminalHierarchy(TerminalHierarchyCommand.renamePane(paneId, label, selectedPaneId())) }

    fun renameTab(tabId: String, label: String) =
        mutate("rename tab") { api.terminalHierarchy(TerminalHierarchyCommand.renameTab(tabId, label, selectedPaneId())) }

    fun renameWorkspace(workspaceId: String, label: String) =
        mutate("rename workspace") { api.terminalHierarchy(TerminalHierarchyCommand.renameWorkspace(workspaceId, label, selectedPaneId())) }

    fun closePane(paneId: String) =
        mutate("close pane") { api.terminalHierarchy(TerminalHierarchyCommand.closePane(paneId, selectedPaneId())) }

    fun closeTab(tabId: String, expectedPaneCount: Int) =
        mutate("close tab") { api.terminalHierarchy(TerminalHierarchyCommand.closeTab(tabId, expectedPaneCount, selectedPaneId())) }

    fun closeWorkspace(workspaceId: String, expectedPaneCount: Int) =
        mutate("close workspace") { api.terminalHierarchy(TerminalHierarchyCommand.closeWorkspace(workspaceId, expectedPaneCount, selectedPaneId())) }

    private fun selectedPaneId(): String? = _ui.value.paneId

    private fun mutate(label: String, call: suspend () -> TerminalHierarchyResponse) {
        if (_ui.value.hierarchyBusy) return
        _ui.update { it.copy(hierarchyBusy = true, hierarchyError = null) }
        viewModelScope.launch {
            try {
                applyHierarchyResponse(call(), label)
            } catch (e: BridgeException) {
                if (e.status == 409) {
                    // Stale pane count: refresh the snapshot so the drawer shows
                    // the new count and the user can confirm again.
                    refreshSnapshot()
                    _ui.update { it.copy(hierarchyError = "The pane count changed — confirm again") }
                } else {
                    _ui.update { it.copy(hierarchyError = e.message ?: "hierarchy $label failed") }
                }
            } catch (stale: CancellationException) {
                throw stale
            } catch (e: Exception) {
                _ui.update { it.copy(hierarchyError = e.message ?: "hierarchy $label failed") }
            } finally {
                _ui.update { it.copy(hierarchyBusy = false) }
            }
        }
    }

    private suspend fun applyHierarchyResponse(response: TerminalHierarchyResponse, label: String) {
        val snapshot = response.snapshot?.let { runCatching { it.toTerminalSnapshot() }.getOrNull() }
        _ui.update {
            it.copy(
                snapshot = snapshot ?: it.snapshot,
                snapshotError = if (response.ok) null else response.error,
            )
        }
        if (!response.ok) {
            _ui.update { it.copy(hierarchyError = response.error ?: "$label failed") }
            return
        }
        val state = _ui.value
        val selected = response.selectedPaneId
        val active = state.paneId
        when {
            selected != null && selected != active -> attach(selected)
            selected == null && active != null && state.snapshot?.panes?.isEmpty() != false ->
                // The active pane was closed and nothing remains to select.
                _ui.update { it.copy(connection = TerminalConnectionState.Closed, paneClosedNotice = true) }
            // selected == active or nothing left: the fresh snapshot above is enough.
        }
    }

    /**
     * User-driven retry from a terminal overlay ("Retry" / "Open terminal"):
     * re-runs the whole capability gate and attach from a settled state.
     *
     * Distinct from [refreshNow], which only refreshes the catalog. A settled
     * [TerminalConnectionState.Failed] has no reconnect scheduled behind it —
     * a rejected upgrade is the bridge's verdict, not a transport hiccup — so
     * without this the button would refresh a snapshot and nothing else.
     */
    fun retry() {
        if (!started) return
        reconnectJob?.cancel()
        retireSocket()
        reconnectAttempt = 0
        _ui.update { it.copy(connection = TerminalConnectionState.Connecting, paneClosedNotice = false) }
        viewModelScope.launch { checkHealthAndAttach() }
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
        } catch (stale: CancellationException) {
            throw stale
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
        if (!hostIsCurrent()) return
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
        hostPreferences.lastPaneId = targetPaneId
        val grid = (if (measuredCols > 0 && measuredRows > 0) measuredCols to measuredRows else GRID_COLS to GRID_ROWS)
        socketGrid = grid
        val ref = SocketRef()
        val socket = transport.open(
            TerminalOpenRequest(
                paneId = targetPaneId,
                cols = grid.first,
                rows = grid.second,
                intent = intent,
            ),
            createListener(ref),
        )
        ref.socket = socket
        activeSocket = socket
    }

    /**
     * The route's requested pane (once), then the last valid pane for this
     * connection, then herdr's focused pane, then the first pane.
     */
    private fun resolvePaneId(): String? {
        val state = _ui.value
        val requested = pendingInitialPaneId
        pendingInitialPaneId = null
        if (requested != null && state.snapshot?.pane(requested) != null) return requested
        val prefsPane = hostPreferences.lastPaneId
        return when {
            prefsPane != null && state.snapshot?.pane(prefsPane) != null -> prefsPane
            state.snapshot?.focusedPane() != null -> state.snapshot.focusedPane()!!.paneId
            state.snapshot?.panes?.isNotEmpty() == true -> state.snapshot.panes.first().paneId
            else -> null
        }
    }

    private class SocketRef {
        var socket: TerminalSocket? = null

        /**
         * Output generation this socket may feed, installed when its `ready` arrives. The handle
         * itself is the gate: once a later socket replaces it, its enqueues are refused by the
         * pump, so a stale socket cannot reach the new emulator even if a state check is missed.
         */
        @Volatile var output: TerminalOutputPump.Generation? = null
    }

    /**
     * Retire the active socket and the output generation it feeds. Used for user-driven
     * replacements (attach, takeover, observer restart, release) where the queued tail of the old
     * generation must not render into what comes next.
     */
    private fun retireSocket() {
        activeSocket?.release()
        activeSocket = null
        outputPump.clearGeneration()
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
                // Installing the generation both retires the previous one's queued bytes and
                // schedules the emulator reset ahead of this generation's first batch, so the
                // reset can never be reordered behind the output it must precede.
                ref.output = outputPump.resetGeneration(
                    onFailed = { failure -> onOutputFailure(ref, failure) },
                    prologue = {
                        session.resetForGeneration(message.cols, message.rows, NOMINAL_CELL_WIDTH, NOMINAL_CELL_HEIGHT)
                    },
                )
            }

            override fun onOwnership(message: TerminalServerMessage.Ownership) {
                if (!isCurrent(ref)) return
                _ui.update { it.copy(canTakeover = message.canTakeover) }
            }

            // Runs on the transport thread for every binary frame: enqueue and return. No
            // coroutine is launched here; the pump's single consumer owns emulator delivery.
            override fun onBytes(bytes: ByteArray) {
                ref.output?.enqueue(bytes)
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
                    // The bridge refused the upgrade, so no reconnect can talk
                    // it round — only a user retry (or a bridge that changed
                    // its mind) can. Offer the retry; never spin on it.
                    TerminalProtocol.ERROR_UPGRADE_REJECTED -> _ui.update {
                        it.copy(connection = TerminalConnectionState.Failed(message.message, retryable = true))
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

    /**
     * This generation's output could not be delivered — its queue hit the pending bound, or an
     * append into the emulator threw. The pump has already retired the generation rather than
     * deliver a hole; rebuild through a fresh one, which replays the screen, the same recovery
     * shape as the bridge's slow-client policy. May be called on the transport or terminal thread.
     */
    private fun onOutputFailure(ref: SocketRef, failure: TerminalOutputFailure) {
        if (!isCurrent(ref)) return
        Log.w(TAG, "terminal output generation retired: $failure")
        val frozen = (currentConnection as? TerminalConnectionState.Ready)?.generation
        activeSocket?.cancel()
        activeSocket = null
        // A delivery failure can be deterministic in the pane's content (an emulator or repaint
        // defect on one escape sequence). Reconnecting replays the same bytes and fails again,
        // and each cycle reaches Ready first, which resets the backoff — so retrying forever
        // would be a tight loop. Give it a few tries per window, then stop and say so instead of
        // hammering the pane.
        if (failure == TerminalOutputFailure.DELIVERY_FAILED && deliveryFailureExhausted()) {
            _ui.update {
                it.copy(
                    connection = TerminalConnectionState.Failed(
                        "terminal output could not be rendered — pick a pane from the menu to try again",
                        retryable = false,
                    ),
                )
            }
            return
        }
        scheduleReconnect(frozen)
    }

    /** Record one delivery failure; true once the window's allowance is used up. */
    private fun deliveryFailureExhausted(): Boolean = synchronized(deliveryFailureLock) {
        val now = SystemClock.elapsedRealtime()
        if (now - deliveryFailureWindowStart > DELIVERY_FAILURE_WINDOW_MS) {
            deliveryFailureWindowStart = now
            deliveryFailuresInWindow = 0
        }
        if (++deliveryFailuresInWindow > MAX_DELIVERY_FAILURES) {
            deliveryFailuresInWindow = 0
            deliveryFailureWindowStart = 0L
            return true
        }
        return false
    }

    private fun isCurrent(ref: SocketRef): Boolean =
        hostIsCurrent() && ref.socket != null && ref.socket === activeSocket

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

    fun dispose() {
        if (disposed) return
        disposed = true
        // Cancel without release so the bridge keeps the child in grace while the user may return.
        activeSocket?.cancel()
        activeSocket = null
        reconnectJob?.cancel()
        feed?.stop()
        logTerminalCounters()
        outputPump.close()
        terminalScope.cancel()
        if (ownsIo) (terminalIo as? kotlinx.coroutines.ExecutorCoroutineDispatcher)?.close()
    }

    override fun onCleared() {
        dispose()
    }

    /**
     * Dump the process-local terminal throughput counters when the route goes away — the read
     * surface for performance experiments (`adb logcat -s ScoutrTerminal`). Scalars only: counts
     * and sizes, never terminal content.
     */
    private fun logTerminalCounters() {
        val terminal = performanceCounters?.snapshot()?.terminal ?: return
        if (terminal.binaryMessages == 0L) return
        Log.i(
            TAG,
            "terminal counters: wsMessages=${terminal.binaryMessages} wsBytes=${terminal.bytesReceived} " +
                "batches=${terminal.outputBatches} batchBytes=${terminal.outputBytes} " +
                "maxBatch=${terminal.maxBatchBytes} maxPending=${terminal.maxPendingBytes} " +
                "appends=${terminal.emulatorAppends} screenUpdates=${terminal.screenUpdates} " +
                "overflows=${terminal.queueOverflows} deliveryFailures=${terminal.deliveryFailures}",
        )
    }

    companion object {
        private const val TAG = "ScoutrTerminal"

        /** Delivery failures tolerated inside [DELIVERY_FAILURE_WINDOW_MS] before giving up. */
        const val MAX_DELIVERY_FAILURES = 3

        /** Window the delivery-failure allowance applies to; it expires on its own. */
        const val DELIVERY_FAILURE_WINDOW_MS = 60_000L

        /** Terminal scrollback retained for the active visit; capped by the vendored emulator at 50,000 rows. */
        const val TRANSCRIPT_ROWS = 50_000

        /** Slice 6 uses the last known grid with nominal monospace cell dims; real pixels arrive with the view. */
        const val GRID_COLS = 80
        const val GRID_ROWS = 24
        const val NOMINAL_CELL_WIDTH = 10
        const val NOMINAL_CELL_HEIGHT = 20

        const val SNAPSHOT_DEBOUNCE_MS = 250L
        const val RECONNECT_BASE_MS = 500L
        const val RECONNECT_MAX_MS = 8_000L

        /** Grid reporting coalesces IME/resize bursts to the latest cols/rows. */
        const val GRID_DEBOUNCE_MS = 150L

        /**
         * Font-size bounds for pinch (shared across panes for the saved
         * connection). Re-exported from the store that enforces them, so the
         * terminal and Settings cannot drift apart on the range.
         */
        const val MIN_FONT_SIZE_SP = TerminalPreferencesStore.ConnectionPreferences.MIN_FONT_SIZE_SP
        const val MAX_FONT_SIZE_SP = TerminalPreferencesStore.ConnectionPreferences.MAX_FONT_SIZE_SP
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

/** Slice 6+7 state surface the TerminalView binds to. */
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
    /** A hierarchy mutation is in flight; drawer confirmations are disabled while true. */
    val hierarchyBusy: Boolean = false,
    /** Last hierarchy mutation failure (stale counts, transport errors). Cleared on the next attempt. */
    val hierarchyError: String? = null,
    /** Monotonic BEL counter; the screen coalesces haptics from it. */
    val bellAt: Long = 0L,
    /** Monotonic device-paste request counter (selection toolbar Paste). */
    val pasteRequestAt: Long = 0L,
) {
    val generation: Long?
        get() = when (val c = connection) {
            is TerminalConnectionState.Ready -> c.generation
            is TerminalConnectionState.Reconnecting -> c.frozenGeneration
            else -> null
        }
}
