package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.BoardState
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.CockpitApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

data class BoardUiState(
    val board: BoardState = BoardState(),
    val loading: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
)

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Testing : ConnectState
    data class Connected(val herdrVersion: String?, val herdrProtocol: Int?) : ConnectState
    data class Failed(val message: String) : ConnectState
}

class BoardViewModel(
    private val bridge: CockpitApi,
    private val connectionStore: ConnectionStore,
    private val ntfyClient: dev.cockpit.app.net.NtfyClient? = null,
    private val onNtfyMessage: (dev.cockpit.app.data.NtfyMessage) -> Unit = {},
    initialState: BoardUiState = BoardUiState(),
) : ViewModel() {

    private val _ui = MutableStateFlow(initialState)
    val ui: StateFlow<BoardUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null
    private var ntfyJob: Job? = null

    val hasSavedConnection: Boolean get() = connectionStore.saved != null

    init {
        if (connectionStore.saved != null) {
            connect(host = "", token = "")
        }
    }

    /** Connects to the stored (or newly saved) connection and starts the live board. */
    fun connect(host: String, token: String) {
        if (host.isNotBlank() && token.isNotBlank()) {
            connectionStore.save(host, token)
        }
        val saved = connectionStore.saved ?: run {
            _ui.update { it.copy(error = "No connection configured") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val health = bridge.health()
                _ui.update {
                    it.copy(connected = health.ok && health.herdr?.connected == true, loading = false)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, connected = false, error = e.message ?: "connection failed") }
            }
            // Always poll; refresh() flips `connected` itself, so a transient
            // probe failure self-heals once the bridge is reachable again.
            startLive()
            startPush()
        }
    }

    private fun startLive() {
        pollJob?.cancel()
        // Poll the bridge for the latest board state. A long-lived WebSocket
        // is deliberately avoided here: an abrupt server close can crash the
        // OkHttp reader, and the bridge already caches + re-snapshots anyway.
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(3_000)
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
                } catch (_: Exception) {
                    // ntfy may be briefly unreachable; retry on the next loop.
                }
                delay(30_000)
            }
        }
    }

    suspend fun refresh() {
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
        viewModelScope.launch {
            try {
                bridge.controlSession(paneId, "close")
            } catch (e: IOException) {
                reportError(e.message ?: "could not close agent")
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        ntfyJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            bridge: CockpitApi,
            connectionStore: ConnectionStore,
            ntfyClient: dev.cockpit.app.net.NtfyClient? = null,
            onNtfyMessage: (dev.cockpit.app.data.NtfyMessage) -> Unit = {},
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BoardViewModel(bridge, connectionStore, ntfyClient, onNtfyMessage) as T
                }
            }
    }
}
