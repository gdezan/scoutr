package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.SessionAction
import dev.cockpit.app.data.CatalogAction
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.SessionCatalogItem
import dev.cockpit.app.net.CockpitApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One palette row: a live agent, a stored session, or a global action. */
data class PaletteResult(
    val kind: PaletteResultKind,
    val title: String,
    val subtitle: String,
    val paneId: String? = null,
    val sessionPath: String? = null,
    /** Raw agent status for running agents ("working", "blocked", …). */
    val agentStatus: String? = null,
)

enum class PaletteResultKind { Agent, Session }

data class PaletteUiState(
    val open: Boolean = false,
    val query: String = "",
    val results: List<PaletteResult> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Session path currently being resumed (busy state). */
    val busyPath: String? = null,
    /** Pane id currently being controlled (abort/close). */
    val busyPaneId: String? = null,
)

/**
 * Global command palette: one query searches live agents and stored sessions
 * (reusing /api/session-catalog?q=), with inline open/abort/close/resume
 * actions. Steer and rename happen inside the session screen, so the palette
 * only ever needs to get you to the right pane.
 */
class CommandPaletteViewModel(
    private val bridge: CockpitApi,
    private val connectionStore: ConnectionStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(PaletteUiState())
    val ui: StateFlow<PaletteUiState> = _ui.asStateFlow()

    private var searchJob: Job? = null
    private var queryGeneration = 0

    fun open() {
        _ui.update { it.copy(open = true, error = null) }
        if (connectionStore.saved == null) {
            _ui.update { it.copy(error = "Not connected to the bridge") }
            return
        }
        if (_ui.value.query.isNotBlank()) setQuery(_ui.value.query)
        else {
            viewModelScope.launch { refreshAgents() }
        }
    }

    fun close() {
        _ui.update { it.copy(open = false) }
        searchJob?.cancel()
    }

    fun setQuery(query: String) {
        _ui.update { it.copy(query = query, error = null) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250) // debounce keystrokes; the bridge catalog query is cheap
            if (query.isBlank()) {
                refreshAgents()
            } else {
                runSearch(query.trim())
            }
        }
    }

    fun clearQuery() = setQuery("")

    private suspend fun runSearch(query: String) {
        val generation = ++queryGeneration
        _ui.update { it.copy(loading = true, error = null) }
        try {
            val agents = bridge.agents().agents.filter { matches(it.title, it.cwd, it.paneId, query = query) }
            val sessions = bridge.sessionCatalog(query = query, limit = 30).sessions
            if (queryGeneration != generation) return
            _ui.update {
                it.copy(
                    loading = false,
                    results = agentResults(agents) + sessionResults(sessions),
                )
            }
        } catch (error: Exception) {
            if (queryGeneration != generation) return
            _ui.update { it.copy(loading = false, error = error.message ?: "Search failed") }
        }
    }

    private suspend fun refreshAgents() {
        val generation = ++queryGeneration
        _ui.update { it.copy(loading = true, error = null) }
        try {
            val agents = bridge.agents().agents
            if (queryGeneration != generation) return
            _ui.update { it.copy(loading = false, results = agentResults(agents)) }
        } catch (error: Exception) {
            if (queryGeneration != generation) return
            _ui.update { it.copy(loading = false, error = error.message ?: "Search failed") }
        }
    }

    /** Open the chat for a result; steering happens there. */
    fun openResult(result: PaletteResult, onNavigate: () -> Unit) {
        close()
        onNavigate()
    }

    fun resume(path: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busyPath = path, error = null) }
            try {
                bridge.sessionCatalogAction(CatalogAction.Resume, path)
                _ui.update { it.copy(busyPath = null) }
            } catch (error: Exception) {
                _ui.update { it.copy(busyPath = null, error = error.message ?: "Resume failed") }
            }
        }
    }

    fun control(paneId: String, action: SessionAction) {
        viewModelScope.launch {
            _ui.update { it.copy(busyPaneId = paneId, error = null) }
            try {
                bridge.controlSession(paneId, action)
                _ui.update { it.copy(busyPaneId = null) }
                delay(400)
                if (_ui.value.query.isBlank()) refreshAgents()
            } catch (error: Exception) {
                _ui.update { it.copy(busyPaneId = null, error = error.message ?: "Action failed") }
            }
        }
    }

    private fun agentResults(agents: List<dev.cockpit.app.data.AgentCard>) = agents.map { agent ->
        PaletteResult(
            kind = PaletteResultKind.Agent,
            title = agent.title?.takeIf { it.isNotBlank() } ?: agent.agent,
            subtitle = agent.cwd ?: agent.workspaceId,
            paneId = agent.paneId,
            sessionPath = agent.sessionPath,
            agentStatus = agent.status,
        )
    }

    private fun sessionResults(sessions: List<SessionCatalogItem>) = sessions.map { session ->
        PaletteResult(
            kind = PaletteResultKind.Session,
            title = session.title,
            subtitle = session.cwd,
            paneId = session.paneId,
            sessionPath = session.path,
        )
    }

    private fun matches(vararg fields: String?, query: String): Boolean {
        val lower = query.lowercase()
        return fields.any { it?.lowercase()?.contains(lower) == true }
    }

    companion object {
        fun factory(bridge: CockpitApi, connectionStore: ConnectionStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CommandPaletteViewModel(bridge, connectionStore) as T
            }
    }
}
