package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.AgentCard
import dev.scoutr.app.data.BoardState
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.classifyScoutrApiCompatibility
import dev.scoutr.app.data.formatScoutrApiIncompatibility
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class BoardUiState(
    val board: BoardState = BoardState(),
    val loading: Boolean = false,
    val isRefreshing: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val apiCompatibility: ScoutrApiCompatibility? = null,
)

class BoardViewModel(
    private val bridge: ScoutrApi,
    private val connectionStore: ConnectionStore,
    private val ntfyClient: dev.scoutr.app.net.NtfyClient? = null,
    private val onNtfyMessage: (dev.scoutr.app.data.NtfyMessage) -> Unit = {},
    initialState: BoardUiState = BoardUiState(),
    private val pollInterval: Duration = 3.seconds,
) : ViewModel() {

    private val _ui = MutableStateFlow(initialState)
    val ui: StateFlow<BoardUiState> = _ui.asStateFlow()

    private val poller = Poller(viewModelScope)
    private val connectionMutex = Mutex()
    private val loadMutex = Mutex()
    private var ntfyJob: Job? = null

    val hasSavedConnection: Boolean get() = connectionStore.saved != null

    init {
        if (connectionStore.saved != null) {
            connect(host = "", token = "")
        }
    }

    /** Connects to the stored (or newly saved) connection and starts the live board. */
    fun connect(host: String, token: String) {
        val hasCandidate = host.isNotBlank() && token.isNotBlank()
        if (!hasCandidate && connectionStore.saved == null) {
            _ui.update { it.copy(error = "No connection configured") }
            return
        }
        viewModelScope.launch {
            probeConnection(
                host = host.takeIf { hasCandidate },
                token = token.takeIf { hasCandidate },
                showLoading = _ui.value.apiCompatibility !is ScoutrApiCompatibility.Incompatible,
            )
        }
    }

    private suspend fun probeConnection(
        host: String? = null,
        token: String? = null,
        showLoading: Boolean = false,
    ) = connectionMutex.withLock {
        if (showLoading) _ui.update { it.copy(loading = true, error = null) }
        try {
            val health = bridge.health(host, token)
            val compatibility = classifyScoutrApiCompatibility(health.api)
            if (compatibility is ScoutrApiCompatibility.Incompatible) {
                stopPush()
                _ui.update {
                    it.copy(
                        board = BoardState(),
                        loading = false,
                        connected = false,
                        error = formatScoutrApiIncompatibility(compatibility),
                        apiCompatibility = compatibility,
                    )
                }
                return@withLock
            }
            val hadSavedConnection = connectionStore.saved != null
            if (host != null && token != null) {
                connectionStore.save(
                    host = host,
                    token = token,
                    ntfyUrl = health.ntfy?.url,
                    ntfyTopic = health.ntfy?.topic,
                )
            }
            _ui.update {
                it.copy(
                    connected = health.ok && health.herdr?.connected == true,
                    loading = false,
                    error = null,
                    apiCompatibility = ScoutrApiCompatibility.Compatible,
                )
            }
            if (lifecycleActive) {
                if (!hadSavedConnection && connectionStore.saved != null) startLive()
                startPush()
                loadBoard()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            _ui.update { current ->
                val incompatible = current.apiCompatibility as? ScoutrApiCompatibility.Incompatible
                current.copy(
                    loading = false,
                    connected = false,
                    error = incompatible?.let(::formatScoutrApiIncompatibility)
                        ?: e.message
                        ?: "connection failed",
                )
            }
        }
    }

    // True while the board screen is STARTED. Only the lifecycle wrapper
    // starts loops; connect() may restart them, but never resurrect them
    // after a stop that raced the health probe.
    private var lifecycleActive = false

    /** Start the 3s board poll and the ntfy push loop; no-op when already polling. */
    fun startPolling() {
        if (lifecycleActive) return
        lifecycleActive = true
        if (connectionStore.saved != null) startLive()
        if (_ui.value.apiCompatibility == ScoutrApiCompatibility.Compatible) startPush()
    }

    /** Stop both loops; in-flight one-shot actions are untouched. */
    fun stopPolling() {
        if (!lifecycleActive) return
        lifecycleActive = false
        stopLiveLoops()
    }

    private fun stopLiveLoops() {
        poller.stop()
        stopPush()
    }

    private fun stopPush() {
        ntfyJob?.cancel()
        ntfyJob = null
    }

    /**
     * Forget: the pairing is gone, so stop both loops and drop the board we
     * fetched under it. This VM is activity-scoped and is not recreated when
     * nav resets to Connect, so without this it would keep polling a cleared
     * store. Re-pairing calls [connect] again, which restarts everything.
     */
    fun disconnect() {
        stopPolling()
        _ui.value = BoardUiState()
    }

    private fun startLive() {
        // Poll the bridge for the latest board state. A long-lived WebSocket
        // is deliberately avoided here: an abrupt server close can crash the
        // OkHttp reader, and the bridge already caches + re-snapshots anyway.
        poller.start(pollInterval) {
            if (_ui.value.apiCompatibility == ScoutrApiCompatibility.Compatible) {
                loadBoard()
            } else {
                probeConnection()
            }
        }
    }

    /**
     * Poll the ntfy topic the bridge publishes to, and surface each new message
     * as a local notification. Failure is silent: push must never break the board.
     */
    private fun startPush() {
        ntfyJob?.cancel()
        val saved = connectionStore.saved ?: return
        val url = saved.ntfyUrl ?: return
        val topic = saved.ntfyTopic ?: return
        val client = ntfyClient ?: return
        ntfyJob = viewModelScope.launch {
            var lastId = try {
                client.latestId(url, topic)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                null
            }
            while (isActive) {
                try {
                    // Collect advances the cursor so re-polls never re-deliver.
                    client.messages(url, topic, initialSince = lastId)
                        .collect { message ->
                            onNtfyMessage(message)
                            lastId = message.id
                        }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // ntfy may be briefly unreachable; retry on the next loop.
                }
                delay(30_000)
            }
        }
    }

    /** Request an immediate board refresh and expose its progress to the pull gesture. */
    fun refreshBoard() {
        if (_ui.value.isRefreshing || _ui.value.apiCompatibility != ScoutrApiCompatibility.Compatible) return
        _ui.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                loadBoard()
            } finally {
                _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun loadBoard() = loadMutex.withLock {
        if (_ui.value.apiCompatibility != ScoutrApiCompatibility.Compatible) return@withLock
        try {
            val response = bridge.agents()
            _ui.update {
                it.copy(
                    board = BoardState.group(response.agents),
                    connected = true,
                    error = null,
                )
            }
        } catch (e: IOException) {
            _ui.update { it.copy(connected = false, error = e.message ?: "lost connection") }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // transient decode issues should not flap the board
        }
    }

    /** Surface a transient error (e.g. a failed session create) on the board. */
    fun reportError(message: String) {
        _ui.update { it.copy(error = message) }
    }

    /** Closes an agent's pane via the bridge control action (swipe-bar Close). */
    fun closeAgent(paneId: String) {
        val incompatible = _ui.value.apiCompatibility as? ScoutrApiCompatibility.Incompatible
        if (incompatible != null) {
            reportError(formatScoutrApiIncompatibility(incompatible))
            return
        }
        if (_ui.value.apiCompatibility != ScoutrApiCompatibility.Compatible) {
            reportError("Checking bridge compatibility")
            return
        }
        viewModelScope.launch {
            try {
                bridge.controlSession(paneId, SessionAction.Close)
            } catch (e: IOException) {
                reportError(e.message ?: "could not close agent")
            }
        }
    }

    override fun onCleared() {
        stopLiveLoops()
        super.onCleared()
    }
}
