package dev.scoutr.app.net

import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.classifyScoutrApiCompatibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Immutable credential binding used for one identity check and one host operation. */
data class HostConnectionBinding(
    val hostId: String,
    val connectionRevision: Long,
    val baseUrl: String,
    val token: String,
    val exposure: ExposureKind,
)

/** Identity-gate failure for a bridge that no longer reports the registered host. */
class HostIdentityChangedException(
    val expectedHostId: String,
    val reportedHostId: String?,
) : IOException("Host identity changed: expected $expectedHostId, reported ${reportedHostId ?: "none"}")
/** Identity-gate failure for a bridge missing the required API contract. */
class HostIncompatibleException(hostId: String) : IOException("Host incompatible: $hostId")

/** Work was rejected because its immutable host binding is being retired. */
class HostBindingRetiredException(hostId: String, revision: Long) :
    CancellationException("Host binding retired: $hostId/$revision")

/** Executes one operation while holding the host binding lock after identity validation. */
interface HostBindingGate {
    suspend fun <T> withVerifiedBinding(
        hostId: String,
        operation: suspend (HostConnectionBinding) -> T,
    ): T

    /** Uses one already-captured revision; it must not silently follow a refresh. */
    suspend fun <T> withVerifiedBinding(
        binding: HostConnectionBinding,
        operation: suspend (HostConnectionBinding) -> T,
    ): T
}

/**
 * Serializes validation and dispatch for each host so credential refresh cannot create
 * a check/use race. A fresh immutable binding is resolved for every operation.
 */
class HostConnectionCoordinator(
    private val registry: HostRegistryStore,
    private val healthProbe: suspend (HostConnectionBinding) -> dev.scoutr.app.data.HealthResponse,
    private val work: HostWorkCoordinator = HostWorkCoordinator(),
) : HostBindingGate {
    /** Source-compatible constructor for focused coordinator tests. */
    constructor(
        registry: HostRegistryStore,
        healthProbe: suspend (HostConnectionBinding) -> dev.scoutr.app.data.HealthResponse,
    ) : this(registry, healthProbe, HostWorkCoordinator())

    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val retiredHostIds = ConcurrentHashMap.newKeySet<String>()
    private val mutableRetiredHostIds = MutableStateFlow<Set<String>>(emptySet())
    val retiredHosts: StateFlow<Set<String>> = mutableRetiredHostIds.asStateFlow()

    init {
        registry.snapshot().profiles.forEach { profile ->
            currentBinding(profile.hostId)?.let(work::activate)
        }
    }
    /** Captures credentials and revision without exposing a mutable client. */
    fun currentBinding(hostId: String): HostConnectionBinding? {
        if (hostId in retiredHostIds) return null
        val profile = registry.snapshot().profiles.firstOrNull { it.hostId == hostId } ?: return null
        val credentials = registry.credentials(hostId) ?: return null
        return HostConnectionBinding(
            hostId = hostId,
            connectionRevision = profile.connectionRevision,
            baseUrl = credentials.baseUrl,
            token = credentials.token,
            exposure = credentials.exposure,
        )
    }

    override suspend fun <T> withVerifiedBinding(
        hostId: String,
        operation: suspend (HostConnectionBinding) -> T,
    ): T {
        val lock = hostLocks.computeIfAbsent(hostId) { Mutex() }
        lock.lock()
        try {
            val binding = currentBinding(hostId)
                ?: throw IOException("Host credentials unavailable: $hostId")
            return withVerifiedBindingLocked(binding, operation)
        } finally {
            lock.unlock()
        }
    }

    override suspend fun <T> withVerifiedBinding(
        binding: HostConnectionBinding,
        operation: suspend (HostConnectionBinding) -> T,
    ): T {
        val lock = hostLocks.computeIfAbsent(binding.hostId) { Mutex() }
        lock.lock()
        try {
            if (binding.hostId in retiredHostIds) {
                throw HostBindingRetiredException(binding.hostId, binding.connectionRevision)
            }
            val current = currentBinding(binding.hostId)
            if (current == null || current.connectionRevision != binding.connectionRevision ||
                current.baseUrl != binding.baseUrl || current.token != binding.token ||
                current.exposure != binding.exposure
            ) {
                throw HostBindingRetiredException(binding.hostId, binding.connectionRevision)
            }
            return withVerifiedBindingLocked(binding, operation)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun <T> withVerifiedBindingLocked(
        binding: HostConnectionBinding,
        operation: suspend (HostConnectionBinding) -> T,
    ): T {
        val hostId = binding.hostId
        if (hostId in retiredHostIds) {
            throw HostBindingRetiredException(hostId, binding.connectionRevision)
        }
        return work.track(binding) {
            val health = healthProbe(binding)
            if (classifyScoutrApiCompatibility(health.api) !is ScoutrApiCompatibility.Compatible) {
                throw HostIncompatibleException(hostId)
            }
            if (health.hostId?.trim()?.takeIf { it.isNotEmpty() } != hostId) {
                throw HostIdentityChangedException(hostId, health.hostId)
            }
            val current = currentBinding(hostId)
            if (current == null || current.connectionRevision != binding.connectionRevision ||
                current.baseUrl != binding.baseUrl || current.token != binding.token ||
                hostId in retiredHostIds || !work.isActive(binding)
            ) {
                throw HostBindingRetiredException(hostId, binding.connectionRevision)
            }
            operation(binding)
        }
    }

    /** Retires one exact binding before any registry credential mutation. */
    suspend fun <T> retire(
        binding: HostConnectionBinding,
        finalOperation: (suspend (HostConnectionBinding) -> T)? = null,
    ): T? {
        retiredHostIds += binding.hostId
        mutableRetiredHostIds.value = retiredHostIds.toSet()
        work.retire(binding)
        val lock = hostLocks.computeIfAbsent(binding.hostId) { Mutex() }
        lock.lock()
        try {
            return finalOperation?.invoke(binding)
        } finally {
            lock.unlock()
        }
    }

    /** Captures and retires the currently registered revision. */
    suspend fun <T> retire(
        hostId: String,
        finalOperation: (suspend (HostConnectionBinding) -> T)? = null,
    ): T? = currentBinding(hostId)?.let { retire(it, finalOperation) }

    /** Allows an existing profile to resume after a committed credential refresh. */
    fun activate(binding: HostConnectionBinding) {
        retiredHostIds -= binding.hostId
        mutableRetiredHostIds.value = retiredHostIds.toSet()
        work.activate(binding)
    }

    fun activate(hostId: String) {
        retiredHostIds -= hostId
        mutableRetiredHostIds.value = retiredHostIds.toSet()
        currentBinding(hostId)?.let(work::activate)
    }

    fun isRetired(hostId: String): Boolean = hostId in retiredHostIds

    fun isActive(binding: HostConnectionBinding): Boolean =
        !isRetired(binding.hostId) &&
            registry.snapshot().profiles.any {
                it.hostId == binding.hostId && it.connectionRevision == binding.connectionRevision
            } && work.isActive(binding)
}

/**
 * Owns every asynchronous handle attached to one immutable connection
 * revision.  Cancellation alone is not enough here: sockets can reconnect
 * after their owner is cancelled and a synchronous notification write can
 * race a forget.  The per-binding lock makes retirement a real admission
 * barrier and gives transports a close hook.
 */
class HostWorkCoordinator {
    private data class Slot(
        val lock: Any = Any(),
        var active: Boolean = false,
        val jobs: MutableSet<Job> = ConcurrentHashMap.newKeySet(),
        val closers: MutableSet<() -> Unit> = ConcurrentHashMap.newKeySet(),
    )

    private val slots = ConcurrentHashMap<Pair<String, Long>, Slot>()

    private fun slot(binding: HostConnectionBinding): Slot =
        slots.computeIfAbsent(binding.hostId to binding.connectionRevision) { Slot() }

    fun activate(binding: HostConnectionBinding) {
        val slot = slot(binding)
        synchronized(slot.lock) { slot.active = true }
    }

    fun register(binding: HostConnectionBinding, job: Job) {
        val slot = slot(binding)
        synchronized(slot.lock) {
            check(slot.active) {
                "Host binding is not active: ${binding.hostId}/${binding.connectionRevision}"
            }
            slot.jobs += job
        }
        job.invokeOnCompletion { unregister(binding, job) }
    }

    private fun unregister(binding: HostConnectionBinding, job: Job) {
        val slot = slot(binding)
        synchronized(slot.lock) { slot.jobs -= job }
    }

    /** Registers a socket close operation; false means retirement won the race. */
    fun registerCloser(binding: HostConnectionBinding, closer: () -> Unit): Boolean {
        val slot = slot(binding)
        synchronized(slot.lock) {
            if (!slot.active) return false
            slot.closers += closer
            return true
        }
    }

    /** Runs one synchronous state/notification write only while the binding is admitted. */
    fun <T> withActive(binding: HostConnectionBinding, block: () -> T): T? {
        val slot = slot(binding)
        synchronized(slot.lock) {
            if (!slot.active) return null
            return block()
        }
    }

    fun isActive(binding: HostConnectionBinding): Boolean {
        val slot = slot(binding)
        synchronized(slot.lock) { return slot.active }
    }

    /** Tracks a child job so retirement can cancel and join the actual HTTP operation. */
    suspend fun <T> track(binding: HostConnectionBinding, block: suspend () -> T): T {
        return coroutineScope {
            val job = currentCoroutineContext()[Job]
                ?: error("Host work must run in a coroutine")
            register(binding, job)
            try {
                currentCoroutineContext().ensureActive()
                block()
            } finally {
                unregister(binding, job)
            }
        }
    }

    /** Admission-safe variant for wake jobs that may lose the retirement race. */
    suspend fun <T> trackIfActive(binding: HostConnectionBinding, block: suspend () -> T): T? {
        if (!isActive(binding)) return null
        return try {
            track(binding, block)
        } catch (error: IllegalStateException) {
            if (!isActive(binding)) null else throw error
        }
    }

    /** Rejects new work, closes sockets, then drains revision-scoped jobs. */
    suspend fun retire(binding: HostConnectionBinding) {
        val slot = slot(binding)
        val retiringJobs: List<Job>
        val closers: List<() -> Unit>
        synchronized(slot.lock) {
            slot.active = false
            retiringJobs = slot.jobs.toList()
            closers = slot.closers.toList()
            slot.closers.clear()
        }
        retiringJobs.forEach(Job::cancel)
        closers.forEach { closer -> runCatching { closer() } }
        retiringJobs.forEach { job -> job.join() }
    }
}

/** Creates identity-guarded registered clients and fixed one-use pairing probes. */
interface HostClientFactory {
    fun api(hostId: String): ScoutrApi

    /** A delayed job may use this only for the exact revision it captured. */
    fun api(binding: HostConnectionBinding): ScoutrApi = api(binding.hostId)

    fun terminal(hostId: String): TerminalTransport
    fun terminal(binding: HostConnectionBinding): TerminalTransport = terminal(binding.hostId)
    fun topologyFeedFactory(hostId: String): TopologyFeed.Factory
    fun topologyFeedFactory(binding: HostConnectionBinding): TopologyFeed.Factory =
        topologyFeedFactory(binding.hostId)
    fun probe(host: String, token: String): ScoutrApi
}

/** Shared-pool host client factory; transport wrappers are supplied by the app container. */
class DefaultHostClientFactory(
    private val okHttp: OkHttpClient,
    private val registry: HostRegistryStore,
    private val performanceCounters: PerformanceCounters? = null,
    private val terminalFactory: ((HostConnectionBinding) -> TerminalTransport)? = null,
    private val topologyFactory: ((HostConnectionBinding) -> TopologyFeed.Factory)? = null,
    private val workCoordinator: HostWorkCoordinator = HostWorkCoordinator(),
) : HostClientFactory {
    private val connectionCoordinator = HostConnectionCoordinator(
        registry = registry,
        healthProbe = { binding -> BridgeClient(okHttp, binding, performanceCounters).health() },
        work = workCoordinator,
    )


    override fun api(hostId: String): ScoutrApi =
        BridgeClient(okHttp, connectionCoordinator, hostId, performanceCounters)

    override fun api(binding: HostConnectionBinding): ScoutrApi =
        BridgeClient(okHttp, connectionCoordinator, binding, performanceCounters)

    override fun terminal(hostId: String): TerminalTransport =
        terminal(requireNotNull(connectionCoordinator.currentBinding(hostId)) { "Host binding is not available: $hostId" })

    override fun terminal(binding: HostConnectionBinding): TerminalTransport =
        requireNotNull(terminalFactory) { "Host terminal factory is not configured" }(binding)

    override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory =
        topologyFeedFactory(
            requireNotNull(connectionCoordinator.currentBinding(hostId)) { "Host binding is not available: $hostId" },
        )

    override fun topologyFeedFactory(binding: HostConnectionBinding): TopologyFeed.Factory =
        requireNotNull(topologyFactory) { "Host topology factory is not configured" }(binding)

    override fun probe(host: String, token: String): ScoutrApi = BridgeClient(
        okHttp,
        HostConnectionBinding(
            hostId = "probe",
            connectionRevision = 0L,
            baseUrl = host,
            token = token,
            exposure = ExposureKind.Custom,
        ),
        performanceCounters,
    )

    fun coordinator(): HostConnectionCoordinator = connectionCoordinator

    fun work(): HostWorkCoordinator = workCoordinator
}
