package dev.cockpit.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.RepoDiffResponse
import dev.cockpit.app.data.RepoOverviewResponse
import dev.cockpit.app.net.CockpitApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    /** Selected repo path; null while the folder picker is shown. */
    val repoPath: String? = null,
    val overview: RepoOverviewResponse? = null,
    val loading: Boolean = false,
    val error: String? = null,

    // Generated-artifact listing (bounded by the bridge).
    val artifacts: List<dev.cockpit.app.data.RepoArtifact> = emptyList(),
    val artifactsTruncated: Boolean = false,
    val artifactsLoading: Boolean = false,
    // Folder picker state.
    val dirPath: String = "",
    val dirs: List<String> = emptyList(),
    val dirsLoading: Boolean = false,
    val dirsError: String? = null,
    // Diff viewer state.
    val diff: RepoDiffResponse? = null,
    val diffRef: String? = null,
    val diffLoading: Boolean = false,
    /** Last reviewed repo (persisted), offered as a quick reopen in the picker. */
    val lastRepoPath: String? = null,
)

/**
 * Read-only git review: pick a repo from the bridge allow-list, read its
 * branch/status/log, and open a bounded diff against any recent commit or the
 * working tree. Every operation goes through the bridge's read-only review
 * API — no mutation, no arbitrary command surface.
 */
class ReviewViewModel(
    private val bridge: CockpitApi,
    private val connectionStore: ConnectionStore,
    private val store: ReviewStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReviewUiState())
    val ui: StateFlow<ReviewUiState> = _ui.asStateFlow()

    fun openPicker() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    repoPath = null,
                    overview = null,
                    diff = null,
                    diffRef = null,
                    lastRepoPath = store.lastRepoPath,
                )
            }
            browse("")
        }
    }

    fun browse(path: String) {
        viewModelScope.launch {
            _ui.update { it.copy(dirsLoading = true, dirsError = null) }
            try {
                val listing = bridge.dirs(path.ifBlank { null })
                _ui.update {
                    it.copy(
                        dirPath = listing.listing?.path ?: "",
                        dirs = listing.listing?.dirs ?: emptyList(),
                        dirsLoading = false,
                    )
                }
            } catch (error: Exception) {
                _ui.update {
                    it.copy(dirsLoading = false, dirsError = error.message ?: "Folder listing failed")
                }
            }
        }
    }

    fun browseInto(dir: String) {
        val next = if (_ui.value.dirPath.endsWith('/')) _ui.value.dirPath + dir else "${_ui.value.dirPath}/$dir"
        browse(next)
    }

    fun browseUp() {
        val current = _ui.value.dirPath
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        browse(parent)
    }

    fun selectRepo(path: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val overview = bridge.repoOverview(path)
                if (overview.error != null) {
                    _ui.update { it.copy(loading = false, error = overview.error) }
                    return@launch
                }
                store.lastRepoPath = path
                _ui.update {
                    it.copy(repoPath = path, overview = overview, loading = false, lastRepoPath = path)
                }
                loadArtifacts(path)
            } catch (error: Exception) {
                _ui.update { it.copy(loading = false, error = error.message ?: "Repo read failed") }
            }
        }
    }

    fun loadArtifacts(path: String) {
        if (_ui.value.artifactsLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(artifactsLoading = true) }
            try {
                val result = bridge.repoArtifacts(path)
                _ui.update {
                    it.copy(
                        artifacts = result.artifacts,
                        artifactsTruncated = result.truncated,
                        artifactsLoading = false,
                    )
                }
            } catch (_: Exception) {
                // Artifacts are supplementary; a failure must not break the overview.
                _ui.update { it.copy(artifactsLoading = false) }
            }
        }
    }

    fun loadDiff(ref: String, kind: String = "working") {
        val path = _ui.value.repoPath ?: return
        if (_ui.value.diffLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(diffLoading = true, diffRef = ref, error = null) }
            try {
                val diff = bridge.repoDiff(path, ref, kind)
                _ui.update { it.copy(diff = diff, diffLoading = false) }
            } catch (error: Exception) {
                _ui.update {
                    it.copy(diffLoading = false, error = error.message ?: "Diff read failed")
                }
            }
        }
    }

    fun refresh() {
        val path = _ui.value.repoPath ?: return
        selectRepo(path)
    }

    companion object {
        fun factory(bridge: CockpitApi, connectionStore: ConnectionStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY] ?: error("application missing in ReviewViewModel factory")
                    return ReviewViewModel(bridge, connectionStore, ReviewStore(app)) as T
                }
            }
    }
}
