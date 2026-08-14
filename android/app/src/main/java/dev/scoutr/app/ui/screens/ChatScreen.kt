package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.theme.ScoutrMono
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scoutr.app.data.AppearancePreferencesStore
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.toSessionActions
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.FileListing
import dev.scoutr.app.data.SlashCommandInfo
import dev.scoutr.app.data.entryText
import dev.scoutr.app.ui.imeOrNavigationBarsPadding
import dev.scoutr.app.ui.components.AssistantMarkdown
import dev.scoutr.app.ui.components.PressTintSurface
import dev.scoutr.app.ui.components.QuestionCard
import dev.scoutr.app.ui.components.WorkingIndicator
import dev.scoutr.app.ui.components.WorkingIndicatorMode
import dev.scoutr.app.ui.components.workingIndicatorMode

import dev.scoutr.app.ui.motion.ScoutrMotion
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import dev.scoutr.app.ui.motion.useReduceMotion
import dev.scoutr.app.state.Loadable
import dev.scoutr.app.state.ChatUiState
import dev.scoutr.app.state.ChatViewModel
import dev.scoutr.app.state.MessageDeliveryState
import dev.scoutr.app.state.PendingUserMessage
import dev.scoutr.app.state.FileCandidate
import dev.scoutr.app.state.activeFileMention
import dev.scoutr.app.state.completeFileMention
import dev.scoutr.app.state.matchFileMentions
import dev.scoutr.app.state.fillSlashCommand
import dev.scoutr.app.state.matchSlashCommands
import dev.scoutr.app.state.slashCommandQuery
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive


@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTerminal: (() -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsState()

    // The transcript poll runs only while the chat screen is STARTED.
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    val haptic = rememberHaptic()
    var input by remember { mutableStateOf(TextFieldValue()) }
    var attachment by remember { mutableStateOf<android.net.Uri?>(null) }
    var attachmentUploading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachment = uri
    }
    LaunchedEffect(ui.sending) {
        if (!ui.sending) attachmentUploading = false
    }
    // Settings supplies the seed; the header toggles below are the override for
    // this visit. rememberSaveable captures the seed once per back-stack entry,
    // so rotation keeps the override and a later Settings change never rewrites
    // a chat that is already open.
    val appearance = remember(context) { AppearancePreferencesStore(context) }
    var showThinking by rememberSaveable { mutableStateOf(appearance.showThinkingDefault) }
    var expandTools by rememberSaveable { mutableStateOf(appearance.expandToolsDefault) }
    var renameOpen by remember { mutableStateOf(false) }
    var closeOpen by rememberSaveable { mutableStateOf(false) }
    var configurationOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        ChatHeader(
            paneId = viewModel.paneId,
            sessionTitle = ui.sessionTitle,
            model = ui.model,
            thinkingLevel = ui.thinkingLevel,
            capabilities = ui.capabilities,
            agentDisplayName = ui.agentDisplayName,
            status = if (viewModel.waitingForAnswer) "needs you" else ui.agentStatus,
            showThinking = showThinking,
            expandTools = expandTools,
            onToggleThinking = { showThinking = !showThinking },
            onToggleTools = { expandTools = !expandTools },
            onOpenConfiguration = { configurationOpen = true },
            onBack = onBack,
            onOpenTerminal = onOpenTerminal,
            onControl = { action ->
                when (action) {
                    SessionAction.Rename -> renameOpen = true
                    SessionAction.Close -> closeOpen = true
                    SessionAction.Retry -> viewModel.control(SessionAction.Retry, ui.lastUserMessage)
                    else -> viewModel.control(action)
                }
            },
        )

        val emptyTranscriptHint = !ui.exists && ui.pendingMessages.isEmpty()
        val loadingTranscript = ui.transcript is Loadable.Loading && ui.entries.isEmpty() && ui.pendingMessages.isEmpty()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loadingTranscript -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading transcript…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                emptyTranscriptHint -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No session transcript for this agent yet.\nUse the input below to steer it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                else -> {
                    // A brand-new session whose agent is still booting shows an
                    // explicit starting stage instead of a bare empty chat, so a
                    // slow first response never reads as broken. Covers both
                    // entry paths: a composer message queued as a pending bubble,
                    // and the launcher flow that sent the task via the bridge
                    // (no pending bubble; the agent reports working). Failed
                    // sends keep only the retry chip; an idle agent with nothing
                    // sent keeps the static hint above.
                    val starting =
                        ui.entries.isEmpty() &&
                            (ui.pendingMessages.any { it.state != MessageDeliveryState.FAILED } ||
                                (!ui.exists && ui.agentStatus == "working"))
                    ChatList(
                        entries = ui.entries,
                        questions = ui.questions,
                        answeringQuestionId = ui.answeringQuestionId,
                        pendingMessages = ui.pendingMessages,
                        showThinking = showThinking,
                        expandTools = expandTools,
                        starting = starting,
                        agentStatus = ui.agentStatus,
                        statusSinceMs = ui.statusSinceMs,
                        hasPendingQuestion = ui.hasPendingQuestion,
                        onRetryPending = viewModel::retryPendingMessage,
                        onAnswerQuestion = { id, answer, labels ->
                            haptic(HapticEvent.Confirm)
                            viewModel.answerQuestion(id, answer, labels)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().imeOrNavigationBarsPadding()) {
            val sendError = ui.sendError
            if (sendError != null) {
                Text(
                    sendError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ChatComposer(
                value = input,
                onValueChange = { input = it },
                placeholder = if (viewModel.waitingForAnswer) "Answer the question…" else "Steer the agent…",
                enabled = !ui.sending,
                commands = ui.commands,
                onRetryCommands = viewModel::retryCommands,
                files = ui.files,
                onOpenMention = viewModel::refreshFiles,
                onRetryFiles = viewModel::refreshFiles,
                attachment = attachment,
                attachmentUploading = attachmentUploading,
                onPickAttachment = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onClearAttachment = { attachment = null },
                onSend = {
                    val text = input.text
                    val current = attachment
                    if (text.isNotBlank() || current != null) {
                        haptic(HapticEvent.Confirm)
                        if (current != null) {
                            val bytes = readAttachmentBytes(context, current)
                            if (bytes != null) {
                                attachmentUploading = true
                                viewModel.sendWithAttachment(
                                    text = text,
                                    name = "image.${extensionFor(context, current)}",
                                    mime = mimeFor(context, current),
                                    bytes = bytes,
                                )
                            }
                        } else {
                            viewModel.send(text)
                        }
                        input = TextFieldValue()
                        attachment = null
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
                        if (label.isNotBlank()) viewModel.control(SessionAction.Rename, label.trim())
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
                        viewModel.control(SessionAction.Close, onSuccess = onBack)
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
            onSelectModel = { viewModel.control(SessionAction.SetModel, it) },
            onSelectThinking = { viewModel.control(SessionAction.SetThinking, it) },
            onDismiss = { configurationOpen = false },
        )
    }
}

/** The overflow-menu surface: everything except the sheet-only model/thinking verbs. */
private val DEFAULT_MENU_ACTIONS = setOf(
    SessionAction.Abort,
    SessionAction.Retry,
    SessionAction.Compact,
    SessionAction.Fork,
    SessionAction.Rename,
    SessionAction.Close,
)

@Composable
private fun ChatHeader(
    paneId: String,
    sessionTitle: String,
    model: String?,
    thinkingLevel: String?,
    capabilities: List<String>?,
    agentDisplayName: String?,
    status: String,
    showThinking: Boolean,
    expandTools: Boolean,
    onToggleThinking: () -> Unit,
    onToggleTools: () -> Unit,
    onOpenConfiguration: () -> Unit,
    onBack: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
    onControl: (SessionAction) -> Unit,
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
                    fontFamily = ScoutrMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onToggleThinking, modifier = Modifier.testTag("toggle_thinking")) {
                Icon(
                    if (showThinking) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    modifier = Modifier.size(20.dp),
                    contentDescription = if (showThinking) "Hide thinking" else "Show thinking",
                    tint = if (showThinking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleTools, modifier = Modifier.testTag("toggle_tools")) {
                Icon(
                    Icons.Default.Terminal,
                    modifier = Modifier.size(20.dp),
                    contentDescription = if (expandTools) "Collapse tool details" else "Expand tool details",
                    tint = if (expandTools) MaterialTheme.colorScheme.primary
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
                    // Terminal for this pane: the chat transcript stays a
                    // rendered transcript, and raw PTY output lives only on the
                    // terminal route.
                    if (onOpenTerminal != null) {
                        DropdownMenuItem(
                            text = { Text("Open terminal") },
                            modifier = Modifier.testTag("chat_open_terminal"),
                            onClick = {
                                menuOpen = false
                                onOpenTerminal()
                            },
                        )
                    }
                    // Null capabilities mean the backend is unknown yet; show the
                    // full pi surface until the first agents poll names it.
                    // Rendered from the decoded set in enum declaration order
                    // (Set iteration does not preserve menu order).
                    val available = capabilities?.toSessionActions() ?: DEFAULT_MENU_ACTIONS
                    SessionAction.entries.forEach { action ->
                        if (action !in DEFAULT_MENU_ACTIONS || action !in available) return@forEach
                        DropdownMenuItem(
                            text = { Text(action.label) },
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
                .height(IntrinsicSize.Min)
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderStatusChip(status, Modifier.height(IntrinsicSize.Min))
            if (capabilities == null || SessionAction.SetThinking.wire in capabilities) {
                HeaderConfigurationChip(
                    label = "Thinking",
                    value = thinkingLevel ?: "…",
                    onClick = onOpenConfiguration,
                    testTag = "chat_thinking_config",
                )
            }
            if (capabilities != null) {
                HeaderConfigurationChip(
                    label = "Agent",
                    value = agentDisplayName ?: capabilities?.let { "" } ?: "",
                    onClick = null,
                    testTag = "chat_agent_config",
                )
            }
            HeaderConfigurationChip(
                label = "Model",
                value = model?.substringAfterLast('/') ?: "…",
                onClick = onOpenConfiguration,
                testTag = "chat_model_config",
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

@Composable
private fun HeaderStatusChip(status: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (status == "needs you") MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = if (status == "needs you") MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun HeaderConfigurationChip(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    testTag: String,
) {
    val shape = RoundedCornerShape(6.dp)
    val color = MaterialTheme.colorScheme.surfaceContainer
    val modifier = Modifier.testTag(testTag)
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, fontFamily = ScoutrMono)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = modifier, content = content)
    } else {
        Surface(shape = shape, color = color, modifier = modifier, content = content)
    }
}

/**
 * The transcript stream. Opens at the last message and follows new ones while
 * the user is at the bottom; scrolling up stops the follow and shows a
 * scroll-to-end button. Auto-scroll is guarded against out-of-range indices so
 * concurrent appends can never crash it.
 */
/** Rows of the chat list in emission order; see ChatList. */
private sealed interface ChatRow {
    data class Entry(val entry: SessionEntry) : ChatRow
    data class Questions(val group: List<QuestionEntry>) : ChatRow
    data class Pending(val message: PendingUserMessage) : ChatRow
    /** The tail busy row: starting, working, or waiting on the user. */
    data class Indicator(val mode: WorkingIndicatorMode) : ChatRow
}

@Composable
fun ChatList(
    entries: List<SessionEntry>,
    showThinking: Boolean = true,
    expandTools: Boolean = false,
    pendingMessages: List<PendingUserMessage> = emptyList(),
    questions: List<QuestionEntry> = emptyList(),
    answeringQuestionId: String? = null,
    starting: Boolean = false,
    agentStatus: String = "idle",
    statusSinceMs: Long? = null,
    hasPendingQuestion: Boolean = false,
    onRetryPending: (String) -> Unit = {},
    onAnswerQuestion: (String, String, List<String>) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val indicatorMode = workingIndicatorMode(starting, agentStatus, hasPendingQuestion)

    val reduceMotion = useReduceMotion()
    var expandedToolIds by remember(entries.firstOrNull()?.entryId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var collapsedToolIds by remember(entries.firstOrNull()?.entryId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    fun isToolExpanded(toolId: String): Boolean =
        if (expandTools) toolId !in collapsedToolIds else toolId in expandedToolIds
    fun toggleTool(toolId: String) {
        if (expandTools) {
            collapsedToolIds = if (toolId in collapsedToolIds) {
                collapsedToolIds - toolId
            } else {
                collapsedToolIds + toolId
            }
        } else {
            expandedToolIds = if (toolId in expandedToolIds) {
                expandedToolIds - toolId
            } else {
                expandedToolIds + toolId
            }
        }
    }

    var followNew by remember { mutableStateOf(true) }

    // A new entry only lands while the user is at the bottom; the moment they
    // scroll up, stop following and surface the scroll-to-end button.
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect {
            followNew = !it
        }
    }

    // Follow: initial open + every append while at the bottom. The index is
    // computed the same way the LazyColumn emits items (entries, questions,
    // pending, working indicator) so follow always lands on the true last item.
    val questionsByCall = questions.groupBy { it.callId.ifEmpty { it.id.substringBefore('#') } }
    // One assistant entry can hold several ask_user_question calls, so a
    // single entry may anchor several groups; groupBy keeps them all.
    val groupsByAnchorEntry = questionsByCall.values.groupBy { it.first().entryId }
    val anchoredEntryIds = entries.mapTo(mutableSetOf()) { it.entryId }
    val rows: List<ChatRow> = remember(entries, pendingMessages, questions, indicatorMode) {
        buildList {
            for (entry in entries) {
                add(ChatRow.Entry(entry))
                groupsByAnchorEntry[entry.entryId]?.forEach { add(ChatRow.Questions(it)) }
            }
            questionsByCall.values
                .filter { it.first().entryId !in anchoredEntryIds }
                .forEach { add(ChatRow.Questions(it)) }
            pendingMessages.forEach { add(ChatRow.Pending(it)) }
            if (indicatorMode != null) add(ChatRow.Indicator(indicatorMode))
        }
    }
    fun keyOf(row: ChatRow): String = when (row) {
        is ChatRow.Entry -> row.entry.entryId
        is ChatRow.Questions -> row.group.joinToString("|") { it.id }
        is ChatRow.Pending -> row.message.localId
        // Stable across mode changes so the row animates in place rather than
        // swapping out when working flips to waiting.
        is ChatRow.Indicator -> "working_indicator"
    }
    val lastIndex = rows.lastIndex
    val hasContent = lastIndex >= 0
    val lastItemKey = rows.lastOrNull()?.let { keyOf(it) }

    // Open-at-bottom: the moment content first arrives (and whenever the list
    // goes empty→non-empty again, e.g. a session switch) jump to the very end
    // unconditionally. Gating this on followNew would race the position
    // collector below, which briefly reports "not at bottom" while the list is
    // still laid out at the top — the session would open scrolled up.
    LaunchedEffect(hasContent) {
        if (hasContent) {
            followNew = true
            scrollChatToEnd(listState, lastIndex)
        }
    }

    // Follow appends while the user is at the bottom; scrolling up stops it.
    LaunchedEffect(rows.size, lastItemKey, indicatorMode) {
        if (followNew && hasContent) {
            followNew = true
            scrollChatToEnd(listState, lastIndex)
        }
    }

    val scope = rememberCoroutineScope()
    Box(modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
            modifier = Modifier.fillMaxSize().testTag("chat_list"),
        ) {
            items(rows, key = { keyOf(it) }) { row ->
                when (row) {
                    is ChatRow.Entry -> MessageRow(
                        entry = row.entry,
                        showThinking = showThinking,
                        toolExpanded = { toolId -> isToolExpanded(toolId) },
                        resultToolKey = { entry -> toolResultKey(entry, entries) },
                        onToggleTool = { toolId -> toggleTool(toolId) },
                        modifier = Modifier.padding(top = entrySpacing(row.entry)).animateItem(
                            fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                            placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                            fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
                        )
                    )
                    is ChatRow.Questions -> {
                        val multiple = row.group.size > 1
                        Column {
                            row.group.forEachIndexed { index, question ->
                                QuestionCard(
                                    question = question,
                                    sending = answeringQuestionId == question.id,
                                    onAnswer = { answer, labels -> onAnswerQuestion(question.id, answer, labels) },
                                    position = if (multiple) (index + 1) to row.group.size else null,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                                        placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                                        fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
                                    ),
                                )
                            }
                        }
                    }
                    is ChatRow.Pending -> PendingUserBubble(
                        message = row.message,
                        onRetry = { onRetryPending(row.message.localId) },
                        Modifier.animateItem(
                            fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                            placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                            fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
                        )
                    )
                    is ChatRow.Indicator -> WorkingIndicator(
                        mode = row.mode,
                        statusSinceMs = statusSinceMs,
                        modifier = Modifier.animateItem(
                            fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                            placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                            fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
                        ),
                    )
                }
            }
        }
        // canScrollForward is computed against LazyColumn's estimated extent,
        // so it can read false mid-list while a tall unmeasured tail item sits
        // below the viewport. Combine it with the last-visible-row check: the
        // FAB must show whenever the last row is anywhere off-screen.
        val notAtBottom by remember(listState) {
            derivedStateOf {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                listState.canScrollForward || lastVisible < info.totalItemsCount - 1
            }
        }
        AnimatedVisibility(
            visible = notAtBottom,
            enter = fadeIn(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE)),
            exit = fadeOut(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    followNew = true
                    scope.launch {
                        scrollChatToEnd(listState, lastIndex.coerceAtLeast(0))
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

/**
 * Jump to the true end of the chat list: the last item, then the maximum
 * remaining scroll. Shared by the open-at-bottom effect, the append-follow
 * effect, and the scroll-to-end button so all three land on the same spot.
 *
 * LazyColumn measures items lazily and estimates the height of items that
 * sit outside the viewport, so maxValue can understate the real content and
 * cap a single scroll below the true end. Each iteration therefore lets the
 * layout apply one step (position request, then scroll) before judging the
 * position: exposing the last item makes it measure at its real height, the
 * extent corrects, and the next scroll reaches the true bottom.
 */


private suspend fun scrollChatToEnd(listState: LazyListState, lastIndex: Int) {
    repeat(10) {
        try {
            listState.scrollToItem(lastIndex)
            // Let the layout apply the position request and measure the last
            // item at its real height before scrolling further.
            delay(16)
            listState.scrollBy(Float.MAX_VALUE)
            // Let the layout consume the scroll before judging the position.
            delay(16)
        } catch (_: Exception) {
            // Concurrent append raced the scroll; retry.
        }
        // Only trust "cannot scroll further" once the list has laid out;
        // before that, totalItemsCount is 0 and canScrollForward is false.
        if (listState.layoutInfo.totalItemsCount > 0 && !listState.canScrollForward) return
    }
}

@Composable
private fun MessageRow(
    entry: SessionEntry,
    showThinking: Boolean,
    toolExpanded: (String) -> Boolean,
    onToggleTool: (String) -> Unit,
    resultToolKey: (SessionEntry) -> String,
    modifier: Modifier = Modifier,
) {
    when (entry.role) {
        "user" -> UserBubble(entry, modifier)
        "assistant" -> AssistantBubble(
            entry = entry,
            showThinking = showThinking,
            toolExpanded = toolExpanded,
            onToggleTool = onToggleTool,
            modifier = modifier,
        )
        "toolResult" -> ToolResultChip(
            entry = entry,
            toolExpanded = toolExpanded,
            resultToolKey = resultToolKey,
            onToggleTool = onToggleTool,
            modifier = modifier,
        )
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
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("user_bubble"),
        ) {
            SelectionContainer {
                Text(text, color = MaterialTheme.colorScheme.onSurface)
            }
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
                        RoundedCornerShape(8.dp),
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
    showThinking: Boolean,
    toolExpanded: (String) -> Boolean,
    onToggleTool: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().testTag("assistant_bubble")) {
        Box(
            Modifier
                .width(12.dp)
                .fillMaxHeight()
                .padding(start = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .testTag("assistant_spine"),
            )
        }
        Column(Modifier.weight(1f)) {
            for ((blockIndex, block) in entry.content.withIndex()) {
                when (block.type) {
                    "text" -> {
                        val text = block.text?.trim()
                        if (!text.isNullOrBlank()) {
                            // Long-press selects, then the system toolbar offers Copy.
                            // Only the prose is selectable — tool chips keep their
                            // tap-to-expand gesture without selection fighting it.
                            SelectionContainer(modifier = Modifier.padding(bottom = 4.dp)) {
                                AssistantMarkdown(content = text)
                            }
                        }
                    }

                    "thinking" -> {
                        val thinking = block.thinking
                        if (!thinking.isNullOrBlank() && showThinking) {
                            ThinkingBlock(thinking, Modifier.padding(top = 4.dp))
                        }
                    }

                    "toolCall" -> {
                        // Quiet one-line machine fact; tapping it expands the linked
                        // result tile below.
                        ToolCallChip(
                            block = block,
                            isExpanded = toolExpanded(toolCallKey(entry.entryId, block, blockIndex)),
                            onToggle = { onToggleTool(toolCallKey(entry.entryId, block, blockIndex)) },
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
                RoundedCornerShape(8.dp),
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
    val args = block.arguments
    if (args == null) return name
    // The tool label already sits next to this text in the chip row, so
    // prefer the argument payload over a redundant "$name …" repetition.
    val command = args["command"]
    if (command is JsonPrimitive && command.isString && command.content.isNotBlank()) {
        return command.content
    }
    val filePath = args["file_path"]
    if (filePath is JsonPrimitive && filePath.isString && filePath.content.isNotBlank()) {
        return filePath.content
    }
    // Prefer a human-meaningful field (e.g. todo's subject) over raw JSON.
    for (key in listOf("subject", "text", "message", "title")) {
        val v = args[key]
        if (v is JsonPrimitive && v.isString && v.content.isNotBlank()) return v.content
    }
    val compact = args.toString()
    return if (compact.length > 64) compact.take(61) + "…" else compact
}
/** Stable key shared by a tool-call row and its linked result state. */
private fun toolCallKey(entryId: String, block: ContentBlock, blockIndex: Int): String =
    block.id?.takeUnless { it.isBlank() } ?: "$entryId:tool:$blockIndex"

/**
 * Resolve a result to its call when the transcript omits or partially omits IDs.
 * Pi normally provides the ID; parentId plus tool name remains a deterministic
 * fallback for older or malformed transcript records.
 */
private fun toolResultKey(entry: SessionEntry, entries: List<SessionEntry>): String {
    val inferredCallKey = inferredToolCallKey(entry, entries)
    val explicitResultKey = entry.toolCallId?.takeUnless { it.isBlank() }
    return inferredCallKey ?: explicitResultKey ?: "${entry.entryId}:result"
}

/** Match partial-ID results to the next unclaimed call in transcript order. */
private fun inferredToolCallKey(entry: SessionEntry, entries: List<SessionEntry>): String? {
    val parent = entries.firstOrNull { it.entryId == entry.parentId } ?: return null
    val calls = parent.content.withIndex().filter { it.value.type == "toolCall" }
    if (calls.isEmpty()) return null
    val resultIndex = entries.indexOfFirst { it.entryId == entry.entryId }
    val priorResults = if (resultIndex >= 0) entries.take(resultIndex) else emptyList()
    val results = priorResults
        .asSequence()
        .plus(entry)
        .filter { it.role == "toolResult" && it.parentId == entry.parentId }
        .filter { entry.toolName == null || it.toolName == entry.toolName }
    val claimedCallIndexes = mutableSetOf<Int>()
    for (result in results) {
        val explicitId = result.toolCallId?.takeUnless { it.isBlank() }
        val explicitMatch = calls.indexOfFirst {
            it.index !in claimedCallIndexes && it.value.id?.takeUnless(String::isBlank) == explicitId
        }
        val fallbackMatch = calls.indexOfFirst {
            it.index !in claimedCallIndexes &&
                (result.toolName == null || it.value.name == result.toolName)
        }
        val call = calls.getOrNull(if (explicitMatch >= 0) explicitMatch else fallbackMatch)
        if (call == null) continue
        claimedCallIndexes += call.index
        if (result.entryId == entry.entryId) return toolCallKey(parent.entryId, call.value, call.index)
    }
    return null
}


/** Vertical rhythm: consecutive tool entries group at 4dp; prose gets air. */
private fun entrySpacing(entry: SessionEntry): Dp = when {
    entry.role == "toolResult" -> 4.dp
    entry.role == "assistant" && entry.content.none { it.type == "text" } -> 4.dp
    else -> 14.dp
}

@Composable
private fun ToolCallChip(
    block: ContentBlock,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = isExpanded
    val command = toolCallCommand(block)
    val name = block.name ?: "tool"
    // Tool calls remain one-line machine facts; the linked result owns the
    // expandable filled tile surface below.
    PressTintSurface(
        onClick = onToggle,
        color = androidx.compose.ui.graphics.Color.Transparent,
        pressedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth().testTag("tool_chip"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                fontFamily = ScoutrMono,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                command,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                fontFamily = ScoutrMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse $name" else "Expand $name",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ToolResultChip(
    entry: SessionEntry,
    toolExpanded: (String) -> Boolean,
    resultToolKey: (SessionEntry) -> String,
    onToggleTool: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = toolExpanded(resultToolKey(entry))
    val output = entryText(entry.content)
    val tool = entry.toolName ?: "tool"
    // Result = evidence: indented under its call, faint fill, no marker — the
    // indent and fill already say "this belongs to the row above." Errors keep
    // the explicit label so they break the pattern loudly.
    ToolChipContainer(
        onClick = { onToggleTool(resultToolKey(entry)) },
        modifier = modifier.fillMaxWidth().padding(start = 14.dp).testTag("tool_result"),
    ) {
        if (entry.isError == true) {
            Text(
                "▸ $tool (error)",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                fontFamily = ScoutrMono,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (output.isNotBlank()) {
            Text(
                output,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                fontFamily = ScoutrMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = if (expanded) Int.MAX_VALUE else 2,
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
    PressTintSurface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        pressedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            content()
        }
    }
}

@Composable
internal fun ChatComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean,
    commands: Loadable<List<SlashCommandInfo>> = Loadable.Idle,
    onRetryCommands: () -> Unit = {},
    files: Loadable<FileListing> = Loadable.Idle,
    onOpenMention: () -> Unit = {},
    onRetryFiles: () -> Unit = {},
    attachment: android.net.Uri? = null,
    attachmentUploading: Boolean = false,
    onPickAttachment: () -> Unit = {},
    onClearAttachment: () -> Unit = {},
    onSend: () -> Unit,
) {
    val query = slashCommandQuery(value.text)
    val commandsValue = (commands as? Loadable.Ready)?.value.orEmpty()
    val matches = remember(commandsValue, query) { query?.let { matchSlashCommands(commandsValue, it) }.orEmpty() }
    val exactMatch = query?.let { typed -> matches.firstOrNull { it.name.equals(typed, ignoreCase = true) } }
    val acceptingCommand = query != null && matches.isNotEmpty() && exactMatch == null

    // A mention is a token around the caret, so it needs the selection, not
    // just the text; a non-collapsed selection is a drag, not a caret.
    val caret = value.selection.takeIf { it.collapsed }?.start
    val mention = remember(value) { caret?.let { activeFileMention(value.text, it) } }
    // Back dismisses the menu without touching the text; re-opening requires
    // leaving the token and coming back, so the offset is the dismissal key.
    var dismissedMentionAt by remember { mutableStateOf<Int?>(null) }
    val mentionOpen = mention != null && mention.start != dismissedMentionAt
    val listing = (files as? Loadable.Ready)?.value
    val mentionMatches = remember(listing, mention?.query, mentionOpen) {
        if (!mentionOpen || listing == null) emptyList()
        else matchFileMentions(listing.files, mention!!.query)
    }
    val acceptingMention = mentionOpen && mentionMatches.isNotEmpty()
    val acceptingCompletion = acceptingCommand || acceptingMention
    var selectedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(value, commandsValue, listing) { selectedIndex = 0 }
    // Fetch once per mention: the key is the `@` offset, so typing and
    // drilling into directories filter the list in hand, while leaving the
    // token and returning to it loads a fresh one.
    LaunchedEffect(mention?.start) {
        if (mention != null && mention.start != dismissedMentionAt) onOpenMention()
        if (mention == null) dismissedMentionAt = null
    }
    BackHandler(enabled = mentionOpen) { dismissedMentionAt = mention?.start }

    fun select(command: SlashCommandInfo) {
        onValueChange(TextFieldValue(fillSlashCommand(command)).let { it.copy(selection = TextRange(it.text.length)) })
    }

    fun selectMention(candidate: FileCandidate) {
        val current = mention ?: return
        onValueChange(completeFileMention(value, current, candidate))
    }

    fun submit() {
        when {
            acceptingCommand -> select(matches[selectedIndex.coerceIn(matches.indices)])
            acceptingMention -> selectMention(mentionMatches[selectedIndex.coerceIn(mentionMatches.indices)])
            else -> onSend()
        }
    }

    Column {
        if (attachment != null) {
            AttachmentChip(
                uri = attachment,
                uploading = attachmentUploading,
                onClear = onClearAttachment,
            )
        }
        if (query != null) {
            SlashCommandMenu(
                commands = matches,
                query = query,
                loading = commands is Loadable.Loading || commands is Loadable.Idle,
                error = (commands as? Loadable.Failed)?.reason,
                selectedIndex = selectedIndex,
                onSelect = ::select,
                onRetry = onRetryCommands,
            )
        } else if (mentionOpen) {
            FileMentionMenu(
                candidates = mentionMatches,
                query = mention!!.query,
                loading = files is Loadable.Loading || files is Loadable.Idle,
                error = (files as? Loadable.Failed)?.reason,
                truncated = listing?.truncated == true,
                selectedIndex = selectedIndex,
                onSelect = ::selectMention,
                onRetry = onRetryFiles,
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            enabled = enabled,
            minLines = 1,
            maxLines = 6,
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            // Enter inserts a newline; sending happens only via the send button.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
            keyboardActions = KeyboardActions(
                onSend = {},
                onDone = {},
                onNext = {},
                onPrevious = {},
                onGo = {},
                onSearch = {},
            ),
            // The M3 trailing slot stacks sibling icons on top of each other, so the
            // two actions must live in an explicit Row to guarantee side-by-side
            // placement (S24 feedback: attach and send buttons overlapped).
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPickAttachment, enabled = enabled) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "Attach image",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (enabled && (value.text.isNotBlank() || attachment != null)) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable(enabled = enabled && (value.text.isNotBlank() || attachment != null)) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (acceptingCompletion) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.Send,
                            contentDescription = when {
                                acceptingMention -> "Complete path"
                                acceptingCompletion -> "Complete command"
                                else -> "Send"
                            },
                            tint = if (enabled && (value.text.isNotBlank() || attachment != null)) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .onPreviewKeyEvent { event ->
                    // Enter completes an accepting slash command or file mention;
                    // with no menu open the field itself inserts a newline
                    // (multiline + imeAction None) and the empty KeyboardActions
                    // guarantee no editor action can send.
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val openMenuSize = when {
                        query != null -> matches.size
                        mentionOpen -> mentionMatches.size
                        else -> 0
                    }
                    if (openMenuSize == 0) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            submit()
                            true
                        }
                        Key.DirectionDown -> {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(openMenuSize - 1)
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        else -> false
                    }
                }
                .testTag("chat_input"),
        )
    }
}

private fun readAttachmentBytes(context: android.content.Context, uri: android.net.Uri): ByteArray? =
    try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

private fun mimeFor(context: android.content.Context, uri: android.net.Uri): String =
    context.contentResolver.getType(uri) ?: "image/png"

private fun extensionFor(context: android.content.Context, uri: android.net.Uri): String = when {
    mimeFor(context, uri) == "image/jpeg" -> "jpg"
    mimeFor(context, uri) == "image/gif" -> "gif"
    mimeFor(context, uri) == "image/webp" -> "webp"
    else -> "png"
}

@Composable
private fun AttachmentChip(
    uri: android.net.Uri,
    uploading: Boolean,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = try {
            // Decode bounds and pixels from two fresh streams: content-provider
            // streams are usually not markable, so reset() used to throw and
            // the preview never rendered.
            val sample = context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
                (bounds.outWidth / 96).coerceAtLeast(1)
            } ?: 1
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("attachment_chip"),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .width(36.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            if (uploading) {
                Spacer(Modifier.width(8.dp))
                Text("Uploading image…", style = MaterialTheme.typography.labelMedium)
            } else {
                Text("Image attached", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClear, modifier = Modifier.width(28.dp).height(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove attachment",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
