package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.ProbedHost
import dev.scoutr.app.data.UpdateHostDisposition
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.classifyScoutrApiCompatibility
import dev.scoutr.app.data.formatScoutrApiIncompatibility
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostLifecycleCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Result of a successful health handshake. */
data class ConnectedInfo(val herdrVersion: String?, val herdrProtocol: Int?)

data class PairingHostOption(val hostId: String, val alias: String)

/** Typed pairing result; identity changes never mutate the registry implicitly. */
sealed interface PairingOutcome {
    data class Added(val hostId: String) : PairingOutcome
    data class Refreshed(val hostId: String) : PairingOutcome
    data class IdentityChanged(
        val previousHostId: String,
        val reportedHostId: String,
        val reportedHostAlreadyPaired: Boolean,
        val replacesUpdateHost: Boolean = false,
        val alternativeUpdateHosts: List<PairingHostOption> = emptyList(),
    ) : PairingOutcome
}

/** Probes candidate credentials and commits only stable, fully compatible host identities. */
class ConnectViewModel internal constructor(
    private val probe: (String, String) -> dev.scoutr.app.net.ScoutrApi,
    private val pairedHostIds: () -> Set<String>,
    private val commit: suspend (ProbedHost, String) -> Unit,
    private val replaceIdentity: suspend (String, ProbedHost, String, UpdateHostDisposition?) -> Unit,
    private val updateHostContext: (String) -> Pair<Boolean, List<PairingHostOption>>,
    private val refreshExisting: suspend (ProbedHost, String) -> Unit,
    private val requireStableIdentity: Boolean,
) : ViewModel() {
    constructor(
        hostClients: HostClientFactory,
        hostRegistry: HostRegistryStore,
        lifecycle: HostLifecycleCoordinator,
    ) : this(
        probe = hostClients::probe,
        pairedHostIds = { hostRegistry.snapshot().profiles.map { it.hostId }.toSet() },
        commit = { host, token -> lifecycle.addOrRefresh(host, token) },
        replaceIdentity = { previousHostId, host, token, disposition ->
            lifecycle.replaceIdentity(
                previousHostId = previousHostId,
                reportedHostId = host.hostId,
                baseUrl = host.baseUrl,
                token = token,
                exposure = host.exposure,
                updateHostDisposition = disposition,
            )
        },
        updateHostContext = { previousHostId ->
            val state = hostRegistry.snapshot()
            (state.updateHostId == previousHostId) to state.profiles
                .filterNot { it.hostId == previousHostId }
                .map { PairingHostOption(it.hostId, it.alias) }
        },
        refreshExisting = { host, token ->
            lifecycle.addOrRefresh(host, token)
        },
        requireStableIdentity = true,
    )

    /** Compatibility constructor for tests; production uses the lifecycle seam. */
    constructor(hostClients: HostClientFactory, hostRegistry: HostRegistryStore) : this(
        probe = hostClients::probe,
        pairedHostIds = { hostRegistry.snapshot().profiles.map { it.hostId }.toSet() },
        commit = { host, token -> hostRegistry.addOrRefresh(host, token) },
        replaceIdentity = { previousHostId, host, token, disposition ->
            hostRegistry.replaceIdentity(
                previousHostId,
                host.hostId,
                host.baseUrl,
                token,
                host.exposure,
                disposition,
            )
        },
        updateHostContext = { previousHostId ->
            val state = hostRegistry.snapshot()
            (state.updateHostId == previousHostId) to state.profiles
                .filterNot { it.hostId == previousHostId }
                .map { PairingHostOption(it.hostId, it.alias) }
        },
        refreshExisting = { host, token ->
            hostRegistry.addOrRefresh(host, token)
        },
        requireStableIdentity = true,
    )

    private val mutableState = MutableStateFlow<Loadable<ConnectedInfo>>(Loadable.Idle)
    val state: StateFlow<Loadable<ConnectedInfo>> = mutableState.asStateFlow()

    private val mutableOutcome = MutableStateFlow<PairingOutcome?>(null)
    val outcome: StateFlow<PairingOutcome?> = mutableOutcome.asStateFlow()

    private data class PendingIdentityChange(
        val previousHostId: String,
        val probedHost: ProbedHost,
        val token: String,
        val connectedInfo: ConnectedInfo,
        val alreadyPaired: Boolean,
        val replacesUpdateHost: Boolean,
        val alternativeUpdateHosts: List<PairingHostOption>,
    )

    private var pendingIdentityChange: PendingIdentityChange? = null
    fun connect(host: String, token: String, exposure: ExposureKind = ExposureKind.Custom) {
        pair(host, token, exposure, refreshingHostId = null)
    }

    fun refresh(hostId: String, host: String, token: String, exposure: ExposureKind = ExposureKind.Custom) {
        pair(host, token, exposure, refreshingHostId = hostId)
    }

    private fun pair(
        host: String,
        token: String,
        exposure: ExposureKind,
        refreshingHostId: String?,
    ) {
        viewModelScope.launch {
            mutableState.value = Loadable.Loading
            mutableOutcome.value = null
            pendingIdentityChange = null
            try {
                val health = probe(host, token).health()
                val compatibility = classifyScoutrApiCompatibility(health.api)
                if (compatibility is ScoutrApiCompatibility.Incompatible) {
                    mutableState.value = Loadable.Failed(
                        formatScoutrApiIncompatibility(compatibility),
                        FailureKind.Server,
                    )
                    return@launch
                }
                val reportedId = health.hostId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (!requireStableIdentity) "" else run {
                        mutableState.value = Loadable.Failed("Bridge did not report a stable host identity", FailureKind.Server)
                        return@launch
                    }
                if (refreshingHostId != null && reportedId != refreshingHostId) {
                    val alreadyPaired = reportedId in pairedHostIds()
                    val connectedInfo = ConnectedInfo(health.herdr?.version, health.herdr?.protocol)
                    val (replacesUpdateHost, alternativeUpdateHosts) = updateHostContext(refreshingHostId)
                    pendingIdentityChange = PendingIdentityChange(
                        previousHostId = refreshingHostId,
                        probedHost = ProbedHost(reportedId, host, exposure),
                        token = token,
                        connectedInfo = connectedInfo,
                        alreadyPaired = alreadyPaired,
                        replacesUpdateHost = replacesUpdateHost,
                        alternativeUpdateHosts = alternativeUpdateHosts,
                    )
                    mutableOutcome.value = PairingOutcome.IdentityChanged(
                        previousHostId = refreshingHostId,
                        reportedHostId = reportedId,
                        reportedHostAlreadyPaired = alreadyPaired,
                        replacesUpdateHost = replacesUpdateHost,
                        alternativeUpdateHosts = alternativeUpdateHosts,
                    )
                    mutableState.value = Loadable.Ready(connectedInfo)
                    return@launch
                }
                val existed = reportedId in pairedHostIds()
                commit(ProbedHost(reportedId, host, exposure), token)
                mutableOutcome.value = if (existed) PairingOutcome.Refreshed(reportedId) else PairingOutcome.Added(reportedId)
                mutableState.value = Loadable.Ready(ConnectedInfo(health.herdr?.version, health.herdr?.protocol))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = Loadable.Failed(
                    error.message ?: "Could not reach the bridge",
                    error.failureKind(),
                )
            }
        }
    }

    fun confirmIdentityReplacement(updateDisposition: UpdateHostDisposition? = null) {
        resolveIdentityChange(expectAlreadyPaired = false, outcome = { PairingOutcome.Refreshed(it) }) { pending ->
            if (pending.replacesUpdateHost) {
                require(updateDisposition != null) { "Choose what should provide in-app updates" }
                if (updateDisposition is UpdateHostDisposition.UseExisting) {
                    require(pending.alternativeUpdateHosts.any { it.hostId == updateDisposition.hostId }) {
                        "Choose a paired update host"
                    }
                }
            } else {
                require(updateDisposition == null) { "This profile is not the update host" }
            }
            replaceIdentity(pending.previousHostId, pending.probedHost, pending.token, updateDisposition)
        }
    }

    fun confirmAddAsNew() {
        resolveIdentityChange(expectAlreadyPaired = false, outcome = { PairingOutcome.Added(it) }) { pending ->
            commit(pending.probedHost, pending.token)
        }
    }

    fun confirmRefreshExisting() {
        resolveIdentityChange(expectAlreadyPaired = true, outcome = { PairingOutcome.Refreshed(it) }) { pending ->
            refreshExisting(pending.probedHost, pending.token)
        }
    }

    private fun resolveIdentityChange(
        expectAlreadyPaired: Boolean,
        outcome: (String) -> PairingOutcome,
        mutation: suspend (PendingIdentityChange) -> Unit,
    ) {
        val pending = pendingIdentityChange
            ?.takeIf { it.alreadyPaired == expectAlreadyPaired }
            ?: return
        pendingIdentityChange = null
        viewModelScope.launch {
            mutableState.value = Loadable.Loading
            try {
                mutation(pending)
                mutableOutcome.value = outcome(pending.probedHost.hostId)
                mutableState.value = Loadable.Ready(pending.connectedInfo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pendingIdentityChange = pending
                mutableState.value = Loadable.Failed(
                    error.message ?: "Could not update the saved host",
                    error.failureKind(),
                )
            }
        }
    }

    fun cancelIdentityChange() {
        reset()
    }

    fun reset() {
        mutableState.value = Loadable.Idle
        mutableOutcome.value = null
        pendingIdentityChange = null
    }
}
