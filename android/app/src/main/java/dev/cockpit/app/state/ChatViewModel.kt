package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.SessionAction
import dev.cockpit.app.data.ModelInfo
import dev.cockpit.app.data.QuestionEntry
import dev.cockpit.app.data.ModelProvider
import dev.cockpit.app.data.FileListing
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.SlashCommandInfo
import dev.cockpit.app.data.entryText
import dev.cockpit.app.net.CockpitApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

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

/**
 * Progress of one ask_user_question run inside pi's TUI questionnaire.
 *
 * The questionnaire auto-advances one tab after every answered question
 * (capped at the review tab), so the current tab is one past the last
 * answered question's index. Keys computed for later questions navigate
 * relative to that tab.
 */
data class QuestionnaireRun(
    /** Question ids already recorded in this run; re-answers are no-ops. */
    val answered: Set<String> = emptySet(),
    /** Index of the most recently answered question within its call group. */
    val lastIndex: Int = -1,
)

/** Navigation + submit keys for one answer, plus any keys sent after text. */
internal data class AnswerKeys(
    val keys: List<String>,
    val trailingKeys: List<String>,
    val custom: Boolean,
)

/**
 * Compute the key sequence that selects an answer inside pi's TUI
 * questionnaire (the ask-user-question extension).
 *
 * The questionnaire is keyboard-only: up/down move, space toggles a
 * multi-select option, enter chooses/continues, tab moves between questions.
 * Typed text is dropped while an option list is focused and enter would pick
 * the first option, so option answers travel entirely as keys; custom
 * answers open the "Type something" editor (the first entry after the
 * authored options) with keys, then the caller sends the text and the bridge
 * submits it with a trailing enter. A multi-question ask (n > 1) shows a
 * review tab after the last question, which needs one more enter to submit.
 */
internal fun answerNavigationKeys(
    question: QuestionEntry,
    group: List<QuestionEntry>,
    lastAnsweredIndex: Int?,
    answer: String,
    selectedLabels: List<String> = emptyList(),
): AnswerKeys {
    val n = group.size
    val k = group.indexOfFirst { it.id == question.id }
    if (k < 0) return AnswerKeys(emptyList(), emptyList(), custom = true)
    val tabCount = n + 1
    val currentTab = (lastAnsweredIndex?.plus(1) ?: 0).coerceAtMost(n)
    var delta = k - currentTab
    if (delta < 0) delta += tabCount
    val keys = mutableListOf<String>()
    repeat(delta) { keys += "tab" }
    val labels = selectedLabels.ifEmpty { if (answer.isNotEmpty()) listOf(answer) else emptyList() }
    val indices = labels.mapNotNull { label -> question.options.indexOfFirst { it.label == label }.takeIf { it >= 0 } }
    val custom = question.options.isEmpty() || indices.isEmpty()
    if (custom) {
        // "Type something" is the first entry after the authored options.
        repeat(question.options.size) { keys += "down" }
        keys += "enter"
    } else if (question.multiSelect) {
        var pos = 0
        for (index in indices.sorted()) {
            val step = index - pos
            if (step > 0) repeat(step) { keys += "down" } else if (step < 0) repeat(-step) { keys += "up" }
            keys += "space"
            pos = index
        }
        keys += "enter"
    } else {
        repeat(indices[0]) { keys += "down" }
        keys += "enter"
    }
    val last = n > 1 && k == n - 1
    return if (custom) {
        // Editor enter submits the answer; on the last question of a
        // multi-question ask that lands on the review tab and a second
        // enter submits the whole questionnaire.
        AnswerKeys(keys, if (last) listOf("enter", "enter") else listOf("enter"), custom = true)
    } else {
        if (last) keys += "enter" // review-tab submit
        AnswerKeys(keys, emptyList(), custom = false)
    }
}

/** Questions from the same ask_user_question call, in ask order. */
internal fun questionGroup(questions: List<QuestionEntry>, question: QuestionEntry): List<QuestionEntry> {
    val call = questions.filter { it.callId.isNotEmpty() && it.callId == question.callId }
    return call.ifEmpty { listOf(question) }
}

/** Drop finished questionnaire runs (their group is fully answered or gone). */
internal fun pruneQuestionnaireProgress(
    progress: Map<String, QuestionnaireRun>,
    questions: List<QuestionEntry>,
): Map<String, QuestionnaireRun> =
    progress.filter { (callId, _) ->
        val group = questions.filter { it.callId == callId }
        group.isNotEmpty() && group.any { !it.answered }
    }
internal fun dropConfirmedMessages(
    pending: List<PendingUserMessage>,
    incoming: List<SessionEntry>,
    incomingQuestions: List<QuestionEntry> = emptyList(),
    previouslyAnsweredIds: Set<String> = emptySet(),
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
        if (match >= 0) return@filter false
        // Answers typed in the composer never appear as user entries — pi
        // records them only in the toolResult's details.answers. Confirm the
        // pending bubble once a question in the fresh snapshot is answered
        // with that text, so it lands in the transcript as the answer bubble
        // instead of lingering forever.
        // Only a question that flips to answered NOW may confirm the pending
        // bubble. An old answer with the same text (e.g. "Proceed") must not
        // eat an unrelated later message before its user entry arrives.
        incomingQuestions.none { question ->
            question.answered &&
                question.id !in previouslyAnsweredIds &&
                (question.answerText == normalizedText || question.selected.contains(normalizedText))
        }
    }
}

data class ChatUiState(
    val entries: List<SessionEntry> = emptyList(),
    val pendingMessages: List<PendingUserMessage> = emptyList(),
    /** Structured ask_user_question cards derived from session events. */
    val questions: List<QuestionEntry> = emptyList(),
    /** Question id currently sending an answer. */
    val answeringQuestionId: String? = null,
    /** Answer-send progress per ask_user_question call; see QuestionnaireRun. */
    val questionnaireProgress: Map<String, QuestionnaireRun> = emptyMap(),
    val questionError: String? = null,
    val exists: Boolean = true,
    /** Transcript read lifecycle; the entries/questions themselves stay in separate fields (they are merged in place). */
    val transcript: Loadable<Unit> = Loadable.Idle,
    val sending: Boolean = false,
    /** Send-path failures only (steer/upload/slash); read failures live in [transcript]. */
    val sendError: String? = null,
    /** Agent status from the last /api/agents poll ("working", "blocked", …). */
    val agentStatus: String = "working",
    /**
     * Epoch ms when the agent entered [agentStatus] (bridge-stamped); null
     * until the first poll, or when the bridge has no stamp for the pane. The
     * working indicator's elapsed timer reads it, so it survives backgrounding
     * and reconnects instead of restarting at 0s.
     */
    val statusSinceMs: Long? = null,
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
    /** Model catalog for the session's backend; Ready even for an empty catalog (catalog-less backends are cached too). */
    val configuration: Loadable<List<ModelProvider>> = Loadable.Idle,
    /** A set_model/set_thinking control is in flight (the sheet's busy state; the catalog fetch shows via [configuration]). */
    val configActionBusy: Boolean = false,
    val cwd: String? = null,
    val commands: Loadable<List<SlashCommandInfo>> = Loadable.Idle,
    /** `@` mention candidates for [cwd]; refetched every time a mention opens. */
    val files: Loadable<FileListing> = Loadable.Idle,
) {
    val lastUserMessage: String?
        get() = pendingMessages.lastOrNull()?.text
            ?: entries.asReversed().firstOrNull { it.role == "user" }
                ?.let { entryText(it.content) }

    val activeModel: ModelInfo?
        get() = (configuration as? Loadable.Ready)?.value.orEmpty().flatMap { it.models }.firstOrNull { "${it.provider}/${it.id}" == model }

    val availableThinkingLevels: List<String>
        get() = activeModel?.thinkingLevels ?: emptyList()

    /** Derived: set_thinking is only offered when the backend advertises it. */
    val canSetThinking: Boolean
        get() = capabilities == null || SessionAction.SetThinking.wire in capabilities

    /**
     * True while a question card is still waiting for an answer. Answered
     * questions stay in [questions] for the rest of the session as answer
     * bubbles, so "any question at all" is not the same thing — the working
     * indicator defers to a card only while one is actually pending.
     */
    val hasPendingQuestion: Boolean
        get() = questions.any { !it.answered }
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
    private val bridge: CockpitApi,
    val paneId: String,
    private val sessionPath: String?,
    agentStatus: String = "working",
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState(agentStatus = agentStatus))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val poller = Poller(viewModelScope)

    /** Resolved transcript path; a fresh session's card may not report it yet. */
    private var resolvedPath: String? = sessionPath

    private var nextMessageId = 0L
    private var commandRequestGeneration = 0L
    private var commandCatalogCwd: String? = null
    private var configurationAgent: String? = null
    private var lastCommandRefreshAt = 0L
    private var fileRequestGeneration = 0L

    /** True when the agent is blocked on a question the user should answer. */
    val waitingForAnswer: Boolean get() = _ui.value.agentStatus == "blocked"

    // True while the chat screen is STARTED. The lifecycle wrapper owns the
    // loop; Poller's immediate first tick doubles as the first paint, so
    // there is deliberately no init refresh (Poller's call-site contract).
    private var lifecycleActive = false

    /** Start the 2.5s transcript poll; no-op when already polling. */
    fun startPolling() {
        if (lifecycleActive) return
        lifecycleActive = true
        poller.start(2.5.seconds) { refresh() }
    }

    /** Stop the transcript poll; in-flight one-shot actions are untouched. */
    fun stopPolling() {
        if (!lifecycleActive) return
        lifecycleActive = false
        poller.stop()
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
                configuration = Loadable.Loading,
            )
        }
        try {
            val response = bridge.models(agent)
            val catalog = response.catalog
            if (catalog == null) {
                _ui.update {
                    it.copy(
                        configuration = Loadable.Failed(
                            response.error ?: "Model catalog unavailable",
                            FailureKind.Server,
                        ),
                    )
                }
                return
            }
            configurationAgent = agent
            _ui.update { it.copy(configuration = Loadable.Ready(catalog.providers)) }
        } catch (c: CancellationException) {
            throw c
        } catch (error: Exception) {
            _ui.update {
                it.copy(
                    configuration = Loadable.Failed(
                        error.message ?: "Model catalog unavailable",
                        error.failureKind(),
                    ),
                )
            }
        }
    }

    /**
     * Load the `@` mention candidates for the session's cwd. Called each time
     * a mention opens (not on drill-down), so the menu always shows a fresh
     * listing — a file the agent just wrote is mentionable immediately.
     */
    fun refreshFiles() {
        val cwd = _ui.value.cwd
        val requestGeneration = ++fileRequestGeneration
        if (cwd.isNullOrBlank()) {
            _ui.update { it.copy(files = Loadable.Failed("This session has no workspace", FailureKind.Server)) }
            return
        }
        _ui.update { it.copy(files = Loadable.Loading) }
        viewModelScope.launch {
            try {
                val response = bridge.files(cwd)
                // The agent can cd mid-request; a listing for the old
                // workspace must never populate the new one's menu.
                if (fileRequestGeneration != requestGeneration || _ui.value.cwd != cwd) return@launch
                val listing = response.listing
                _ui.update {
                    it.copy(
                        files = if (listing == null) {
                            Loadable.Failed(response.error ?: "Files unavailable", FailureKind.Server)
                        } else {
                            Loadable.Ready(listing)
                        },
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                if (fileRequestGeneration != requestGeneration || _ui.value.cwd != cwd) return@launch
                _ui.update {
                    it.copy(files = Loadable.Failed(error.message ?: "Files unavailable", error.failureKind()))
                }
            }
        }
    }

    suspend fun refreshCommands(cwd: String? = _ui.value.cwd) {
        val requestGeneration = ++commandRequestGeneration
        val cwdChanged = cwd != commandCatalogCwd
        lastCommandRefreshAt = System.currentTimeMillis()
        _ui.update {
            it.copy(commands = if (cwdChanged) Loadable.Loading else it.commands)
        }
        try {
            val response = bridge.commands(cwd, _ui.value.agentKind)
            if (commandRequestGeneration != requestGeneration) return
            val catalog = response.catalog
            if (catalog == null) {
                _ui.update {
                    it.copy(commands = Loadable.Failed(response.error ?: "Commands unavailable", FailureKind.Server))
                }
                return
            }
            commandCatalogCwd = cwd
            _ui.update { it.copy(commands = Loadable.Ready(catalog.commands)) }
        } catch (c: CancellationException) {
            throw c
        } catch (error: Exception) {
            if (commandRequestGeneration == requestGeneration) {
                _ui.update {
                    it.copy(commands = Loadable.Failed(error.message ?: "Commands unavailable", error.failureKind()))
                }
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
            if (_ui.value.commands !is Loadable.Ready || System.currentTimeMillis() - lastCommandRefreshAt >= COMMAND_REFRESH_MS) {
                refreshCommands(_ui.value.cwd)
            }
            if (path == null) {
                // No transcript path yet (fresh backend session: claude writes
                // its JSONL only after the first exchange). That is a pending
                // state, not an error — the composer still steers the agent
                // and the next poll picks the transcript up once it lands.
                _ui.update { it.copy(transcript = Loadable.Ready(Unit), exists = false) }
                return
            }
            val response = bridge.session(path, since = _ui.value.entries.lastOrNull()?.entryId)
            _ui.update {
                val previouslyAnswered = it.questions
                    .filter { q -> q.answered }
                    .mapTo(mutableSetOf()) { q -> q.id }
                it.copy(
                    entries = mergeSessionEntries(it.entries, response.entries, incremental = response.since != null),
                    questions = mergeQuestions(it.questions, response.questions),
                    questionnaireProgress = pruneQuestionnaireProgress(it.questionnaireProgress, response.questions),
                    pendingMessages = dropConfirmedMessages(
                        it.pendingMessages,
                        response.entries,
                        response.questions,
                        previouslyAnswered,
                    ),
                    exists = response.exists,
                    transcript = Loadable.Ready(Unit),
                    model = response.model ?: it.model,
                    thinkingLevel = response.thinkingLevel ?: it.thinkingLevel,
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            _ui.update { it.copy(transcript = Loadable.Failed(e.message ?: "session read failed", e.failureKind())) }
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
                        // An unstamped card keeps the previous stamp only while
                        // the status itself is unchanged; across a transition a
                        // stale stamp would time the wrong state, so drop it and
                        // let the indicator render without a timer.
                        statusSinceMs = card.statusSinceMs?.toLong()
                            ?: it.statusSinceMs.takeIf { _ -> card.status == it.agentStatus },
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
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
            // bridge unreachable; keep the current state
        }
        return resolvedPath
    }

    /** Run a lifecycle action or select an explicit model/thinking level. */
    fun control(action: SessionAction, text: String? = null, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val configurationAction = action == SessionAction.SetModel || action == SessionAction.SetThinking
            if (action == SessionAction.SetThinking && !_ui.value.canSetThinking) {
                _ui.update {
                    it.copy(
                        configActionBusy = false,
                        configuration = Loadable.Failed("This agent does not support thinking levels", FailureKind.Server),
                    )
                }
                return@launch
            }
            // A control action is not a fetch: keep the loaded catalog visible
            // while busy and on failure; only the busy flag flips.
            _ui.update {
                it.copy(
                    sending = !configurationAction,
                    configActionBusy = configurationAction,
                    sendError = null,
                )
            }
            try {
                bridge.controlSession(paneId, action, text)
                _ui.update {
                    it.copy(
                        sending = false,
                        configActionBusy = false,
                        model = if (action == SessionAction.SetModel) text else it.model,
                        thinkingLevel = if (action == SessionAction.SetThinking) text else it.thinkingLevel,
                    )
                }
                onSuccess()
                delay(700)
                refresh()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                val message = e.message ?: "control failed"
                _ui.update {
                    if (configurationAction) it.copy(configActionBusy = false)
                    else it.copy(sending = false, sendError = message)
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
        _ui.update { it.copy(pendingMessages = it.pendingMessages + message, sending = true, sendError = null) }
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
            _ui.update { it.copy(sending = true, sendError = null) }
            try {
                val response = bridge.uploadAttachment(name, mime, bytes)
                if (response.error != null) throw IOException(response.error)
                val prefix = "@${response.path}"
                val full = if (trimmed.isEmpty()) prefix else "$prefix $trimmed"
                _ui.update { it.copy(sending = false) }
                send(full)
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update {
                    it.copy(
                        sending = false,
                        sendError = error.message ?: "Attachment upload failed",
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
    fun answerQuestion(questionId: String, text: String, selectedLabels: List<String> = emptyList()) {
        val safe = sanitizeAnswerText(text)
        if (safe.isEmpty() && selectedLabels.isEmpty()) return
        if (_ui.value.questions.none { it.id == questionId }) return
        if (_ui.value.answeringQuestionId != null) return
        val callId = _ui.value.questions.firstOrNull { it.id == questionId }?.callId.orEmpty()
        val run = _ui.value.questionnaireProgress[callId]
        if (run != null && questionId in run.answered) return // already recorded in this run
        _ui.update { it.copy(answeringQuestionId = questionId, questionError = null) }
        viewModelScope.launch {
            try {
                sendAnswer(questionId, safe, selectedLabels)
                _ui.update { it.copy(answeringQuestionId = null) }
                repeat(3) {
                    refresh()
                    val now = _ui.value.questions.firstOrNull { it.id == questionId }
                    if (now?.answered == true || now == null) return@launch
                    delay(750)
                }
            } catch (c: CancellationException) {
                throw c
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

    /**
     * Deliver one answer into the pane as keyboard navigation plus optional
     * text, and record the questionnaire progress for the next answer.
     * Throws on transport failure (callers handle retry UI).
     */
    private suspend fun sendAnswer(questionId: String, text: String, selectedLabels: List<String>) {
        val state = _ui.value
        val question = state.questions.firstOrNull { it.id == questionId } ?: return
        val group = questionGroup(state.questions, question)
        val run = state.questionnaireProgress[question.callId]
        if (run != null && questionId in run.answered) return // already recorded in this run
        val safe = sanitizeAnswerText(text)
        val nav = answerNavigationKeys(question, group, run?.lastIndex, safe, selectedLabels)
        val sendText = if (nav.custom) safe else ""
        bridge.answerQuestion(paneId, sendText, nav.keys, nav.trailingKeys)
        val index = group.indexOfFirst { it.id == questionId }.coerceAtLeast(0)
        _ui.update { state ->
            val progress = state.questionnaireProgress[question.callId]
            state.copy(
                questionnaireProgress = state.questionnaireProgress +
                    (question.callId to QuestionnaireRun(
                        answered = (progress?.answered ?: emptySet()) + questionId,
                        lastIndex = index,
                    )),
            )
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
            _ui.update { it.copy(sending = true, sendError = null) }
            try {
                bridge.runSlashCommand(paneId, text)
                _ui.update { it.copy(sending = false) }
                delay(500)
                refresh()
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update { it.copy(sending = false, sendError = error.message ?: "Command failed") }
            }
        }
    }

    private fun deliver(localId: String) {
        viewModelScope.launch {
            val message = _ui.value.pendingMessages.firstOrNull { it.localId == localId } ?: return@launch
            try {
                if (waitingForAnswer) {
                    // The composer answers as a custom answer. When an
                    // unanswered question is on screen, route through the
                    // questionnaire so the text lands in the "Type something"
                    // editor instead of pi dropping it and picking option 1.
                    val pending = _ui.value.questions.firstOrNull { !it.answered }
                    if (pending != null) sendAnswer(pending.id, message.text, emptyList())
                    else bridge.answerQuestion(paneId, message.text)
                } else bridge.steer(paneId, message.text)

                _ui.update { state -> state.copy(sending = false) }
                repeat(3) {
                    refresh()
                    if (_ui.value.pendingMessages.none { it.localId == localId }) return@launch
                    delay(750)
                }
            } catch (c: CancellationException) {
                throw c
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
        poller.stop()
        super.onCleared()
    }

    companion object {
        private const val COMMAND_REFRESH_MS = 30_000L
    }
}
