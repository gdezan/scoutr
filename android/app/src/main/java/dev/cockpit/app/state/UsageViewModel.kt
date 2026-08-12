package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.net.CockpitApi
import dev.cockpit.app.data.UsageResponse
import dev.cockpit.app.data.UsageSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsageUiState(
    val providers: List<UsageSnapshot> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class UsageViewModel(
    private val bridge: CockpitApi,
) : ViewModel() {

    private val _ui = MutableStateFlow(UsageUiState())
    val ui: StateFlow<UsageUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val response: UsageResponse = bridge.usage()
                _ui.update {
                    it.copy(
                        providers = response.usage,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "usage fetch failed") }
            }
        }
    }

    companion object {
        fun factory(bridge: CockpitApi): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return UsageViewModel(bridge) as T
            }
        }
    }
}
