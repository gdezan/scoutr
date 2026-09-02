package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.PiSubagentProgress
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Polling state for one PI-workflow run-store progress payload. */
data class SubagentProgressUiState(
    val runId: String,
    val progress: Loadable<PiSubagentProgress> = Loadable.Idle,
)

/**
 * Polls [ScoutrApi.subagentProgress] while the progress screen is open.
 * A later 404 (Herdr pane gone, run dir still readable or already deleted)
 * keeps the last successful payload until Back — first miss is a visible failure.
 */
class SubagentProgressViewModel(
    private val bridge: ScoutrApi,
    runId: String,
    private val pollIntervalMs: Long = SUBAGENT_PROGRESS_POLL_INTERVAL_MS,
) : ViewModel() {
    private val _ui = MutableStateFlow(SubagentProgressUiState(runId = runId))
    val ui: StateFlow<SubagentProgressUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                fetch()
                delay(pollIntervalMs)
            }
        }
    }

    /** Manual retry after a first-load failure. */
    fun retry() {
        viewModelScope.launch { fetch() }
    }

    private suspend fun fetch() {
        val runId = _ui.value.runId
        if (runId.isBlank()) {
            _ui.update { it.copy(progress = Loadable.Failed("Run id is not available", FailureKind.Server)) }
            return
        }
        if (_ui.value.progress !is Loadable.Ready) {
            _ui.update { it.copy(progress = Loadable.Loading) }
        }
        try {
            val payload = bridge.subagentProgress(runId)
            _ui.update { it.copy(progress = Loadable.Ready(payload)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _ui.update { current ->
                if (current.progress is Loadable.Ready) current
                else current.copy(
                    progress = Loadable.Failed(
                        error.message ?: "Could not load subagent progress",
                        error.failureKind(),
                    ),
                )
            }
        }
    }
}

internal const val SUBAGENT_PROGRESS_POLL_INTERVAL_MS = 2_000L
