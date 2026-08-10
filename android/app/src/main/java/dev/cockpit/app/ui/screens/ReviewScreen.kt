package dev.cockpit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cockpit.app.CockpitApp
import dev.cockpit.app.state.ReviewViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only review center: pick a repo from the bridge's allow-list, read its
 * branch/status/log, and open a bounded diff against the working tree or any
 * recent commit. Everything goes through the read-only /api/repo surface —
 * no checkout, no mutation.
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()

    // Self-initializing: show the repo picker whenever no repo is selected.
    LaunchedEffect(Unit) { viewModel.openPicker() }
    var diffOpen by rememberSaveable { mutableStateOf(false) }

    if (ui.repoPath == null) {
        PickerMode(viewModel, ui, modifier)
    } else {
        ReviewMode(viewModel, ui, diffOpen, onDiffChanged = { diffOpen = it }, modifier)
    }
}

@Composable
private fun PickerMode(
    viewModel: ReviewViewModel,
    ui: dev.cockpit.app.state.ReviewUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = viewModel::browseUp,
                enabled = ui.dirPath.isNotBlank(),
                modifier = Modifier.testTag("review_up"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
            }
            Text(
                ui.dirPath.ifBlank { "Home" },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { viewModel.selectRepo(ui.dirPath) },
                enabled = ui.dirPath.isNotBlank(),
                modifier = Modifier.testTag("review_select"),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Review this folder")
            }
        }
        HorizontalDivider()
        if (ui.dirsError != null) {
            Text(
                ui.dirsError ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (ui.dirsLoading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (ui.dirs.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("No subdirectories", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.dirs, key = { it }) { dir ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.browseInto(dir) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(dir, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewMode(
    viewModel: ReviewViewModel,
    ui: dev.cockpit.app.state.ReviewUiState,
    diffOpen: Boolean,
    onDiffChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = ui.overview
    if (overview == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (ui.loading) CircularProgressIndicator() else Text(ui.error ?: "No data", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    if (diffOpen && ui.diff != null) {
        DiffMode(viewModel, ui, onBack = { onDiffChanged(false) }, modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    overview.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (overview.branch?.let { "branch $it" } ?: "detached HEAD") +
                        " · ${overview.status.size} changed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = viewModel::openPicker) { Text("Switch repo") }
            TextButton(onClick = viewModel::refresh) { Text("Refresh") }
        }
        HorizontalDivider()
        if (ui.loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (overview.status.isNotEmpty()) {
                    item {
                        SectionLabel("Working tree")
                    }
                    items(overview.status, key = { it.path }) { entry ->
                        StatusRow(entry.code, entry.path)
                    }
                    if (overview.statusTruncated) {
                        item { TruncatedNote("status list truncated") }
                    }
                }
                item {
                    SectionLabel("Commits")
                }
                item {
                    // Working-tree diff entry.
                    DiffRow(
                        title = "Working tree",
                        subtitle = "diff vs HEAD",
                        selected = ui.diffRef == "HEAD",
                        loading = ui.diffLoading && ui.diffRef == "HEAD",
                        onClick = { viewModel.loadDiff("HEAD"); onDiffChanged(true) },
                    )
                }
                items(overview.log, key = { it.hash }) { commit ->
                    DiffRow(
                        title = commit.subject,
                        subtitle = "${shortHash(commit.hash)} · ${commit.author} · ${commitDate(commit.date)}",
                        selected = ui.diffRef == commit.hash,
                        loading = ui.diffLoading && ui.diffRef == commit.hash,
                        onClick = { viewModel.loadDiff(commit.hash); onDiffChanged(true) },
                    )
                }
                if (overview.logTruncated) {
                    item { TruncatedNote("recent commits truncated") }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatusRow(code: String, path: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            code,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = statusCodeColor(code),
            modifier = Modifier.width(28.dp),
        )
        Text(
            path,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun statusCodeColor(code: String): Color {
    val first = code.firstOrNull() ?: ' '
    return when {
        first == '?' -> MaterialTheme.colorScheme.tertiary
        first == 'M' || first == 'A' || first == 'R' || first == 'C' -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
private fun DiffRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiffMode(
    viewModel: ReviewViewModel,
    ui: dev.cockpit.app.state.ReviewUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to overview")
            }
            Text(
                "Diff ${ui.diffRef?.take(12) ?: ""}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${ui.diff?.stat?.size ?: 0} files",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        if (ui.diffLoading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (ui.error != null) {
            Text(
                ui.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val diff = ui.diff?.diff ?: ""
            if (diff.isBlank()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("No changes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        diff.split("\n").forEach { line -> DiffLine(line) }
                    }
                }
            }
            if (ui.diff?.truncated == true) {
                TruncatedNote("diff truncated to 64 KiB")
            }
        }
    }

@Composable
private fun DiffLine(line: String) {
    val color = when {
        line.startsWith("+++") || line.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant
        line.startsWith('+') -> MaterialTheme.colorScheme.secondary
        line.startsWith('-') -> MaterialTheme.colorScheme.error
        line.startsWith("@@") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun TruncatedNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

internal fun shortHash(hash: String) = hash.take(8)

internal fun commitDate(seconds: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(seconds * 1000))

@Composable
fun rememberReviewViewModel(): ReviewViewModel {
    val app = LocalContext.current.applicationContext as CockpitApp
    return androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ReviewViewModel.factory(app.container.bridge, app.container.connectionStore),
    )
}
