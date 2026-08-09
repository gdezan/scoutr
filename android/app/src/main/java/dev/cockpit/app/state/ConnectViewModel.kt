package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the connect form state and the health-check handshake.
 * On success the caller navigates to the board.
 */
class ConnectViewModel(
    private val bridge: BridgeClient,
    private val connectionStore: ConnectionStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    fun connect(host: String, token: String) {
        viewModelScope.launch {
            _state.value = ConnectState.Testing
            try {
                // Probe with the form values before persisting anything.
                val health = bridge.health(host = host, token = token)
                if (health.ok && health.herdr?.connected == true) {
                    connectionStore.save(host, token)
                    _state.value = ConnectState.Connected(health.herdr.version, health.herdr.protocol)
                } else {
                    _state.value = ConnectState.Failed("Bridge reachable, but herdr is not connected")
                }
            } catch (e: Exception) {
                _state.value = ConnectState.Failed(e.message ?: "Could not reach the bridge")
            }
        }
    }

    fun reset() {
        _state.value = ConnectState.Idle
    }

    companion object {
        fun factory(bridge: BridgeClient, connectionStore: ConnectionStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ConnectViewModel(bridge, connectionStore) as T
                }
            }
    }
}
