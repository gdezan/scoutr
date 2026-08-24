package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.HostSessionKey
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.SessionCatalogItem
import dev.scoutr.app.data.SessionCatalogResponse
import dev.scoutr.app.data.SessionCatalogStore
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.SessionSnapshotStore
import dev.scoutr.app.data.validateSessionCatalogResponse
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostIdentityChangedException
import dev.scoutr.app.net.HostIncompatibleException
import dev.scoutr.app.net.HostWorkCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One host's Sessions snapshot: the last valid unfiltered catalog, in memory. */
data class HostCatalogState(
    val items: List<SessionCatalogItem> = emptyList(),
    val fetchedAtMs: Long? = null,
    val truncated: Boolean = false,
)

/** One catalog row with its owning host and on-device pin/archive flags. */
data class HistoryItem(
    val hostId: String,
    val session: SessionCatalogItem,
    val pinned: Boolean,
    val archived: Boolean,
)

/** Which sessions the Sessions tab shows. Starts on All so today's work is visible without an Active-only cut. */
enum class HistoryScope(val label: String) {
    All("All"),
    Active("Active"),
    Completed("Completed"),
    Pinned("Pinned"),
    Archived("Archived"),
}

data class HistoryUiState(
    /** Base snapshots per host: cached-first, then live worker updates. */
    val hostCatalogs: Map<String, HostCatalogState> = emptyMap(),
    /** Search-only results per host; never persisted, never mixed into the cache. */
    val queryResults: Map<String, HostCatalogState> = emptyMap(),
    val statuses: Map<String, HostAvailability> = emptyMap(),
    val profiles: Map<String, HostProfileKey> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    /** Settings order; also the selector's option order after All. */
    val registryOrder: List<String> = emptyList(),
    /** null means All hosts; shared with Board through [HostFilterStore]. */
    val filter: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    /** True until the persistent caches have had their first load. */
    val loading: Boolean = true,
    val transientError: String? = null,
    /** Host-qualified session currently running a catalog mutation (row shows progress). */
    val busySessionKey: HostSessionKey? = null,
    /** Human label of the mutation in flight, e.g. "Deleting…". */
    val busyLabel: String? = null,
) {
    internal fun includedSourceHosts(searching: Boolean): List<String> =
        hostCatalogs.keys.filter { id ->
            (filter == null || id == filter) &&
                statuses[id].usableForData() &&
                profiles.containsKey(id) &&
                (!searching || id in queryResults || statuses[id] is HostAvailability.Offline)
        }

    /** True while any selected host has reported a successful health check. */
    val connected: Boolean get() = statuses.values.any { it is HostAvailability.Online }

    /** Aliases of selected hosts whose newest-200 cap is flagged, for the copy line. */
    fun truncatedAliases(): List<String> {
        val searching = query.isNotEmpty()
        val sources = if (searching) queryResults else hostCatalogs
        return includedSourceHosts(searching)
            .mapNotNull { id -> sources[id]?.takeIf { it.truncated }?.let { aliases[id] ?: id } }
            .sorted()
    }
}

/** Result of a resume/fork: enough to open the chat route on the owning host. */
data class ResumedSession(
    val key: SessionKey? = null,
    val bootstrapPaneId: String? = null,
    val workspaceId: String? = null,
    val profile: HostProfileKey? = null,
)

/**
 * Per-host Sessions workers over the durable [SessionSnapshotStore] cache.
 *
 * Cache-first: every paired host's persisted snapshot loads concurrently
 * before the first network refresh, so rows are visible instantly and stay
 * visible when a host goes offline. Independent unfiltered workers then
 * reconcile by `(hostId, connectionRevision)` on the screen lifecycle cadence,
 * exactly like the Board's. Search runs as separate generation-guarded jobs —
 * cancelled by a newer keystroke, a blank query, or any registry/status change
 * for that host — and never touches the disk cache.
 */
class SessionHistoryViewModel internal constructor(
    private val hostClients: HostClientFactory,
    private val registry: HostRegistryStore,
    private val currentBinding: (String) -> HostConnectionBinding?,
    private val work: HostWorkCoordinator,
    private val hostStatus: HostStatusRepository,
    private val snapshots: SessionSnapshotStore,
    private val catalogStore: SessionCatalogStore,
    private val hostFilter: HostFilterStore,
    private val adoptLegacyMetadata: (Collection<SessionKey>) -> Unit,
    private val pollInterval: Duration,
    private val searchDebounceMs: Long,
    private val clock: () -> Long,
) : ViewModel() {

    constructor(
        hostClients: HostClientFactory,
        registry: HostRegistryStore,
        currentBinding: (String) -> HostConnectionBinding?,
        work: HostWorkCoordinator,
        hostStatus: HostStatusRepository,
        snapshots: SessionSnapshotStore,
        catalogStore: SessionCatalogStore,
        hostFilter: HostFilterStore,
        adoptLegacyMetadata: (Collection<SessionKey>) -> Unit = {},
        pollInterval: Duration = 8.seconds,
        searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
    ) : this(
        hostClients, registry, currentBinding, work, hostStatus, snapshots,
        catalogStore, hostFilter, adoptLegacyMetadata, pollInterval, searchDebounceMs,
        System::currentTimeMillis,
    )

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    private var lifecycleActive = false
    private var workerScope: CoroutineScope? = null
    private val workerLock = Any()

    private class Worker(val revision: Long) {
        val mutex = Mutex()
        var job: Job? = null
    }

    private val workers = HashMap<String, Worker>()

    init {
        viewModelScope.launch {
            registry.states.collect { state ->
                _ui.update {
                    it.copy(
                        profiles = state.profiles.associate { p ->
                            p.hostId to HostProfileKey(p.hostId, p.profileGeneration)
                        },
                        aliases = state.profiles.associate { it.hostId to it.alias },
                        registryOrder = state.profiles.map { it.hostId },
                    )
                }
                if (lifecycleActive) reconcile(state)
            }
        }
        viewModelScope.launch {
            hostStatus.all.collect { statuses -> _ui.update { it.copy(statuses = statuses) } }
        }
        viewModelScope.launch {
            hostFilter.selectedHostId.collect { selected -> _ui.update { it.copy(filter = selected) } }
        }
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────

    fun startPolling() {
        if (lifecycleActive) return
        lifecycleActive = true
        workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        viewModelScope.launch {
            // Cache-first: persisted snapshots render before any network hop.
            val cached = registry.snapshot().profiles.map { profile ->
                launch {
                    val record = withContext(Dispatchers.IO) { snapshots.read(profile.hostId) }
                    if (record != null) {
                        _ui.update {
                            it.copy(
                                hostCatalogs = it.hostCatalogs + (
                                    profile.hostId to HostCatalogState(
                                        items = record.sessions,
                                        fetchedAtMs = record.fetchedAtMs,
                                        truncated = record.truncated,
                                    )
                                    ),
                            )
                        }
                    }
                }
            }
            cached.forEach { it.join() }
            _ui.update { it.copy(loading = false) }
            if (lifecycleActive) reconcile(registry.snapshot())
        }
    }

    fun stopPolling() {
        if (!lifecycleActive) return
        lifecycleActive = false
        cancelSearchJobs()
        synchronized(workerLock) {
            workers.values.forEach { it.job?.cancel() }
            workers.clear()
        }
        workerScope?.cancel()
        workerScope = null
    }

    private fun reconcile(state: dev.scoutr.app.data.HostRegistryState) {
        val scope = workerScope ?: return
        synchronized(workerLock) {
            val alive = state.profiles.associateBy { it.hostId }
            workers.entries.toList().forEach { (hostId, worker) ->
                val profile = alive[hostId]
                if (profile == null || profile.connectionRevision != worker.revision) {
                    worker.job?.cancel()
                    workers.remove(hostId)
                    // Removal drops memory rows (disk stays until forget); a
                    // revision change invalidates this host's search results too.
                    _ui.update { it.copy(queryResults = it.queryResults - hostId) }
                    if (profile == null) {
                        _ui.update { it.copy(hostCatalogs = it.hostCatalogs - hostId) }
                    }
                }
            }
            alive.values.forEach { profile ->
                if (workers[profile.hostId] == null) {
                    val worker = Worker(profile.connectionRevision)
                    // Registered before the launch: Main.immediate can run the
                    // loop synchronously, and the cycle must see its worker.
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
     * One identity-guarded unfiltered fetch for one host: validate, apply to
     * that host only, persist through an atomic snapshot write. Failures keep
     * the previous snapshot and mark only this host stale.
     */
    private suspend fun runCycle(hostId: String) {
        val worker = synchronized(workerLock) { workers[hostId] } ?: return
        worker.mutex.withLock {
            if (!lifecycleActive) return
            fetchAndApply(hostId, query = null)
        }
    }

    private suspend fun fetchAndApply(hostId: String, query: String?) {
        val binding = currentBinding(hostId) ?: run {
            hostStatus.record(hostId, HostObservation.Failed("Host is not available"))
            return
        }
        var observation: HostObservation? = null
        work.trackIfActive(binding) {
            try {
                val response = hostClients.api(binding).sessionCatalog(query = query, limit = CATALOG_LIMIT)
                val current = registry.snapshot().profiles.firstOrNull { it.hostId == hostId }
                val stillOwner = work.isActive(binding) &&
                    current?.connectionRevision == binding.connectionRevision
                if (!stillOwner) return@trackIfActive
                when (val validation = validateSessionCatalogResponse(response.sessions)) {
                    is dev.scoutr.app.data.CatalogValidation.Valid -> {
                        observation = HostObservation.Succeeded(clock())
                        if (query == null) {
                            // Write-point admission recheck: retirement must not
                            // repopulate memory or disk after a credential swap.
                            if (!work.isActive(binding)) return@trackIfActive
                            adoptLegacyMetadata(response.sessions.map { it.key })
                            applyCatalog(hostId, response)
                            // Persist outside the guard's hot path is fine —
                            // the write itself must be atomic, not prompt.
                            withContext(Dispatchers.IO) {
                                snapshots.write(hostId, clock(), response.truncated, response.sessions)
                            }
                        }
                    }
                    is dev.scoutr.app.data.CatalogValidation.Invalid -> {
                        // A bad live response is host-local: keep the previous
                        // snapshot, mark the host stale, leave the cache alone.
                        observation = HostObservation.Failed("Sessions data was invalid")
                    }
                }
                if (query != null && stillOwner) applySearchResult(hostId, response)
            } catch (incompatible: HostIncompatibleException) {
                observation = HostObservation.Incompatible(
                    incompatible.message ?: "Incompatible bridge protocol",
                )
            } catch (changed: HostIdentityChangedException) {
                observation = HostObservation.IdentityChanged(changed.reportedHostId.orEmpty())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                observation = HostObservation.Failed(failure.message ?: "lost connection")
            }
        }
        // A retired binding's outcome is discarded, not recorded.
        observation?.takeIf { work.isActive(binding) }?.let {
            hostStatus.record(hostId, it)
            // The banner mirrors the shared status: failures explain why rows
            // may be stale, successes clear the stale explanation.
            when (it) {
                is HostObservation.Failed ->
                    _ui.update { state -> state.copy(transientError = it.message) }
                is HostObservation.Succeeded ->
                    _ui.update { state -> state.copy(transientError = null) }
                else -> Unit
            }
        }
    }

    private fun applyCatalog(hostId: String, response: SessionCatalogResponse) {
        _ui.update {
            it.copy(
                hostCatalogs = it.hostCatalogs + (
                    hostId to HostCatalogState(
                        items = response.sessions,
                        fetchedAtMs = clock(),
                        truncated = response.truncated,
                    )
                    ),
            )
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    private var searchGeneration = 0
    private val searchJobs = mutableListOf<Job>()

    private fun cancelSearchJobs() {
        synchronized(searchJobs) {
            searchJobs.forEach { it.cancel() }
            searchJobs.clear()
        }
    }

    /**
     * Debounced cross-host search. A new query bumps the generation and kills
     * every job from the previous one; a blank query additionally drops all
     * search results so rows derive straight from the base snapshots.
     */
    fun setQuery(value: String) {
        val next = value.trim()
        if (next == _ui.value.query) return
        searchGeneration++
        cancelSearchJobs()
        _ui.update {
            it.copy(
                query = next,
                queryResults = emptyMap(),
                searching = false,
                transientError = if (next.isEmpty()) it.transientError else null,
            )
        }
        if (next.isEmpty()) return

        val generation = searchGeneration
        val debounced = viewModelScope.launch {
            delay(searchDebounceMs)
            runSearch(next, generation)
        }
        synchronized(searchJobs) { searchJobs += debounced }
    }

    private suspend fun runSearch(text: String, generation: Int) {
        _ui.update { it.copy(searching = true) }
        try {
            val targets = registry.snapshot().profiles.map { it.hostId }
            coroutineScope {
                targets.map { hostId ->
                    launch { searchHost(hostId, text, generation) }
                }.forEach { it.join() }
            }
        } finally {
            if (generation == searchGeneration) {
                _ui.update { it.copy(searching = false) }
            }
        }
    }

    /**
     * One host's search leg. Online compatible hosts query the bridge so
     * matches beyond their cached newest 200 stay discoverable; offline hosts
     * fall back to filtering their own cached rows. Results live only in
     * memory and only while the capturing generation is still current.
     */
    private suspend fun searchHost(hostId: String, text: String, generation: Int) {
        when (val availability = hostStatus.status(hostId)) {
            // Blocked hosts contribute nothing — and any earlier result rows of
            // theirs are dropped so they cannot outlive the block.
            is HostAvailability.Incompatible, is HostAvailability.IdentityChanged -> {
                _ui.update { it.copy(queryResults = it.queryResults - hostId) }
                return
            }
            is HostAvailability.Offline -> {
                filterCachedIntoResults(hostId, text, generation)
                return
            }
            else -> Unit
        }

        val binding = currentBinding(hostId) ?: run {
            filterCachedIntoResults(hostId, text, generation)
            return
        }
        work.trackIfActive(binding) {
            try {
                val response = hostClients.api(binding).sessionCatalog(query = text, limit = CATALOG_LIMIT)
                val current = registry.snapshot().profiles.firstOrNull { it.hostId == hostId }
                val valid = generation == searchGeneration &&
                    text == _ui.value.query &&
                    work.isActive(binding) &&
                    current?.connectionRevision == binding.connectionRevision &&
                    validateSessionCatalogResponse(response.sessions) is dev.scoutr.app.data.CatalogValidation.Valid
                if (valid) applySearchResult(hostId, response)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The host's cached rows remain its search contribution; the
                // status area already says why the live leg failed.
                filterCachedIntoResults(hostId, text, generation)
            }
        }
    }

    private fun filterCachedIntoResults(hostId: String, text: String, generation: Int) {
        // Full guarded-result predicate: a host removed, refreshed, or blocked
        // since the query started contributes nothing.
        val current = registry.snapshot().profiles.firstOrNull { it.hostId == hostId }
        val status = hostStatus.status(hostId)
        val admitted = generation == searchGeneration &&
            text == _ui.value.query &&
            current != null &&
            status.usableForData()
        if (!admitted) return
        val cached = _ui.value.hostCatalogs[hostId] ?: return
        val lowered = text.lowercase()
        val matches = cached.items.filter { item ->
            item.title.lowercase().contains(lowered) ||
                item.preview.lowercase().contains(lowered) ||
                item.cwd.lowercase().contains(lowered)
        }
        _ui.update {
            if (it.query != text) {
                it
            } else {
                it.copy(
                    queryResults = it.queryResults + (
                        hostId to HostCatalogState(
                            items = matches,
                            fetchedAtMs = cached.fetchedAtMs,
                            truncated = cached.truncated,
                        )
                        ),
                )
            }
        }
    }

    private fun applySearchResult(hostId: String, response: SessionCatalogResponse) {
        _ui.update {
            if (it.query.isEmpty()) {
                it
            } else {
                it.copy(
                    queryResults = it.queryResults + (
                        hostId to HostCatalogState(
                            items = response.sessions,
                            fetchedAtMs = clock(),
                            truncated = response.truncated,
                        )
                        ),
                )
            }
        }
    }

    // ── Derived rows ──────────────────────────────────────────────────────

    /**
     * The visible rows for [state]: merged base snapshots when the query is
     * blank, merged search results when one is active — offline hosts always
     * contribute locally filtered cache rows. Pin/archive flags are read in
     * one batch per derivation; ordering stays with the screen's scope and
     * date grouping.
     */
    fun items(state: HistoryUiState = ui.value): List<HistoryItem> {
        val searching = state.query.isNotEmpty()
        val sources = if (searching) state.queryResults else state.hostCatalogs
        val hostIds = state.includedSourceHosts(searching)
        if (hostIds.isEmpty()) return emptyList()
        val keys = hostIds.flatMap { hostId ->
            (sources[hostId]?.items ?: emptyList()).map { item -> HostSessionKey(hostId, item.key) }
        }
        val pinned = catalogStore.pinnedKeys(keys)
        val archived = catalogStore.archivedKeys(keys)
        return hostIds.flatMap { hostId ->
            (sources[hostId]?.items ?: emptyList()).map { item ->
                val hsk = HostSessionKey(hostId, item.key)
                HistoryItem(
                    hostId = hostId,
                    session = item,
                    pinned = hsk in pinned,
                    archived = hsk in archived,
                )
            }
        }
    }

    // ── User actions ──────────────────────────────────────────────────────

    /** Shared with Board; null selects All hosts. */
    fun selectFilter(hostId: String?) {
        hostFilter.select(hostId)
    }

    /** Remote mutations need a live bridge; offline rows stay local-only. */
    fun remoteActionsEnabled(item: HistoryItem): Boolean =
        hostStatus.status(item.hostId) !is HostAvailability.Offline

    /** Refresh every usable host's base snapshot concurrently. */
    fun retry() {
        val targets = registry.snapshot().profiles
            .map { it.hostId }
            .filter { id -> hostStatus.status(id).usableForData() }
        viewModelScope.launch {
            targets.map { hostId -> launch { runCycle(hostId) } }.forEach { it.join() }
        }
    }

    fun togglePin(item: HistoryItem) {
        catalogStore.setPinned(HostSessionKey(item.hostId, item.session.key), !item.pinned)
    }

    fun toggleArchive(item: HistoryItem) {
        catalogStore.setArchived(HostSessionKey(item.hostId, item.session.key), !item.archived)
    }

    fun hostSessionKey(item: HistoryItem): HostSessionKey =
        HostSessionKey(item.hostId, item.session.key)

    /** Resume an active or stored session on its own host; null on failure. */
    suspend fun resume(item: HistoryItem): ResumedSession? =
        mutate(item, "Resuming…") { bridge, key ->
            val response = bridge.sessionCatalogAction(CatalogAction.Resume, key)
            if (response.ok && response.paneId != null) {
                ResumedSession(
                    key = key,
                    workspaceId = response.workspaceId,
                    profile = ui.value.profiles[item.hostId],
                )
            } else null
        }

    suspend fun fork(item: HistoryItem): ResumedSession? =
        mutate(item, "Forking…") { bridge, key ->
            val response = bridge.sessionCatalogAction(CatalogAction.Fork, key)
            if (response.ok && response.paneId != null) {
                ResumedSession(
                    bootstrapPaneId = response.paneId,
                    workspaceId = response.workspaceId,
                    profile = ui.value.profiles[item.hostId],
                )
            } else null
        }

    suspend fun rename(item: HistoryItem, newName: String): Boolean =
        mutate(item, "Renaming…") { bridge, key ->
            bridge.sessionCatalogAction(CatalogAction.Rename, key, text = newName).ok
        } != null

    /** Close only stops the live pane; the transcript is preserved. */
    suspend fun close(item: HistoryItem): Boolean {
        if (item.session.live?.paneId == null) {
            reportError("Session has no live pane to close")
            return false
        }
        return mutate(item, "Closing…") { bridge, key ->
            bridge.controlSession(item.session.live!!.paneId, SessionAction.Close).ok
        } != null
    }

    /** Delete removes the stored session file; the bridge rejects live sessions. */
    suspend fun delete(item: HistoryItem): Boolean {
        val ok = mutate(item, "Deleting…") { bridge, key ->
            bridge.sessionCatalogAction(CatalogAction.Delete, key).ok
        } != null
        if (ok) {
            val hsk = HostSessionKey(item.hostId, item.session.key)
            catalogStore.setPinned(hsk, false)
            catalogStore.setArchived(hsk, false)
        }
        return ok
    }

    /**
     * Runs one remote catalog action against the row's own host, with busy
     * state and error surfacing shared across all mutations.
     */
    private suspend fun <T> mutate(
        item: HistoryItem,
        label: String,
        action: suspend (bridge: dev.scoutr.app.net.ScoutrApi, key: SessionKey) -> T,
    ): T? {
        // Central offline rule: only pin/archive/copy stay local; every
        // remote mutation refuses on a host that cannot be reached.
        if (!remoteActionsEnabled(item)) {
            reportError("Host is offline")
            return null
        }
        val hsk = HostSessionKey(item.hostId, item.session.key)
        _ui.update { it.copy(busySessionKey = hsk, busyLabel = label, transientError = null) }
        try {
            val binding = bindingForCurrent(item.hostId) ?: run {
                reportError("Host is not available")
                return null
            }
            return hostClients.api(binding).let { bridge -> action(bridge, item.session.key) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            reportError(e.message ?: "operation failed")
            return null
        } finally {
            _ui.update { it.copy(busySessionKey = null, busyLabel = null) }
        }
    }

    private fun bindingForCurrent(hostId: String): HostConnectionBinding? {
        val current = registry.snapshot().profiles.firstOrNull { it.hostId == hostId }
            ?: return null
        if (ui.value.profiles[hostId] != HostProfileKey(hostId, current.profileGeneration)) return null
        return currentBinding(hostId)
    }

    private fun reportError(message: String) {
        _ui.update { it.copy(transientError = message) }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private companion object {
        const val CATALOG_LIMIT = 200
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
