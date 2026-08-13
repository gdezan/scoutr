package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.data.UsageResponse
import dev.scoutr.app.data.UsageSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.seconds

data class UsageUiState(
    /** The quota list; Loading/Failed only while no data has ever arrived. */
    val providers: Loadable<List<UsageSnapshot>> = Loadable.Idle,
    /** True only for a user-requested refresh, never for the background poll. */
    val isRefreshing: Boolean = false,
    /**
     * A refresh that failed after data was already on screen. Kept separate
     * from [providers] on purpose: the screen shows the cached list with a
     * banner, and the next refresh self-heals (offline rule: never blank the
     * usage chart because the bridge blinked).
     */
    val error: String? = null,
)

class UsageViewModel(
    private val bridge: ScoutrApi,
) : ViewModel() {

    private val _ui = MutableStateFlow(UsageUiState())
    val ui: StateFlow<UsageUiState> = _ui.asStateFlow()

    private val poller = Poller(viewModelScope)
    private val loadMutex = Mutex()

    init {
        poller.start(10.seconds) { loadUsage() }
    }

    override fun onCleared() {
        poller.stop()
        super.onCleared()
    }

    /** Request an immediate usage refresh and expose its progress to the pull gesture. */
    fun refreshUsage() {
        if (_ui.value.isRefreshing) return
        _ui.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                loadUsage()
            } finally {
                _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun loadUsage() = loadMutex.withLock {
        val hadData = _ui.value.providers is Loadable.Ready
        _ui.update { it.copy(providers = if (hadData) it.providers else Loadable.Loading, error = null) }
        try {
            val response: UsageResponse = bridge.usage()
            _ui.update { it.copy(providers = Loadable.Ready(response.usage), error = null) }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            _ui.update {
                it.copy(
                    providers = if (hadData) it.providers else Loadable.Failed(e.message ?: "usage fetch failed", e.failureKind()),
                    error = if (hadData) e.message ?: "usage fetch failed" else null,
                )
            }
        }
    }
}