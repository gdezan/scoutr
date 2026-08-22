package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.BoardState
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ScoutrApiCompatibility
import dev.scoutr.app.data.classifyScoutrApiCompatibility
import dev.scoutr.app.data.formatScoutrApiIncompatibility
import dev.scoutr.app.net.AskAnswer
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.ScoutrApi
// The Board's own quick-answer eligibility rule, shared with the cards that
// draw the controls so the check that submits cannot drift from the check that
// offers.
import dev.scoutr.app.ui.screens.quickAnswerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class BoardUiState(
    val board: BoardState = BoardState(),
    val loading: Boolean = false,
    val isRefreshing: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val apiCompatibility: ScoutrApiCompatibility? = null,
    /**
     * Pane ids with a quick answer in flight. Membership is what makes a
     * second tap a no-op, so it is set before the request leaves and cleared
     * only once the request has settled.
     */
    val quickAnswering: Set<String> = emptySet(),
)

class BoardViewModel(
    private val bridge: ScoutrApi,
    private val connectionStore: ConnectionStore,
    initialState: BoardUiState = BoardUiState(),
    private val pollInterval: Duration = 3.seconds,
) : ViewModel() {

    private val _ui = MutableStateFlow(initialState)
    val ui: StateFlow<BoardUiState> = _ui.asStateFlow()

    private val poller = Poller(viewModelScope)
    private val connectionMutex = Mutex()
    private val loadMutex = Mutex()

    val hasSavedConnection: Boolean get() = connectionStore.saved != null

    init {
        if (connectionStore.saved != null) {
            connect(host = "", token = "")
        }
    }

    /** Connects to the stored (or newly saved) connection and starts the live board. */
    fun connect(host: String, token: String) {
        val hasCandidate = host.isNotBlank() && token.isNotBlank()
        if (!hasCandidate && connectionStore.saved == null) {
            _ui.update { it.copy(error = "No connection configured") }
            return
        }
        viewModelScope.launch {
            probeConnection(
                host = host.takeIf { hasCandidate },
                token = token.takeIf { hasCandidate },
                showLoading = _ui.value.apiCompatibility !is ScoutrApiCompatibility.Incompatible,
            )
        }
    }

    private suspend fun probeConnection(
        host: String? = null,
        token: String? = null,
        showLoading: Boolean = false,
    ) = connectionMutex.withLock {
        if (showLoading) _ui.update { it.copy(loading = true, error = null) }
        try {
            val health = bridge.health(host, token)
            val compatibility = classifyScoutrApiCompatibility(health.api)
            if (compatibility is ScoutrApiCompatibility.Incompatible) {
                _ui.update {
                    it.copy(
                        board = BoardState(),
                        loading = false,
                        connected = false,
                        error = formatScoutrApiIncompatibility(compatibility),
                        apiCompatibility = compatibility,
                    )
                }
                return@withLock
            }
            val hadSavedConnection = connectionStore.saved != null
            if (host != null && token != null) {
                // Explicit form connect: the pairing is replaced wholesale.
                connectionStore.save(host = host, token = token, hostId = health.hostId)
            } else if (health.ok && health.herdr?.connected == true) {
                // Stored-credential probe: adopt the bridge's identity when it
                // differs from what the pairing recorded (e.g. bridge reinstall).
                connectionStore.updateHostId(health.hostId)
            }
            _ui.update {
                it.copy(
                    connected = health.ok && health.herdr?.connected == true,
                    loading = false,
                    error = null,
                    apiCompatibility = ScoutrApiCompatibility.Compatible,
                )
            }
            if (lifecycleActive) {
                if (!hadSavedConnection && connectionStore.saved != null) startLive()
                loadBoard()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            _ui.update { current ->
                val incompatible = current.apiCompatibility as? ScoutrApiCompatibility.Incompatible
                current.copy(
                    loading = false,
                    connected = false,
                    error = incompatible?.let(::formatScoutrApiIncompatibility)
                        ?: e.message
                        ?: "connection failed",
                )
            }
        }
    }

    // True while the board screen is STARTED. Only the lifecycle wrapper
    // starts loops; connect() may restart them, but never resurrect them
    // after a stop that raced the health probe.
    private var lifecycleActive = false

    /** Start the 3s board poll; no-op when already polling. */
    fun startPolling() {
        if (lifecycleActive) return
        lifecycleActive = true
        if (connectionStore.saved != null) startLive()
    }

    /** Stop the poll; in-flight one-shot actions are untouched. */
    fun stopPolling() {
        if (!lifecycleActive) return
        lifecycleActive = false
        poller.stop()
    }

    /**
     * Forget: the pairing is gone, so stop polling and drop the board we
     * fetched under it. This VM is activity-scoped and is not recreated when
     * nav resets to Connect, so without this it would keep polling a cleared
     * store. Re-pairing calls [connect] again, which restarts everything.
     */
    fun disconnect() {
        stopPolling()
        _ui.value = BoardUiState()
    }

    private fun startLive() {
        // Poll the bridge for the latest board state. A long-lived WebSocket
        // is deliberately avoided here: an abrupt server close can crash the
        // OkHttp reader, and the bridge already caches + re-snapshots anyway.
        poller.start(pollInterval) {
            if (_ui.value.apiCompatibility == ScoutrApiCompatibility.Compatible) {
                loadBoard()
            } else {
                probeConnection()
            }
        }
    }

    /** Request an immediate board refresh and expose its progress to the pull gesture. */
    fun refreshBoard() {
        if (_ui.value.isRefreshing || _ui.value.apiCompatibility != ScoutrApiCompatibility.Compatible) return
        _ui.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                loadBoard()
            } finally {
                _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun loadBoard() = loadMutex.withLock {
        if (_ui.value.apiCompatibility != ScoutrApiCompatibility.Compatible) return@withLock
        try {
            val response = bridge.agents()
            _ui.update {
                it.copy(
                    board = BoardState.group(response.agents),
                    connected = true,
                    error = null,
                )
            }
        } catch (e: IOException) {
            _ui.update { it.copy(connected = false, error = e.message ?: "lost connection") }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // transient decode issues should not flap the board
        }
    }

    /** Surface a transient error (e.g. a failed session create) on the board. */
    fun reportError(message: String) {
        _ui.update { it.copy(error = message) }
    }

    /** Closes an agent's pane via the bridge control action (swipe-bar Close). */
    fun closeAgent(paneId: String) {
        val incompatible = _ui.value.apiCompatibility as? ScoutrApiCompatibility.Incompatible
        if (incompatible != null) {
            reportError(formatScoutrApiIncompatibility(incompatible))
            return
        }
        if (_ui.value.apiCompatibility != ScoutrApiCompatibility.Compatible) {
            reportError("Checking bridge compatibility")
            return
        }
        viewModelScope.launch {
            try {
                bridge.controlSession(paneId, SessionAction.Close)
            } catch (e: IOException) {
                reportError(e.message ?: "could not close agent")
            }
        }
    }

    /**
     * Answer a Needs-you card's open ask straight from the Board.
     *
     * Only the whole round travels: exactly one [AskAnswer] built from the
     * server's own question id and the server's own option label (never the
     * truncated text the button drew). Eligibility and ids are re-read from
     * the board this VM currently holds rather than the card the tap carried,
     * because a poll may have replaced the ask in between. Nothing is marked
     * answered locally — the Board refreshes after every outcome and lets
     * `/api/agents` say what is still open.
     */
    fun quickAnswer(agent: SessionDescriptor, optionLabel: String) {
        val incompatible = _ui.value.apiCompatibility as? ScoutrApiCompatibility.Incompatible
        if (incompatible != null) {
            reportError(formatScoutrApiIncompatibility(incompatible))
            return
        }
        if (_ui.value.apiCompatibility != ScoutrApiCompatibility.Compatible) {
            reportError("Checking bridge compatibility")
            return
        }
        val paneId = agent.live?.paneId
        if (paneId == null) {
            reportError("That agent is no longer running")
            return
        }
        // The one guard against a double tap: the second call sees the first
        // still in flight and stops here, before any request is built.
        if (paneId in _ui.value.quickAnswering) return

        val attention = _ui.value.board.sessions
            .firstOrNull { it.live?.paneId == paneId }
            ?.attention
        val question = attention?.currentQuestion
        val callId = attention?.callId
        val option = quickAnswerOptions(attention).firstOrNull { it.label == optionLabel }
        if (question == null || callId.isNullOrEmpty() || option == null) {
            viewModelScope.launch {
                loadBoard()
                reportError(QUICK_ANSWER_STALE)
            }
            return
        }

        _ui.update { it.copy(quickAnswering = it.quickAnswering + paneId, error = null) }
        viewModelScope.launch {
            val failure = try {
                bridge.answerAsk(
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
            _ui.update { it.copy(quickAnswering = it.quickAnswering - paneId) }
            // Refresh on success and on failure alike: only the bridge knows
            // whether the ask is gone. The error is raised after the refresh
            // so the successful reload does not clear it.
            loadBoard()
            failure?.let { reportError(quickAnswerErrorMessage(it)) }
        }
    }

    private fun quickAnswerErrorMessage(error: Exception): String = when {
        // The bridge rejects an ask that no longer matches the pane with 409:
        // someone (or something) got there first.
        error is BridgeException && error.status == 409 -> QUICK_ANSWER_STALE
        else -> error.message ?: "Answer failed to send"
    }

    override fun onCleared() {
        poller.stop()
        super.onCleared()
    }

    private companion object {
        const val QUICK_ANSWER_STALE = "That question is no longer open"
    }
}
