package dev.scoutr.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scoutr.app.state.FileBrowserEntry
import dev.scoutr.app.state.FileBrowserViewModel
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.ui.imeOrNavigationBarsPadding
import dev.scoutr.app.ui.theme.ScoutrType

/** Full-screen drill-only browser for files under an active agent workspace. */
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    BackHandler(onBack = onBack)
    BackHandler(enabled = ui.directory.isNotBlank()) { viewModel.backDirectory() }

    Column(modifier.fillMaxSize().imeOrNavigationBarsPadding()) {
        FileBrowserHeader(
            cwd = ui.cwd,
            directory = ui.directory,
            canGoUp = ui.directory.isNotBlank(),
            onBack = onBack,
            onUp = viewModel::backDirectory,
            onRefresh = viewModel::refresh,
        )
        when (val listing = ui.listing) {
            Loadable.Idle, Loadable.Loading -> FileBrowserMessage("Loading files…")
            is Loadable.Failed -> FileBrowserFailure(listing.reason, viewModel::refresh)
            is Loadable.Ready -> {
                Column(Modifier.fillMaxSize().testTag("file_browser_content")) {
                    if (listing.value.truncated) {
                        Text(
                            "File list truncated; some entries are not shown",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (ui.children.isEmpty()) {
                        FileBrowserMessage("No files in this folder")
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(ui.children, key = { it.path }) { entry ->
                                FileBrowserRow(entry) {
                                    if (entry.isDirectory) viewModel.drill(entry) else onOpenFile(entry.path)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserHeader(
    cwd: String,
    directory: String,
    canGoUp: Boolean,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = if (canGoUp) onUp else onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (canGoUp) "Up" else "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (directory.isBlank()) "Files" else directory.trimEnd('/').substringAfterLast('/'),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (directory.isBlank()) cwd else "$cwd/${directory.trimEnd('/')}",
                style = ScoutrType.monoMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("file_browser_refresh")) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun FileBrowserRow(entry: FileBrowserEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.FolderOpen else Icons.Default.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
        Text(
            entry.name,
            style = if (entry.isDirectory) MaterialTheme.typography.bodyLarge else ScoutrType.monoFact,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        if (entry.isDirectory) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileBrowserMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileBrowserFailure(reason: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(reason, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
