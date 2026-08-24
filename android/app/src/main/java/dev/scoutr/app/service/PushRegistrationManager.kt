package dev.scoutr.app.service

import android.util.Log
import dev.scoutr.app.data.FcmTokenStore
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.net.HostClientFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Registers the current Firebase token independently with every paired host profile. */
class PushRegistrationManager(
    private val registry: HostRegistryStore,
    private val tokens: FcmTokenStore,
    private val hostClients: HostClientFactory,
    private val scope: CoroutineScope,
) {
    private val retiringHostIds = ConcurrentHashMap.newKeySet<String>()
    private val registrationJobs = ConcurrentHashMap<String, Job>()
    private val launchLocks = ConcurrentHashMap<String, Any>()
    private val connectionLocks = ConcurrentHashMap<String, Mutex>()

    init {
        scope.launch {
            combine(registry.states, tokens.token) { state, token -> state.profiles to token }
                .collect { (profiles, token) ->
                    if (token == null) return@collect
                    // Each host gets an independent child job. A failure is
                    // logged in that child and cannot stop the other hosts.
                    profiles.forEach { profile ->
                        register(profile.hostId, profile.profileGeneration, token)
                    }
                }
        }
    }

    fun updateToken(token: String) = tokens.update(token)

    /** Immediate retry used after a same-id refresh commits a new revision. */
    fun registerCurrent(hostId: String) {
        val profile = registry.snapshot().profiles.firstOrNull { it.hostId == hostId } ?: return
        val token = tokens.token.value ?: return
        register(profile.hostId, profile.profileGeneration, token)
    }

    /** Retries every paired host after connectivity or foreground changes. */
    fun registerAllCurrent() {
        val token = tokens.token.value ?: return
        registry.snapshot().profiles.forEach { profile ->
            register(profile.hostId, profile.profileGeneration, token)
        }
    }
    private fun register(hostId: String, generation: Long, token: String) {
        val launchLock = launchLocks.computeIfAbsent(hostId) { Any() }
        synchronized(launchLock) {
            if (hostId in retiringHostIds) return
            registrationJobs[hostId]?.cancel()
            val job = scope.launch {
                val connectionLock = connectionLocks.computeIfAbsent(hostId) { Mutex() }
                connectionLock.withLock {
                    if (hostId in retiringHostIds) return@withLock
                    try {
                        hostClients.api(hostId).registerDevice(token, generation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "FCM registration failed for host $hostId", error)
                    }
                }
            }
            registrationJobs[hostId] = job
            job.invokeOnCompletion {
                synchronized(launchLock) {
                    if (registrationJobs[hostId] === job) registrationJobs.remove(hostId)
                }
            }
        }
    }

    /**
     * Stops observer-launched work and waits for its current HTTP request. The
     * lifecycle coordinator calls this before retiring the binding, then does
     * the final unregister through the old fixed credential snapshot.
     */
    suspend fun prepareRetire(hostId: String): String? {
        val launchLock = launchLocks.computeIfAbsent(hostId) { Any() }
        val job: Job?
        synchronized(launchLock) {
            retiringHostIds += hostId
            job = registrationJobs.remove(hostId)
            job?.cancel()
        }
        job?.join()
        return tokens.token.value
    }

    /** Performs the final unregister after registration work has quiesced. */
    suspend fun unregister(hostId: String, token: String, api: dev.scoutr.app.net.ScoutrApi) {
        val connectionLock = connectionLocks.computeIfAbsent(hostId) { Mutex() }
        connectionLock.withLock {
            runCatching { api.unregisterDevice(token) }
                .onFailure { Log.w(TAG, "FCM unregister failed for host $hostId", it) }
        }
    }

    /** Compatibility convenience for callers that have not adopted lifecycle coordination. */
    suspend fun retire(hostId: String) {
        val token = prepareRetire(hostId) ?: return
        val connectionLock = connectionLocks.computeIfAbsent(hostId) { Mutex() }
        connectionLock.withLock {
            runCatching { hostClients.api(hostId).unregisterDevice(token) }
                .onFailure { Log.w(TAG, "FCM unregister failed for host $hostId", it) }
        }
    }

    fun activate(hostId: String) {
        retiringHostIds -= hostId
    }

    fun isRetiring(hostId: String): Boolean = hostId in retiringHostIds

    private companion object {
        const val TAG = "PushRegistration"
    }
}
