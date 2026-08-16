package dev.scoutr.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.state.FileViewerViewModel
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.ui.components.AssistantMarkdown
import dev.scoutr.app.ui.theme.ScoutrMono
import dev.scoutr.app.ui.theme.ScoutrType

/** Full-screen renderer for markdown, highlighted source, and plain workspace text. */
@Composable
fun FileViewerScreen(
    viewModel: FileViewerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    BackHandler(onBack = onBack)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        FileViewerHeader(ui.file, ui.cwd, viewModel::refresh, onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (val content = ui.content) {
            Loadable.Idle, Loadable.Loading -> ViewerMessage(
                title = "Reading file",
                detail = "Fetching the workspace content…",
            )
            is Loadable.Failed -> ViewerFailure(content.reason, viewModel::refresh)
            is Loadable.Ready -> FileViewerBody(ui.file, content.value)
        }
    }
}

@Composable
private fun FileViewerHeader(file: String, cwd: String, onRefresh: () -> Unit, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp),
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
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("file_viewer_refresh")) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun FileViewerBody(file: String, body: FileReadResponse) {
    when {
        !body.exists -> ViewerMessage(
            title = "File is unavailable",
            detail = "It may have been moved or removed from the workspace.",
        )
        body.binary -> ViewerMessage(
            title = "Binary file",
            detail = "Scoutr only previews text files.",
        )
        else -> {
            val scrollState = rememberScrollState()
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileTypeBar(file)
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("file_viewer_content"),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                        SelectionContainer {
                            Box(Modifier.fillMaxWidth().padding(16.dp)) {
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(17.dp),
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ViewerFailure(reason: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Could not read file", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
