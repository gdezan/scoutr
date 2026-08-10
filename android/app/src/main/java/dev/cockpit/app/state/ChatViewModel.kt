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
    /** Agent status from the last /api/agents poll ("working", "blocked", …). */
    val agentStatus: String = "working",
    val liveOutputExpanded: Boolean = false,
    val liveOutputLoading: Boolean = false,
    val liveOutputText: String = "",
    val liveOutputRevision: Long = 0,
    val liveOutputTruncated: Boolean = false,
    val liveOutputError: String? = null,
) {
    val lastUserMessage: String?
        get() = entries.asReversed().firstOrNull { it.role == "user" }
            ?.let { entryText(it.content) }

    val liveOutputSummary: String
        get() = meaningfulLiveOutputLines(liveOutputText)
            .lastOrNull()
            ?.trim()
            ?: when (agentStatus) {
                "working" -> "Agent working"
                "blocked" -> "Agent needs you"
                "done" -> "Agent finished"
                "idle" -> "Agent idle"
                else -> "Live output"
            }
}

internal fun meaningfulLiveOutputLines(text: String): List<String> = text
    .lineSequence()
    .map(String::trimEnd)
    .filterNot { isLiveOutputChromeLine(it.trim()) }
    .toList()

internal fun isLiveOutputChromeLine(line: String): Boolean {
    if (line.isBlank() || line.none(Char::isLetterOrDigit)) return true
    if (line.startsWith("Elapsed ", ignoreCase = true)) return true
    if (line.startsWith("Took ", ignoreCase = true) && line.drop(5).firstOrNull()?.isDigit() == true) return true
    if (line.endsWith("Working...", ignoreCase = true)) return true
    if (line.contains("cache R/W", ignoreCase = true)) return true
    return line.count { it == '│' } >= 2 && line.contains('/')
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
    agentStatus: String = "working",
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState(agentStatus = agentStatus))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null
    private var liveOutputJob: Job? = null

    /** Resolved transcript path; a fresh session's card may not report it yet. */
    private var resolvedPath: String? = sessionPath

    /** True when the agent is blocked on a question the user should answer. */
    val waitingForAnswer: Boolean get() = _ui.value.agentStatus == "blocked"

    init {
        viewModelScope.launch { refresh() }
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2500)
                refresh()
            }
        }
    }

    fun setLiveOutputExpanded(expanded: Boolean) {
        _ui.update { it.copy(liveOutputExpanded = expanded) }
        if (!expanded) stopLiveOutputPolling()
    }

    fun startLiveOutputPolling() {
        if (!_ui.value.liveOutputExpanded || liveOutputJob?.isActive == true) return
        liveOutputJob = viewModelScope.launch {
            while (isActive) {
                refreshLiveOutput()
                delay(LIVE_OUTPUT_POLL_MS)
            }
        }
    }

    fun stopLiveOutputPolling() {
        liveOutputJob?.cancel()
        liveOutputJob = null
    }

    suspend fun refreshLiveOutput() {
        _ui.update { it.copy(liveOutputLoading = it.liveOutputText.isEmpty()) }
        try {
            val response = bridge.liveOutput(paneId, LIVE_OUTPUT_LINES)
            val output = response.output
            if (output == null) {
                _ui.update { it.copy(liveOutputLoading = false, liveOutputError = response.error ?: "Live output unavailable") }
                return
            }
            _ui.update {
                it.copy(
                    liveOutputLoading = false,
                    liveOutputText = output.text,
                    liveOutputRevision = output.revision,
                    liveOutputTruncated = output.truncated,
                    liveOutputError = null,
                )
            }
        } catch (error: Exception) {
            _ui.update { it.copy(liveOutputLoading = false, liveOutputError = error.message ?: "Live output unavailable") }
        }
    }

    suspend fun refresh() {
        try {
            val path = syncStatusAndPath()
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

    /** Refresh the agent's status and session path from the board. */
    private suspend fun syncStatusAndPath(): String? {
        try {
            val agents = bridge.agents()
            val card = agents.agents.firstOrNull { it.paneId == paneId }
            if (card != null) {
                resolvedPath = card.sessionPath?.takeIf { it.isNotBlank() } ?: resolvedPath
                _ui.update { it.copy(agentStatus = card.status) }
            }
        } catch (_: Exception) {
            // bridge unreachable; keep the current state
        }
        return resolvedPath
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
        liveOutputJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val LIVE_OUTPUT_LINES = 80
        private const val LIVE_OUTPUT_POLL_MS = 1_500L
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
