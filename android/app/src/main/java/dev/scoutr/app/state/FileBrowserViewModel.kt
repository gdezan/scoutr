package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.FileListing
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A directory or file visible in the active-agent workspace browser. */
data class FileBrowserEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)

/** Browser state for one active-agent workspace and its current relative directory. */
data class FileBrowserUiState(
    val cwd: String,
    val listing: Loadable<FileListing> = Loadable.Idle,
    /** Relative directory prefix; empty means the workspace root. */
    val directory: String = "",
) {
    /** Direct children of the current directory, with directories before files. */
    val children: List<FileBrowserEntry>
        get() = when (val state = listing) {
            is Loadable.Ready -> browserChildren(state.value, directory)
            else -> emptyList()
        }
    }

/** Loads a hidden-inclusive workspace listing and exposes uncapped drill-down children. */
class FileBrowserViewModel(
    private val bridge: ScoutrApi,
    cwd: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(FileBrowserUiState(cwd = cwd))
    val ui: StateFlow<FileBrowserUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    /** Fetches the browser listing, including hidden and ignored workspace files. */
    fun refresh() {
        val cwd = _ui.value.cwd
        if (cwd.isBlank()) {
            _ui.update { it.copy(listing = Loadable.Failed("Workspace is not available", FailureKind.Server)) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(listing = Loadable.Loading) }
            try {
                val response = bridge.files(cwd, includeHidden = true)
                val listing = response.listing
                if (listing == null) {
                    _ui.update { it.copy(listing = Loadable.Failed(response.error ?: "Workspace listing failed", FailureKind.Server)) }
                } else {
                    _ui.update { it.copy(listing = Loadable.Ready(listing)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.update { it.copy(listing = Loadable.Failed(error.message ?: "Workspace listing failed", error.failureKind())) }
            }
        }
    }

    /** Opens a direct child directory, preserving the trailing slash invariant. */
    fun drill(entry: FileBrowserEntry) {
        if (!entry.isDirectory) return
        _ui.update { it.copy(directory = entry.path.ensureTrailingSlash()) }
    }

    /** Moves to the parent directory, or stays at the workspace root. */
    fun backDirectory() {
        val current = _ui.value.directory.trimEnd('/')
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        _ui.update { it.copy(directory = parent.ensureTrailingSlashUnlessBlank()) }
    }
}


private fun browserChildren(listing: FileListing?, directory: String): List<FileBrowserEntry> {
    val prefix = directory.ensureTrailingSlashUnlessBlank()
    val directories = linkedMapOf<String, String>()
    val files = mutableListOf<FileBrowserEntry>()
    listing?.files.orEmpty().forEach { relativePath ->
        if (!relativePath.startsWith(prefix)) return@forEach
        val remainder = relativePath.removePrefix(prefix)
        if (remainder.isBlank()) return@forEach
        val slash = remainder.indexOf('/')
        if (slash >= 0) {
            val name = remainder.substring(0, slash)
            directories.putIfAbsent(name, prefix + name)
        } else {
            files += FileBrowserEntry(name = remainder, path = prefix + remainder, isDirectory = false)
        }
    }
    return directories.map { (name, path) -> FileBrowserEntry(name, path, true) }
        .sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
}

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

private fun String.ensureTrailingSlashUnlessBlank(): String = if (isBlank()) "" else ensureTrailingSlash()
