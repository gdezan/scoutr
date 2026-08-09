package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChatUiState(
    val entries: List<SessionEntry> = emptyList(),
    val exists: Boolean = true,
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
    /** Pending dialog request on a bridge-owned rpc session. */
    val uiRequest: dev.cockpit.app.data.RpcUiRequest? = null,
)

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
    private val agentStatus: String = "working",
    /** Bridge-owned pi --mode rpc session id; when set, chat talks RPC not panes. */
    val rpcId: String? = null,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null

    val isRpc: Boolean get() = rpcId != null

    /** True when the agent is blocked on a question the user should answer. */
    val waitingForAnswer: Boolean
        get() = if (isRpc) _ui.value.uiRequest != null else agentStatus == "blocked"

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
            if (isRpc) {
                val id = requireNotNull(rpcId)
                val info = bridge.rpcSession(id)
                val since = _ui.value.entries.lastOrNull()?.entryId
                val response = bridge.rpcEntries(id, since = since)
                _ui.update {
                    it.copy(
                        entries = if (since != null) it.entries + response.entries else response.entries,
                        exists = true,
                        loading = false,
                        uiRequest = info.uiRequests.firstOrNull(),
                        error = null,
                    )
                }
                return
            }
            val path = sessionPath ?: run {
                _ui.update { it.copy(loading = false, exists = false, error = "No session transcript on this agent") }
                return
            }
            val response = bridge.session(path, since = _ui.value.entries.lastOrNull()?.entryId)
            _ui.update {
                it.copy(
                    entries = if (response.since != null) it.entries + response.entries else response.entries,
                    exists = response.exists,
                    loading = false,
                    error = null,
                )
            }
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.message ?: "session read failed") }
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
                if (isRpc) {
                    val id = requireNotNull(rpcId)
                    val pending = _ui.value.uiRequest
                    if (pending != null) bridge.rpcRespond(id, pending.id, value = text)
                    else bridge.rpcPrompt(id, text)
                } else if (waitingForAnswer) {
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
            rpcId: String? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(bridge, paneId, sessionPath, agentStatus, rpcId) as T
            }
        }
    }
}
