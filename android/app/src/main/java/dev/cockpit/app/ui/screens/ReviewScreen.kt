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
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
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
import dev.cockpit.app.state.Loadable
import dev.cockpit.app.state.ReviewViewModel
import dev.cockpit.app.ui.components.CockpitTextField
import dev.cockpit.app.ui.components.ReadableContentColumn
import dev.cockpit.app.ui.theme.DiffPalette
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

    // Self-initializing: show the repo picker when no repo is selected. The
    // guard keeps a config change (rotation) from wiping the open review.
    LaunchedEffect(Unit) {
        if (ui.repoPath == null) viewModel.openPicker()
    }
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
    // Editable path: breadcrumbs are nice, but dot-directories (the default
    // worktree root) are hidden from the listing, so typing a path is the
    // reliable way to reach a repo.
    var pathText by rememberSaveable(ui.dirPath) { mutableStateOf(ui.dirPath) }
    LaunchedEffect(ui.dirPath) { pathText = ui.dirPath }
    ReadableContentColumn(
        modifier = modifier.fillMaxSize(),
        contentTag = "review_picker_content",
    ) {
        if (ui.lastRepoPath != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectRepo(ui.lastRepoPath) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).testTag("review_resume_last")) {
                    Text(
                        ui.lastRepoPath?.substringAfterLast('/') ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ui.lastRepoPath?.substringBeforeLast('/') ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = viewModel::browseUp,
                enabled = ui.dirPath.isNotBlank(),
                modifier = Modifier.testTag("review_up"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
            }
            CockpitTextField(
                value = pathText,
                onValueChange = { pathText = it },
                placeholder = "Repository path",
                modifier = Modifier
                    .weight(1f)
                    .testTag("review_path"),
            )
            TextButton(
                onClick = { viewModel.selectRepo(pathText.trim().ifBlank { ui.dirPath }) },
                enabled = pathText.isNotBlank() || ui.dirPath.isNotBlank(),
                modifier = Modifier.testTag("review_select"),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Review this folder")
            }
        }
        HorizontalDivider()
        val pickerError = (ui.dirs as? Loadable.Failed)?.reason ?: (ui.overview as? Loadable.Failed)?.reason
        if (pickerError != null) {
            Text(
                pickerError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp).testTag("review_picker_error"),
            )
        }
        if (ui.dirs is Loadable.Loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val dirs = (ui.dirs as? Loadable.Ready)?.value ?: emptyList()
            if (dirs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("No subdirectories", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(dirs, key = { it }) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.browseInto(dir) }
                                .padding(vertical = 12.dp),
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
    if (overview is Loadable.Loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val overviewData = (overview as? Loadable.Ready)?.value
    if (overviewData == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text((overview as? Loadable.Failed)?.reason ?: "No data", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    if (diffOpen) {
        DiffMode(viewModel, ui, onBack = { onDiffChanged(false) }, modifier.testTag("review_capture_root"))
        return
    }

    ReadableContentColumn(
        modifier = modifier.fillMaxSize(),
        contentTag = "review_capture_root",
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    overviewData.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (overviewData.branch?.let { "branch $it" } ?: "detached HEAD") +
                        " · ${overviewData.status.size} changed" +
                        syncSuffix(overviewData),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = viewModel::openPicker) { Text("Switch repo") }
            TextButton(onClick = viewModel::refresh) { Text("Refresh") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (overviewData.status.isNotEmpty()) {
                    item {
                        SectionLabel("Working tree")
                    }
                    items(overviewData.status, key = { it.path }) { entry ->
                        StatusRow(entry.code, entry.path)
                    }
                    if (overviewData.statusTruncated) {
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
                        loading = ui.diff is Loadable.Loading && ui.diffRef == "HEAD",
                        onClick = { viewModel.loadDiff("HEAD"); onDiffChanged(true) },
                    )
                }
                items(overviewData.log, key = { it.hash }) { commit ->
                    DiffRow(
                        title = commit.subject,
                        subtitle = "${shortHash(commit.hash)} · ${commit.author} · ${commitDate(commit.date)}",
                        selected = ui.diffRef == commit.hash,
                        loading = ui.diff is Loadable.Loading && ui.diffRef == commit.hash,
                        onClick = { viewModel.loadDiff(commit.hash, "commit"); onDiffChanged(true) },
                    )
                }
                if (overviewData.logTruncated) {
                    item { TruncatedNote("recent commits truncated") }
                }
                val artifacts = (ui.artifacts as? Loadable.Ready)?.value.orEmpty()
                if (artifacts.isNotEmpty()) {
                    item {
                        SectionLabel("Generated artifacts")
                    }
                    items(artifacts, key = { it.path }) { artifact ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                artifact.path.removePrefix(overviewData.root).removePrefix("/"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                humanSize(artifact.size),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (ui.artifactsTruncated) {
                        item { TruncatedNote("artifacts truncated") }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun StatusRow(code: String, path: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
        first == '?' -> DiffPalette.Added // untracked
        first == 'D' -> DiffPalette.Deleted
        first == 'R' -> DiffPalette.Renamed
        first == 'U' -> DiffPalette.Conflict
        first == 'M' || first == 'A' || first == 'C' -> DiffPalette.Modified
        else -> DiffPalette.Ignored
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
            .padding(vertical = 10.dp),
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
    val diffLoad = ui.diff
    val diffData = (diffLoad as? Loadable.Ready)?.value
    var selectedIndex by rememberSaveable(ui.diffRef) { mutableStateOf(0) }
    var fileMenuOpen by remember { mutableStateOf(false) }
    val files = parseDiffFiles(diffData?.diff.orEmpty(), diffData?.stat.orEmpty(), diffData?.truncated == true)
    val clampedIndex = selectedIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0))
    val selected = files.getOrNull(clampedIndex)
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    LaunchedEffect(clampedIndex) {
        selectedIndex = clampedIndex
        verticalScroll.scrollTo(0)
        horizontalScroll.scrollTo(0)
    }

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
            Text("${files.size} files", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        if (diffLoad is Loadable.Loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (diffLoad is Loadable.Failed) {
            Text(diffLoad.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("No changes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (files.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { selectedIndex = (clampedIndex - 1).coerceAtLeast(0) },
                        enabled = clampedIndex > 0 && selected?.unavailable != true,
                        modifier = Modifier.testTag("diff_previous"),
                    ) { Text("‹ Previous", modifier = Modifier.testTag("diff_previous_label")) }
                    Box(Modifier.weight(1f)) {
                        TextButton(
                            onClick = { fileMenuOpen = true },
                            modifier = Modifier.fillMaxWidth().testTag("diff_file_selector"),
                        ) {
                            Text("${clampedIndex + 1} / ${files.size}  ${selected?.path ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = fileMenuOpen,
                            onDismissRequest = { fileMenuOpen = false },
                        ) {
                            files.forEachIndexed { index, file ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("${index + 1} / ${files.size}  ${file.path}  +${file.stat?.additions ?: 0} −${file.stat?.deletions ?: 0}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { selectedIndex = index; fileMenuOpen = false },
                                    modifier = Modifier.testTag("diff_file_$index"),
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { selectedIndex = (clampedIndex + 1).coerceAtMost(files.lastIndex) },
                        enabled = clampedIndex < files.lastIndex && selected?.unavailable != true,
                        modifier = Modifier.testTag("diff_next"),
                    ) { Text("Next ›") }
                }
                HorizontalDivider()
            } else {
                Text(
                    "1 / 1  ${selected?.path ?: ""}  +${selected?.stat?.additions ?: 0} −${selected?.stat?.deletions ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("diff_single_file_header"),
                )
                HorizontalDivider()
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(verticalScroll).horizontalScroll(horizontalScroll)) {
                if (selected?.unavailable == true) {
                    Text(
                        "Content unavailable — diff truncated",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).testTag("diff_unavailable"),
                    )
                } else {
                    selected?.raw.orEmpty().split("\n").forEach { line -> DiffLine(line) }
                }
            }
            if (diffData?.truncated == true) {
                TruncatedNote("diff truncated to 64 KiB")
            }
        }
    }
}

@Composable
private fun DiffLine(line: String) {
    // gdezan-material version_control mapping: inserted cyan, deleted red,
    // hunk headers quiet muted; line backgrounds are the editor's faint tints.
    val (color, background) = when {
        line.startsWith("+++") -> DiffPalette.Added to DiffPalette.AddedBackground
        line.startsWith("---") -> DiffPalette.Deleted to DiffPalette.DeletedBackground
        line.startsWith('+') -> DiffPalette.Added to DiffPalette.AddedBackground
        line.startsWith('-') -> DiffPalette.Deleted to DiffPalette.DeletedBackground
        line.startsWith("@@") -> DiffPalette.Ignored to Color.Transparent
        else -> MaterialTheme.colorScheme.onSurface to Color.Transparent
    }
    Row(
        // Wrap content (not the viewport) so long lines extend past the right
        // edge and the parent's horizontalScroll can pan to them.
        Modifier.background(background).padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            softWrap = false,
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

private fun syncSuffix(overview: dev.cockpit.app.data.RepoOverviewResponse): String {
    val upstream = overview.upstream ?: return ""
    return when {
        overview.ahead > 0 && overview.behind > 0 -> " · ahead ${overview.ahead}, behind ${overview.behind}"
        overview.ahead > 0 -> " · ahead ${overview.ahead}"
        overview.behind > 0 -> " · behind ${overview.behind}"
        else -> " · in sync with $upstream"
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
