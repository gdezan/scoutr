package dev.scoutr.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
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

    Column(modifier.fillMaxSize()) {
        FileViewerHeader(ui.file, ui.cwd, viewModel::refresh, onBack)
        when (val content = ui.content) {
            Loadable.Idle, Loadable.Loading -> ViewerMessage("Loading file…")
            is Loadable.Failed -> ViewerFailure(content.reason, viewModel::refresh)
            is Loadable.Ready -> FileViewerBody(ui.file, content.value)
        }
    }
}

@Composable
private fun FileViewerHeader(file: String, cwd: String, onRefresh: () -> Unit, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(
                file.substringAfterLast('/'),
                style = MaterialTheme.typography.titleLarge,
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
        !body.exists -> ViewerMessage("File does not exist")
        body.binary -> ViewerMessage("Binary file")
        else -> {
            Column(Modifier.fillMaxSize().testTag("file_viewer_content")) {
                val contentModifier = Modifier.fillMaxWidth().weight(1f)
                key(file) {
                    when {
                        isMarkdownFile(file) -> AssistantMarkdown(body.content, contentModifier)
                        languageForPath(file) != null -> CodeLines(
                            lines = body.content.split("\n"),
                            language = languageForPath(file),
                            wrapLines = false,
                            horizontalPadding = 12.dp,
                            style = ScoutrType.monoCode(14f),
                        )
                        else -> PlainFileText(body.content, contentModifier)
                    }
                }
                if (body.truncated) {
                    Text(
                        "File truncated to 256 KiB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun isMarkdownFile(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase()
    return name.endsWith(".md") || name.endsWith(".markdown")
}

@Composable
private fun PlainFileText(content: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(
            content,
            modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = ScoutrMono,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun ViewerMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("file_viewer_notice"))
    }
}

@Composable
private fun ViewerFailure(reason: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(reason, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
