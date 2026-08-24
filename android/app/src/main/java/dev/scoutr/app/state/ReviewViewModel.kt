package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.RepoDiffResponse
import dev.scoutr.app.data.RepoFileDiffResponse
import dev.scoutr.app.data.RepoFileResponse
import dev.scoutr.app.data.RepoOverviewResponse
import dev.scoutr.app.net.ScoutrApi
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

    // Folder picker state.
    val dirPath: String = "",
    val dirs: Loadable<List<String>> = Loadable.Idle,
    // Diff viewer state. The diff itself is a stat-only listing; per-file
    // hunks and file content are lazy-loaded for the selected path.
    val diff: Loadable<RepoDiffResponse> = Loadable.Idle,
    val diffRef: String? = null,
    val diffKind: String = "working",
    /** Path of the file open in the per-file viewer; null until one is picked. */
    val selectedFile: String? = null,
    /** Diff/File toggle for the per-file viewer. */
    val viewMode: DiffViewMode = DiffViewMode.Diff,
    val fileDiff: Loadable<RepoFileDiffResponse> = Loadable.Idle,
    val fileContent: Loadable<RepoFileResponse> = Loadable.Idle,
    /** Last reviewed repo (persisted), offered as a quick reopen in the picker. */
    val lastRepoPath: String? = null,
)
/** Which side of a single file the per-file viewer shows. */
enum class DiffViewMode { Diff, File, Preview }

/** Whether the selected file content, rather than diff hunks, should be loaded/rendered. */
fun DiffViewMode.showsContent(): Boolean = this == DiffViewMode.File || this == DiffViewMode.Preview

/**
 * Read-only git review: pick a repo from the bridge allow-list, read its
 * branch/status/log, and open a bounded diff against any recent commit or the
 * working tree. Every operation goes through the bridge's read-only review
 * API — no mutation, no arbitrary command surface.
 */
class ReviewViewModel(
    private val bridge: ScoutrApi,
    private val store: ReviewStore,
) : ViewModel() {
    // Per-(repo, ref, kind, file) caches so flipping Diff/File/Preview or revisiting a
    // file inside one diff session never refetches. Bounded by bridge caps;
    // cleared whenever the repo or its data reloads.
    private val fileDiffCache = mutableMapOf<String, RepoFileDiffResponse>()
    private val fileContentCache = mutableMapOf<String, RepoFileResponse>()
    private val fileContentLoads = mutableSetOf<String>()
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
                    diffKind = "working",
                    selectedFile = null,
                    viewMode = DiffViewMode.Diff,
                    fileDiff = Loadable.Idle,
                    fileContent = Loadable.Idle,
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
        if (_ui.value.repoPath != path) {
            // New repository: previous per-file caches must never serve here.
            fileDiffCache.clear()
            fileContentCache.clear()
            fileContentLoads.clear()
        }
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
                // Review opens on the working tree's file list (§9c), so the
                // stat listing is part of arriving — not a second tap.
                loadDiff("HEAD")
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update {
                    it.copy(overview = Loadable.Failed(error.message ?: "Repo read failed", error.failureKind()))
                }
            }
        }
    }

    fun loadDiff(ref: String, kind: String = "working") {
        val path = _ui.value.repoPath ?: return
        // Only the same ref is deduplicated. Picking another commit while one
        // is in flight has to win, or a tap in the history sheet silently does
        // nothing; the response then applies only if its ref is still current.
        val current = _ui.value
        if (current.diff is Loadable.Loading && current.diffRef == ref && current.diffKind == kind) return
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    diff = Loadable.Loading,
                    diffRef = ref,
                    diffKind = kind,
                    selectedFile = null,
                    viewMode = DiffViewMode.Diff,
                    fileDiff = Loadable.Idle,
                    fileContent = Loadable.Idle,
                )
            }
            try {
                val diff = bridge.repoDiff(path, ref, kind)
                _ui.update { if (it.isCurrentDiff(path, ref, kind)) it.copy(diff = Loadable.Ready(diff)) else it }
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update {
                    if (it.isCurrentDiff(path, ref, kind)) {
                        it.copy(diff = Loadable.Failed(error.message ?: "Diff read failed", error.failureKind()))
                    } else it
                }
            }
        }
    }

    /**
     * Opens a file from the stat listing; fetches its hunks unless cached.
     * [mode] starts an untracked file on its content — git has no diff for a
     * path it has never seen, so [DiffViewMode.Diff] would open on nothing.
     */
    fun selectFile(file: String, mode: DiffViewMode = DiffViewMode.Diff) {
        val state = _ui.value
        val path = state.repoPath ?: return
        val ref = state.diffRef ?: return
        val kind = state.diffKind
        _ui.update {
            it.copy(
                selectedFile = file,
                viewMode = mode,
                fileDiff = Loadable.Idle,
                fileContent = Loadable.Idle,
            )
        }
        when (mode) {
            DiffViewMode.Diff -> loadFileDiff(path, ref, kind, file)
            DiffViewMode.File, DiffViewMode.Preview -> loadFileContent(path, ref, kind, file)
        }
    }

    /** Flips the per-file viewer between hunks and the final file content. */
    fun setViewMode(mode: DiffViewMode) {
        val state = _ui.value
        val path = state.repoPath ?: return
        val ref = state.diffRef ?: return
        val file = state.selectedFile ?: return
        _ui.update { it.copy(viewMode = mode) }
        if (mode.showsContent() && _ui.value.fileContent !is Loadable.Ready) {
            loadFileContent(path, ref, state.diffKind, file)
        }
    }

    fun closeFile() {
        _ui.update {
            it.copy(
                selectedFile = null,
                viewMode = DiffViewMode.Diff,
                fileDiff = Loadable.Idle,
                fileContent = Loadable.Idle,
            )
        }
    }

    private fun loadFileDiff(path: String, ref: String, kind: String, file: String) {
        val key = "$path|$ref|$kind|$file"
        fileDiffCache[key]?.let {
            _ui.update { state ->
                if (state.isCurrentFile(path, ref, kind, file)) state.copy(fileDiff = Loadable.Ready(it))
                else state
            }
            return
        }
        viewModelScope.launch {
            _ui.update { state ->
                if (state.isCurrentFile(path, ref, kind, file)) state.copy(fileDiff = Loadable.Loading)
                else state
            }
            try {
                val result = bridge.repoFileDiff(path, ref, kind, file)
                fileDiffCache[key] = result
                _ui.update { state ->
                    if (state.isCurrentFile(path, ref, kind, file)) state.copy(fileDiff = Loadable.Ready(result))
                    else state
                }
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update { state ->
                    if (state.isCurrentFile(path, ref, kind, file)) {
                        state.copy(fileDiff = Loadable.Failed(error.message ?: "File diff read failed", error.failureKind()))
                    } else state
                }
            }
        }
    }

    private fun loadFileContent(path: String, ref: String, kind: String, file: String) {
        val key = "$path|$ref|$kind|$file"
        fileContentCache[key]?.let {
            _ui.update { state ->
                if (state.isCurrentFile(path, ref, kind, file) && state.viewMode.showsContent()) {
                    state.copy(fileContent = Loadable.Ready(it))
                } else state
            }
            return
        }
        if (!fileContentLoads.add(key)) return
        viewModelScope.launch {
            _ui.update { state ->
                if (state.isCurrentFile(path, ref, kind, file) && state.viewMode.showsContent()) {
                    state.copy(fileContent = Loadable.Loading)
                } else state
            }
            try {
                val result = bridge.repoFile(path, ref, kind, file)
                fileContentCache[key] = result
                _ui.update { state ->
                    if (state.isCurrentFile(path, ref, kind, file) && state.viewMode.showsContent()) {
                        state.copy(fileContent = Loadable.Ready(result))
                    } else state
                }
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update { state ->
                    if (state.isCurrentFile(path, ref, kind, file) && state.viewMode.showsContent()) {
                        state.copy(fileContent = Loadable.Failed(error.message ?: "File read failed", error.failureKind()))
                    } else state
                }
            } finally {
                fileContentLoads.remove(key)
            }
        }
    }

    private fun ReviewUiState.isCurrentDiff(path: String, ref: String, kind: String): Boolean =
        repoPath == path && diffRef == ref && diffKind == kind

    private fun ReviewUiState.isCurrentFile(path: String, ref: String, kind: String, file: String): Boolean =
        repoPath == path && diffRef == ref && diffKind == kind && selectedFile == file
    fun refresh() {
        val path = _ui.value.repoPath ?: return
        // The working tree may have changed under us; cached per-file data is
        // no longer trustworthy, so refetch on next open.
        fileDiffCache.clear()
        fileContentCache.clear()
        fileContentLoads.clear()
        selectRepo(path)
    }

    companion object {
        fun factory(bridge: ScoutrApi, hostId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY] ?: error("application missing in ReviewViewModel factory")
                    return ReviewViewModel(bridge, ReviewStore(app, hostId)) as T
                }
            }
    }
}
