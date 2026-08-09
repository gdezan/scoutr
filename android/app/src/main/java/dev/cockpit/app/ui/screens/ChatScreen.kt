package dev.cockpit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cockpit.app.data.ContentBlock
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.entryText
import dev.cockpit.app.state.ChatUiState
import dev.cockpit.app.state.ChatViewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Session", style = MaterialTheme.typography.titleMedium)
                Text(
                    viewModel.paneId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        when {
            ui.loading && ui.entries.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            !ui.exists -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No session transcript for this agent yet.\nUse the input below to steer it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            else -> {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ui.entries, key = { it.entryId }) { entry ->
                        MessageRow(entry)
                    }
                }
            }
        }

        if (ui.error != null) {
            Text(
                ui.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = {
                Text(if (viewModel.waitingForAnswer) "Answer the question…" else "Steer the agent…")
            },
            enabled = !ui.sending,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (input.isNotBlank()) {
                    viewModel.send(input)
                    input = ""
                }
            }),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            viewModel.send(input)
                            input = ""
                        }
                    },
                    enabled = !ui.sending,
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding()
                .testTag("chat_input"),
        )
    }
}

@Composable
private fun MessageRow(entry: SessionEntry) {
    when (entry.role) {
        "user" -> UserBubble(entry)
        "assistant" -> AssistantBubble(entry)
        "toolResult" -> ToolResultChip(entry)
        else -> {}
    }
}

@Composable
private fun UserBubble(entry: SessionEntry) {
    val text = entryText(entry.content)
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("user_bubble"),
        ) {
            Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun AssistantBubble(entry: SessionEntry) {
    val text = entryText(entry.content)
    if (text.isBlank()) return
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("assistant_bubble"),
        ) {
            Text(text, color = MaterialTheme.colorScheme.onSurface)
        }
        if (!entry.model.isNullOrBlank()) {
            Text(
                entry.model,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun ToolResultChip(entry: SessionEntry) {
    val tool = entry.toolName ?: "tool"
    val text = entryText(entry.content).take(90)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().testTag("tool_result"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "↳ $tool${if (entry.isError == true) " (error)" else ""}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (entry.isError == true) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (text.isNotBlank()) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
    }
}

