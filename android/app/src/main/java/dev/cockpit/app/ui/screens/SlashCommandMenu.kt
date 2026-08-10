package dev.cockpit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.cockpit.app.data.SlashCommandInfo

@Composable
internal fun SlashCommandMenu(
    commands: List<SlashCommandInfo>,
    query: String,
    loading: Boolean,
    error: String?,
    selectedIndex: Int,
    onSelect: (SlashCommandInfo) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, commands.size) {
        if (selectedIndex in commands.indices) listState.scrollToItem(selectedIndex)
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("slash_command_menu"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        when {
            loading -> CommandMenuStatus("Loading commands…", progress = true)
            error != null -> CommandMenuError(error, onRetry)
            commands.isEmpty() && query.isEmpty() -> CommandMenuStatus("No commands available")
            commands.isEmpty() -> CommandMenuStatus("No commands match “$query”")
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(min = 52.dp, max = 182.dp).testTag("slash_command_list"),
            ) {
                itemsIndexed(commands, key = { index, command -> "$index:${command.source}:${command.name}" }) { index, command ->
                    CommandRow(
                        command = command,
                        selected = index == selectedIndex,
                        onClick = { onSelect(command) },
                    )
                    if (index < commands.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: SlashCommandInfo, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .testTag("slash_command_${command.name}"),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("/${command.name}") }
                            command.argumentHint?.let { hint ->
                                append("  ")
                                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(hint) }
                            }
                        },
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when (command.source) {
                            "skill" -> "SKILL"
                            "prompt" -> "PROMPT"
                            "extension" -> "EXTENSION"
                            else -> "BUILT-IN"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CommandMenuStatus(text: String, progress: Boolean = false) {
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp), contentAlignment = Alignment.CenterStart) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (progress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommandMenuError(error: String, onRetry: () -> Unit) {
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
        TextButton(onClick = onRetry, modifier = Modifier.testTag("slash_command_retry")) { Text("Retry") }
    }
}
