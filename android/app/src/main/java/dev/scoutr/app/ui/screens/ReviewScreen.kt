package dev.scoutr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scoutr.app.data.AppearancePreferencesStore
import dev.scoutr.app.data.RepoCommit
import dev.scoutr.app.data.RepoDiffFileStat
import dev.scoutr.app.data.RepoOverviewResponse
import dev.scoutr.app.state.DiffViewMode
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.state.showsContent
import dev.scoutr.app.state.ReviewUiState
import dev.scoutr.app.state.ReviewViewModel
import dev.scoutr.app.ui.components.AssistantMarkdown
import dev.scoutr.app.ui.components.ReadableContentColumn
import dev.scoutr.app.ui.components.ScoutrTextField
import dev.scoutr.app.ui.nav.TabScaffold
import dev.scoutr.app.ui.shortenHostPath
import dev.scoutr.app.ui.theme.DiffPalette
import dev.scoutr.app.ui.theme.ScoutrMono
import dev.scoutr.app.ui.theme.ScoutrType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only review center: pick a repo from the bridge's allow-list, read its
 * branch/status/log, and read a bounded diff of the working tree or any recent
 * commit. Everything goes through the read-only /api/repo surface — no
 * checkout, no mutation, and so no stage/commit/revert controls.
 *
 * The layout is §9c's Review screen: one header carrying `~/repo · ref · N
 * files`, a three-tile stat strip, and a list of file tiles that expand in
 * place into their hunks. Commits live behind the header's history glyph.
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    // Wide windows have no bottom bar, so the screen owns the bottom inset;
    // compact windows leave it to ScoutrBottomBar.
    ownsBottomInset: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val appearance = remember(context) { AppearancePreferencesStore(context) }
    val reviewFontSizeSp = appearance.reviewFontSizeSp

    // Self-initializing: show the repo picker when no repo is selected. The
    // guard keeps a config change (rotation) from wiping the open review.
    LaunchedEffect(Unit) {
        if (ui.repoPath == null) viewModel.openPicker()
    }

    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var wrapLines by rememberSaveable { mutableStateOf(false) }

    val overviewData = (ui.overview as? Loadable.Ready)?.value
    val rows = remember(ui.diff, ui.diffKind, overviewData) { fileRows(ui, overviewData) }

    TabScaffold(
        title = "Review",
        subtitle = overviewData?.let { headerFacts(it, ui, rows.size) },
        extraActions = {
            if (ui.repoPath != null) {
                IconButton(
                    onClick = { historyOpen = true },
                    modifier = Modifier.testTag("review_history"),
                ) {
                    Icon(Icons.Default.History, contentDescription = "Commits", modifier = Modifier.size(22.dp))
                }
                ReviewOverflow(
                    wrapLines = wrapLines,
                    onToggleWrap = { wrapLines = !wrapLines },
                    onRefresh = viewModel::refresh,
                    onSwitchRepo = viewModel::openPicker,
                )
            }
        },
        ownsBottomInset = ownsBottomInset,
        modifier = modifier,
    ) { inner ->
        Box(Modifier.padding(inner)) {
            if (ui.repoPath == null) {
                PickerMode(viewModel, ui)
            } else {
                ReviewMode(
                    viewModel = viewModel,
                    ui = ui,
                    rows = rows,
                    wrapLines = wrapLines,
                    reviewFontSizeSp = reviewFontSizeSp,
                )
            }
        }
    }

    if (historyOpen && overviewData != null) {
        HistorySheet(
            overview = overviewData,
            ui = ui,
            onWorkingTree = {
                viewModel.loadDiff("HEAD")
                historyOpen = false
            },
            onCommit = { commit ->
                viewModel.loadDiff(commit.hash, "commit")
                historyOpen = false
            },
            onDismiss = { historyOpen = false },
        )
    }
}

/** One row of the file list: a diffed path, or an untracked path git has no diff for. */
private data class ReviewFileRow(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val untracked: Boolean,
)

/**
 * The file list is the diff's stat listing, plus the untracked paths from
 * status — `git diff` never reports a file it has never seen, and a new file
 * missing from Review is a change the operator cannot review.
 */
private fun fileRows(ui: ReviewUiState, overview: RepoOverviewResponse?): List<ReviewFileRow> {
    val stats = (ui.diff as? Loadable.Ready)?.value?.stat.orEmpty()
    val diffed = stats.map { ReviewFileRow(it.path, it.additions, it.deletions, untracked = false) }
    if (ui.diffKind != "working" || overview == null) return diffed
    val known = stats.mapTo(mutableSetOf()) { it.path }
    val untracked = overview.status
        .filter { it.code.startsWith("?") && it.path !in known }
        .map { ReviewFileRow(it.path, additions = 0, deletions = 0, untracked = true) }
    return diffed + untracked
}

/** `~/scoutr · main · 3 files` — one mono line of machine facts under the title (§9c). */
private fun headerFacts(overview: RepoOverviewResponse, ui: ReviewUiState, fileCount: Int): String {
    val working = ui.diffKind != "commit"
    val ref = if (working) overview.branch ?: "detached HEAD" else shortHash(ui.diffRef.orEmpty())
    val facts = listOf(shortenHostPath(overview.path), ref, "$fileCount files").joinToString(" · ")
    return if (working) facts + syncSuffix(overview) else facts
}

@Composable
private fun ReviewOverflow(
    wrapLines: Boolean,
    onToggleWrap: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchRepo: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("review_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (wrapLines) "Wrap lines · on" else "Wrap lines · off") },
                leadingIcon = { Icon(Icons.Default.WrapText, contentDescription = null) },
                onClick = {
                    onToggleWrap()
                    open = false
                },
                modifier = Modifier.testTag("diff_wrap_toggle"),
            )
            DropdownMenuItem(
                text = { Text("Refresh") },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                onClick = {
                    onRefresh()
                    open = false
                },
                modifier = Modifier.testTag("review_refresh"),
            )
            DropdownMenuItem(
                text = { Text("Switch repo") },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                onClick = {
                    onSwitchRepo()
                    open = false
                },
                modifier = Modifier.testTag("review_switch_repo"),
            )
        }
    }
}

@Composable
private fun PickerMode(
    viewModel: ReviewViewModel,
    ui: ReviewUiState,
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
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
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
                            // A directory name is a path fragment, not a label (§9d).
                            Text(
                                dir,
                                style = ScoutrType.monoMeta,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
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
    ui: ReviewUiState,
    rows: List<ReviewFileRow>,
    wrapLines: Boolean,
    reviewFontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val overview = ui.overview
    if (overview is Loadable.Loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading review…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (overview !is Loadable.Ready) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text((overview as? Loadable.Failed)?.reason ?: "No data", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    ReadableContentColumn(
        modifier = modifier.fillMaxSize(),
        contentTag = "review_capture_root",
    ) {
        StatStrip(ui, rows)
        when (val diff = ui.diff) {
            is Loadable.Failed ->
                Text(
                    diff.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp).testTag("review_diff_error"),
                )
            is Loadable.Loading, Loadable.Idle -> CenteredLoading()
            is Loadable.Ready ->
                if (rows.isEmpty()) {
                    CenteredNote("No changes", "diff_no_changes")
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (diff.value.truncated) {
                            item { TruncatedNote("file list truncated to 64 KiB — files past the cap are not listed") }
                        }
                        items(rows, key = { it.path }) { row ->
                            FileTile(
                                row = row,
                                expanded = ui.selectedFile == row.path,
                                ui = ui,
                                wrapLines = wrapLines,
                                reviewFontSizeSp = reviewFontSizeSp,
                                onToggle = {
                                    if (ui.selectedFile == row.path) {
                                        viewModel.closeFile()
                                    } else {
                                        viewModel.selectFile(
                                            row.path,
                                            if (row.untracked) DiffViewMode.File else DiffViewMode.Diff,
                                        )
                                    }
                                },
                                onViewMode = viewModel::setViewMode,
                            )
                        }
                    }
                }
        }
    }
}

/**
 * `+128 added · −41 removed · 3 files` as three tiles (§9c). The counts ride
 * the diff, so they hold a muted placeholder until it lands rather than
 * flashing a wrong zero.
 */
@Composable
private fun StatStrip(ui: ReviewUiState, rows: List<ReviewFileRow>) {
    val stats: List<RepoDiffFileStat>? = (ui.diff as? Loadable.Ready)?.value?.stat
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).testTag("review_stat_strip"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatTile(
            value = stats?.let { "+${it.sumOf(RepoDiffFileStat::additions)}" },
            label = "added",
            color = DiffPalette.Added,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = stats?.let { "−${it.sumOf(RepoDiffFileStat::deletions)}" },
            label = "removed",
            color = DiffPalette.Deleted,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = stats?.let { "${rows.size}" },
            label = "files",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    value: String?,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(
            value ?: "—",
            style = ScoutrType.monoFact,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else color,
        )
        Text(
            label,
            style = ScoutrType.monoMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * A file tile: one mono row of path and counts that expands in place into the
 * file's hunks (§9c). Expansion is the selection — the ViewModel holds one
 * open file, so opening another closes the last and its fetch is dropped.
 */
@Composable
private fun FileTile(
    row: ReviewFileRow,
    expanded: Boolean,
    ui: ReviewUiState,
    wrapLines: Boolean,
    reviewFontSizeSp: Float,
    onToggle: () -> Unit,
    onViewMode: (DiffViewMode) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("review_file_${row.path}"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            // Ellipsize the head, not the tail. Every path in a repo shares its
            // leading directories, so tail-truncation renders a screen of
            // identical `android/app/src/main/java/dev/sc…` rows; the filename
            // is the fact worth reading (§9c).
            Text(
                row.path,
                style = ScoutrType.monoFact,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
            if (row.untracked) {
                Text("new", style = ScoutrType.monoFact, color = DiffPalette.Added)
            } else {
                Text("+${row.additions}", style = ScoutrType.monoFact, color = DiffPalette.Added)
                Text("−${row.deletions}", style = ScoutrType.monoFact, color = DiffPalette.Deleted)
            }
        }
        if (expanded) {
            // Untracked files have no hunks; their tile opens on content and
            // has nothing to toggle between.
            if (!row.untracked) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    ViewToggleButton("Diff", ui.viewMode == DiffViewMode.Diff, "diff_view_diff") {
                        onViewMode(DiffViewMode.Diff)
                    }
                    ViewToggleButton("File", ui.viewMode == DiffViewMode.File, "diff_view_file") {
                        onViewMode(DiffViewMode.File)
                    }
                    if (isMarkdownFile(row.path)) {
                        ViewToggleButton("Preview", ui.viewMode == DiffViewMode.Preview, "diff_view_preview") {
                            onViewMode(DiffViewMode.Preview)
                        }
                    }
                }
            }
            // The transcript surface stays on the canvas black, one step below
            // the tile it sits in (§9a).
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 10.dp)) {
                if (row.untracked || ui.viewMode.showsContent()) {
                    FileContent(ui, row.path, wrapLines, reviewFontSizeSp)
                } else {
                    FileDiffContent(ui, row.path, wrapLines, reviewFontSizeSp)
                }
            }
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
private fun FileDiffContent(
    ui: ReviewUiState,
    selectedFile: String,
    wrapLines: Boolean,
    reviewFontSizeSp: Float,
) {
    when (val load = ui.fileDiff) {
        is Loadable.Loading, Loadable.Idle -> CenteredLoading()
        is Loadable.Failed ->
            Text(
                load.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        is Loadable.Ready -> {
            val language = languageForPath(selectedFile)
            val lines = remember(load.value.diff) { hunkLines(load.value.diff) }
            key(selectedFile) {
                DiffLines(
                    lines,
                    language,
                    wrapLines,
                    horizontalPadding = 12.dp,
                    style = ScoutrType.monoCode(reviewFontSizeSp),
                )
            }
            if (load.value.truncated) TruncatedNote("file diff truncated to 64 KiB")
        }
    }
}

/**
 * The tile already names the file, so the `diff --git`/`index`/`---`/`+++`
 * preamble is repetition; the body opens on the first hunk (§9c). A diff with
 * no `@@` at all (binary, mode-only) keeps every line it has.
 */
private fun hunkLines(diff: String): List<String> {
    val lines = diff.split("\n")
    val firstHunk = lines.indexOfFirst { it.startsWith("@@") }
    return if (firstHunk > 0) lines.drop(firstHunk) else lines
}

@Composable
private fun FileContent(
    ui: ReviewUiState,
    selectedFile: String,
    wrapLines: Boolean,
    reviewFontSizeSp: Float,
) {
    when (val load = ui.fileContent) {
        is Loadable.Loading, Loadable.Idle -> CenteredLoading()
        is Loadable.Failed ->
            Text(
                load.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        is Loadable.Ready -> {
            val body = load.value
            when {
                !body.exists -> CenteredNote("File does not exist at ${ui.diffRef?.take(12)}", "diff_file_missing")
                body.binary -> CenteredNote("Binary file", "diff_file_binary")
                else -> {
                    key(selectedFile) {
                        if (ui.viewMode == DiffViewMode.Preview && isMarkdownFile(selectedFile)) {
                            AssistantMarkdown(
                                body.content,
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            )
                        } else {
                            val language = languageForPath(selectedFile)
                            CodeLines(
                                body.content.split("\n"),
                                language,
                                wrapLines,
                                horizontalPadding = 12.dp,
                                style = ScoutrType.monoCode(reviewFontSizeSp),
                            )
                        }
                    }
                    if (body.truncated) TruncatedNote("file truncated to 256 KiB")
                }
            }
        }
    }
}

private fun isMarkdownFile(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase()
    return name.endsWith(".md") || name.endsWith(".markdown")
}

/**
 * The commit log, one sheet behind the header's history glyph (§9c). A row
 * loads that commit's diff into the same file list; the trailing chevron
 * opens the message body without leaving the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    overview: RepoOverviewResponse,
    ui: ReviewUiState,
    onWorkingTree: () -> Unit,
    onCommit: (RepoCommit) -> Unit,
    onDismiss: () -> Unit,
) {
    var openBody by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("commit_sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            HistoryRow(
                title = "Working tree",
                subtitle = "diff vs HEAD",
                selected = ui.diffKind != "commit",
                onClick = onWorkingTree,
                modifier = Modifier.testTag("review_working_tree"),
            )
            SectionLabel("Commits")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(overview.log, key = { it.hash }) { commit ->
                    Column {
                        HistoryRow(
                            title = commit.subject,
                            subtitle = "${shortHash(commit.hash)} · ${commit.author} · ${commitDate(commit.date)}",
                            selected = ui.diffRef == commit.hash,
                            onClick = { onCommit(commit) },
                            trailing = {
                                if (commit.body.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            openBody = if (openBody == commit.hash) null else commit.hash
                                        },
                                        modifier = Modifier.testTag("commit_body_toggle"),
                                    ) {
                                        Icon(
                                            if (openBody == commit.hash) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                            contentDescription = "Commit message",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            },
                        )
                        if (openBody == commit.hash) {
                            Text(
                                commit.body,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = ScoutrMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp)
                                    .testTag("commit_body"),
                            )
                        }
                    }
                }
                if (overview.logTruncated) {
                    item { TruncatedNote("recent commits truncated") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HistoryRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = ScoutrType.monoMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title.uppercase(),
        style = ScoutrType.monoSection,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
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
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

internal fun shortHash(hash: String) = hash.take(8)

internal fun commitDate(seconds: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(seconds * 1000))

private fun syncSuffix(overview: RepoOverviewResponse): String {
    val upstream = overview.upstream ?: return ""
    return when {
        overview.ahead > 0 && overview.behind > 0 -> " · ahead ${overview.ahead}, behind ${overview.behind}"
        overview.ahead > 0 -> " · ahead ${overview.ahead}"
        overview.behind > 0 -> " · behind ${overview.behind}"
        else -> " · in sync with $upstream"
    }
}
