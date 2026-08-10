package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.ModelProvider
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The home directory the bridge roots dir listings at (also the quick pick). */
val HOME_DIR: String get() = "/home/${System.getProperty("user.name") ?: "gdezan"}"

/** Quick-pick roots for the folder picker. */
fun quickPicks(home: String): List<String> = listOf(home, "$home/Dev")

/** Breadcrumb parts of a path, home-relative. */
fun breadcrumb(path: String, home: String): List<String> {
    if (!path.startsWith(home)) return listOf(path)
    val rest = path.removePrefix(home).trimStart('/')
    if (rest.isEmpty()) return listOf(home)
    return listOf(home) + rest.split('/').fold(listOf<String>()) { acc, part ->
        acc + (acc.lastOrNull()?.let { "$it/$part" } ?: "$home/$part")
    }
}

/** Human label for a breadcrumb path. */
fun crumbLabel(crumb: String, home: String): String = if (crumb == home) "~" else crumb.substringAfterLast('/')

/** Provider label for the model picker. */
fun providerLabel(provider: ModelProvider): String = provider.name

data class NewSessionUiState(
    val loadingDirs: Boolean = true,
    val loadingModels: Boolean = true,
    val home: String = "",
    val path: String = "",
    val dirs: List<String> = emptyList(),
    val providers: List<ModelProvider> = emptyList(),
    val selectedModel: String? = null,
    val name: String = "",
    val creating: Boolean = false,
    val error: String? = null,
    val created: CreatedSessionResult? = null,
)

data class CreatedSessionResult(val paneId: String)

/**
 * The new-session flow: browse folders (bridge /api/dirs, rooted at home),
 * pick a model (bridge /api/models), then create a pane-native pi session.
 */
class NewSessionViewModel(private val bridge: BridgeClient) : ViewModel() {

    private val _ui = MutableStateFlow(NewSessionUiState())
    val ui: StateFlow<NewSessionUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { loadDirs() }
        viewModelScope.launch { loadModels() }
    }

    suspend fun loadDirs(path: String? = null) {
        _ui.update { it.copy(loadingDirs = true, error = null) }
        try {
            val listing = bridge.dirs(path).listing
            if (listing != null) {
                _ui.update { it.copy(loadingDirs = false, path = listing.path, dirs = listing.dirs) }
                if (path == null) _ui.update { it.copy(home = listing.path) }
            } else {
                _ui.update { it.copy(loadingDirs = false, error = "folder listing failed") }
            }
        } catch (e: Exception) {
            _ui.update { it.copy(loadingDirs = false, error = e.message ?: "folder listing failed") }
        }
    }

    suspend fun loadModels() {
        _ui.update { it.copy(loadingModels = true) }
        try {
            val response = bridge.models()
            if (response.catalog != null) {
                _ui.update { it.copy(loadingModels = false, providers = response.catalog.providers) }
            } else {
                _ui.update { it.copy(loadingModels = false, error = response.error ?: "model catalog failed") }
            }
        } catch (e: Exception) {
            _ui.update { it.copy(loadingModels = false, error = e.message ?: "model catalog failed") }
        }
    }

    fun enterDir(dir: String) {
        viewModelScope.launch { loadDirs(ui.value.path.trimEnd('/') + "/" + dir) }
    }

    fun goUp() {
        val path = ui.value.path.trimEnd('/')
        val parent = path.substringBeforeLast('/')
        if (parent.isNotEmpty() && parent != path) viewModelScope.launch { loadDirs(parent) }
    }

    fun jumpTo(path: String) {
        viewModelScope.launch { loadDirs(path) }
    }

    fun selectModel(model: String) {
        _ui.update { it.copy(selectedModel = model, error = null) }
    }

    fun setName(name: String) {
        _ui.update { it.copy(name = name) }
    }

    fun create() {
        val state = _ui.value
        val model = state.selectedModel ?: return
        if (state.creating) return
        viewModelScope.launch {
            _ui.update { it.copy(creating = true, error = null) }
            try {
                val name = state.name.trim().ifEmpty { null }
                val response = bridge.createSession(state.path, model, name)
                if (response.ok && response.paneId != null) {
                    _ui.update { it.copy(creating = false, created = CreatedSessionResult(response.paneId)) }
                } else {
                    _ui.update { it.copy(creating = false, error = response.error ?: "session creation failed") }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(creating = false, error = e.message ?: "session creation failed") }
            }
        }
    }

    companion object {
        fun factory(bridge: BridgeClient): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NewSessionViewModel(bridge) as T
            }
        }
    }
}
