package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.classifyScoutrApiCompatibility
import dev.scoutr.app.data.formatScoutrApiIncompatibility
import dev.scoutr.app.net.ScoutrApi
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
    private val bridge: ScoutrApi,
    private val connectionStore: ConnectionStore,
) : ViewModel() {

    private val _state = MutableStateFlow<Loadable<ConnectedInfo>>(Loadable.Idle)
    val state: StateFlow<Loadable<ConnectedInfo>> = _state.asStateFlow()

    /**
     * Probes [host]/[token] and, only on success, persists them. [exposure] is
     * the QR's metadata; a hand-typed address has none and stays [ExposureKind.Custom].
     */
    fun connect(host: String, token: String, exposure: ExposureKind = ExposureKind.Custom) {
        viewModelScope.launch {
            _state.value = Loadable.Loading
            try {
                // Probe with the form values before persisting anything.
                val health = bridge.health(host = host, token = token)
                val compatibility = classifyScoutrApiCompatibility(health.api)
                if (compatibility is ScoutrApiCompatibility.Incompatible) {
                    _state.value = Loadable.Failed(
                        formatScoutrApiIncompatibility(compatibility),
                        FailureKind.Server,
                    )
                    return@launch
                }
                if (health.ok && health.herdr?.connected == true) {
                    connectionStore.save(
                        host = host,
                        token = token,
                        ntfyUrl = health.ntfy?.url,
                        ntfyTopic = health.ntfy?.topic,
                        exposure = exposure,
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
