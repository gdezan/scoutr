package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.SessionCatalogItem
import dev.scoutr.app.data.SessionCatalogStore
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** One catalog row with its on-device pin/archive flags. */
data class HistoryItem(
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
    val items: List<HistoryItem> = emptyList(),
    val loading: Boolean = true,
    val connected: Boolean = false,
    val error: String? = null,
    val truncated: Boolean = false,
    /** Canonical session currently running a catalog mutation (row shows progress). */
    val busySessionKey: SessionKey? = null,
    /** Human label of the mutation in flight, e.g. "Deleting…". */
    val busyLabel: String? = null,
)

/** Result of a resume/fork: enough to open the chat route. */
data class ResumedSession(
    val key: SessionKey? = null,
    val bootstrapPaneId: String? = null,
    val workspaceId: String? = null,
)

class SessionHistoryViewModel(
    private val bridge: ScoutrApi,
    private val connectionStore: ConnectionStore,
    private val store: SessionCatalogStore,
    initialState: HistoryUiState = HistoryUiState(),
) : ViewModel() {

    private val _ui = MutableStateFlow(initialState)
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    /** The active search query, applied on refresh (and on user commit). */
    private var query: String = ""

    private val poller = Poller(viewModelScope)

    // No init-started polling: HistoryScreen's LifecycleStartEffect drives
    // the loop so it runs only while the screen is STARTED.

    fun setQuery(value: String) {
        query = value.trim()
        viewModelScope.launch { refresh() }
    }

    fun retry() = viewModelScope.launch { refresh() }

    // True while the history screen is STARTED; the lifecycle wrapper owns
    // the loop, and Poller's immediate first tick doubles as the first paint.
    private var lifecycleActive = false

    /** Start the 8s catalog poll; no-op when already polling or offline. */
    fun startPolling() {
        if (lifecycleActive) return
        if (connectionStore.saved == null) {
            _ui.update { it.copy(loading = false) }
            return
        }
        lifecycleActive = true
        poller.start(8.seconds) { refresh() }
    }

    /** Stop the catalog poll; in-flight one-shot actions are untouched. */
    fun stopPolling() {
        if (!lifecycleActive) return
        lifecycleActive = false
        poller.stop()
    }

    suspend fun refresh() {
        _ui.update { it.copy(loading = true, error = null) }
        try {
            val response = bridge.sessionCatalog(query = query.ifBlank { null }, limit = 200)
            val catalogKeys = response.sessions.map { it.key }
            val pinned = store.pinnedKeys(catalogKeys)
            val archived = store.archivedKeys(catalogKeys)
            _ui.update {
                it.copy(
                    items = response.sessions.map { session ->
                        HistoryItem(
                            session = session,
                            pinned = session.key in pinned,
                            archived = session.key in archived,
                        )
                    },
                    loading = false,
                    connected = true,
                    error = null,
                    truncated = response.truncated,
                )
            }
        } catch (e: IOException) {
            _ui.update { it.copy(connected = false, loading = false, error = e.message ?: "lost connection") }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            _ui.update { it.copy(loading = false, error = "Unable to load sessions") }
        }
    }

    fun togglePin(item: HistoryItem) {
        store.setPinned(item.session.key, !item.pinned)
        refreshFlags()
    }

    fun toggleArchive(item: HistoryItem) {
        store.setArchived(item.session.key, !item.archived)
        refreshFlags()
    }

    private fun refreshFlags() {
        val catalogKeys = _ui.value.items.map { it.session.key }
        val pinned = store.pinnedKeys(catalogKeys)
        val archived = store.archivedKeys(catalogKeys)
        _ui.update { state ->
            state.copy(
                items = state.items.map {
                    it.copy(pinned = it.session.key in pinned, archived = it.session.key in archived)
                },
            )
        }
    }

    /** Resume an active or stored session; returns the pane to open, or null on failure. */
    suspend fun resume(item: HistoryItem): ResumedSession? {
        setBusy(item, "Resuming…")
        return try {
            val response = bridge.sessionCatalogAction(CatalogAction.Resume, item.session.key)
            if (response.ok && response.paneId != null) {
                ResumedSession(key = item.session.key, workspaceId = response.workspaceId)
            } else null
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            reportError(e)
            null
        } finally {
            clearBusy()
        }
    }

    suspend fun fork(item: HistoryItem): ResumedSession? {
        setBusy(item, "Forking…")
        return try {
            val response = bridge.sessionCatalogAction(CatalogAction.Fork, item.session.key)
            if (response.ok && response.paneId != null) {
                ResumedSession(bootstrapPaneId = response.paneId, workspaceId = response.workspaceId)
            } else null
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            reportError(e)
            null
        } finally {
            clearBusy()
        }
    }

    suspend fun rename(item: HistoryItem, newName: String): Boolean {
        setBusy(item, "Renaming…")
        return try {
            val response = bridge.sessionCatalogAction(CatalogAction.Rename, item.session.key, text = newName)
            response.ok
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            reportError(e)
            false
        } finally {
            clearBusy()
        }
    }

    /** Close only stops the live pane; the transcript is preserved. */
    suspend fun close(item: HistoryItem): Boolean {
        setBusy(item, "Closing…")
        return try {
            val paneId = item.session.live?.paneId
            if (paneId == null) {
                reportError("Session has no live pane to close")
                return false
            }
            bridge.controlSession(paneId, SessionAction.Close).ok
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            reportError(e)
            false
        } finally {
            clearBusy()
        }
    }

    /** Delete removes the stored session file; the bridge rejects live sessions. */
    suspend fun delete(item: HistoryItem): Boolean {
        setBusy(item, "Deleting…")
        return try {
            val response = bridge.sessionCatalogAction(CatalogAction.Delete, item.session.key)
            if (response.ok) {
                store.setPinned(item.session.key, false)
                store.setArchived(item.session.key, false)
            }
            response.ok
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            reportError(e)
            false
        } finally {
            clearBusy()
        }
    }

    private fun setBusy(item: HistoryItem, label: String) {
        _ui.update { it.copy(busySessionKey = item.session.key, busyLabel = label) }
    }

    private fun clearBusy() {
        _ui.update { it.copy(busySessionKey = null, busyLabel = null) }
    }

    private fun reportError(e: Exception) {
        reportError(e.message ?: "operation failed")
    }

    private fun reportError(message: String) {
        _ui.update { it.copy(error = message) }
    }

    override fun onCleared() {
        poller.stop()
        super.onCleared()
    }
}
