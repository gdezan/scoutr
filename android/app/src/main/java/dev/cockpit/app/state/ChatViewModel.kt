package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.ModelInfo
import dev.cockpit.app.data.QuestionEntry
import dev.cockpit.app.data.ModelProvider
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.SlashCommandInfo
import dev.cockpit.app.data.entryText
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.IOException
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

enum class MessageDeliveryState { QUEUED, FAILED }

data class PendingUserMessage(
    val localId: String,
    val text: String,
    val state: MessageDeliveryState,
    internal val baselineIds: Set<String> = emptySet(),
)

/**
 * Merge question cards by stable id. Incremental polls only deliver new
 * questions, but a cursor reset re-delivers everything; upserting by id keeps
 * answered state transitions visible and never duplicates card keys.
 */
fun mergeQuestions(
    existing: List<QuestionEntry>,
    incoming: List<QuestionEntry>,
): List<QuestionEntry> {
    if (incoming.isEmpty()) return existing
    val byId = existing.associateByTo(mutableMapOf()) { it.id }
    for (question in incoming) byId[question.id] = question
    return byId.values.sortedBy { it.timestamp }
}


/**
 * Answer safety, mirroring the bridge: one line, no control characters,
 * capped at pi's MAX_FIELD_LENGTH (4000).
 */
fun sanitizeAnswerText(text: String): String {
    val singleLine = text.replace(Regex("[\\r\\n\\u2028\\u2029]+"), " ")
    val clean = singleLine.replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim()
    return if (clean.length > 4000) clean.take(4000) else clean
}
internal fun dropConfirmedMessages(
    pending: List<PendingUserMessage>,
    incoming: List<SessionEntry>,
): List<PendingUserMessage> {
    val freshUsers = incoming.filter { it.role == "user" }.toMutableList()
    return pending.filter { message ->
        // entryText collapses whitespace runs, so the typed text must be
        // normalized the same way or multi-space/newline messages never reconcile.
        val normalizedText = message.text.replace(Regex("\\s+"), " ").trim()
        val match = freshUsers.indexOfFirst { entry ->
            entry.entryId !in message.baselineIds && entryText(entry.content) == normalizedText
        }
        if (match >= 0) freshUsers.removeAt(match)
        match < 0
    }
}

data class ChatUiState(
    val entries: List<SessionEntry> = emptyList(),
    val pendingMessages: List<PendingUserMessage> = emptyList(),
    /** Structured ask_user_question cards derived from session events. */
    val questions: List<QuestionEntry> = emptyList(),
    /** Question id currently sending an answer. */
    val answeringQuestionId: String? = null,
    val questionError: String? = null,
    val exists: Boolean = true,
    val loading: Boolean = true,
    val sending: Boolean = false,
    val error: String? = null,
    /** Agent status from the last /api/agents poll ("working", "blocked", …). */
    val agentStatus: String = "working",
    /** Registry backend id from the card; null until the first poll. */
    val agentKind: String? = null,
    /**
     * Control verbs the backend supports; null until the first poll. Null
     * means "assume pi" so controls never flicker out before the card lands.
     */
    val capabilities: List<String>? = null,
    val agentDisplayName: String? = null,
    val sessionTitle: String = "Session",
    val model: String? = null,
    val thinkingLevel: String? = null,
    val modelProviders: List<ModelProvider> = emptyList(),
    val configurationLoading: Boolean = false,
    val configurationError: String? = null,
    val cwd: String? = null,
    val commands: List<SlashCommandInfo> = emptyList(),
    val commandsLoading: Boolean = true,
    val commandsError: String? = null,
) {
    val lastUserMessage: String?
        get() = pendingMessages.lastOrNull()?.text
            ?: entries.asReversed().firstOrNull { it.role == "user" }
                ?.let { entryText(it.content) }

    val activeModel: ModelInfo?
        get() = modelProviders.flatMap { it.models }.firstOrNull { "${it.provider}/${it.id}" == model }

    val availableThinkingLevels: List<String>
        get() = activeModel?.thinkingLevels ?: emptyList()

    /** Derived: set_thinking is only offered when the backend advertises it. */
    val canSetThinking: Boolean
        get() = capabilities == null || "set_thinking" in capabilities
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

    /** Resolved transcript path; a fresh session's card may not report it yet. */
    private var resolvedPath: String? = sessionPath

    private var nextMessageId = 0L
    private var commandRequestGeneration = 0L
    private var commandCatalogCwd: String? = null
    private var configurationAgent: String? = null
    private var lastCommandRefreshAt = 0L

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

    /**
     * Model catalog for the session's backend. The backend id comes from the
     * agents poll, so this no-ops until the card lands; refresh() re-invokes
     * it after syncStatusAndPath() and whenever the kind changes.
     */
    suspend fun refreshConfiguration() {
        val agent = _ui.value.agentKind ?: return
        val agentChanged = agent != configurationAgent
        // configurationAgent is set only after a successful fetch, so an
        // empty catalog (catalog-less backend like claude) is cached too —
        // never refetch on the 2.5s poll cycle. A failed fetch leaves
        // configurationAgent unset and retries on the next poll.
        if (!agentChanged) return
        _ui.update {
            it.copy(
                configurationLoading = true,
                configurationError = null,
                modelProviders = emptyList(),
            )
        }
        try {
            val response = bridge.models(agent)
            val catalog = response.catalog
            if (catalog == null) {
                _ui.update { it.copy(configurationLoading = false, configurationError = response.error ?: "Model catalog unavailable") }
                return
            }
            configurationAgent = agent
            _ui.update { it.copy(modelProviders = catalog.providers, configurationLoading = false, configurationError = null) }
        } catch (error: Exception) {
            _ui.update { it.copy(configurationLoading = false, configurationError = error.message ?: "Model catalog unavailable") }
        }
    }

    suspend fun refreshCommands(cwd: String? = _ui.value.cwd) {
        val requestGeneration = ++commandRequestGeneration
        val cwdChanged = cwd != commandCatalogCwd
        lastCommandRefreshAt = System.currentTimeMillis()
        _ui.update {
            it.copy(
                commands = if (cwdChanged) emptyList() else it.commands,
                commandsLoading = true,
                commandsError = null,
            )
        }
        try {
            val response = bridge.commands(cwd, _ui.value.agentKind)
            if (commandRequestGeneration != requestGeneration) return
            val catalog = response.catalog
            if (catalog == null) {
                _ui.update { it.copy(commandsLoading = false, commandsError = response.error ?: "Commands unavailable") }
                return
            }
            commandCatalogCwd = cwd
            _ui.update { it.copy(commands = catalog.commands, commandsLoading = false, commandsError = null) }
        } catch (error: Exception) {
            if (commandRequestGeneration == requestGeneration) {
                _ui.update { it.copy(commandsLoading = false, commandsError = error.message ?: "Commands unavailable") }
            }
        }
    }

    fun retryCommands() {
        viewModelScope.launch { refreshCommands() }
    }

    suspend fun refresh() {
        try {
            val path = syncStatusAndPath()
            refreshConfiguration()
            if (_ui.value.commandsLoading || System.currentTimeMillis() - lastCommandRefreshAt >= COMMAND_REFRESH_MS) {
                refreshCommands(_ui.value.cwd)
            }
            if (path == null) {
                // No transcript path yet (fresh backend session: claude writes
                // its JSONL only after the first exchange). That is a pending
                // state, not an error — the composer still steers the agent
                // and the next poll picks the transcript up once it lands.
                _ui.update { it.copy(loading = false, exists = false, error = null) }
                return
            }
            val response = bridge.session(path, since = _ui.value.entries.lastOrNull()?.entryId)
            _ui.update {
                it.copy(
                    entries = mergeSessionEntries(it.entries, response.entries, incremental = response.since != null),
                    questions = mergeQuestions(it.questions, response.questions),
                    pendingMessages = dropConfirmedMessages(it.pendingMessages, response.entries),
                    exists = response.exists,
                    loading = false,
                    model = response.model ?: it.model,
                    thinkingLevel = response.thinkingLevel ?: it.thinkingLevel,
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
                val cwd = card.cwd?.takeIf(String::isNotBlank)
                val cwdChanged = cwd != _ui.value.cwd
                _ui.update {
                    it.copy(
                        agentStatus = card.status,
                        agentKind = card.agentKind.takeIf(String::isNotBlank) ?: it.agentKind,
                        agentDisplayName = card.displayName?.takeIf(String::isNotBlank)
                            ?: card.agentKind?.takeIf(String::isNotBlank)
                            ?: it.agentDisplayName,
                        capabilities = card.capabilities ?: it.capabilities,
                        cwd = cwd,
                        sessionTitle = card.title?.takeIf(String::isNotBlank)
                            ?: cwd?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                            ?: it.sessionTitle,
                    )
                }
                if (cwdChanged) refreshCommands(cwd)
            } else if (_ui.value.cwd != null) {
                _ui.update { it.copy(cwd = null) }
                refreshCommands(null)
            }
        } catch (_: Exception) {
            // bridge unreachable; keep the current state
        }
        return resolvedPath
    }

    /** Run a lifecycle action or select an explicit model/thinking level. */
    fun control(action: String, text: String? = null, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val configurationAction = action == "set_model" || action == "set_thinking"
            if (action == "set_thinking" && !_ui.value.canSetThinking) {
                _ui.update { it.copy(configurationLoading = false, configurationError = "This agent does not support thinking levels") }
                return@launch
            }
            _ui.update {
                it.copy(
                    sending = !configurationAction,
                    configurationLoading = configurationAction,
                    configurationError = null,
                    error = null,
                )
            }
            try {
                bridge.controlSession(paneId, action, text)
                _ui.update {
                    it.copy(
                        sending = false,
                        configurationLoading = false,
                        model = if (action == "set_model") text else it.model,
                        thinkingLevel = if (action == "set_thinking") text else it.thinkingLevel,
                    )
                }
                onSuccess()
                delay(700)
                refresh()
            } catch (e: Exception) {
                val message = e.message ?: "control failed"
                _ui.update {
                    if (configurationAction) it.copy(configurationLoading = false, configurationError = message)
                    else it.copy(sending = false, error = message)
                }
            }
        }
    }

    /** Send now and keep a local transcript row until the session file confirms it. */
    fun send(text: String) {
        if (text.isBlank()) return
        if (text.startsWith('/')) {
            runSlashCommand(text)
            return
        }
        val message = PendingUserMessage(
            localId = "local-${nextMessageId++}",
            text = text,
            state = MessageDeliveryState.QUEUED,
            baselineIds = _ui.value.entries.mapTo(mutableSetOf()) { it.entryId },
        )
        _ui.update { it.copy(pendingMessages = it.pendingMessages + message, sending = true, error = null) }
        deliver(message.localId)
    }

    /**
     * Upload an image attachment, then send "@path [text]" so pi attaches the
     * image to the next prompt. The upload failure marks the send as FAILED.
     */
    fun sendWithAttachment(text: String, name: String, mime: String, bytes: ByteArray) {
        val trimmed = text.trim()
        if (bytes.isEmpty() && trimmed.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }
            try {
                val response = bridge.uploadAttachment(name, mime, bytes)
                if (response.error != null) throw IOException(response.error)
                val prefix = "@${response.path}"
                val full = if (trimmed.isEmpty()) prefix else "$prefix $trimmed"
                _ui.update { it.copy(sending = false) }
                send(full)
            } catch (error: Exception) {
                _ui.update {
                    it.copy(
                        sending = false,
                        error = error.message ?: "Attachment upload failed",
                    )
                }
            }
        }
    }

    /**
     * Answer a structured question card. The composed text is sanitized here
     * (single line, no control chars, capped) and again on the bridge before
     * it is typed into pi's questionnaire.
     */
    fun answerQuestion(questionId: String, text: String) {
        val safe = sanitizeAnswerText(text)
        if (safe.isEmpty()) return
        if (_ui.value.questions.none { it.id == questionId }) return
        if (_ui.value.answeringQuestionId != null) return
        _ui.update { it.copy(answeringQuestionId = questionId, questionError = null) }
        viewModelScope.launch {
            try {
                bridge.answerQuestion(paneId, safe)
                _ui.update { it.copy(answeringQuestionId = null) }
                repeat(3) {
                    refresh()
                    val now = _ui.value.questions.firstOrNull { it.id == questionId }
                    if (now?.answered == true || now == null) return@launch
                    delay(750)
                }
            } catch (error: Exception) {
                _ui.update {
                    it.copy(
                        answeringQuestionId = null,
                        questionError = error.message ?: "Answer failed to send",
                    )
                }
            }
        }
    }

    fun retryPendingMessage(localId: String) {
        val message = _ui.value.pendingMessages.firstOrNull { it.localId == localId } ?: return
        if (message.state != MessageDeliveryState.FAILED) return
        _ui.update { state ->
            state.copy(
                pendingMessages = state.pendingMessages.map {
                    if (it.localId == localId) it.copy(state = MessageDeliveryState.QUEUED) else it
                },
                sending = true,
            )
        }
        deliver(localId)
    }

    private fun runSlashCommand(text: String) {
        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }
            try {
                bridge.runSlashCommand(paneId, text)
                _ui.update { it.copy(sending = false) }
                delay(500)
                refresh()
            } catch (error: Exception) {
                _ui.update { it.copy(sending = false, error = error.message ?: "Command failed") }
            }
        }
    }

    private fun deliver(localId: String) {
        viewModelScope.launch {
            val message = _ui.value.pendingMessages.firstOrNull { it.localId == localId } ?: return@launch
            try {
                if (waitingForAnswer) bridge.answerQuestion(paneId, message.text)
                else bridge.steer(paneId, message.text)

                _ui.update { state -> state.copy(sending = false) }
                repeat(3) {
                    refresh()
                    if (_ui.value.pendingMessages.none { it.localId == localId }) return@launch
                    delay(750)
                }
            } catch (_: Exception) {
                _ui.update { state ->
                    state.copy(
                        pendingMessages = state.pendingMessages.map {
                            if (it.localId == localId) it.copy(state = MessageDeliveryState.FAILED) else it
                        },
                        sending = false,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val COMMAND_REFRESH_MS = 30_000L
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
