package dev.scoutr.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scoutr.app.data.PiSubagentProgress
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.state.SubagentProgressViewModel
import dev.scoutr.app.ui.components.StatusRing
import dev.scoutr.app.ui.components.StatusRingAnimation
import dev.scoutr.app.ui.imeOrNavigationBarsPadding
import dev.scoutr.app.ui.theme.ScoutrType

/**
 * Read-only PI-workflow run progress. No composer, no asks, no steer —
 * Back is the only way off the screen.
 */
@Composable
fun SubagentProgressScreen(
    viewModel: SubagentProgressViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    BackHandler(onBack = onBack)

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imeOrNavigationBarsPadding()
            .testTag("subagent_progress_screen"),
    ) {
        when (val progress = ui.progress) {
            Loadable.Idle, Loadable.Loading -> {
                SubagentProgressHeader(title = "Subagent", status = null, onBack = onBack)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag("subagent_progress_loading"))
                }
            }
            is Loadable.Failed -> {
                SubagentProgressHeader(title = "Subagent", status = null, onBack = onBack)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Could not load progress", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            progress.reason,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("subagent_progress_error"),
                        )
                        TextButton(onClick = viewModel::retry) { Text("Retry") }
                    }
                }
            }
            is Loadable.Ready -> {
                val body = progress.value
                SubagentProgressHeader(
                    title = body.label?.takeIf { it.isNotBlank() } ?: body.role.ifBlank { "Subagent" },
                    status = body.status,
                    onBack = onBack,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SubagentProgressBody(body)
            }
        }
    }
}

@Composable
private fun SubagentProgressHeader(title: String, status: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("subagent_progress_title"),
            )
            if (status != null) {
                Text(
                    status,
                    style = ScoutrType.monoMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("subagent_progress_status"),
                )
            }
        }
    }
}

@Composable
private fun SubagentProgressBody(body: PiSubagentProgress) {
    val scroll = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRing(
                    color = subagentProgressStatusColor(body.status, scheme.error, scheme.primary, scheme.onSurfaceVariant),
                    animation = subagentProgressRingAnimation(body.status),
                )
                Text(body.status, style = MaterialTheme.typography.labelLarge)
            }
            val task = body.task.ifBlank { body.taskPreview }
            if (task.isNotBlank()) {
                Text("Task", style = ScoutrType.monoSection, color = scheme.onSurfaceVariant)
                Text(task, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("subagent_progress_task"))
            }
            body.lastMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text("Last message", style = ScoutrType.monoSection, color = scheme.onSurfaceVariant)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            body.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text("Error", style = ScoutrType.monoSection, color = scheme.error)
                Text(error, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
            }
            body.output?.takeIf { it.isNotBlank() }?.let { output ->
                Text("Result", style = ScoutrType.monoSection, color = scheme.onSurfaceVariant)
                Text(output, style = ScoutrType.monoCode(13f))
            }
            if (body.truncated) {
                Text(
                    "Output is truncated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun subagentProgressStatusColor(
    status: String,
    error: Color,
    primary: Color,
    done: Color,
): Color = when (status.lowercase()) {
    "failed" -> error
    "queued", "running" -> primary
    else -> done
}

internal fun subagentProgressRingAnimation(status: String): StatusRingAnimation = when (status.lowercase()) {
    "queued", "running" -> StatusRingAnimation.Live
    else -> StatusRingAnimation.Static
}
