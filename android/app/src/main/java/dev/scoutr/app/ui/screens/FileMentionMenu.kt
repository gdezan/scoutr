package dev.scoutr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scoutr.app.state.FileCandidate

/**
 * `@` mention picker. Mirrors [SlashCommandMenu]'s surface, height, selection
 * bar, and status rows; the rows differ (path candidates, not commands), so
 * the two stay separate components rather than one with two row modes.
 */
@Composable
internal fun FileMentionMenu(
    candidates: List<FileCandidate>,
    query: String,
    loading: Boolean,
    error: String?,
    truncated: Boolean,
    selectedIndex: Int,
    onSelect: (FileCandidate) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, candidates.size) {
        if (selectedIndex in candidates.indices) listState.scrollToItem(selectedIndex)
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("file_mention_menu"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        when {
            loading -> FileMentionStatus("Loading files…", progress = true)
            error != null -> FileMentionError(error, onRetry)
            candidates.isEmpty() && query.isEmpty() -> FileMentionStatus("No files available")
            candidates.isEmpty() -> FileMentionStatus("No files match “$query”")
            else -> Column {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(min = 52.dp, max = 182.dp).testTag("file_mention_list"),
                ) {
                    itemsIndexed(candidates, key = { _, candidate -> candidate.path }) { index, candidate ->
                        FileMentionRow(
                            candidate = candidate,
                            selected = index == selectedIndex,
                            onClick = { onSelect(candidate) },
                        )
                        if (index < candidates.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
                if (truncated) {
                    Text(
                        "Showing part of a very large workspace — type to narrow",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileMentionRow(candidate: FileCandidate, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .testTag("file_mention_${candidate.path}"),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Icon(
                imageVector = if (candidate.isDirectory) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
                contentDescription = if (candidate.isDirectory) "Directory" else "File",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp).size(18.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(
                    text = if (candidate.isDirectory) "${candidate.name}/" else candidate.name,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (candidate.parent.isNotEmpty()) {
                    Text(
                        candidate.parent,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileMentionStatus(text: String, progress: Boolean = false) {
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp), contentAlignment = Alignment.CenterStart) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (progress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileMentionError(error: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag("file_mention_retry")) { Text("Retry") }
    }
}
