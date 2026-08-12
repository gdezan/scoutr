package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.net.CockpitApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class LiveOutputUiState(
    val loading: Boolean = false,
    val text: String = "",
    val revision: Long = 0,
    val truncated: Boolean = false,
    val error: String? = null,
) {
    val lines: List<String> get() = meaningfulLiveOutputLines(text)
}

/**
 * Strip terminal chrome (spinners, elapsed counters, the pi status line) so the
 * viewer shows agent output rather than the frame around it.
 */
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
    // A herdr prompt line ("~/Dev/agents-mobile (main) │ 101k/1.0M ↑576.") has
    // a single │ so the bar-count rule below misses it and the prompt eats one
    // of the tail lines. Anchor on the prompt's shape instead: a path-prefixed
    // line with a │.
    if ((line.startsWith("~") || line.startsWith("/")) && line.contains('│')) return true
    return line.count { it == '│' } >= 2 && line.contains('/')
}

/**
 * Raw pane tail for one agent, owned by the live output screen.
 *
 * The poll is deliberately screen-scoped: the chat screen shows a working
 * indicator instead of ambient output, so `/api/agents/{id}/read` is hit only
 * while the viewer is actually on screen. `startPolling`/`stopPolling` are
 * driven by the screen's `LifecycleStartEffect`; the VM only guards idempotence.
 */
class LiveOutputViewModel(
    private val bridge: CockpitApi,
    val paneId: String,
) : ViewModel() {

    private val _ui = MutableStateFlow(LiveOutputUiState())
    val ui: StateFlow<LiveOutputUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(LIVE_OUTPUT_POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refresh() {
        _ui.update { it.copy(loading = it.text.isEmpty()) }
        try {
            val response = bridge.liveOutput(paneId, LIVE_OUTPUT_LINES)
            val output = response.output
            if (output == null) {
                _ui.update { it.copy(loading = false, error = response.error ?: "Live output unavailable") }
                return
            }
            _ui.update {
                it.copy(
                    loading = false,
                    text = output.text,
                    revision = output.revision,
                    truncated = output.truncated,
                    error = null,
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (error: Exception) {
            _ui.update { it.copy(loading = false, error = error.message ?: "Live output unavailable") }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val LIVE_OUTPUT_LINES = 80
        // Fast enough that the tail reads as live while the viewer is open;
        // bounded by the screen lifecycle, so it costs nothing when closed.
        private const val LIVE_OUTPUT_POLL_MS = 900L

        fun factory(bridge: CockpitApi, paneId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LiveOutputViewModel(bridge, paneId) as T
                }
            }
    }
}
