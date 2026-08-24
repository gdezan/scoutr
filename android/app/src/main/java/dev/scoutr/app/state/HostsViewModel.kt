package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.HostRegistryState
import dev.scoutr.app.data.UpdateHostDisposition
import dev.scoutr.app.net.HostLifecycleCoordinator
import dev.scoutr.app.net.HostWorkCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the Settings Hosts list. */
data class HostRowUi(
    val hostId: String,
    val alias: String,
    val url: String,
    val exposure: ExposureKind,
    val isDefault: Boolean,
    val isUpdateHost: Boolean,
    val status: HostAvailability,
    /** True while [HostLifecycleCoordinator] is retiring/removing this host. */
    val removing: Boolean,
)

data class HostsUiState(
    val rows: List<HostRowUi> = emptyList(),
    /** Host ids with a probe in flight ("Checking"). */
    val checking: Set<String> = emptySet(),
    val transientError: String? = null,
)

/**
 * Drives the Settings Hosts section: one row per paired profile, per-host
 * probes through the shared [HostStatusRepository], and the destructive flows
 * (default/update/forget/identity replacement) executed only through
 * [HostLifecycleCoordinator] so worker retirement and cleanup stay ordered.
 */
class HostsViewModel internal constructor(
    private val registry: HostRegistryStore,
    private val lifecycle: HostLifecycleCoordinator,
    private val hostStatus: HostStatusRepository,
    private val currentBinding: (String) -> dev.scoutr.app.net.HostConnectionBinding?,
    work: HostWorkCoordinator,
    private val clock: () -> Long,
) : ViewModel() {

    constructor(
        registry: HostRegistryStore,
        lifecycle: HostLifecycleCoordinator,
        hostStatus: HostStatusRepository,
        currentBinding: (String) -> dev.scoutr.app.net.HostConnectionBinding?,
        work: HostWorkCoordinator,
    ) : this(registry, lifecycle, hostStatus, currentBinding, work, System::currentTimeMillis)

    private val _ui = MutableStateFlow(HostsUiState())
    val ui: StateFlow<HostsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            registry.states.collect { state -> applyRegistry(state) }
        }
        viewModelScope.launch {
            hostStatus.all.collect { statuses ->
                _ui.update { it.copy(rows = it.rows.map { row -> row.copy(status = statuses[row.hostId] ?: row.status) }) }
            }
        }
        viewModelScope.launch {
            lifecycle.removingHostIds.collect { removing ->
                _ui.update { state ->
                    state.copy(rows = state.rows.map { row -> row.copy(removing = row.hostId in removing) })
                }
            }
        }
    }

    private fun applyRegistry(state: HostRegistryState) {
        val statuses = hostStatus.all.value
        val removing = lifecycle.removingHostIds.value
        _ui.update {
            it.copy(
                rows = state.profiles.map { profile ->
                    HostRowUi(
                        hostId = profile.hostId,
                        alias = profile.alias,
                        url = profile.baseUrl,
                        exposure = profile.exposure,
                        isDefault = profile.hostId == state.defaultHostId,
                        isUpdateHost = profile.hostId == state.updateHostId &&
                            state.inAppUpdatesEnabled,
                        status = statuses[profile.hostId] ?: HostAvailability.Unknown,
                        removing = profile.hostId in removing,
                    )
                },
            )
        }
    }

    fun reportError(message: String?) {
        _ui.update { it.copy(transientError = message) }
    }

    /** On-demand health check of exactly one host ("Refresh"). */
    fun refresh(hostId: String) {
        _ui.update { it.copy(checking = it.checking + hostId, transientError = null) }
        viewModelScope.launch {
            try {
                hostStatus.probe(hostId)
            } finally {
                _ui.update { it.copy(checking = it.checking - hostId) }
            }
        }
    }

    /** Local, nonblank, trimmed; restored again after forget/re-pair of the id. */
    fun rename(hostId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        runCatching { registry.rename(hostId, trimmed) }
            .onFailure { reportError(it.message ?: "Could not rename") }
    }

    fun setDefault(hostId: String) {
        runCatching { registry.setDefaultHost(hostId) }
            .onFailure { reportError(it.message ?: "Could not set default") }
    }

    /**
     * The caller has already shown the signing-key warning; confirmation is
     * the only thing that reaches here.
     */
    fun useForUpdates(hostId: String) {
        runCatching { registry.confirmUpdateHost(hostId) }
            .onFailure { reportError(it.message ?: "Could not change update host") }
    }

    fun disableUpdates() {
        runCatching { registry.disableUpdates() }
            .onFailure { reportError(it.message ?: "Could not disable updates") }
    }

    /**
     * Whether forgetting this host needs the extra disposition step: only an
     * enabled update host with surviving profiles forces a choice.
     */
    fun forgetRequiresUpdateDisposition(hostId: String): Boolean {
        val state = registry.snapshot()
        return state.updateHostId == hostId &&
            state.inAppUpdatesEnabled &&
            state.profiles.any { it.hostId != hostId }
    }

    /** Replacement aliases offered by the forget dialog, in registry order. */
    fun otherHostAliases(hostId: String): Map<String, String> =
        registry.snapshot().profiles
            .filter { it.hostId != hostId }
            .associate { it.hostId to it.alias }

    /**
     * Forgets through the lifecycle coordinator: retire, final unregister,
     * atomic removal, cleanup tombstone. Returns true when the registry no
     * longer holds any host, which sends Settings back toward Connect.
     */
    suspend fun forget(hostId: String, updateHostDisposition: UpdateHostDisposition?): Boolean {
        try {
            lifecycle.forget(hostId, updateHostDisposition)
        } catch (e: Exception) {
            reportError(e.message ?: "Could not forget host")
            return false
        }
        return registry.snapshot().profiles.isEmpty()
    }

    /** True when the bridge's newly reported id is already a paired profile. */
    fun reportedIdIsPaired(previousHostId: String): Boolean {
        val changed = hostStatus.status(previousHostId) as? HostAvailability.IdentityChanged
            ?: return false
        val reported = changed.reportedHostId.takeIf(String::isNotBlank) ?: return false
        return registry.snapshot().profiles.any { it.hostId == reported }
    }

    fun reportedHostId(previousHostId: String): String? =
        (hostStatus.status(previousHostId) as? HostAvailability.IdentityChanged)
            ?.reportedHostId
            ?.takeIf(String::isNotBlank)

    /**
     * Same-id repair: the bridge kept its identity; refresh URL/token/exposure
     * on the existing profile without touching alias/default/update/pins.
     */
    suspend fun refreshExistingProfile(hostId: String, token: String): Boolean {
        val binding = currentBinding(hostId) ?: run {
            reportError("Host is not reachable")
            return false
        }
        return try {
            lifecycle.addOrRefresh(
                dev.scoutr.app.data.ProbedHost(
                    hostId = hostId,
                    baseUrl = binding.baseUrl,
                    exposure = binding.exposure,
                ),
                token,
            )
            hostStatus.record(hostId, HostObservation.Succeeded(clock()))
            true
        } catch (e: Exception) {
            reportError(e.message ?: "Could not refresh profile")
            false
        }
    }

    /**
     * Foreign-id replacement. [copyRetained] moves pin/archive flags after its
     * own explicit confirmation; snapshots, notifications, mutes and terminal
     * ownership are never migrated.
     */
    suspend fun replaceIdentity(
        previousHostId: String,
        newToken: String,
        copyRetained: Boolean,
        updateHostDisposition: UpdateHostDisposition?,
    ): Boolean {
        val reported = reportedHostId(previousHostId) ?: run {
            reportError("No replacement identity was reported")
            return false
        }
        val binding = currentBinding(previousHostId) ?: run {
            reportError("Host is not reachable")
            return false
        }
        return try {
            lifecycle.replaceIdentity(
                previousHostId = previousHostId,
                reportedHostId = reported,
                baseUrl = binding.baseUrl,
                token = newToken,
                exposure = binding.exposure,
                updateHostDisposition = updateHostDisposition,
                migrateRetainedMetadata = copyRetained,
            )
            true
        } catch (e: Exception) {
            reportError(e.message ?: "Could not replace identity")
            false
        }
    }
}
