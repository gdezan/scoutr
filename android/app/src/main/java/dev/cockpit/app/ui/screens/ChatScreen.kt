package dev.cockpit.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cockpit.app.data.ContentBlock
import dev.cockpit.app.data.SessionEntry
import dev.cockpit.app.data.QuestionEntry
import dev.cockpit.app.data.SlashCommandInfo
import dev.cockpit.app.data.entryText
import dev.cockpit.app.ui.components.QuestionCard
import dev.cockpit.app.state.ChatUiState
import dev.cockpit.app.state.ChatViewModel
import dev.cockpit.app.state.MessageDeliveryState
import dev.cockpit.app.state.PendingUserMessage
import dev.cockpit.app.state.fillSlashCommand
import dev.cockpit.app.state.matchSlashCommands
import dev.cockpit.app.state.slashCommandQuery
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive


@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var closeOpen by rememberSaveable { mutableStateOf(false) }
    var configurationOpen by rememberSaveable { mutableStateOf(false) }

    LifecycleStartEffect(ui.liveOutputExpanded) {
        if (ui.liveOutputExpanded) viewModel.startLiveOutputPolling()
        onStopOrDispose { viewModel.stopLiveOutputPolling() }
    }

    Column(modifier.fillMaxSize()) {
        ChatHeader(
            paneId = viewModel.paneId,
            sessionTitle = ui.sessionTitle,
            model = ui.model,
            thinkingLevel = ui.thinkingLevel,
            status = if (viewModel.waitingForAnswer) "needs you" else ui.agentStatus,
            detailsVisible = detailsVisible,
            onToggleDetails = { detailsVisible = !detailsVisible },
            onOpenConfiguration = { configurationOpen = true },
            onBack = onBack,
            onControl = { action ->
                when (action) {
                    "rename" -> renameOpen = true
                    "close" -> closeOpen = true
                    "retry" -> viewModel.control("retry", ui.lastUserMessage)
                    else -> viewModel.control(action)
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.loading && ui.entries.isEmpty() && ui.pendingMessages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                !ui.exists && ui.pendingMessages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No session transcript for this agent yet.\nUse the input below to steer it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                else -> {
                    ChatList(
                        entries = ui.entries,
                        questions = ui.questions,
                        answeringQuestionId = ui.answeringQuestionId,
                        pendingMessages = ui.pendingMessages,
                        detailsVisible = detailsVisible,
                        liveOutputExpanded = ui.liveOutputExpanded,
                        onRetryPending = viewModel::retryPendingMessage,
                        onAnswerQuestion = viewModel::answerQuestion,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().imePadding()) {
            if (ui.error != null) {
                Text(
                    ui.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LiveOutputDrawer(ui)
            LiveOutputStrip(
                ui = ui,
                onToggle = { viewModel.setLiveOutputExpanded(!ui.liveOutputExpanded) },
            )
            ChatComposer(
                value = input,
                onValueChange = { input = it },
                placeholder = if (viewModel.waitingForAnswer) "Answer the question…" else "Steer the agent…",
                enabled = !ui.sending,
                commands = ui.commands,
                commandsLoading = ui.commandsLoading,
                commandsError = ui.commandsError,
                onRetryCommands = viewModel::retryCommands,
                onSend = {
                    if (input.isNotBlank()) {
                        viewModel.send(input)
                        input = ""
                    }
                },
            )
        }
    }

    if (renameOpen) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (label.isNotBlank()) viewModel.control("rename", label.trim())
                        renameOpen = false
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (closeOpen) {
        AlertDialog(
            onDismissRequest = { closeOpen = false },
            title = { Text("Close this session?") },
            text = { Text("This stops the running agent and closes its workspace. The transcript stays on disk so you can resume it later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeOpen = false
                        viewModel.control("close", onSuccess = onBack)
                    },
                ) { Text("Close session", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { closeOpen = false }) { Text("Keep running") }
            },
            modifier = Modifier.testTag("close_session_dialog"),
        )
    }

    if (configurationOpen) {
        ConversationConfigSheet(
            ui = ui,
            onSelectModel = { viewModel.control("set_model", it) },
            onSelectThinking = { viewModel.control("set_thinking", it) },
            onDismiss = { configurationOpen = false },
        )
    }
}

@Composable
private fun ChatHeader(
    paneId: String,
    sessionTitle: String,
    model: String?,
    thinkingLevel: String?,
    status: String,
    detailsVisible: Boolean,
    onToggleDetails: () -> Unit,
    onOpenConfiguration: () -> Unit,
    onBack: () -> Unit,
    onControl: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    sessionTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    paneId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onToggleDetails) {
                Icon(
                    if (detailsVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    modifier = Modifier.size(20.dp),
                    contentDescription = if (detailsVisible) "Hide thinking and tool details"
                    else "Show thinking and tool details",
                    tint = if (detailsVisible) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("chat_controls"),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Session actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val items = listOf(
                        "abort" to "Abort response",
                        "retry" to "Retry last message",
                        "compact" to "Compact context",
                        "fork" to "Fork session",
                        "rename" to "Rename session…",
                        "close" to "Close session…",
                    )
                    items.forEach { (action, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                onControl(action)
                            },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderStatusChip(status)
            HeaderConfigurationChip(
                label = "Thinking",
                value = thinkingLevel ?: "—",
                onClick = onOpenConfiguration,
                testTag = "chat_thinking_config",
            )
            HeaderConfigurationChip(
                label = "Model",
                value = model?.substringAfterLast('/') ?: "—",
                onClick = onOpenConfiguration,
                testTag = "chat_model_config",
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

@Composable
private fun HeaderStatusChip(status: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (status == "needs you") MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = if (status == "needs you") MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun HeaderConfigurationChip(
    label: String,
    value: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(testTag),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The transcript stream. Opens at the last message and follows new ones while
 * the user is at the bottom; scrolling up stops the follow and shows a
 * scroll-to-end button. Auto-scroll is guarded against out-of-range indices so
 * concurrent appends can never crash it.
 */
@Composable
fun ChatList(
    entries: List<SessionEntry>,
    detailsVisible: Boolean,
    pendingMessages: List<PendingUserMessage> = emptyList(),
    questions: List<QuestionEntry> = emptyList(),
    answeringQuestionId: String? = null,
    liveOutputExpanded: Boolean = false,
    onRetryPending: (String) -> Unit = {},
    onAnswerQuestion: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var followNew by remember { mutableStateOf(true) }

    // A new entry only lands while the user is at the bottom; the moment they
    // scroll up, stop following and surface the scroll-to-end button.
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.totalItemsCount - 1
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to last
        }.collect { (lastVisible, totalLast) ->
            followNew = lastVisible >= totalLast - 1
        }
    }

    // Follow: initial open + every append while at the bottom. Bounded index
    // and a guard mean this can never throw on a race with the 2.5s poll.
    val lastItemKey = pendingMessages.lastOrNull()?.localId ?: entries.lastOrNull()?.entryId
    LaunchedEffect(entries.size, pendingMessages.size, lastItemKey, liveOutputExpanded) {
        val lastIndex = entries.size + pendingMessages.size - 1
        if (followNew && lastIndex >= 0) {
            try {
                listState.scrollToItem(lastIndex)
                listState.scrollBy(Float.MAX_VALUE)
            } catch (_: Exception) {
                // Concurrent append raced the scroll; the next state change retries.
            }
        }
    }

    val scope = rememberCoroutineScope()
    Box(modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().testTag("chat_list"),
        ) {
            items(entries, key = { it.entryId }) { entry ->
                MessageRow(
                    entry = entry,
                    detailsVisible = detailsVisible,
                    modifier = Modifier.animateItem(),
                )
            }
            items(questions, key = { it.id }) { question ->
                QuestionCard(
                    question = question,
                    sending = answeringQuestionId == question.id,
                    onAnswer = { answer -> onAnswerQuestion(question.id, answer) },
                    modifier = Modifier.animateItem(),
                )
            }
            items(pendingMessages, key = { it.localId }) { message ->
                PendingUserBubble(
                    message = message,
                    onRetry = { onRetryPending(message.localId) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        val notAtBottom by remember(listState) {
            derivedStateOf {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible < info.totalItemsCount - 1
            }
        }
        AnimatedVisibility(
            visible = notAtBottom,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    followNew = true
                    scope.launch {
                        try {
                            listState.scrollToItem((entries.size + pendingMessages.size - 1).coerceAtLeast(0))
                            listState.scrollBy(Float.MAX_VALUE)
                        } catch (_: Exception) {
                            // List changed between the tap and the scroll; retry next frame.
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp).testTag("scroll_to_end_fab"),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to end",
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    entry: SessionEntry,
    detailsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    when (entry.role) {
        "user" -> UserBubble(entry, modifier)
        "assistant" -> AssistantBubble(entry, detailsVisible, modifier)
        "toolResult" -> ToolResultChip(entry, forceExpanded = detailsVisible, modifier)
        else -> {}
    }
}

@Composable
private fun UserBubble(entry: SessionEntry, modifier: Modifier = Modifier) {
    val text = entryText(entry.content)
    if (text.isBlank()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .padding(end = 4.dp)
                .widthIn(max = 288.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("user_bubble"),
        ) {
            Text(text, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PendingUserBubble(
    message: PendingUserMessage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .widthIn(max = 288.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("pending_user_bubble"),
            ) {
                Text(message.text, color = MaterialTheme.colorScheme.onSurface)
            }
            when (message.state) {
                MessageDeliveryState.QUEUED -> Row(
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp).testTag("pending_message_queued"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Text("Queued", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MessageDeliveryState.FAILED -> TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("pending_message_failed"),
                ) {
                    Text("Not sent · Retry", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    entry: SessionEntry,
    detailsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("assistant_bubble")) {
        for (block in entry.content) {
            when (block.type) {
                "text" -> {
                    val text = block.text?.trim()
                    if (!text.isNullOrBlank()) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                "thinking" -> {
                    val thinking = block.thinking
                    if (!thinking.isNullOrBlank() && detailsVisible) {
                        ThinkingBlock(thinking, Modifier.padding(top = 4.dp))
                    }
                }

                "toolCall" -> {
                    if (detailsVisible) {
                        ToolCallChip(
                            block = block,
                            expanded = true,
                            onToggle = {},
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("thinking_block"),
    ) {
        Text(
            "thinking",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text.trim(),
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The visible command/argument summary for a tool call block. */
fun toolCallCommand(block: ContentBlock): String {
    val name = block.name ?: "tool"
    val args = block.arguments ?: return name
    val command = args["command"]
    if (command is JsonPrimitive && command.isString && command.content.isNotBlank()) {
        return command.content
    }
    val filePath = args["file_path"]
    if (filePath is JsonPrimitive && filePath.isString) {
        return "$name ${filePath.content}"
    }
    val compact = "$name ${args}"
    return if (compact.length > 64) compact.take(61) + "…" else compact
}

@Composable
private fun ToolCallChip(
    block: ContentBlock,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val command = toolCallCommand(block)
    val name = block.name ?: "tool"
    ToolChipContainer(onClick = onToggle, modifier = modifier.testTag("tool_chip")) {
        Text(
            if (expanded) "▾ $name" else "▸ $name",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            command,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolResultChip(
    entry: SessionEntry,
    forceExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    var localExpanded by remember(entry.entryId) { mutableStateOf(false) }
    val expanded = forceExpanded || localExpanded
    val output = entryText(entry.content)
    val tool = entry.toolName ?: "tool"
    ToolChipContainer(
        onClick = { localExpanded = !localExpanded },
        modifier = modifier.fillMaxWidth().testTag("tool_result"),
    ) {
        Text(
            "${if (expanded) "▾" else "▸"} $tool${if (entry.isError == true) " (error)" else ""}",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (entry.isError == true) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (output.isNotBlank()) {
            Text(
                output,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolChipContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
internal fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    commands: List<SlashCommandInfo> = emptyList(),
    commandsLoading: Boolean = false,
    commandsError: String? = null,
    onRetryCommands: () -> Unit = {},
    onSend: () -> Unit,
) {
    val query = slashCommandQuery(value)
    val matches = remember(commands, query) { query?.let { matchSlashCommands(commands, it) }.orEmpty() }
    val exactMatch = query?.let { typed -> matches.firstOrNull { it.name.equals(typed, ignoreCase = true) } }
    val acceptingCompletion = query != null && matches.isNotEmpty() && exactMatch == null
    var selectedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(value, commands) { selectedIndex = 0 }

    fun select(command: SlashCommandInfo) {
        onValueChange(fillSlashCommand(command))
    }

    fun submit() {
        if (acceptingCompletion) select(matches[selectedIndex.coerceIn(matches.indices)])
        else onSend()
    }

    Column {
        if (query != null) {
            SlashCommandMenu(
                commands = matches,
                query = query,
                loading = commandsLoading,
                error = commandsError,
                selectedIndex = selectedIndex,
                onSelect = ::select,
                onRetry = onRetryCommands,
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = if (acceptingCompletion) ImeAction.Next else ImeAction.Send),
            keyboardActions = KeyboardActions(onNext = { submit() }, onSend = { submit() }),
            trailingIcon = {
                IconButton(onClick = { submit() }, enabled = enabled && value.isNotBlank()) {
                    Icon(
                        imageVector = if (acceptingCompletion) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (acceptingCompletion) "Complete command" else "Send",
                        tint = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || query == null || matches.isEmpty()) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(matches.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            submit()
                            true
                        }
                        else -> false
                    }
                }
                .testTag("chat_input"),
        )
    }
}
