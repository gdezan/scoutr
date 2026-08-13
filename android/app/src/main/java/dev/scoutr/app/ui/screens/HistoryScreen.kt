package dev.scoutr.app.ui.screens

import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import dev.scoutr.app.ui.components.ScoutrTextField
import dev.scoutr.app.ui.components.ConfirmDialog
import dev.scoutr.app.data.SessionCatalogItem
import dev.scoutr.app.state.HistoryItem
import dev.scoutr.app.state.HistoryUiState
import dev.scoutr.app.state.HistoryView
import dev.scoutr.app.state.ResumedSession
import dev.scoutr.app.state.SessionHistoryViewModel
import dev.scoutr.app.state.viewModelFactory
import dev.scoutr.app.ui.components.ReadableContentColumn

/** The Sessions tab: catalog of stored and live pi sessions with lifecycle actions. */
@Composable
fun HistoryScreen(
    onOpenSession: (ResumedSession) -> Unit,
    viewModel: SessionHistoryViewModel = rememberHistoryViewModel(),
    onReview: (HistoryItem) -> Unit = {},
    // rememberLazyListState keeps the raw position across recreation; the per-tab
    // anchors cover tab switches where the position must not leak across views.
    historyListState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()

    // The catalog poll runs only while the history screen is STARTED.
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    var view by rememberSaveable { mutableStateOf(HistoryView.Active) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingClose by remember { mutableStateOf<HistoryItem?>(null) }
    var pendingDelete by remember { mutableStateOf<HistoryItem?>(null) }
    var renaming by remember { mutableStateOf<HistoryItem?>(null) }
    val scope = rememberCoroutineScope()
    var anchors by rememberSaveable(stateSaver = HistoryAnchorMapSaver) {
        mutableStateOf(emptyMap())
    }
    val sortedItems = remember(ui.items, view) { sortedHistoryItems(ui.items, view) }
    var pendingTabRestore by remember { mutableStateOf<HistoryView?>(null) }
    var anchorCaptureEnabled by remember { mutableStateOf(true) }

    // Tabs are parallel places: capture by stable path, then restore the tab's own anchor.
    val latestSortedItems by rememberUpdatedState(sortedItems)
    LaunchedEffect(view, sortedItems) {
        if (sortedItems.isNotEmpty() && pendingTabRestore == view) {
            val target = resolveHistoryAnchor(anchors[view], sortedItems)
            anchorCaptureEnabled = false
            historyListState.scrollToItem(
                index = target?.index?.plus(if (ui.truncated) 1 else 0) ?: 0,
                scrollOffset = target?.scrollOffset ?: 0,
            )
            anchorCaptureEnabled = true
            pendingTabRestore = null
        }
    }
    LaunchedEffect(view, anchorCaptureEnabled, pendingTabRestore) {
        if (!anchorCaptureEnabled || pendingTabRestore != null) return@LaunchedEffect
        snapshotFlow { historyListState.firstVisibleItemIndex to historyListState.firstVisibleItemScrollOffset }
            .first { latestSortedItems.isNotEmpty() }
        snapshotFlow {
            historyListState.firstVisibleItemIndex to historyListState.firstVisibleItemScrollOffset
        }.collect {
            captureHistoryAnchor(historyListState, latestSortedItems)?.let { anchor ->
                anchors = anchors + (view to anchor)
            }
        }
    }

    ReadableContentColumn(
        modifier = modifier.fillMaxSize(),
        contentTag = "history_content",
    ) {
        SearchField(
            query = query,
            onQuery = {
                query = it
                viewModel.setQuery(it)
            },
        )
        ViewTabs(
            view = view,
            onSelect = { nextView ->
                if (nextView != view) {
                    captureHistoryAnchor(historyListState, sortedItems)?.let { anchor ->
                        anchors = anchors + (view to anchor)
                    }
                    pendingTabRestore = nextView
                    view = nextView
                }
            },
        )
        if (!ui.connected && ui.error != null) {
            OfflineBanner(onRetry = viewModel::retry)
        }
        if (ui.error != null && ui.connected) {
            ErrorBanner(message = ui.error ?: "Something went wrong", onDismiss = { /* poll heals */ })
        }
        Box(Modifier.weight(1f)) {
            if (ui.loading && ui.items.isEmpty()) {
                HistorySkeleton()
            } else {
                HistoryList(
                    ui = ui,
                    view = view,
                    sorted = sortedItems,
                    listState = historyListState,
                    onOpen = { item ->
                        if (viewModel.ui.value.busyPath == null) {
                            scope.launch { viewModel.resume(item)?.let(onOpenSession) }
                        }
                    },
                    onFork = { item ->
                        if (viewModel.ui.value.busyPath == null) {
                            scope.launch { viewModel.fork(item)?.let(onOpenSession) }
                        }
                    },
                    onRename = { renaming = it },
                    onClose = { pendingClose = it },
                    onDelete = { pendingDelete = it },
                    onTogglePin = viewModel::togglePin,
                    onToggleArchive = viewModel::toggleArchive,
                    onReview = onReview,
                )
            }
        }
    }

    pendingClose?.let { item ->
        ConfirmDialog(
            title = "Close session?",
            text = "Closing “${item.session.title}” stops its live pane. The transcript is preserved and can be resumed later.",
            confirmLabel = "Close",
            onConfirm = {
                pendingClose = null
                if (viewModel.ui.value.busyPath == null) scope.launch { viewModel.close(item) }
            },
            onDismiss = { pendingClose = null },
        )
    }
    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "Delete session?",
            text = "Deleting “${item.session.title}” removes its stored transcript from the host permanently. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                if (viewModel.ui.value.busyPath == null) scope.launch { viewModel.delete(item) }
            },
            onDismiss = { pendingDelete = null },
        )
    }
    renaming?.let { item ->
        RenameDialog(
            initial = item.session.title,
            onConfirm = { name ->
                renaming = null
                if (viewModel.ui.value.busyPath == null) scope.launch { viewModel.rename(item, name) }
            },
            onDismiss = { renaming = null },
        )
    }
}

/** Runs [action] only while no other catalog mutation is in flight. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    ScoutrTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = "Search sessions",
        leadingIcon = Icons.Default.Search,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("history_search"),
        trailingIcon = {
            if (query.isNotEmpty()) {
                Text(
                    "Clear",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onQuery("") }
                        .padding(6.dp),
                )
            }
        },
    )
}

@Composable
private fun ViewTabs(view: HistoryView, onSelect: (HistoryView) -> Unit) {
    // Horizontally scrollable so the four chips keep their intrinsic width:
    // in a fixed Row the longest label ("Archived") gets squeezed and wraps
    // onto two lines on narrow screens. Overflow scrolls instead of crushing.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryView.entries.forEach { candidate ->
            FilterChip(
                selected = view == candidate,
                onClick = { onSelect(candidate) },
                label = { Text(candidate.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("history_view_${candidate.name}"),
            )
        }
    }
}

@Composable
private fun HistoryList(
    ui: HistoryUiState,
    view: HistoryView,
    sorted: List<HistoryItem>,
    listState: LazyListState,
    onOpen: (HistoryItem) -> Unit,
    onFork: (HistoryItem) -> Unit,
    onRename: (HistoryItem) -> Unit,
    onClose: (HistoryItem) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    onTogglePin: (HistoryItem) -> Unit,
    onToggleArchive: (HistoryItem) -> Unit,
    onReview: (HistoryItem) -> Unit,
) {
    if (sorted.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 72.dp), contentAlignment = Alignment.Center) {
            Text(
                when (view) {
                    HistoryView.Active -> "No active sessions"
                    HistoryView.Completed -> "No completed sessions"
                    HistoryView.Pinned -> "Nothing pinned yet"
                    HistoryView.Archived -> "Nothing archived"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("history_empty"),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("history_list"),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.truncated) {
            item {
                Text(
                    "Results are capped; refine the search to see more.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        items(sorted, key = { it.session.path }) { item ->
            HistoryRow(
                item = item,
                busy = ui.busyPath == item.session.path,
                busyLabel = ui.busyLabel,
                onOpen = { onOpen(item) },
                onFork = { onFork(item) },
                onRename = { onRename(item) },
                onClose = { onClose(item) },
                onDelete = { onDelete(item) },
                onTogglePin = { onTogglePin(item) },
                onToggleArchive = { onToggleArchive(item) },
                onReview = { onReview(item) },
            )
        }
    }
}

private data class HistoryAnchor(
    val path: String,
    val scrollOffset: Int,
    /** The path's index in the old ordered list, used when path and neighbors all disappear. */
    val index: Int,
    val previousPath: String?,
    val nextPath: String?,
)

private data class HistoryAnchorTarget(val index: Int, val scrollOffset: Int)

private val HistoryAnchorMapSaver = Saver<Map<HistoryView, HistoryAnchor>, List<String>>(
    save = { anchors ->
        anchors.entries.flatMap { (view, anchor) ->
            listOf(
                view.name,
                anchor.path,
                anchor.scrollOffset.toString(),
                anchor.index.toString(),
                anchor.previousPath.orEmpty(),
                anchor.nextPath.orEmpty(),
            )
        }
    },
    restore = { saved ->
        saved.chunked(6).associate { fields ->
            HistoryView.valueOf(fields[0]) to HistoryAnchor(
                path = fields[1],
                scrollOffset = fields[2].toInt(),
                index = fields[3].toInt(),
                previousPath = fields[4].ifEmpty { null },
                nextPath = fields[5].ifEmpty { null },
            )
        }
    }
)

private fun sortedHistoryItems(items: List<HistoryItem>, view: HistoryView): List<HistoryItem> {
    val visible = when (view) {
        HistoryView.Active -> items.filter { it.session.active && !it.archived }
        HistoryView.Completed -> items.filter { !it.session.active && !it.archived }
        HistoryView.Pinned -> items.filter { it.pinned }
        HistoryView.Archived -> items.filter { it.archived }
    }
    return visible.sortedWith(
        compareByDescending<HistoryItem> { it.pinned }
            .thenByDescending { it.session.active }
            .thenByDescending { it.session.updatedAt },
    )
}

private fun captureHistoryAnchor(
    listState: LazyListState,
    items: List<HistoryItem>,
): HistoryAnchor? {
    val paths = items.map { it.session.path }
    // Select the row at (or after) the state's first-visible index, not the
    // layout's first visible key: an overscroll stretch at the list end can
    // pull the previous row back into the layout while the state still points
    // at the real top row, which would store the wrong anchor.
    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
        it.index >= listState.firstVisibleItemIndex && it.key in paths
    } ?: return null
    val path = item.key as String
    val index = paths.indexOf(path)
    if (index < 0) return null
    return HistoryAnchor(
        path = path,
        scrollOffset = if (item.index == listState.firstVisibleItemIndex) listState.firstVisibleItemScrollOffset else 0,
        index = index,
        previousPath = paths.getOrNull(index - 1),
        nextPath = paths.getOrNull(index + 1),
    )
}

private fun resolveHistoryAnchor(
    anchor: HistoryAnchor?,
    items: List<HistoryItem>,
): HistoryAnchorTarget? {
    if (anchor == null) return null
    val paths = items.map { it.session.path }
    val exactIndex = paths.indexOf(anchor.path)
    if (exactIndex >= 0) return HistoryAnchorTarget(exactIndex, anchor.scrollOffset)

    val nextIndex = anchor.nextPath?.let(paths::indexOf)?.takeIf { it >= 0 }
    if (nextIndex != null) return HistoryAnchorTarget(nextIndex, 0)
    // Anchor and its next neighbor are gone: the saved old ordered index prefers
    // the next surviving item at the old slot. When the removals run to the tail,
    // the clamp lands the prior item; an empty list falls back to the top.
    // (previousPath stays in the snapshot to document the anchor's neighborhood,
    // but the positional clamp subsumes the prior-item fallback.)
    if (paths.isEmpty()) return null
    return HistoryAnchorTarget(anchor.index.coerceIn(0, paths.lastIndex), 0)
}

/** Swipe-to-reveal anchor values for a session row. */
private enum class RowReveal { Closed, Open }

/** A single action button surfaced by the swipe-to-reveal bar. */
private data class RowAction(
    /** Stable test-tag suffix; unlike [label] it does not flip with pin/archive state. */
    val key: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit,
)

@Composable
private fun HistoryRow(
    item: HistoryItem,
    busy: Boolean,
    busyLabel: String?,
    onOpen: () -> Unit,
    onFork: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onReview: () -> Unit,
) {
    val session = item.session
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptic = rememberHaptic()
    val context = LocalContext.current
    val copyPath = {
        clipboard.setText(AnnotatedString(session.cwd))
        haptic(HapticEvent.Confirm)
        Toast.makeText(context, "Copied path", Toast.LENGTH_SHORT).show()
    }

    // Swipe-to-reveal action bar: rename, pin, archive, plus close for active
    // sessions (delete for stored ones). Anchored so a half-swiped row settles
    // open or closed, never in between; horizontal-only, so list scrolling and
    // the search field are untouched. Tapping a revealed button fires the
    // action; tapping the row while open just closes the reveal.
    val scheme = MaterialTheme.colorScheme
    // Outlined throughout: filled pin/archive/delete glyphs read as much heavier
    // than the pencil and the cross, which made the bar look like four unrelated
    // buttons rather than one quiet strip.
    val actions = buildList {
        // Code (<>) is the Review tab's own nav glyph: same destination, same
        // mark. RateReview's speech-bubble-and-pencil both misread as "comment"
        // and collided with the Rename pencil sitting right next to it.
        add(RowAction("review", "Review", Icons.Outlined.Code, scheme.onSurfaceVariant, onReview))
        // Rename persists the title in the pi session file; claude sessions
        // (agentKind != pi) reject it at the bridge, so the action is only
        // offered where it works.
        if (session.agentKind == "pi") {
            add(RowAction("rename", "Rename", Icons.Outlined.DriveFileRenameOutline, scheme.onSurfaceVariant, onRename))
        }
        add(RowAction("pin", if (item.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin, scheme.onSurfaceVariant, onTogglePin))
        add(RowAction(
            "archive",
            if (item.archived) "Unarchive" else "Archive",
            if (item.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
            scheme.onSurfaceVariant,
            onToggleArchive,
        ))
        if (session.active && session.paneId != null) {
            add(RowAction("close", "Close", Icons.Outlined.Close, scheme.onSurfaceVariant, onClose))
        } else {
            add(RowAction("delete", "Delete", Icons.Outlined.Delete, scheme.error, onDelete))
        }
    }
    val density = LocalDensity.current
    val revealWidthPx = with(density) { (actions.size * 52).dp.toPx() }
    val reveal = remember {
        AnchoredDraggableState(
            initialValue = RowReveal.Closed,
            anchors = DraggableAnchors {
                RowReveal.Closed at 0f
                RowReveal.Open at -revealWidthPx
            },
        )
    }
    fun closeReveal() {
        scope.launch { reveal.animateTo(RowReveal.Closed) }
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        // Action bar, right-aligned, revealed as the card slides left. It sizes
        // itself from the card rather than the other way round: a LazyColumn
        // item is measured with an unbounded height, so fillMaxSize() here
        // would collapse the bar to icon height instead of filling the row.
        Row(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            horizontalArrangement = Arrangement.End,
        ) {
            actions.forEach { action ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(52.dp)
                        .clickable {
                            closeReveal()
                            action.onClick()
                        }
                        .testTag("history_row_action_${action.key}_${session.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint = action.tint,
                    )
                }
            }
        }
        // Foreground card slides left on a horizontal drag.
        Box(
            Modifier
                .offset { IntOffset(reveal.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(reveal, reverseDirection = false, orientation = Orientation.Horizontal)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .clickable {
                    if (reveal.currentValue == RowReveal.Open) closeReveal() else onOpen()
                }
                .testTag("history_row_${session.id}"),
        ) {
            Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .background(
                                when {
                                    item.pinned -> MaterialTheme.colorScheme.tertiary
                                    session.active -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                                RoundedCornerShape(50),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = menuOpenLabel(session, item),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (session.active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Box {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Session actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { menuOpen = true }
                                .padding(10.dp)
                                .testTag("history_row_menu_${session.id}"),
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (session.active) "Open" else "Resume") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                onClick = { menuOpen = false; onOpen() },
                            )
                            if (session.agentKind == "pi") {
                                DropdownMenuItem(
                                    text = { Text("Fork") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                                    onClick = { menuOpen = false; onFork() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = { menuOpen = false; onRename() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (item.pinned) "Unpin" else "Pin") },
                                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                                onClick = { menuOpen = false; onTogglePin() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (item.archived) "Unarchive" else "Archive") },
                                leadingIcon = { Icon(if (item.archived) Icons.Default.Unarchive else Icons.Default.Archive, contentDescription = null) },
                                onClick = { menuOpen = false; onToggleArchive() },
                            )
                            if (session.active && session.paneId != null) {
                                DropdownMenuItem(
                                    text = { Text("Close") },
                                    leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                    onClick = { menuOpen = false; onClose() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Copy path") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = { menuOpen = false; copyPath() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; onDelete() },
                                enabled = !session.active,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (session.preview.isNotBlank()) {
                    Text(
                        text = session.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.cwd,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (session.model != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = shortModel(session.model),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = relativeTime(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            busyLabel ?: "Working…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun menuOpenLabel(session: SessionCatalogItem, item: HistoryItem): String = when {
    item.pinned -> "Pinned"
    session.active -> "Running"
    else -> relativeTime(session.updatedAt)
}

/** Trim provider/model to a short readable key, e.g. "opencode-go/gpt-5.6" → "gpt-5.6". */
internal fun shortModel(model: String): String {
    val slash = model.indexOf('/')
    return if (slash in 1 until model.length - 1) model.substring(slash + 1) else model
}

/** Compact relative time for epoch-millisecond stamps ("now", "5m", "3h", "2d", else date). */
internal fun relativeTime(epochMs: Double, nowMs: Long = System.currentTimeMillis()): String =
    dev.scoutr.app.ui.relativeTime(epochMs, nowMs = nowMs, dateAfterDays = 7)

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Session name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(10.dp))
        Text("Disconnected from the bridge", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        Text(
            "Retry",
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(6.dp)
                .testTag("history_retry"),
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

/** Stable skeleton rows so the list does not flash spinners. */
@Composable
private fun HistorySkeleton() {
    Column(
        Modifier.fillMaxSize().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(6) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.8f)
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .width(40.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
                )
            }
            if (index == 5) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberHistoryViewModel(): SessionHistoryViewModel {
    return viewModel(
        factory = viewModelFactory<SessionHistoryViewModel> { app ->
            SessionHistoryViewModel(
                app.container.bridge,
                app.container.connectionStore,
                app.container.sessionCatalogStore,
            )
        },
    )
}
