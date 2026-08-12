package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.CockpitApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Result of a successful health handshake. */
data class ConnectedInfo(val herdrVersion: String?, val herdrProtocol: Int?)

/**
 * Owns the connect form state and the health-check handshake.
 * On success the caller navigates to the board.
 *
 * The handshake is one load: [Loadable.Idle] before the user acts,
 * [Loadable.Loading] while probing, [Loadable.Ready] with the handshake
 * result, [Loadable.Failed] with the taxonomy's [FailureKind].
 */
class ConnectViewModel(
    private val bridge: CockpitApi,
    private val connectionStore: ConnectionStore,
) : ViewModel() {

    private val _state = MutableStateFlow<Loadable<ConnectedInfo>>(Loadable.Idle)
    val state: StateFlow<Loadable<ConnectedInfo>> = _state.asStateFlow()

    fun connect(host: String, token: String) {
        viewModelScope.launch {
            _state.value = Loadable.Loading
            try {
                // Probe with the form values before persisting anything.
                val health = bridge.health(host = host, token = token)
                if (health.ok && health.herdr?.connected == true) {
                    connectionStore.save(
                        host = host,
                        token = token,
                        ntfyUrl = health.ntfy?.url,
                        ntfyTopic = health.ntfy?.topic,
                    )
                    _state.value = Loadable.Ready(
                        ConnectedInfo(health.herdr.version, health.herdr.protocol),
                    )
                } else {
                    _state.value = Loadable.Failed(
                        "Bridge reachable, but herdr is not connected",
                        FailureKind.Server,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = Loadable.Failed(
                    e.message ?: "Could not reach the bridge",
                    e.failureKind(),
                )
            }
        }
    }

    fun reset() {
        _state.value = Loadable.Idle
    }
}