package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.BoardState
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    private val bridge: BridgeClient,
    private val connectionStore: ConnectionStore,
    initialState: BoardUiState = BoardUiState(),
) : ViewModel() {

    private val _ui = MutableStateFlow(initialState)
    val ui: StateFlow<BoardUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null

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
                startLive()
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, connected = false, error = e.message ?: "connection failed") }
            }
        }
    }

    private fun startLive() {
        pollJob?.cancel()
        // Any feed activity (herdr event or snapshot) triggers a refresh of the
        // derived board; a slow poll catches anything the feed missed.
        viewModelScope.launch {
            bridge.feed().collect { refresh() }
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                refresh()
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

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(bridge: BridgeClient, connectionStore: ConnectionStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BoardViewModel(bridge, connectionStore) as T
                }
            }
    }
}
