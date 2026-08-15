package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.ModelInfo
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.ModelProvider
import dev.scoutr.app.data.FileListing
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.data.SlashCommandInfo
import dev.scoutr.app.data.entryText
import dev.scoutr.app.net.PerformanceCounters
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * QUEUED covers only the moment between tapping send and the bridge accepting
 * the request. Once accepted the message is SENT — it is on the host whether or
 * not the transcript has caught up — and the row stops claiming otherwise.
 */
enum class MessageDeliveryState { QUEUED, SENT, FAILED }

/**
 * Why a Chat refresh is running. Every trigger routes through the same
 * single-flight coordinator, and the source is recorded in the performance
 * counters so the study's traffic experiments can attribute each read to the
 * trigger that caused it (no user-controlled values reach the counters).
 */
enum class RefreshSource {
    /** The 2.5s poller tick; its immediate first tick doubles as the first paint. */
    PollTick,
    /** Post-send reconciliation loop. */
    SendReconciliation,
    /** A control action (set model/thinking, close, ...) just completed. */
    ControlCompletion,
    /** A slash command just completed. */
    SlashCommandCompletion,
    /** Post-answer reconciliation loop. */
    AnswerReconciliation,
    /** The user pulled to refresh. */
    Pull,
}
data class PendingUserMessage(
    val localId: String,
    val text: String,
    val state: MessageDeliveryState,
    internal val baselineIds: Set<String> = emptySet(),
    /** When the bridge accepted it; the echo grace period runs from here. */
    internal val sentAtMs: Long? = null,
)

/**
 * A sent message is reconciled by matching its text against the transcript, but
 * an agent is free to record what it received in a form that never matches —
 * rewritten, annotated, or folded into another entry. Without a bound the local
 * row outlives its own delivery forever, which is the lie the row exists to
 * prevent; with too tight a bound the row vanishes before the transcript catches
 * up, which looks like the message was lost.
 *
 * So the bound is wall-clock, not poll count. Polls are not evenly spaced — a
 * send fires three refreshes inside two seconds — and counting them retired
 * messages seconds after they were sent, long before any agent could echo.
 */
internal const val ECHO_GRACE_MS = 90_000L

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
 * An answer this device has sent that the transcript has not echoed back yet.
 *
 * Neither agent writes a partly answered ask to its session file — every
 * question of one ask flips to answered together, when the whole ask is
 * submitted. Without this the card a user just answered would sit there
 * unchanged until the last question of the ask was answered too, which reads
 * as a lost tap. The card resolves to its answer bubble immediately instead,
 * and the transcript's own answer replaces it on the next poll.
 */
data class LocalAnswer(val text: String, val labels: List<String>)

/** Drop local answers whose question has landed answered, or is gone. */
internal fun pruneLocalAnswers(
    local: Map<String, LocalAnswer>,
    questions: List<QuestionEntry>,
): Map<String, LocalAnswer> {
    if (local.isEmpty()) return local
    val byId = questions.associateBy { it.id }
    return local.filterKeys { id ->
        val question = byId[id]
        question == null || !question.answered
    }
}

/** A question card with the answer this device sent overlaid on it. */
internal fun overlayLocalAnswer(question: QuestionEntry, local: LocalAnswer?): QuestionEntry {
    if (local == null || question.answered) return question
    return question.copy(
        answered = true,
        answerText = local.text.ifBlank { null },
        selected = local.labels,
    )
}

internal fun dropConfirmedMessages(
    pending: List<PendingUserMessage>,
    incoming: List<SessionEntry>,
    incomingQuestions: List<QuestionEntry> = emptyList(),
    previouslyAnsweredIds: Set<String> = emptySet(),
    nowMs: Long = System.currentTimeMillis(),
): List<PendingUserMessage> {
    val freshUsers = incoming.filter { it.role == "user" }.toMutableList()
    return pending.filter { message ->
        if (!keepPendingMessage(message, freshUsers, incomingQuestions, previouslyAnsweredIds)) {
            return@filter false
        }
        // Nothing matched it. Keep showing it until the grace period is up —
        // then trust the send that already succeeded rather than leaving a row
        // that waits forever.
        val sentAt = message.sentAtMs.takeIf { message.state == MessageDeliveryState.SENT }
        sentAt == null || nowMs - sentAt < ECHO_GRACE_MS
    }
}

/** True while the transcript still owes this message an echo. */
private fun keepPendingMessage(
    message: PendingUserMessage,
    freshUsers: MutableList<SessionEntry>,
    incomingQuestions: List<QuestionEntry>,
    previouslyAnsweredIds: Set<String>,
): Boolean {
    // entryText collapses whitespace runs, so the typed text must be
    // normalized the same way or multi-space/newline messages never reconcile.
    val normalizedText = message.text.replace(Regex("\\s+"), " ").trim()
    val match = freshUsers.indexOfFirst { entry ->
        entry.entryId !in message.baselineIds && entryText(entry.content) == normalizedText
    }
    if (match >= 0) {
        freshUsers.removeAt(match)
        return false
    }
    // Answers typed in the composer never appear as user entries — pi
    // records them only in the toolResult's details.answers. Confirm the
    // pending bubble once a question in the fresh snapshot is answered
    // with that text, so it lands in the transcript as the answer bubble
    // instead of lingering forever.
    // Only a question that flips to answered NOW may confirm the pending
    // bubble. An old answer with the same text (e.g. "Proceed") must not
    // eat an unrelated later message before its user entry arrives.
    return incomingQuestions.none { question ->
        question.answered &&
            question.id !in previouslyAnsweredIds &&
            (question.answerText == normalizedText || question.selected.contains(normalizedText))
    }
}

data class ChatUiState(
    val entries: List<SessionEntry> = emptyList(),
    val pendingMessages: List<PendingUserMessage> = emptyList(),
    /** Structured ask_user_question cards derived from session events. */
    val questions: List<QuestionEntry> = emptyList(),
    /** Question id currently sending an answer. */
    val answeringQuestionId: String? = null,
    /** Answers sent from this device that the transcript has not echoed yet. */
    val localAnswers: Map<String, LocalAnswer> = emptyMap(),
    val questionError: String? = null,
    val exists: Boolean = true,
    /** Transcript read lifecycle; the entries/questions themselves stay in separate fields (they are merged in place). */
    val transcript: Loadable<Unit> = Loadable.Idle,
    val sending: Boolean = false,
    /** Send-path failures only (steer/upload/slash); read failures live in [transcript]. */
    val sendError: String? = null,
    /**
     * True only while a user-requested pull refresh is settling. Never true
     * for a background poll: the indicator represents the user's gesture,
     * and clears on success, failure, or lifecycle cancellation.
     */
    val isRefreshing: Boolean = false,
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
     * The cards to render: [questions] with any answer this device has already
     * sent overlaid, so a card resolves the moment it is answered instead of
     * waiting for the whole ask to reach the transcript.
     */
    val questionCards: List<QuestionEntry>
        get() = if (localAnswers.isEmpty()) questions
        else questions.map { overlayLocalAnswer(it, localAnswers[it.id]) }

    /**
     * True while a question card is still waiting for an answer. Answered
     * questions stay in [questions] for the rest of the session as answer
     * bubbles, so "any question at all" is not the same thing — the working
     * indicator defers to a card only while one is actually pending.
     */
    val hasPendingQuestion: Boolean
        get() = questionCards.any { !it.answered }
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
    private val bridge: ScoutrApi,
    val paneId: String,
    private val sessionPath: String?,
    agentStatus: String = "working",
    private val performanceCounters: PerformanceCounters = PerformanceCounters(),
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState(agentStatus = agentStatus))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val poller = Poller(viewModelScope)

    /**
     * Single-flight guard for the authoritative Chat read. [refreshGate] is
     * held only to check-and-launch; the whole read runs inside
     * [inFlightRefresh], so concurrent triggers await the same read instead
     * of launching duplicate /api/agents + /api/sessions calls. stopPolling()
     * cancels the in-flight read; the pane's own lifecycle (STARTED/STOPPED)
     * is the stale-result guard — paneId never changes for this instance.
     */
    private val refreshGate = Mutex()
    private var inFlightRefresh: Deferred<Boolean>? = null

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
        poller.start(2.5.seconds) { refresh(RefreshSource.PollTick) }
    }

    /**
     * Stop the transcript poll; in-flight one-shot actions are untouched.
     * Gates new refresh triggers and cancels the in-flight authoritative
     * read, so no refresh traffic survives the screen reaching STOPPED.
     */
    fun stopPolling() {
        if (!lifecycleActive) return
        lifecycleActive = false
        poller.stop()
        inFlightRefresh?.let { inFlight ->
            performanceCounters.chatRefreshCancelled()
            inFlight.cancel()
        }
    }

    /**
     * Model catalog for the session's backend. The backend id comes from the
     * agents poll, so this no-ops until the card lands; the single-flight
     * read re-invokes it after syncStatusAndPath() and whenever the kind
     * changes.
     */
    suspend fun refreshConfiguration() {
        val agent = _ui.value.agentKind ?: return
        val agentChanged = agent != configurationAgent
        // configurationAgent is set only after a successful fetch, so an
        // empty catalog (a backend that publishes none) is cached too —
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

    /**
     * The one entry point for every Chat refresh trigger (poll tick, action
     * reconciliation, pull). Single-flight: at most one authoritative read
     * per pane runs at a time, and concurrent triggers join the in-flight
     * read — they await its result and add no follow-up of their own.
     *
     * Returns true when the read landed (or was already done by a join),
     * false when it failed or when the screen reached STOPPED. A
     * cancellation of the caller itself (its coroutine is gone) rethrows;
     * a cancellation of the shared read (stopPolling) while the caller is
     * still alive reports false so the caller can settle its UI.
     */
    suspend fun refresh(source: RefreshSource): Boolean {
        if (!lifecycleActive) return false
        val deferred = refreshGate.withLock {
            // Re-checked under the gate: stopPolling() does not take the gate,
            // so a caller that passed the fast path above can resume here only
            // after STOPPED — it must not register a new read.
            if (!lifecycleActive) return false
            inFlightRefresh?.takeIf { it.isActive }?.also {
                performanceCounters.joinChatRefresh(source.name)
            } ?: run {
                performanceCounters.beginChatRefresh(source.name)
                lateinit var d: Deferred<Boolean>
                d = viewModelScope.async {
                    try {
                        readAndMerge()
                    } finally {
                        refreshGate.withLock {
                            if (inFlightRefresh === d) inFlightRefresh = null
                        }
                    }
                }
                inFlightRefresh = d
                d
            }
        }
        return try {
            deferred.await()
        } catch (c: CancellationException) {
            if (currentCoroutineContext().isActive) false else throw c
        }
    }

    /**
     * A pull-to-refresh gesture. The indicator represents only this
     * user-requested refresh and clears on success, failure, or lifecycle
     * cancellation; a pull joins an in-flight poll instead of racing it
     * (repeated pulls are no-ops against the same read), and a successful
     * pull resets the poll deadline so the next tick is a full interval
     * away. Content stays rendered on failure — the entries are untouched.
     */
    fun onPullRefresh() {
        performanceCounters.chatPullAttempted()
        viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true) }
            var recorded = false
            try {
                val succeeded = refresh(RefreshSource.Pull)
                performanceCounters.chatPullCompleted(success = succeeded)
                recorded = true
                if (succeeded) poller.resetNextDeadline()
            } catch (c: CancellationException) {
                // The pane reached STOPPED: the shared read was cancelled.
                // The finally still records the failed pull and clears the indicator.
                throw c
            } finally {
                if (!recorded) performanceCounters.chatPullCompleted(success = false)
                _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * The authoritative Chat read: board status/path, model catalog, slash
     * commands, then the transcript. Runs as the single-flight owner; call
     * [refresh] instead of this directly.
     */
    private suspend fun readAndMerge(): Boolean {
        return try {
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
                return true
            }
            val response = bridge.session(path, since = _ui.value.entries.lastOrNull()?.entryId)
            _ui.update {
                val previouslyAnswered = it.questions
                    .filter { q -> q.answered }
                    .mapTo(mutableSetOf()) { q -> q.id }
                val questions = mergeQuestions(it.questions, response.questions)
                it.copy(
                    entries = mergeSessionEntries(it.entries, response.entries, incremental = response.since != null),
                    questions = questions,
                    localAnswers = pruneLocalAnswers(it.localAnswers, questions),
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
            true
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            _ui.update { it.copy(transcript = Loadable.Failed(e.message ?: "session read failed", e.failureKind())) }
            false
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
                refresh(RefreshSource.ControlCompletion)
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
     * Answer a structured question card. The text is sanitized here (single
     * line, no control chars, capped) and again on the bridge, which turns the
     * card and the picked labels into keystrokes for the agent's own
     * questionnaire — the app never speaks a TUI's key grammar.
     */
    fun answerQuestion(questionId: String, text: String, selectedLabels: List<String> = emptyList()) {
        val safe = sanitizeAnswerText(text)
        if (safe.isEmpty() && selectedLabels.isEmpty()) return
        if (_ui.value.questionCards.none { it.id == questionId && !it.answered }) return
        if (_ui.value.answeringQuestionId != null) return
        _ui.update { it.copy(answeringQuestionId = questionId, questionError = null) }
        viewModelScope.launch {
            try {
                sendAnswer(questionId, safe, selectedLabels)
                _ui.update { it.copy(answeringQuestionId = null) }
                repeat(3) {
                    refresh(RefreshSource.AnswerReconciliation)
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
     * Deliver one answer to the bridge and record it locally, so the card
     * resolves to its answer now rather than when the whole ask lands in the
     * transcript. Throws on transport failure (callers handle retry UI), and
     * nothing is recorded then — the card stays answerable.
     */
    private suspend fun sendAnswer(questionId: String, text: String, selectedLabels: List<String>) {
        val safe = sanitizeAnswerText(text)
        bridge.answerQuestion(paneId, questionId, safe, selectedLabels)
        if (questionId.isEmpty()) return
        _ui.update { state ->
            state.copy(localAnswers = state.localAnswers + (questionId to LocalAnswer(safe, selectedLabels)))
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
                refresh(RefreshSource.SlashCommandCompletion)
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
                    val pending = _ui.value.questionCards.firstOrNull { !it.answered }
                    sendAnswer(pending?.id.orEmpty(), message.text, emptyList())
                } else bridge.steer(paneId, message.text)

                // The bridge accepted it, so it is no longer queued — only
                // waiting for the transcript to echo it back.
                _ui.update { state ->
                    state.copy(
                        sending = false,
                        pendingMessages = state.pendingMessages.map {
                            if (it.localId == localId && it.state != MessageDeliveryState.FAILED) {
                                it.copy(
                                    state = MessageDeliveryState.SENT,
                                    sentAtMs = System.currentTimeMillis(),
                                )
                            } else {
                                it
                            }
                        },
                    )
                }
                repeat(3) {
                    refresh(RefreshSource.SendReconciliation)
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
