package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.theme.ScoutrMono
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.data.RepoCommit
import dev.scoutr.app.data.RepoDiffFileStat
import dev.scoutr.app.state.DiffViewMode
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.state.ReviewViewModel
import dev.scoutr.app.ui.components.ScoutrTextField
import dev.scoutr.app.ui.components.ReadableContentColumn
import dev.scoutr.app.ui.theme.DiffPalette
import dev.snipme.highlights.model.SyntaxLanguage
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
    ui: dev.scoutr.app.state.ReviewUiState,
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
            ScoutrTextField(
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
                Text("Loading folders…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    ui: dev.scoutr.app.state.ReviewUiState,
    diffOpen: Boolean,
    onDiffChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = ui.overview
    if (overview is Loadable.Loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading review…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var commitSheet by remember { mutableStateOf<RepoCommit?>(null) }

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
                        onClick = { commitSheet = commit },
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
                                fontFamily = ScoutrMono,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                humanSize(artifact.size),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = ScoutrMono,
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
    commitSheet?.let { commit ->
        CommitSheet(
            commit = commit,
            onOpenDiff = {
                viewModel.loadDiff(commit.hash, "commit")
                onDiffChanged(true)
                commitSheet = null
            },
            onDismiss = { commitSheet = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitSheet(
    commit: RepoCommit,
    onOpenDiff: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("commit_sheet")) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(commit.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${shortHash(commit.hash)} · ${commit.author} · ${commitDate(commit.date)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = ScoutrMono,
            )
            Spacer(Modifier.height(12.dp))
            if (commit.body.isBlank()) {
                Text(
                    "No commit message body",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    commit.body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = ScoutrMono,
                    modifier = Modifier.testTag("commit_body"),
                )
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenDiff, modifier = Modifier.testTag("commit_diff_button")) { Text("Diff vs parent") }
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
            fontFamily = ScoutrMono,
            color = statusCodeColor(code),
            modifier = Modifier.width(28.dp),
        )
        Text(
            path,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = ScoutrMono,
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
                fontFamily = ScoutrMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (loading) {
            Text(
                "Loading…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffMode(
    viewModel: ReviewViewModel,
    ui: dev.scoutr.app.state.ReviewUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val diffLoad = ui.diff
    val diffData = (diffLoad as? Loadable.Ready)?.value
    val stats = diffData?.stat.orEmpty()
    var fileSheetOpen by rememberSaveable { mutableStateOf(false) }
    var wrapLines by rememberSaveable { mutableStateOf(false) }
    // Auto-open the first file once per diff session — not after the user
    // closes the per-file view.
    var autoOpenedForRef by rememberSaveable(ui.diffRef) { mutableStateOf<String?>(null) }
    LaunchedEffect(diffData, ui.diffRef) {
        if (diffData != null && autoOpenedForRef != ui.diffRef) {
            autoOpenedForRef = ui.diffRef
            if (stats.isNotEmpty()) viewModel.selectFile(stats.first().path)
        }
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
                fontFamily = ScoutrMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { wrapLines = !wrapLines },
                modifier = Modifier.testTag("diff_wrap_toggle"),
            ) {
                Icon(
                    Icons.Default.WrapText,
                    contentDescription = "Wrap lines",
                    tint = if (wrapLines) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (diffData?.truncated == true) "≥ ${stats.size} files (truncated)" else "${stats.size} files",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        if (diffData?.truncated == true) TruncatedNote("file list truncated to 64 KiB — files past the cap are not listed")
        when {
            diffLoad is Loadable.Loading -> CenteredLoading()
            diffLoad is Loadable.Failed ->
                Text(
                    diffLoad.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            stats.isEmpty() -> CenteredNote("No changes", "diff_no_changes")
            else -> PerFileView(
                viewModel = viewModel,
                ui = ui,
                stats = stats,
                wrapLines = wrapLines,
                onOpenPicker = { fileSheetOpen = true },
            )
        }
    }
    if (fileSheetOpen) {
        FilePickerSheet(
            stats = stats,
            selectedPath = ui.selectedFile,
            onPick = { path ->
                viewModel.selectFile(path)
                fileSheetOpen = false
            },
            onDismiss = { fileSheetOpen = false },
        )
    }
}

@Composable
private fun PerFileView(
    viewModel: ReviewViewModel,
    ui: dev.scoutr.app.state.ReviewUiState,
    stats: List<RepoDiffFileStat>,
    wrapLines: Boolean,
    onOpenPicker: () -> Unit,
) {
    val selectedFile = ui.selectedFile
    val selectedIndex = stats.indexOfFirst { it.path == selectedFile }
    if (selectedFile == null || selectedIndex < 0) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pick a file to review", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onOpenPicker, modifier = Modifier.testTag("diff_pick_file")) { Text("Browse files") }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Close (X), not a back arrow: the arrow is reserved for leaving
            // DiffMode entirely, so the two affordances read differently.
            IconButton(onClick = viewModel::closeFile, modifier = Modifier.testTag("diff_file_back")) {
                Icon(Icons.Default.Close, contentDescription = "Close file")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    selectedFile.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val dir = selectedFile.substringBeforeLast('/', missingDelimiterValue = "")
                if (dir.isNotEmpty()) {
                    Text(
                        dir,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = ScoutrMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ViewToggleButton("Diff", ui.viewMode == DiffViewMode.Diff, "diff_view_diff") {
                viewModel.setViewMode(DiffViewMode.Diff)
            }
            ViewToggleButton("File", ui.viewMode == DiffViewMode.File, "diff_view_file") {
                viewModel.setViewMode(DiffViewMode.File)
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { viewModel.selectFile(stats[selectedIndex - 1].path) },
                enabled = selectedIndex > 0,
                modifier = Modifier.testTag("diff_previous"),
            ) { Text("‹ Previous", modifier = Modifier.testTag("diff_previous_label")) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(
                    onClick = onOpenPicker,
                    modifier = Modifier.testTag("diff_file_selector"),
                ) {
                    Text("${selectedIndex + 1} / ${stats.size}  $selectedFile", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(
                onClick = { viewModel.selectFile(stats[selectedIndex + 1].path) },
                enabled = selectedIndex < stats.lastIndex,
                modifier = Modifier.testTag("diff_next"),
            ) { Text("Next ›") }
        }
        HorizontalDivider()
        when (ui.viewMode) {
            DiffViewMode.Diff -> FileDiffContent(ui, selectedFile, wrapLines)
            DiffViewMode.File -> FileContent(ui, selectedFile, wrapLines)
        }
    }
}

@Composable
private fun ViewToggleButton(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.FileDiffContent(
    ui: dev.scoutr.app.state.ReviewUiState,
    selectedFile: String,
    wrapLines: Boolean,
) {
    when (val load = ui.fileDiff) {
        is Loadable.Loading -> CenteredLoading()
        is Loadable.Failed ->
            Text(
                load.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        is Loadable.Ready -> {
            val language = languageForPath(selectedFile)
            key(selectedFile) {
                LineBody(load.value.diff.split("\n"), wrapLines) { line ->
                    DiffLine(line, language, wrapLines)
                }
            }
            if (load.value.truncated) TruncatedNote("file diff truncated to 64 KiB")
        }
        Loadable.Idle -> Unit
    }
}

@Composable
private fun ColumnScope.FileContent(
    ui: dev.scoutr.app.state.ReviewUiState,
    selectedFile: String,
    wrapLines: Boolean,
) {
    when (val load = ui.fileContent) {
        is Loadable.Loading -> CenteredLoading()
        is Loadable.Failed ->
            Text(
                load.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        is Loadable.Ready -> {
            val body = load.value
            when {
                !body.exists -> CenteredNote("File does not exist at ${ui.diffRef?.take(12)}", "diff_file_missing")
                body.binary -> CenteredNote("Binary file", "diff_file_binary")
                else -> {
                    val language = languageForPath(selectedFile)
                    key(selectedFile) {
                        LineBody(body.content.split("\n"), wrapLines) { line ->
                            CodeLine(line, language, MaterialTheme.colorScheme.onSurface, Color.Transparent, 0, wrapLines)
                        }
                    }
                    if (body.truncated) TruncatedNote("file truncated to 256 KiB")
                }
            }
        }
        Loadable.Idle -> Unit
    }
}

/** Shared scrolling body for code lines; resets scroll position per file via [key]. */
@Composable
private fun ColumnScope.LineBody(
    lines: List<String>,
    wrapLines: Boolean,
    content: @Composable (line: String) -> Unit,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(verticalScroll)
            .then(if (wrapLines) Modifier else Modifier.horizontalScroll(horizontalScroll)),
    ) {
        lines.forEach { content(it) }
    }
}

@Composable
private fun DiffLine(line: String, language: SyntaxLanguage?, wrapLines: Boolean) {
    // gdezan-material version_control mapping: inserted cyan, deleted red,
    // hunk headers quiet muted; line backgrounds are the editor's faint tints.
    val (baseColor, background, codeOffset) = when {
        line.startsWith("+++") -> Triple(DiffPalette.Added, DiffPalette.AddedBackground, -1)
        line.startsWith("---") -> Triple(DiffPalette.Deleted, DiffPalette.DeletedBackground, -1)
        line.startsWith('+') -> Triple(DiffPalette.Added, DiffPalette.AddedBackground, 1)
        line.startsWith('-') -> Triple(DiffPalette.Deleted, DiffPalette.DeletedBackground, 1)
        line.startsWith("@@") -> Triple(DiffPalette.Ignored, Color.Transparent, -1)
        else -> Triple(MaterialTheme.colorScheme.onSurface, Color.Transparent, 0)
    }
    CodeLine(line, language, baseColor, background, codeOffset, wrapLines)
}

/**
 * One code line as an [AnnotatedString]: the line color stays the diff
 * identity and syntax token colors compose on top. [codeOffset] is the index
 * where code starts (1 for +/- markers); -1 disables syntax spans.
 */
@Composable
private fun CodeLine(
    line: String,
    language: SyntaxLanguage?,
    baseColor: Color,
    background: Color,
    codeOffset: Int,
    wrapLines: Boolean,
) {
    val spans = remember(line, language) { highlightLine(line, language) }
    val annotated = remember(line, spans, codeOffset, baseColor) {
        buildAnnotatedString {
            append(line)
            addStyle(SpanStyle(color = baseColor), 0, line.length)
            if (codeOffset >= 0) {
                spans.forEach { span ->
                    val start = span.start.coerceAtLeast(codeOffset)
                    if (span.end > start) addStyle(SpanStyle(color = span.color), start, span.end)
                }
            }
        }
    }
    Row(
        Modifier
            .background(background)
            .then(if (wrapLines) Modifier.fillMaxWidth() else Modifier)
            .padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        Text(
            annotated,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = ScoutrMono,
            color = baseColor,
            maxLines = if (wrapLines) Int.MAX_VALUE else 1,
            softWrap = wrapLines,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerSheet(
    stats: List<RepoDiffFileStat>,
    selectedPath: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(stats, filter) {
        if (filter.isBlank()) stats else stats.filter { it.path.contains(filter, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("diff_file_sheet")) {
        Column(Modifier.fillMaxWidth()) {
            ScoutrTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = "Filter files",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("diff_file_filter"),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(filtered, key = { it.path }) { stat ->
                    val index = stats.indexOfFirst { it.path == stat.path }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(stat.path) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("diff_file_$index"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stat.path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val dir = stat.path.substringBeforeLast('/', missingDelimiterValue = "")
                            if (dir.isNotEmpty()) {
                                Text(
                                    dir,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = ScoutrMono,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            "+${stat.additions} −${stat.deletions}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = ScoutrMono,
                            color = when {
                                stat.additions > 0 && stat.deletions == 0 -> DiffPalette.Added
                                stat.deletions > 0 && stat.additions == 0 -> DiffPalette.Deleted
                                else -> DiffPalette.Modified
                            },
                        )
                        if (stat.path == selectedPath) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(18.dp).height(18.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenteredNote(text: String, tag: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(tag))
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
    val app = LocalContext.current.applicationContext as ScoutrApp
    return androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ReviewViewModel.factory(app.container.bridge, app.container.connectionStore),
    )
}

private fun syncSuffix(overview: dev.scoutr.app.data.RepoOverviewResponse): String {
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
