package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.entryText
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Merge a poll result into the transcript: a null cursor replaces the list
 * (full snapshot), an incremental poll appends only ids not already present —
 * a bad cursor can never create duplicate LazyColumn keys (Compose crashes).
 */
fun mergeSessionEntries(
    existing: List<SessionEntry>,
    incoming: List<SessionEntry>,
    incremental: Boolean,
): List<SessionEntry> {
    if (!incremental) return incoming
    val known = existing.mapTo(mutableSetOf()) { it.entryId }
    return existing + incoming.filter { it.entryId !in known }
}

data class ChatUiState(
    val entries: List<SessionEntry> = emptyList(),
    val exists: Boolean = true,
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val lastUserMessage: String?
        get() = entries.asReversed().firstOrNull { it.role == "user" }
            ?.let { entryText(it.content) }
}

/**
 * Transcript + steering for one agent session.
 *
 * Transcript: the bridge reads the pi session JSONL (read-only) and returns
 * incremental entries via ?since=<entryId>. We poll lightly while the screen
 * is alive; the pi session file is append-only so the cursor is stable.
 *
 * Steering: herdr agent.prompt through the bridge (user-initiated only).
 */
class ChatViewModel(
    private val bridge: BridgeClient,
    val paneId: String,
    private val sessionPath: String?,
    val agentStatus: String = "working",
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null

    /** Resolved transcript path; a fresh session's card may not report it yet. */
    private var resolvedPath: String? = sessionPath

    /** True when the agent is blocked on a question the user should answer. */
    val waitingForAnswer: Boolean get() = agentStatus == "blocked"

    init {
        viewModelScope.launch { refresh() }
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2500)
                refresh()
            }
        }
    }

    suspend fun refresh() {
        try {
            val path = resolvedPath ?: resolveSessionPath()
            if (path == null) {
                _ui.update { it.copy(loading = false, exists = false, error = "No session transcript on this agent yet") }
                return
            }
            val response = bridge.session(path, since = _ui.value.entries.lastOrNull()?.entryId)
            _ui.update {
                it.copy(
                    entries = mergeSessionEntries(it.entries, response.entries, incremental = response.since != null),
                    exists = response.exists,
                    loading = false,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.message ?: "session read failed") }
        }
    }

    /** Poll the board until this pane reports a session path (fresh sessions). */
    private suspend fun resolveSessionPath(): String? {
        try {
            val agents = bridge.agents()
            val card = agents.agents.firstOrNull { it.paneId == paneId }
            val path = card?.sessionPath
            if (!path.isNullOrBlank()) {
                resolvedPath = path
                return path
            }
        } catch (_: Exception) {
            // bridge unreachable; keep the current state
        }
        return null
    }

    /** One pane control action (abort/retry/compact/fork/rename/cycle_thinking). */
    fun control(action: String, text: String? = null) {
        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }
            try {
                bridge.controlSession(paneId, action, text)
                _ui.update { it.copy(sending = false) }
                delay(1500)
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(sending = false, error = e.message ?: "control failed") }
            }
        }
    }

    /**
     * Send the input: if the agent is blocked (e.g. pi's ask_user_question),
     * type the answer into its pane; otherwise steer it with a prompt.
     */
    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }
            try {
                if (waitingForAnswer) {
                    bridge.answerQuestion(paneId, text)
                } else {
                    bridge.steer(paneId, text)
                }
                _ui.update { it.copy(sending = false) }
                delay(1500) // let the agent react before re-syncing the transcript
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(sending = false, error = e.message ?: "send failed") }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            bridge: BridgeClient,
            paneId: String,
            sessionPath: String?,
            agentStatus: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(bridge, paneId, sessionPath, agentStatus) as T
            }
        }
    }
}
