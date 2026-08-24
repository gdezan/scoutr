package dev.scoutr.app.state

import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostIdentityChangedException
import dev.scoutr.app.net.HostIncompatibleException
import dev.scoutr.app.net.HostWorkCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** One classified outcome observed by a probe or an authenticated data operation. */
sealed interface HostObservation {
    data class Succeeded(val atMs: Long) : HostObservation
    data class Failed(val message: String) : HostObservation
    data class Incompatible(val message: String) : HostObservation
    data class IdentityChanged(val reportedHostId: String) : HostObservation
}

/**
 * Process-local reachability/compatibility state for one paired host. Nothing
 * here is persisted: Session snapshot timestamps provide durable freshness,
 * and no claim that a host is "currently online" survives process death.
 */
sealed interface HostAvailability {
    data object Unknown : HostAvailability

    data class Online(val checkedAtMs: Long) : HostAvailability

    data class Offline(val lastSuccessAtMs: Long?, val message: String) : HostAvailability

    data class Incompatible(val message: String) : HostAvailability

    data class IdentityChanged(val reportedHostId: String) : HostAvailability
}

/**
 * Owns independent status for every registered host id. One slow, offline, or
 * incompatible host never touches another host's entry, and no outcome here
 * mutates the registry.
 *
 * Probes capture the current `(hostId, connectionRevision)` binding up front;
 * concurrent probes for the same binding are coalesced (single-flight), never
 * behind one global mutex. The probe runs inside [HostWorkCoordinator] so a
 * credential refresh or forget cancels it; a result is written only while its
 * binding is still active.
 */
class HostStatusRepository(
    private val clients: HostClientFactory,
    private val bindingFor: (String) -> HostConnectionBinding?,
    private val work: HostWorkCoordinator,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val statuses = MutableStateFlow<Map<String, HostAvailability>>(emptyMap())

    /** Current status per host id; hosts without an entry are [HostAvailability.Unknown]. */
    val all: StateFlow<Map<String, HostAvailability>> = statuses.asStateFlow()

    fun status(hostId: String): HostAvailability =
        statuses.value[hostId] ?: HostAvailability.Unknown

    private val lastSuccessAtMs = ConcurrentHashMap<String, Long>()

    private val inFlight = ConcurrentHashMap<Pair<String, Long>, CompletableDeferred<HostAvailability?>>()

    /**
     * Probes one host and records the outcome. Returns the classified
     * availability, or null when the response was discarded because the
     * captured binding was retired before the write.
     */
    suspend fun probe(hostId: String): HostAvailability? {
        val binding = bindingFor(hostId)
            ?: return write(hostId, HostAvailability.Offline(lastSuccessAtMs[hostId], "Host is not available"))
        val key = binding.hostId to binding.connectionRevision
        while (true) {
            val existing = inFlight[key]
            if (existing != null && !existing.isCompleted) return existing.await()
            val mine = CompletableDeferred<HostAvailability?>()
            if (inFlight.putIfAbsent(key, mine) == null) {
                try {
                    val result = runProbe(binding)
                    mine.complete(result)
                    return result
                } catch (failure: Throwable) {
                    if (failure is CancellationException) {
                        // A cancelled leader must not satisfy waiters with success;
                        // let them retry under their own admission.
                        inFlight.remove(key, mine)
                    }
                    mine.completeExceptionally(failure)
                    throw failure
                } finally {
                    inFlight.remove(key, mine)
                }
            }
        }
    }

    private suspend fun runProbe(binding: HostConnectionBinding): HostAvailability? {
        val hostId = binding.hostId
        val outcome = try {
            work.trackIfActive(binding) {
                try {
                    clients.api(binding).health()
                    HostAvailability.Online(clock())
                } catch (incompatible: HostIncompatibleException) {
                    HostAvailability.Incompatible(incompatible.message ?: "Incompatible bridge protocol")
                } catch (changed: HostIdentityChangedException) {
                    HostAvailability.IdentityChanged(changed.reportedHostId.orEmpty())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: IOException) {
                    HostAvailability.Offline(lastSuccessAtMs[hostId], failure.message ?: "unreachable")
                } catch (failure: Exception) {
                    HostAvailability.Offline(lastSuccessAtMs[hostId], failure.message ?: "request failed")
                }
            } ?: return null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (retired: Exception) {
            return null
        }
        return when (outcome) {
            is HostAvailability.Online -> write(hostId, outcome)
            is HostAvailability.Incompatible -> write(hostId, outcome)
            is HostAvailability.IdentityChanged -> write(hostId, outcome)
            is HostAvailability.Offline -> write(hostId, outcome)
            HostAvailability.Unknown -> outcome
        }
    }

    /** Records an outcome observed by an authenticated data operation (Board/Session workers). */
    fun record(hostId: String, observation: HostObservation) {
        when (observation) {
            is HostObservation.Succeeded -> write(hostId, HostAvailability.Online(observation.atMs))
            is HostObservation.Failed -> write(hostId, HostAvailability.Offline(lastSuccessAtMs[hostId], observation.message))
            is HostObservation.Incompatible -> write(hostId, HostAvailability.Incompatible(observation.message))
            is HostObservation.IdentityChanged -> write(hostId, HostAvailability.IdentityChanged(observation.reportedHostId))
        }
    }

    private fun write(hostId: String, availability: HostAvailability): HostAvailability {
        if (availability is HostAvailability.Online) lastSuccessAtMs[hostId] = availability.checkedAtMs
        // Atomic read-modify-write: concurrent per-host workers must not
        // overwrite each other's entries with a stale whole map.
        statuses.update { it + (hostId to availability) }
        return availability
    }

    /** Drops every trace of one host (forget/cleanup); status returns to Unknown. */
    fun remove(hostId: String) {
        lastSuccessAtMs.remove(hostId)
        statuses.update { it - hostId }
    }
}
