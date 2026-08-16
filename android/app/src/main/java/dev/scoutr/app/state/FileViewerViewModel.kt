package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Viewer state for one cwd-relative file in an active-agent workspace. */
data class FileViewerUiState(
    val cwd: String,
    val file: String,
    val content: Loadable<FileReadResponse> = Loadable.Idle,
)

/** Reads one workspace file through the bridge and exposes retryable load state. */
class FileViewerViewModel(
    private val bridge: ScoutrApi,
    cwd: String,
    file: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(FileViewerUiState(cwd = cwd, file = file))
    val ui: StateFlow<FileViewerUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    /** Fetches the cwd-relative file using the absolute workspace path expected by the bridge. */
    fun refresh() {
        val state = _ui.value
        if (state.cwd.isBlank() || state.file.isBlank()) {
            _ui.update { it.copy(content = Loadable.Failed("File path is not available", FailureKind.Server)) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(content = Loadable.Loading) }
            try {
                val response = bridge.file(workspaceFilePath(state.cwd, state.file))
                if (!response.ok && response.error != null) {
                    _ui.update { it.copy(content = Loadable.Failed(response.error, FailureKind.Server)) }
                } else {
                    _ui.update { it.copy(content = Loadable.Ready(response)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.update { it.copy(content = Loadable.Failed(error.message ?: "File read failed", error.failureKind())) }
            }
        }
    }
}

/** Joins an active workspace root to a browser-relative file without duplicating a slash. */
fun workspaceFilePath(cwd: String, file: String): String =
    cwd.trimEnd('/') + "/" + file.trimStart('/')
