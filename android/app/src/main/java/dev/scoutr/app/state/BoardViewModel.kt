package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.HostPaneKey
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.HostRegistryState
import dev.scoutr.app.data.LegacyMigrationState
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.net.AskAnswer
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostIdentityChangedException
import dev.scoutr.app.net.HostIncompatibleException
import dev.scoutr.app.net.HostWorkCoordinator
import dev.scoutr.app.ui.screens.quickAnswerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One host's process-local Board snapshot. Sessions are kept verbatim from the
 * last successful fetch; staleness and availability come from the shared
 * [HostStatusRepository] so every surface agrees on one status.
 */
data class HostBoardState(
    val sessions: List<SessionDescriptor> = emptyList(),
    val fetchedAtMs: Long? = null,
)

/**
 * The unified multi-host Board state. Raw per-host snapshots stay in
 * [hostBoards]; the visible rows are derived by [BoardMerge] under the shared
 * host filter, so one slow or blocked host never delays or clears another's.
 */
data class BoardUiState(
    val hostBoards: Map<String, HostBoardState> = emptyMap(),
    val statuses: Map<String, HostAvailability> = emptyMap(),
    val profiles: Map<String, HostProfileKey> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    /** Settings order; also the selector's option order after All. */
    val registryOrder: List<String> = emptyList(),
    /** null means All hosts; shared with Sessions through [HostFilterStore]. */
    val filter: String? = null,
    val isRefreshing: Boolean = false,
    val migrationMessage: String? = null,
    val transientError: String? = null,
    /** Pane keys with a quick answer in flight, keyed by host so ids cannot collide. */
    val quickAnswering: Set<HostPaneKey> = emptySet(),
) {
    /** Merged rows for the current filter, globally ordered per [BoardMerge]. */
    val hostedSessions: List<HostedSession>
        get() = BoardMerge.hostedSessions(hostBoards, statuses, profiles, aliases, filter)

    val board: dev.scoutr.app.data.BoardState
        get() = BoardMerge.grouped(hostedSessions)

    private val scopedHostIds: List<String>
        get() = if (filter == null) registryOrder else registryOrder.filter { it == filter }

    /**
     * True while any host *in the selected scope* has reported a successful
     * health check — an offline selected host must not hide behind another
     * host's online status.
     */
    val connected: Boolean
        get() = scopedHostIds.any { statuses[it] is HostAvailability.Online }

    /**
     * Whether at least one paired host can serve data right now. Blocked hosts
     * (incompatible / identity-changed) do not count; offline hosts still do —
     * their actions fail loudly instead of hiding the product.
     */
    val hasCompatibleHost: Boolean
        get() = scopedHostIds.any { id -> statuses[id].usableForData() }

    /** Incompatible / identity-changed hosts, shown as a compact status area. */
    val hostIssues: List<HostIssue>
        get() = BoardMerge.issues(scopedHostIds, aliases, statuses)

    val needsYouCount: Int get() = board.needsYou.size

    /** Alias shown on each card; null under a single-host filter (no redundancy). */
    fun hostLabelFor(hostId: String): String? =
        aliases[hostId]?.takeIf { filter == null && registryOrder.size > 1 }
}

/**
 * Per-host workers owned by the screen lifecycle. Each worker probes its own
 * binding and fetches `agents()` on the poll cadence inside a sibling job, so
 * one host's failure stays local. Registry changes reconcile workers by
 * `(hostId, connectionRevision)`; forget retires the old worker before its
 * replacement could start.
 *
 * Every request runs through the identity-gated fixed-binding client, so an
 * incompatible bridge or a foreign identity classifies into typed failures;
 * responses are applied only while the captured revision still owns the host.
 */
class BoardViewModel internal constructor(
    private val hostClients: HostClientFactory,
    private val registry: HostRegistryStore,
    private val currentBinding: (String) -> HostConnectionBinding?,
    private val work: HostWorkCoordinator,
    private val hostStatus: HostStatusRepository,
    private val hostFilter: HostFilterStore,
    private val migrationState: StateFlow<LegacyMigrationState>?,
    private val adoptLegacyMetadata: (Collection<SessionKey>) -> Unit,
    private val pollInterval: Duration,
    private val clock: () -> Long,
) : ViewModel() {

    constructor(
        hostClients: HostClientFactory,
        registry: HostRegistryStore,
        currentBinding: (String) -> HostConnectionBinding?,
        work: HostWorkCoordinator,
        hostStatus: HostStatusRepository,
        hostFilter: HostFilterStore,
        migrationState: StateFlow<LegacyMigrationState>? = null,
        adoptLegacyMetadata: (Collection<SessionKey>) -> Unit = {},
        pollInterval: Duration = 3.seconds,
    ) : this(
        hostClients = hostClients,
        registry = registry,
        currentBinding = currentBinding,
        work = work,
        hostStatus = hostStatus,
        hostFilter = hostFilter,
        migrationState = migrationState,
        adoptLegacyMetadata = adoptLegacyMetadata,
        pollInterval = pollInterval,
        clock = System::currentTimeMillis,
    )

    private val _ui = MutableStateFlow(BoardUiState())
    val ui: StateFlow<BoardUiState> = _ui.asStateFlow()

    private var lifecycleActive = false
    private var workerScope: CoroutineScope? = null
    private val workerLock = Any()

    /** One scheduled fetch loop per host; keyed and restarted by revision. */
    private class Worker(val revision: Long) {
        val mutex = Mutex()
        var job: Job? = null
    }

    private val workers = HashMap<String, Worker>()

    init {
        viewModelScope.launch {
            registry.states.collect { state ->
                applyRegistry(state)
                if (lifecycleActive) reconcile(state)
            }
        }
        viewModelScope.launch {
            hostStatus.all.collect { statuses ->
                _ui.update { it.copy(statuses = statuses) }
            }
        }
        viewModelScope.launch {
            hostFilter.selectedHostId.collect { selected ->
                _ui.update { it.copy(filter = selected) }
            }
        }
        migrationState?.let { flow ->
            viewModelScope.launch {
                flow.collect { migration ->
                    _ui.update {
                        it.copy(
                            migrationMessage = when (migration) {
                                LegacyMigrationState.None -> null
                                LegacyMigrationState.Pending,
                                LegacyMigrationState.Probing -> "Checking saved bridge…"
                                is LegacyMigrationState.WaitingToRetry -> migration.message
                            },
                        )
                    }
                }
            }
        }
    }

    private fun applyRegistry(state: HostRegistryState) {
        _ui.update {
            it.copy(
                profiles = state.profiles.associate { profile ->
                    profile.hostId to HostProfileKey(profile.hostId, profile.profileGeneration)
                },
                aliases = state.profiles.associate { it.hostId to it.alias },
                registryOrder = state.profiles.map { it.hostId },
            )
        }
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────

    /** Start per-host polling; only the screen lifecycle may call this. */
    fun startPolling() {
        if (lifecycleActive) return
        lifecycleActive = true
        workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        reconcile(registry.snapshot())
    }

    /** Stop the poll; in-flight one-shot actions are untouched. */
    fun stopPolling() {
        if (!lifecycleActive) return
        lifecycleActive = false
        synchronized(workerLock) {
            workers.values.forEach { it.job?.cancel() }
            workers.clear()
        }
        workerScope?.cancel()
        workerScope = null
        _ui.update { it.copy(isRefreshing = false) }
    }

    /**
     * Reconciles one worker per registered `(hostId, connectionRevision)`.
     * Removals cancel and discard immediately; a revision change (credential
     * refresh / identity replacement) restarts the worker for that host.
     */
    private fun reconcile(state: HostRegistryState) {
        val scope = workerScope ?: return
        synchronized(workerLock) {
            val alive = state.profiles.associateBy { it.hostId }
            workers.entries.toList().forEach { (hostId, worker) ->
                val profile = alive[hostId]
                if (profile == null || profile.connectionRevision != worker.revision) {
                    worker.job?.cancel()
                    workers.remove(hostId)
                    if (profile == null) discardHost(hostId)
                }
            }
            alive.values.forEach { profile ->
                if (workers[profile.hostId] == null) {
                    val worker = Worker(profile.connectionRevision)
                    // Registered before the launch: Main.immediate can run the
                    // loop synchronously on this thread, and the cycle must see
                    // its own worker.
                    workers[profile.hostId] = worker
                    worker.job = scope.launch { pollLoop(profile.hostId) }
                }
            }
        }
    }

    private suspend fun pollLoop(hostId: String) {
        while (currentCoroutineContext().isActive) {
            runCycle(hostId)
            delay(pollInterval)
        }
    }

    /**
     * One identity-guarded fetch round for one host. Runs under the per-host
     * mutex so a pull-to-refresh cannot double-fetch against the scheduled
     * cycle, and inside [HostWorkCoordinator.trackIfActive] so retirement
     * (forget / credential refresh) cancels and discards in flight work.
     */
    private suspend fun runCycle(hostId: String) {
        val worker = synchronized(workerLock) { workers[hostId] } ?: return
        worker.mutex.withLock {
            if (!lifecycleActive) return
            val snapshot = registry.snapshot()
            val profile = snapshot.profiles.firstOrNull { it.hostId == hostId } ?: return
            val binding = currentBinding(hostId)
            if (binding == null) {
                hostStatus.record(hostId, HostObservation.Failed("Host is not available"))
                return
            }
            var observation: HostObservation? = null

            work.trackIfActive(binding) {
                try {
                    val response = hostClients.api(binding).agents()
                    // Discard unless the captured revision still owns the host
                    // and the binding stayed admitted — cancellation alone can
                    // lose that race.
                    val current = registry.snapshot().profiles.firstOrNull { it.hostId == hostId }
                    // Write-point admission recheck: retirement may land
                    // between this check and the apply below, but never inside it.
                    if (!work.isActive(binding) || current?.connectionRevision != binding.connectionRevision) {
                        return@trackIfActive
                    }
                    applySessions(hostId, response.agents)
                    observation = HostObservation.Succeeded(clock())
                } catch (incompatible: HostIncompatibleException) {
                    observation = HostObservation.Incompatible(
                        incompatible.message ?: "Incompatible bridge protocol",
                    )
                } catch (changed: HostIdentityChangedException) {
                    observation = HostObservation.IdentityChanged(changed.reportedHostId.orEmpty())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Keep this host's last snapshot; only its status goes stale.
                    observation = HostObservation.Failed(failure.message ?: "lost connection")
                }
            }
            // A retired binding's outcome is discarded, not recorded.
            observation?.takeIf { work.isActive(binding) }?.let { hostStatus.record(hostId, it) }
        }
    }

    private fun applySessions(hostId: String, sessions: List<SessionDescriptor>) {
        adoptLegacyMetadata(sessions.mapNotNull(SessionDescriptor::key))
        _ui.update {
            it.copy(
                hostBoards = it.hostBoards + (
                    hostId to HostBoardState(
                        sessions = sessions,
                        fetchedAtMs = clock(),
                    )
                    ),
            )
        }
    }

    /** Forget: cancel the worker and drop the process-local snapshot. */
    private fun discardHost(hostId: String) {
        _ui.update { it.copy(hostBoards = it.hostBoards - hostId) }
    }

    // ── User actions ──────────────────────────────────────────────────────

    /**
     * Pull-to-refresh: starts all usable hosts' cycles concurrently and
     * completes once they have settled. A failed host does not fail the
     * gesture; its stale markers update independently.
     */
    fun refreshBoard() {
        if (_ui.value.isRefreshing) return
        val targets = registry.snapshot().profiles
            .map { it.hostId }
            .filter { id -> hostStatus.status(id).usableForData() }
        _ui.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                targets.map { hostId ->
                    launch { runCycle(hostId) }
                }.forEach { it.join() }
            } finally {
                _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** Retry one host's cycle from a status card or banner action. */
    fun retryHost(hostId: String) {
        viewModelScope.launch { runCycle(hostId) }
    }

    /** Shared with Sessions; null selects All hosts. */
    fun selectFilter(hostId: String?) {
        hostFilter.select(hostId)
    }

    private fun remoteActionsAllowed(): Boolean =
        migrationState == null || migrationState.value == LegacyMigrationState.None

    /** Surface a transient error on the board. */
    fun reportError(message: String) {
        _ui.update { it.copy(transientError = message) }
    }

    /**
     * Closes an agent's pane via the session's own host. The captured binding
     * pins the route: a concurrent refresh makes the gated client throw stale
     * rather than send an old pane's action to a new generation.
     */
    fun closeAgent(profile: HostProfileKey, paneId: String) {
        if (!remoteActionsAllowed()) {
            reportError("Finishing saved connection migration")
            return
        }
        val binding = bindingForCurrent(profile) ?: return
        viewModelScope.launch {
            try {
                hostClients.api(binding).controlSession(paneId, dev.scoutr.app.data.SessionAction.Close)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                reportError(e.message ?: "could not close agent")
            }
        }
    }

    /**
     * Answer a Needs-you card's open ask straight from the Board.
     *
     * Only the whole round travels: exactly one [AskAnswer] built from the
     * server's own question id and the server's own option label. Eligibility
     * and ids are re-read from the owning host's snapshot, because a poll may
     * have replaced the ask in between. Busy state is keyed by
     * [dev.scoutr.app.data.HostPaneKey], so identical pane ids on two hosts
     * never block each other.
     */
    fun quickAnswer(agent: HostedSession, optionLabel: String) {
        if (!remoteActionsAllowed()) {
            reportError("Finishing saved connection migration")
            return
        }
        val paneId = agent.session.live?.paneId
        if (paneId == null) {
            reportError("That agent is no longer running")
            return
        }
        val busyKey = dev.scoutr.app.data.HostPaneKey(agent.profile, paneId)
        // The one guard against a double tap: the second call sees the first
        // still in flight and stops here, before any request is built.
        if (busyKey in _ui.value.quickAnswering) return

        val attention = _ui.value.hostBoards[agent.profile.hostId]
            ?.sessions
            ?.firstOrNull { it.live?.paneId == paneId }
            ?.attention
        val question = attention?.currentQuestion
        val callId = attention?.callId
        val option = quickAnswerOptions(attention).firstOrNull { it.label == optionLabel }
        if (question == null || callId.isNullOrEmpty() || option == null) {
            viewModelScope.launch {
                runCycle(agent.profile.hostId)
                reportError(QUICK_ANSWER_STALE)
            }
            return
        }

        val binding = bindingForCurrent(agent.profile) ?: return
        _ui.update { it.copy(quickAnswering = it.quickAnswering + busyKey, transientError = null) }
        viewModelScope.launch {
            val failure = try {
                hostClients.api(binding).answerAsk(
                    paneId = paneId,
                    callId = callId,
                    answers = listOf(
                        AskAnswer(questionId = question.id, selectedLabels = listOf(option.label)),
                    ),
                )
                null
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                e
            }
            _ui.update { it.copy(quickAnswering = it.quickAnswering - busyKey) }
            // Refresh on success and on failure alike: only the bridge knows
            // whether the ask is gone. The error is raised after the refresh
            // so the successful reload does not clear it.
            runCycle(agent.profile.hostId)
            failure?.let { reportError(quickAnswerErrorMessage(it)) }
        }
    }

    private fun bindingForCurrent(profile: HostProfileKey): HostConnectionBinding? {
        val current = registry.snapshot().profiles.firstOrNull { it.hostId == profile.hostId }
        if (current == null || current.profileGeneration != profile.profileGeneration) return null
        return currentBinding(profile.hostId)
    }

    private fun quickAnswerErrorMessage(error: Exception): String = when {
        // The bridge rejects an ask that no longer matches the pane with 409:
        // someone (or something) got there first.
        error is BridgeException && error.status == 409 -> QUICK_ANSWER_STALE
        else -> error.message ?: "Answer failed to send"
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private companion object {
        const val QUICK_ANSWER_STALE = "That question is no longer open"
    }
}
