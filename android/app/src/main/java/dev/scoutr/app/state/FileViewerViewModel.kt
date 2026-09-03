package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.ScoutrApi
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** Downloaded image bytes for image-eligible triage; Idle until triage proves eligibility. */
    val imageFile: Loadable<File> = Loadable.Idle,
)

/**
 * Reads one workspace file through the bridge and exposes retryable load state.
 * For image-eligible triage (binary + raster mime) it additionally downloads
 * `/api/file/bytes` into [imageCacheDir] and exposes the staged file — Coil
 * renders the file, so auth headers never enter the image pipeline.
 */
class FileViewerViewModel(
    private val bridge: ScoutrApi,
    cwd: String,
    file: String,
    /** Auto-purgeable directory (`cacheDir/images`) staging this viewer's downloads. */
    val imageCacheDir: File,
    private val hostKey: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(FileViewerUiState(cwd = cwd, file = file))
    val ui: StateFlow<FileViewerUiState> = _ui.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    /** Fetches every bounded page for the cwd-relative file expected by the bridge. */
    fun refresh() {
        loadJob?.cancel()
        val state = _ui.value
        if (state.cwd.isBlank() || state.file.isBlank()) {
            _ui.update {
                it.copy(
                    content = Loadable.Failed("File path is not available", FailureKind.Server),
                    imageFile = Loadable.Idle,
                )
            }
            return
        }
        loadJob = viewModelScope.launch {
            _ui.update { it.copy(content = Loadable.Loading, imageFile = Loadable.Idle) }
            try {
                val path = workspaceFilePath(state.cwd, state.file)
                val response = readAllPages(path)
                if (!response.ok && response.error != null) {
                    _ui.update { it.copy(content = Loadable.Failed(response.error, FailureKind.Server)) }
                    return@launch
                }
                _ui.update { it.copy(content = Loadable.Ready(response)) }
                if (!ImageFileCache.isImagePreviewable(response.binary, response.mime)) return@launch
                downloadImage(path, state.file, response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.update {
                    it.copy(content = Loadable.Failed(error.message ?: "File read failed", error.failureKind()))
                }
            }
        }
    }

    private suspend fun downloadImage(path: String, filename: String, triage: FileReadResponse) {
        val overCap = triage.sizeBytes?.let { it > IMAGE_BYTES_CAP } ?: false
        if (overCap) {
            _ui.update {
                it.copy(imageFile = Loadable.Failed(tooLargeReason(triage.sizeBytes), FailureKind.Server))
            }
            return
        }
        _ui.update { it.copy(imageFile = Loadable.Loading) }
        try {
            val cache = ImageFileCache(imageCacheDir)
            val destination = cache.cacheFileFor(hostKey, path, triage.sizeBytes, filename.substringAfterLast('/'))
            // Resume only a strictly partial prefix of the triaged size. A complete
            // (or unknown-size) file restarts from zero, so an overwritten image can
            // never render a stale-prefix/fresh-tail mix. Residual: a same-size
            // overwrite landing between an interrupted download and its resume mixes
            // versions — the bridge offers no validator, and the next refresh
            // (complete prefix restarts from zero) self-heals.
            val resumeFrom =
                if (triage.sizeBytes != null && destination.exists() && destination.length() < triage.sizeBytes) {
                    destination.length()
                } else {
                    0L
                }
            bridge.downloadWorkspaceFile(destination, path, resumeFrom)
            cache.trimToMaxBytes()
            _ui.update { it.copy(imageFile = Loadable.Ready(destination)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is BridgeException && error.status == FILE_TOO_LARGE_STATUS) {
                _ui.update {
                    it.copy(imageFile = Loadable.Failed(tooLargeReason(triage.sizeBytes), FailureKind.Server))
                }
            } else if (error is BridgeException && error.status == 404) {
                // Deleted between triage and bytes: read as missing, not as a broken image.
                _ui.update {
                    it.copy(imageFile = Loadable.Failed("File is unavailable", FailureKind.Server))
                }
            } else {
                _ui.update {
                    it.copy(
                        imageFile = Loadable.Failed(
                            error.message ?: "Image download failed",
                            error.failureKind(),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun readAllPages(path: String): FileReadResponse {
        var page = bridge.file(path, offset = 0, limit = MAX_PAGE_BYTES)
        if (!page.ok || !page.exists || page.binary) return page

        val content = StringBuilder()
        var contentBytes = 0
        var offset = page.offset
        while (true) {
            content.append(page.content)
            contentBytes += page.content.toByteArray(Charsets.UTF_8).size
            val nextOffset = page.nextOffset
            if (nextOffset == null || contentBytes >= MAX_DISPLAY_BYTES) {
                return page.copy(
                    content = content.toString(),
                    offset = 0,
                    nextOffset = null,
                    truncated = page.truncated || nextOffset != null,
                )
            }
            if (nextOffset <= offset) throw IllegalStateException("File page did not advance")
            offset = nextOffset
            page = bridge.file(
                path,
                offset = offset,
                limit = minOf(MAX_PAGE_BYTES, MAX_DISPLAY_BYTES - contentBytes),
            )
            if (!page.ok || !page.exists || page.binary) return page
        }
    }

    private companion object {
        const val MAX_PAGE_BYTES = 256 * 1024
        const val MAX_DISPLAY_BYTES = 4 * 1024 * 1024

        /** Mirrors the bridge `FILE_BYTES_MAX_BYTES` cap; the bridge 413 stays authoritative. */
        const val IMAGE_BYTES_CAP = 20L * 1024 * 1024
        const val FILE_TOO_LARGE_STATUS = 413
    }
}

/** Too-large copy shared by the pre-check and a mid-download 413. */
internal fun tooLargeReason(sizeBytes: Long?): String {
    val size = sizeBytes?.let { " (${formatViewerBytes(it)})" } ?: ""
    return "This image$size is too large to preview — the bridge caps downloads at ${formatViewerBytes(20L * 1024 * 1024)}."
}

/** Compact byte count for triage lines; locale-independent by construction. */
internal fun formatViewerBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).toInt() / 10.0} KB"
    return "${(bytes / 104857.6).toInt() / 10.0} MB"
}

/** Joins an active workspace root to a browser-relative file without duplicating a slash. */
fun workspaceFilePath(cwd: String, file: String): String =
    cwd.trimEnd('/') + "/" + file.trimStart('/')
