package dev.scoutr.app.ui.screens

import dev.scoutr.app.state.ImageFileCache
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.state.formatViewerBytes
import dev.scoutr.app.ui.theme.ScoutrBorder
import dev.scoutr.app.ui.theme.ScoutrSpace
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.state.FileViewerViewModel as ViewerModel
import dev.scoutr.app.ui.components.AssistantMarkdown
import dev.scoutr.app.ui.theme.ScoutrMono
import dev.scoutr.app.ui.theme.ScoutrType
import java.io.File
import kotlinx.coroutines.launch

/** Full-screen renderer for images, markdown, highlighted source, and plain workspace text. */
@Composable
fun FileViewerScreen(
    viewModel: ViewerModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val triage = (ui.content as? Loadable.Ready)?.value
    val readyImage = (ui.imageFile as? Loadable.Ready)?.value

    fun notice(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun openImage(image: File, mime: String?) {
        val opened = try {
            ImageShare.openWith(context, viewModel.imageCacheDir, image, mime)
        } catch (rejected: IllegalArgumentException) {
            notice("Could not open this image")
            return
        }
        if (!opened) notice("No app can open this image")
    }

    var pendingSave by remember { mutableStateOf<File?>(null) }
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
            val image = pendingSave ?: return@rememberLauncherForActivityResult
            pendingSave = null
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    ImageShare.saveToUri(context, image, uri)
                    notice("Saved to Downloads (${ui.file.substringAfterLast('/')})")
                } catch (error: Exception) {
                    notice("Save failed: ${error.message ?: "unknown error"}")
                }
            }
        }

    fun saveImage(image: File, mime: String?) {
        val filename = ui.file.substringAfterLast('/')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scope.launch {
                try {
                    val stored = ImageShare.saveToDownloads(context, image, filename, mime)
                    notice("Saved to Downloads ($stored)")
                } catch (error: Exception) {
                    notice("Save failed: ${error.message ?: "unknown error"}")
                }
            }
        } else {
            pendingSave = image
            createDocument.launch(filename)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FileViewerHeader(
                file = ui.file,
                cwd = ui.cwd,
                onRefresh = viewModel::refresh,
                onBack = onBack,
                onOpenWith = readyImage?.let { image -> { openImage(image, triage?.mime) } },
                onSave = readyImage?.let { image -> { saveImage(image, triage?.mime) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (val content = ui.content) {
                Loadable.Idle, Loadable.Loading -> ViewerMessage(
                    title = "Reading file",
                    detail = "Fetching the workspace content…",
                )
                is Loadable.Failed -> ViewerFailure(content.reason, viewModel::refresh)
                is Loadable.Ready -> FileViewerBody(
                    file = ui.file,
                    body = content.value,
                    imageState = ui.imageFile,
                    onRetry = viewModel::refresh,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun FileViewerHeader(
    file: String,
    cwd: String,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpenWith: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = ScoutrSpace.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                file.substringAfterLast('/'),
                style = ScoutrType.monoCode(15f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$cwd/${file.substringBeforeLast('/', missingDelimiterValue = "")}".trimEnd('/'),
                style = ScoutrType.monoMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onOpenWith != null) {
            IconButton(onClick = onOpenWith, modifier = Modifier.testTag("file_viewer_open_with")) {
                Icon(Icons.Default.OpenInNew, contentDescription = "Open with")
            }
        }
        if (onSave != null) {
            IconButton(onClick = onSave, modifier = Modifier.testTag("file_viewer_save")) {
                Icon(Icons.Default.Download, contentDescription = "Save")
            }
        }
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("file_viewer_refresh")) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun FileViewerBody(
    file: String,
    body: FileReadResponse,
    imageState: Loadable<File>,
    onRetry: () -> Unit,
) {
    when {
        !body.exists -> ViewerMessage(
            title = "File is unavailable",
            detail = "It may have been moved or removed from the workspace.",
        )
        ImageFileCache.isImagePreviewable(body.binary, body.mime) ->
            ImageViewer(imageState = imageState, onRetry = onRetry)
        // SVG arrives as text (it is valid UTF-8) but stays in binary triage:
        // without an SVG renderer the source view would dump image markup.
        body.binary || body.mime == "image/svg+xml" -> ViewerMessage(
            title = binaryPreviewTitle(body.mime),
            detail = binaryPreviewDetail(body.mime, body.sizeBytes),
        )
        else -> {
            val scrollState = rememberScrollState()
            Column(
                Modifier.fillMaxSize().padding(horizontal = ScoutrSpace.md, vertical = ScoutrSpace.md),
                verticalArrangement = Arrangement.spacedBy(ScoutrSpace.sm),
            ) {
                FileTypeBar(file)
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("file_viewer_content"),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(ScoutrBorder.hairline, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                        SelectionContainer {
                            Column(Modifier.fillMaxWidth().padding(ScoutrSpace.lg)) {
                                key(file) {
                                    when {
                                        isMarkdownFile(file) -> AssistantMarkdown(
                                            body.content,
                                            Modifier.fillMaxWidth(),
                                        )
                                        languageForPath(file) != null -> CodeLines(
                                            lines = body.content.split("\n"),
                                            language = languageForPath(file),
                                            wrapLines = true,
                                            horizontalPadding = 0.dp,
                                            style = ScoutrType.monoCode(13f),
                                        )
                                        else -> PlainFileText(body.content)
                                    }
                                }
                            }
                        }
                    }
                }
                if (body.truncated) {
                    ViewerWarning(
                        title = "Only part of this file is available",
                        detail = "Very large files are capped to keep the viewer responsive.",
                    )
                }
            }
        }
    }
}

/** Zoomable/pannable image surface for a downloaded workspace image. */
@Composable
private fun ImageViewer(imageState: Loadable<File>, onRetry: () -> Unit) {
    when (imageState) {
        Loadable.Idle, Loadable.Loading -> ViewerMessage(
            title = "Loading image…",
            detail = "Fetching the full image…",
        )
        is Loadable.Failed -> Box(
            Modifier.fillMaxSize().padding(ScoutrSpace.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Could not load image", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    imageState.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        is Loadable.Ready -> ZoomableImage(file = imageState.value)
    }
}

@Composable
private fun ZoomableImage(file: File) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context).components {
            add(GifDecoder.Factory())
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
        }.build()
    }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var viewport by remember(file) { mutableStateOf(IntSize.Zero) }
    val transformable = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size ->
                // A viewport change here means rotation: re-fit instead of keeping a stale zoom.
                if (viewport != IntSize.Zero && size != viewport) {
                    scale = 1f
                    offset = Offset.Zero
                }
                viewport = size
            }
            .transformable(transformable)
            .pointerInput(file) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2f
                        offset = Offset.Zero
                    },
                )
            }
            .testTag("file_viewer_image"),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file,
            imageLoader = imageLoader,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

@Composable
private fun FileTypeBar(file: String) {
    val kind = when {
        isMarkdownFile(file) -> "MARKDOWN PREVIEW"
        languageForPath(file) != null -> "SOURCE"
        else -> "TEXT"
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScoutrSpace.sm),
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(kind, style = ScoutrType.monoSection, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            "SELECTABLE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Triage title for a binary file: names the coming preview when the type is known. */
private fun binaryPreviewTitle(mime: String?): String = when {
    mime?.startsWith("text/html") == true -> "Browser handoff is coming"
    mime == "application/pdf" -> "PDF handoff is coming"
    else -> "Binary file"
}

/** Triage detail: what this file is and which slice will open it. */
private fun binaryPreviewDetail(mime: String?, sizeBytes: Long?): String {
    val size = sizeBytes?.let { " (${formatViewerBytes(it)})" } ?: ""
    return when {
        mime?.startsWith("text/html") == true ->
            "This page$size can't be rendered yet — browser handoff is the next slice."
        mime == "application/pdf" ->
            "This document$size can't be opened yet — viewer handoff is the next slice."
        size.isNotEmpty() ->
            "Scoutr only previews text files (this one is$size)."
        else -> "Scoutr only previews text files."
    }
}

private fun isMarkdownFile(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase()
    return name.endsWith(".md") || name.endsWith(".markdown")
}

@Composable
private fun PlainFileText(content: String) {
    Text(
        content,
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = ScoutrMono,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
}

@Composable
private fun ViewerMessage(title: String, detail: String) {
    Box(Modifier.fillMaxSize().padding(ScoutrSpace.xl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("file_viewer_notice"),
            )
        }
    }
}

@Composable
private fun ViewerWarning(title: String, detail: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(ScoutrBorder.hairline, MaterialTheme.colorScheme.tertiary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(17.dp),
            )
            Column(Modifier.padding(start = ScoutrSpace.sm)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ViewerFailure(reason: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(ScoutrSpace.xl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Could not read file", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
