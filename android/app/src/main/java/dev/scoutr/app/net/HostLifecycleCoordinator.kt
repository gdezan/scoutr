package dev.scoutr.app.net

import android.util.Log
import dev.scoutr.app.data.HostIdentityReplacement
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.classifyScoutrApiCompatibility
import dev.scoutr.app.data.HostProfile
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.ProbedHost
import dev.scoutr.app.data.UpdateHostDisposition
import dev.scoutr.app.service.PushRegistrationManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * The only production seam allowed to change a registered host binding.
 * Retirement is deliberately completed before registry credentials change:
 * old HTTP jobs and sockets cannot observe the new credential revision, and a
 * same-id refresh cannot resurrect work that was started against the old one.
 */
class HostLifecycleCoordinator(
    private val registry: HostRegistryStore,
    private val hostClients: HostClientFactory,
    private val connections: HostConnectionCoordinator,
    private var pushRegistrations: PushRegistrationManager? = null,
    private val cleanupLocal: (String) -> Unit = {},
    private val copyRetainedMetadata: ((String, String) -> Unit)? = null,
    private val onActivated: (String) -> Unit = {},
) {
    private val mutableRemovingHostIds = MutableStateFlow<Set<String>>(emptySet())
    private val lifecycleLock = Mutex()
    val removingHostIds: StateFlow<Set<String>> = mutableRemovingHostIds.asStateFlow()

    private suspend fun <T> serialized(block: suspend () -> T): T {
        lifecycleLock.lock()
        return try {
            block()
        } finally {
            lifecycleLock.unlock()
        }
    }

    /** Adds a new profile, or retires and refreshes the old revision for the same id. */
    suspend fun addOrRefresh(probe: ProbedHost, token: String): HostProfile =
        withContext(NonCancellable) { serialized {
            val old = connections.currentBinding(probe.hostId)
            if (old != null) {
                pushRegistrations?.prepareRetire(probe.hostId)
                connections.retire<Unit>(old)
            }
            try {
                val profile = registry.addOrRefresh(probe, token)
                activate(profile)
                pushRegistrations?.registerCurrent(profile.hostId)
                profile
            } catch (error: Throwable) {
                // A failed refresh must not strand an otherwise valid profile
                // in the retired state.
                old?.let(connections::activate)
                pushRegistrations?.activate(probe.hostId)
                pushRegistrations?.registerCurrent(probe.hostId)
                throw error
            }
        } }

    /** Startup wires push only after cleanup and migration have been resumed. */
    fun attachPushRegistrations(manager: PushRegistrationManager) {
        check(pushRegistrations == null) { "Push registration manager already attached" }
        pushRegistrations = manager
    }

    /** Makes a migration-created profile usable by all revision-scoped workers. */
    fun activate(profile: HostProfile) {
        connections.activate(profile.hostId)
        connections.currentBinding(profile.hostId)?.let(connections::activate)
        pushRegistrations?.activate(profile.hostId)
        onActivated(profile.hostId)
    }

    /**
     * Retires work, performs best-effort final unregister with the old token,
     * removes credentials, then drains the local cleanup tombstone.
     */
    suspend fun forget(
        hostId: String,
        updateHostDisposition: UpdateHostDisposition? = null,
    ) = withContext(NonCancellable) { serialized {
        registry.validateForget(hostId, updateHostDisposition)
        mutableRemovingHostIds.value = mutableRemovingHostIds.value + hostId
        var old: HostConnectionBinding? = null
        var token: String? = null
        var removed = false
        try {
            old = connections.currentBinding(hostId)
            token = pushRegistrations?.prepareRetire(hostId)
            if (old != null) {
                connections.retire(old!!) { binding ->
                    unregisterBestEffort(binding, token)
                }
            }
            registry.forget(hostId, updateHostDisposition)
            removed = true
            finishCleanup(hostId)
        } finally {
            if (!removed) {
                old?.let(connections::activate)
                pushRegistrations?.activate(hostId)
                pushRegistrations?.registerCurrent(hostId)
            }
            mutableRemovingHostIds.value = mutableRemovingHostIds.value - hostId
        }
    } }

    /**
     * Identity replacement is intentionally separate from ordinary refresh;
     * callers decide the update disposition and whether retained metadata is
     * copied. Board/session snapshots are never copied here.
     */
    suspend fun replaceIdentity(
        previousHostId: String,
        reportedHostId: String,
        baseUrl: String,
        token: String,
        exposure: dev.scoutr.app.data.ExposureKind,
        updateHostDisposition: UpdateHostDisposition? = null,
        migrateRetainedMetadata: Boolean = false,
    ): HostIdentityReplacement = withContext(NonCancellable) { serialized {
        registry.validateIdentityReplacement(previousHostId, reportedHostId, updateHostDisposition)
        val old = connections.currentBinding(previousHostId)
        val unregisterToken = pushRegistrations?.prepareRetire(previousHostId)
        var registryCommitted = false
        try {
            val result = if (old == null) {
                // A replacement normally has an old binding. Keeping this
                // branch makes restart/recovery deterministic if the process
                // lost its in-memory coordinator state.
                registry.replaceIdentity(
                    previousHostId,
                    reportedHostId,
                    baseUrl,
                    token,
                    exposure,
                    updateHostDisposition,
                )
            } else {
                connections.retire(old) { binding ->
                    unregisterBestEffort(binding, unregisterToken)
                    registry.replaceIdentity(
                        previousHostId,
                        reportedHostId,
                        baseUrl,
                        token,
                        exposure,
                        updateHostDisposition,
                    )
                } ?: error("Could not retire host: $previousHostId")
            }
            registryCommitted = true
            activate(result.replacement)
            if (migrateRetainedMetadata) {
                copyRetainedMetadata?.invoke(previousHostId, reportedHostId)
            }
            finishCleanup(previousHostId)
            result
        } finally {
            if (!registryCommitted) {
                old?.let(connections::activate)
                pushRegistrations?.activate(previousHostId)
                pushRegistrations?.registerCurrent(previousHostId)
            }
        }
    } }

    /** Replays cleanup tombstones after a process crash. */
    fun resumePendingCleanup() {
        registry.snapshot().pendingCleanupHostIds.toList().forEach { hostId ->
            runCatching { finishCleanup(hostId) }
                .onFailure { Log.w(TAG, "Host cleanup still pending for $hostId", it) }
        }
    }

    private suspend fun unregisterBestEffort(
        binding: HostConnectionBinding,
        token: String?,
    ) {
        if (token == null) return
        runCatching {
            // Registered-host APIs are retired, so validate the immutable old
            // binding with a one-use client before sending its final unregister.
            val api = hostClients.probe(binding.baseUrl, binding.token)
            val health = api.health()
            if (classifyScoutrApiCompatibility(health.api) !is ScoutrApiCompatibility.Compatible) {
                throw HostIncompatibleException(binding.hostId)
            }
            if (health.hostId?.trim()?.takeIf { it.isNotEmpty() } != binding.hostId) {
                throw HostIdentityChangedException(binding.hostId, health.hostId)
            }
            pushRegistrations?.unregister(binding.hostId, token, api) ?: api.unregisterDevice(token)
        }.onFailure { error ->
            Log.w(TAG, "Could not unregister device from ${binding.hostId}", error)
        }
    }

    private fun finishCleanup(hostId: String) {
        cleanupLocal(hostId)
        registry.completePendingCleanup(hostId)
        registry.clearCredentialsIfUnused()
    }

    private companion object {
        const val TAG = "HostLifecycle"
    }
}
