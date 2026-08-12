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
import kotlinx.coroutines.CancellationException

data class ReviewUiState(
    /** Selected repo path; null while the folder picker is shown. */
    val repoPath: String? = null,
    val overview: Loadable<RepoOverviewResponse> = Loadable.Idle,

    // Generated-artifact listing (bounded by the bridge); a failure here must
    // not break the overview, so it has its own slot.
    val artifacts: Loadable<List<dev.cockpit.app.data.RepoArtifact>> = Loadable.Idle,
    val artifactsTruncated: Boolean = false,
    // Folder picker state.
    val dirPath: String = "",
    val dirs: Loadable<List<String>> = Loadable.Idle,
    // Diff viewer state.
    val diff: Loadable<RepoDiffResponse> = Loadable.Idle,
    val diffRef: String? = null,
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
                    overview = Loadable.Idle,
                    diff = Loadable.Idle,
                    diffRef = null,
                    lastRepoPath = store.lastRepoPath,
                )
            }
            browse("")
        }
    }

    fun browse(path: String) {
        viewModelScope.launch {
            _ui.update { it.copy(dirs = Loadable.Loading) }
            try {
                val listing = bridge.dirs(path.ifBlank { null })
                _ui.update {
                    it.copy(
                        dirPath = listing.listing?.path ?: "",
                        dirs = Loadable.Ready(listing.listing?.dirs ?: emptyList()),
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update {
                    it.copy(dirs = Loadable.Failed(error.message ?: "Folder listing failed", error.failureKind()))
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
            _ui.update { it.copy(overview = Loadable.Loading) }
            try {
                val overview = bridge.repoOverview(path)
                if (overview.error != null) {
                    _ui.update {
                        it.copy(overview = Loadable.Failed(overview.error, FailureKind.Server))
                    }
                    return@launch
                }
                store.lastRepoPath = path
                _ui.update {
                    it.copy(repoPath = path, overview = Loadable.Ready(overview), lastRepoPath = path)
                }
                loadArtifacts(path)
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update {
                    it.copy(overview = Loadable.Failed(error.message ?: "Repo read failed", error.failureKind()))
                }
            }
        }
    }

    fun loadArtifacts(path: String) {
        if (_ui.value.artifacts is Loadable.Loading) return
        viewModelScope.launch {
            _ui.update { it.copy(artifacts = Loadable.Loading) }
            try {
                val result = bridge.repoArtifacts(path)
                _ui.update {
                    it.copy(
                        artifacts = Loadable.Ready(result.artifacts),
                        artifactsTruncated = result.truncated,
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // Artifacts are supplementary; a failure must not break the overview.
                _ui.update { it.copy(artifacts = Loadable.Idle) }
            }
        }
    }

    fun loadDiff(ref: String, kind: String = "working") {
        val path = _ui.value.repoPath ?: return
        if (_ui.value.diff is Loadable.Loading) return
        viewModelScope.launch {
            _ui.update { it.copy(diff = Loadable.Loading, diffRef = ref) }
            try {
                val diff = bridge.repoDiff(path, ref, kind)
                _ui.update { it.copy(diff = Loadable.Ready(diff)) }
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update {
                    it.copy(diff = Loadable.Failed(error.message ?: "Diff read failed", error.failureKind()))
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
