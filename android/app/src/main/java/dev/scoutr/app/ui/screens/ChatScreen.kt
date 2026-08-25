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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.scoutr.app.ui.components.ChatProseMeasure
import dev.scoutr.app.ui.components.AgentMark
import dev.scoutr.app.ui.agentDisplayTitle
import dev.scoutr.app.ui.projectFolderName
import dev.scoutr.app.data.RepoSummary
import dev.scoutr.app.ui.theme.DiffPalette
import dev.scoutr.app.ui.theme.ScoutrType
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
import dev.scoutr.app.data.SkillInvocation
import dev.scoutr.app.data.entryText
import dev.scoutr.app.data.typedPromptPresentation
import dev.scoutr.app.data.userPromptPresentation
import dev.scoutr.app.ui.imeOrNavigationBarsPadding
import dev.scoutr.app.ui.components.AssistantMarkdown
import dev.scoutr.app.ui.components.PressTintSurface
import dev.scoutr.app.ui.components.SkillInvocationChip
import dev.scoutr.app.ui.components.AskCard
import dev.scoutr.app.ui.components.AskAnswerBubble
import dev.scoutr.app.ui.components.StatusRing
import dev.scoutr.app.ui.components.StatusRingAnimation
import dev.scoutr.app.ui.components.WorkingIndicator
import dev.scoutr.app.ui.components.WorkingIndicatorMode
import dev.scoutr.app.ui.components.workingIndicatorMode
import dev.scoutr.app.ui.components.PullRefreshIndicator
import dev.scoutr.app.ui.components.pullRefreshSemantics

import dev.scoutr.app.ui.motion.ScoutrMotion
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import dev.scoutr.app.ui.motion.useReduceMotion
import dev.scoutr.app.state.AskDraft
import dev.scoutr.app.state.ContextTone
import dev.scoutr.app.state.ContextUsage
import dev.scoutr.app.state.DraftAnswer
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive


@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTerminal: (() -> Unit)? = null,
    onOpenFiles: ((String) -> Unit)? = null,
    onOpenReview: ((String) -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsState()

    // The transcript poll runs only while the chat screen is STARTED.
    LifecycleStartEffect(viewModel) {
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
    val markdownCodeFontSizeSp = appearance.markdownCodeFontSizeSp
    val toolOutputFontSizeSp = appearance.toolOutputFontSizeSp
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
            contextUsage = ui.contextUsage,
            thinkingLevel = ui.thinkingLevel,
            capabilities = ui.capabilities,
            agentKind = ui.agentKind,
            status = if (viewModel.waitingForAnswer) "needs you" else ui.agentStatus,
            showThinking = showThinking,
            expandTools = expandTools,
            onToggleThinking = { showThinking = !showThinking },
            onToggleTools = { expandTools = !expandTools },
            onOpenConfiguration = { configurationOpen = true },
            onBack = onBack,
            cwd = ui.cwd,
            repoSummary = ui.repoSummary,
            onOpenTerminal = onOpenTerminal,
            onOpenFiles = onOpenFiles,
            onOpenReview = onOpenReview,
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
        val failedTranscript =
            ui.transcript is Loadable.Failed && ui.entries.isEmpty() && ui.pendingMessages.isEmpty()
        val refreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = ui.isRefreshing,
            onRefresh = viewModel::onPullRefresh,
            // The transcript is prose: it reads at ChatProseMeasure and centers
            // in whatever is left. The header stays full-bleed so the bar still
            // reads as chrome.
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterHorizontally)
                .widthIn(max = ChatProseMeasure)
                .fillMaxWidth()
                .pullRefreshSemantics(viewModel::onPullRefresh)
                .testTag("chat_refresh_root"),
            state = refreshState,
            indicator = { PullRefreshIndicator(refreshState, ui.isRefreshing) },
        ) {
            when {
                loadingTranscript -> {
                    // The filler keeps the pull gesture usable before content
                    // exists; the centered label only explains the wait.
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading transcript…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                emptyTranscriptHint -> {
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No session transcript for this agent yet.\nUse the input below to steer it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                failedTranscript -> {
                    // A failed load with nothing rendered stays quiet (no new
                    // error surface); the filler keeps the pull gesture able
                    // to retry the read.
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
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
                        questions = ui.questionCards,
                        askDrafts = ui.askDrafts,
                        submittingCallId = ui.submittingCallId,
                        submitIsSlow = ui.submitIsSlow,
                        questionError = ui.questionError,
                        pendingMessages = ui.pendingMessages,
                        showThinking = showThinking,
                        expandTools = expandTools,
                        markdownCodeFontSizeSp = markdownCodeFontSizeSp,
                        toolOutputFontSizeSp = toolOutputFontSizeSp,
                        starting = starting,
                        agentStatus = ui.agentStatus,
                        statusSinceMs = ui.statusSinceMs,
                        hasPendingQuestion = ui.hasPendingQuestion,
                        agentKind = ui.agentKind,
                        hasOlderEntries = ui.hasOlderEntries,
                        loadingOlderEntries = ui.loadingOlderEntries,
                        onLoadOlder = viewModel::loadOlderEntries,
                        onRetryPending = viewModel::retryPendingMessage,
                        onAskAnswer = viewModel::setAskAnswer,
                        onAskPage = viewModel::setAskPage,
                        onAskSubmit = { callId ->
                            haptic(HapticEvent.Confirm)
                            viewModel.submitAsk(callId)
                        },
                        onAskDismiss = viewModel::dismissAsk,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = ChatProseMeasure)
                .fillMaxWidth()
                .imeOrNavigationBarsPadding()
        ) {
            val sendError = ui.sendError
            if (sendError != null) {
                Text(
                    sendError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // An ask answered in the terminal (or on another device) takes any
            // half-filled draft with it. The card is already gone by the time
            // this shows, so the notice is the only trace the work existed.
            val askNotice = ui.askNotice
            if (askNotice != null) {
                LaunchedEffect(askNotice) {
                    delay(4_000)
                    viewModel.clearAskNotice()
                }
                Text(
                    askNotice,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("ask_notice"),
                )
            }
            ChatComposer(
                value = input,
                onValueChange = { input = it },
                // A card owns its own answers, so the composer steps aside
                // while one is open rather than offering a second, silently
                // different way to answer. Dismiss on the card is the way out.
                placeholder = when {
                    ui.hasPendingQuestion -> "Answer above, or dismiss the question"
                    viewModel.waitingForAnswer -> "Answer the question…"
                    else -> "Steer the agent…"
                },
                enabled = !ui.sending && !ui.hasPendingQuestion,
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

private fun SessionAction.icon(): ImageVector = when (this) {
    SessionAction.Abort -> Icons.Default.Stop
    SessionAction.Retry -> Icons.Default.Refresh
    SessionAction.Compact -> Icons.Default.Compress
    SessionAction.Fork -> Icons.AutoMirrored.Filled.CallSplit
    SessionAction.Rename -> Icons.Default.DriveFileRenameOutline
    SessionAction.Close -> Icons.Default.Close
    SessionAction.SetModel, SessionAction.SetThinking -> Icons.Default.Settings
}

@Composable
private fun ChatHeader(
    paneId: String,
    sessionTitle: String,
    model: String?,
    contextUsage: ContextUsage?,
    thinkingLevel: String?,
    capabilities: List<String>?,
    agentKind: String?,
    status: String,
    showThinking: Boolean,
    expandTools: Boolean,
    onToggleThinking: () -> Unit,
    onToggleTools: () -> Unit,
    onOpenConfiguration: () -> Unit,
    onBack: () -> Unit,
    cwd: String?,
    /** Git facts for this session's repo; null hides the branch chip entirely. */
    repoSummary: RepoSummary?,
    onOpenTerminal: (() -> Unit)?,
    onOpenFiles: ((String) -> Unit)?,
    onOpenReview: ((String) -> Unit)?,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentMark(agentKind, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        agentDisplayTitle(sessionTitle),
                        // §7a header: 17/600/-.2, a step above the tile title.
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(listOfNotNull(paneId, model?.substringAfterLast('/')).joinToString(" · "))
                        }
                        if (contextUsage != null) {
                            append(" · ")
                            withStyle(SpanStyle(color = contextUsage.tone.color())) {
                                append(contextUsage.label)
                            }
                        }
                    },
                    style = ScoutrType.monoMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("chat_header_meta"),
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
                    DropdownMenuItem(
                        text = { Text(if (showThinking) "Hide thinking" else "Show thinking") },
                        leadingIcon = {
                            Icon(if (showThinking) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        },
                        modifier = Modifier.testTag("toggle_thinking"),
                        onClick = {
                            menuOpen = false
                            onToggleThinking()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (expandTools) "Collapse tool details" else "Expand tool details") },
                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                        modifier = Modifier.testTag("toggle_tools"),
                        onClick = {
                            menuOpen = false
                            onToggleTools()
                        },
                    )
                    if (onOpenFiles != null && !cwd.isNullOrBlank()) {
                        DropdownMenuItem(
                            text = { Text("Files") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            modifier = Modifier.testTag("chat_open_files"),
                            onClick = {
                                menuOpen = false
                                onOpenFiles(cwd)
                            },
                        )
                    }
                    // Review this session's workspace: same destination and
                    // Code mark as the Review tab and the board/sessions swipe.
                    if (onOpenReview != null && !cwd.isNullOrBlank()) {
                        DropdownMenuItem(
                            text = { Text("Review") },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                            modifier = Modifier.testTag("chat_open_review"),
                            onClick = {
                                menuOpen = false
                                onOpenReview(cwd)
                            },
                        )
                    }
                    // Terminal for this pane: the chat transcript stays a
                    // rendered transcript, and raw PTY output lives only on the
                    // terminal route.
                    if (onOpenTerminal != null) {
                        DropdownMenuItem(
                            text = { Text("Open terminal") },
                            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
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
                    val visibleActions = SessionAction.entries.filter { it in DEFAULT_MENU_ACTIONS && it in available }
                    if (visibleActions.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    visibleActions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            leadingIcon = { Icon(action.icon(), contentDescription = null) },
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
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status is not a chip: the working bar and the ring already carry it,
            // and §7a's rail is configuration only — agent, provider, model, thinking.
            if (capabilities != null) {
                HeaderAgentChip(
                    agentKind = agentKind,
                    testTag = "chat_agent_config",
                )
            }
            // The workspace fact this rail exists for: which repo, on which
            // branch, and whether the working tree carries uncommitted work.
            // Read-only — the rail is configuration, not actions (§7a).
            if (repoSummary != null) {
                HeaderBranchChip(
                    repo = projectFolderName(cwd) ?: "git",
                    branch = repoSummary.branch,
                    dirty = repoSummary.dirty,
                    testTag = "chat_branch_config",
                )
            }
            if (agentKind == "pi") {
                HeaderConfigurationChip(
                    label = "Provider",
                    value = model?.substringBefore('/') ?: "…",
                    onClick = onOpenConfiguration,
                    testTag = "chat_provider_config",
                )
            }
            HeaderConfigurationChip(
                label = "Model",
                value = model?.substringAfterLast('/') ?: "…",
                onClick = onOpenConfiguration,
                testTag = "chat_model_config",
            )
            if (capabilities == null || SessionAction.SetThinking.wire in capabilities) {
                HeaderConfigurationChip(
                    label = "Thinking",
                    value = thinkingLevel ?: "…",
                    onClick = onOpenConfiguration,
                    testTag = "chat_thinking_config",
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

/** Quiet/Warning/Critical → the DESIGN.md status color for each tone. */
@Composable
private fun ContextTone.color(): Color = when (this) {
    ContextTone.Quiet -> MaterialTheme.colorScheme.onSurfaceVariant
    ContextTone.Warning -> MaterialTheme.colorScheme.tertiary
    ContextTone.Critical -> MaterialTheme.colorScheme.error
}

/**
 * The agent slot in the chat header rail: icon only, no label or value text —
 * the mark alone identifies the agent, so it stays quiet in the rail (§7a).
 */
@Composable
private fun HeaderAgentChip(agentKind: String?, testTag: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(testTag),
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            AgentMark(agentKind, size = 14.dp)
        }
    }
}

/**
 * The workspace fact in the chat header rail: repo name as the quiet label,
 * branch as the emphatic value, and the shared 9dp status ring in the warning
 * color when the working tree is dirty. Same Space Grotesk grammar as the
 * configuration chips (§7a); read-only this cut.
 */
@Composable
private fun HeaderBranchChip(
    repo: String,
    branch: String?,
    dirty: Boolean,
    testTag: String,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(testTag),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(repo)
                    }
                    append(" ")
                    withStyle(
                        SpanStyle(
                            color = if (branch == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (branch == null) FontWeight.Medium else FontWeight.SemiBold,
                        ),
                    ) {
                        append(branch ?: "no branch")
                    }
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            // DESIGN.md's status dot: a 9dp outlined ring, never a filled circle.
            if (dirty) {
                StatusRing(
                    color = MaterialTheme.colorScheme.tertiary,
                    animation = StatusRingAnimation.Static,
                )
            }
        }
    }
}

/**
 * A configuration fact in the chat header rail: quiet label, emphatic value, one
 * 4dp tile. Both halves are Space Grotesk — the reference keeps mono out of this
 * rail even though the value is a machine identifier (§7a, §9b "CONTROLS").
 */
@Composable
private fun HeaderConfigurationChip(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    testTag: String,
) {
    val shape = RoundedCornerShape(4.dp)
    val color = MaterialTheme.colorScheme.surfaceContainer
    val modifier = Modifier.testTag(testTag)
    val content: @Composable () -> Unit = {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(label)
                }
                append(" ")
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append(value)
                }
            },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = modifier, content = content)
    } else {
        Surface(shape = shape, color = color, modifier = modifier, content = content)
    }
}

/**
 * The transcript stream. Opens at the last message; new rows pull the list
 * only while it is fully scrolled down (see isChatListAtEnd) — a reader in
 * history, whether dragging or flinging, is never chased and gets a
 * scroll-to-end button instead.
 */
/** Rows of the chat list in emission order; see ChatList. */
internal sealed interface ChatRow {
    data class Entry(val entry: SessionEntry) : ChatRow
    data class Questions(val group: List<QuestionEntry>) : ChatRow
    /**
     * What the agent wrote just before an open ask, when only the card can
     * carry it. It reads as an ordinary agent message because that is what it
     * is — the transcript replaces it with the real entry once the round
     * lands (see QuestionEntry.preamble).
     */
    data class Preamble(val callId: String, val text: String) : ChatRow
    data class Pending(val message: PendingUserMessage) : ChatRow
    /** The tail busy row: starting, working, or waiting on the user. */
    data class Indicator(val mode: WorkingIndicatorMode) : ChatRow
}

/**
 * Build the transcript row list. Answered questions whose anchor entry is
 * not yet loaded stay off the list so they do not jump to the top during
 * reverse pagination.
 */
internal fun buildChatRows(
    entries: List<SessionEntry>,
    pendingMessages: List<PendingUserMessage>,
    questions: List<QuestionEntry>,
    indicatorMode: WorkingIndicatorMode?,
) : List<ChatRow> {
    val questionsByCall = questions.groupBy { it.callId.ifEmpty { it.id.substringBefore('#') } }
    val groupsByAnchorEntry = questionsByCall.values.groupBy { it.first().entryId }
    val anchoredEntryIds = entries.mapTo(mutableSetOf()) { it.entryId }
    // An ask's background belongs above the card, and only while the card is
    // there: once the round is answered the transcript carries the same prose
    // as a real entry, and emitting both would say it twice.
    fun MutableList<ChatRow>.addAsk(group: List<QuestionEntry>) {
        val first = group.first()
        if (group.any { !it.answered } && first.preamble.isNotBlank()) {
            add(ChatRow.Preamble(first.callId.ifEmpty { first.id }, first.preamble))
        }
        add(ChatRow.Questions(group))
    }
    return buildList {
        for (entry in entries) {
            add(ChatRow.Entry(entry))
            groupsByAnchorEntry[entry.entryId]?.forEach { addAsk(it) }
        }
        questionsByCall.values
            .filter { group ->
                val anchorId = group.first().entryId
                anchorId !in anchoredEntryIds && group.any { !it.answered }
            }
            .forEach { addAsk(it) }
        pendingMessages.forEach { add(ChatRow.Pending(it)) }
        if (indicatorMode != null) add(ChatRow.Indicator(indicatorMode))
    }
}

@Composable
fun ChatList(
    entries: List<SessionEntry>,
    state: LazyListState = rememberLazyListState(),
    showThinking: Boolean = true,
    expandTools: Boolean = false,
    markdownCodeFontSizeSp: Float = AppearancePreferencesStore.DEFAULT_MARKDOWN_CODE_FONT_SIZE_SP,
    toolOutputFontSizeSp: Float = AppearancePreferencesStore.DEFAULT_TOOL_OUTPUT_FONT_SIZE_SP,
    pendingMessages: List<PendingUserMessage> = emptyList(),
    questions: List<QuestionEntry> = emptyList(),
    askDrafts: Map<String, AskDraft> = emptyMap(),
    submittingCallId: String? = null,
    submitIsSlow: Boolean = false,
    questionError: String? = null,
    starting: Boolean = false,
    agentStatus: String = "idle",
    statusSinceMs: Long? = null,
    hasPendingQuestion: Boolean = false,
    agentKind: String? = null,
    hasOlderEntries: Boolean = false,
    loadingOlderEntries: Boolean = false,
    onLoadOlder: () -> Unit = {},
    onRetryPending: (String) -> Unit = {},
    onAskAnswer: (callId: String, questionId: String, answer: DraftAnswer) -> Unit = { _, _, _ -> },
    onAskPage: (callId: String, page: Int) -> Unit = { _, _ -> },
    onAskSubmit: (callId: String) -> Unit = {},
    onAskDismiss: (callId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = state
    val indicatorMode = workingIndicatorMode(starting, agentStatus, hasPendingQuestion)

    val reduceMotion = useReduceMotion()
    var expandedToolIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }
    var collapsedToolIds by remember {
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

    // An edit's diff arrives on the tool-result entry, but the call chip above
    // is what shows its `+n −n`. Both resolve to the same tool key, so one map
    // built here serves the summary on the call and the diff on the result.
    val fileEdits: Map<String, ContentBlock> = remember(entries) {
        entries
            .filter { it.role == "toolResult" }
            .mapNotNull { entry -> fileEditOf(entry)?.let { toolResultKey(entry, entries) to it } }
            .toMap()
    }

    // One owner of programmatic scroll-to-end (plan 007): a newer request —
    // open-at-bottom, append-follow, or the button — cancels and replaces the
    // prior one, and a finger drag cancels it too. Cancellation rethrows.
    // Defined after `lastIndex` so the closure captures the tail row index
    // current at the moment the request runs.
    val scrollScope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    // A finger drag always wins over programmatic movement: cancel any
    // running scroll-to-end the instant a drag starts. Whether future content
    // may pull the list is never stored here — it is re-read from position at
    // the moment that content arrives, so no gesture can leave stale intent
    // behind.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) scrollJob?.cancel()
        }
    }


    val rows: List<ChatRow> = remember(entries, pendingMessages, questions, indicatorMode) {
        buildChatRows(entries, pendingMessages, questions, indicatorMode)
    }
    fun keyOf(row: ChatRow): String = when (row) {
        is ChatRow.Entry -> row.entry.entryId
        is ChatRow.Questions -> row.group.joinToString("|") { it.id }
        is ChatRow.Preamble -> "ask_preamble:${row.callId}"
        is ChatRow.Pending -> row.message.localId
        // Stable across mode changes so the row animates in place rather than
        // swapping out when working flips to waiting.
        is ChatRow.Indicator -> "working_indicator"
    }
    val lastIndex = rows.lastIndex
    val hasContent = lastIndex >= 0
    val lastItemKey = rows.lastOrNull()?.let { keyOf(it) }

    fun scrollToEnd() {
        scrollJob?.cancel()
        lateinit var job: Job
        job = scrollScope.launch {
            try {
                scrollChatToEnd(listState, lastIndex)
            } finally {
                // Clear the owner only when this job is still the current one
                // (a newer request replaced it meanwhile), so the FAB's
                // settling state re-enables when the movement actually ends.
                if (scrollJob === job) scrollJob = null
            }
        }
        scrollJob = job
    }

    // Open-at-bottom: the moment content first arrives (and whenever the list
    // goes empty→non-empty again, e.g. a session switch) jump to the very end
    // unconditionally. Gating this on the at-end position check would race the
    // first layout, which briefly reports "not at end" while the list is still
    // at the top.
    LaunchedEffect(hasContent) {
        if (hasContent) scrollToEnd()
    }

    // Reader intent, maintained from measured position only: flush with the end
    // means "wants the tail"; any settled position off it means detached. The
    // live signal updates from layout; a settled detachment (not our own
    // convergence mid-flight) also clears the frozen verdict below.
    var prevAtEnd by remember { mutableStateOf(true) }
    var atEndNow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { isChatListAtEnd(listState) }.collect { atEnd ->
            atEndNow = atEnd
            if (!atEnd && scrollJob?.isActive != true) prevAtEnd = false
        }
    }

    // Follow new content only for a reader that was fully scrolled down when
    // the change landed. The frozen verdict is what makes that readable:
    // once the new rows measure, they have already pushed a glued reader off
    // the end by their own height, so the live check alone would refuse to
    // follow and the transcript would drift away one append at a time.
    // Prepends, drags, flings, and parked readers all read detached and are
    // never chased. The keys are the tail row's identity only: a status-only
    // change (the indicator's mode or label) never moves the bottom edge, so
    // it must not issue a scroll request (plan 007).
    LaunchedEffect(rows.size, lastItemKey) {
        if (!hasContent) return@LaunchedEffect
        val follow = atEndNow || prevAtEnd
        if (follow) scrollToEnd()
        prevAtEnd = follow
    }

    // Load older history when the user is near the top. One request per
    // threshold visit; leaving the near-top band is what allows another try.
    val nearTopRowThreshold = 6
    var requestedOlderThisVisit by remember { mutableStateOf(false) }
    var olderScrollAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(listState, hasOlderEntries, loadingOlderEntries) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE
        }.collect { firstIndex ->
            if (firstIndex > nearTopRowThreshold) {
                requestedOlderThisVisit = false
                return@collect
            }
            if (!hasOlderEntries || loadingOlderEntries || requestedOlderThisVisit) return@collect
            val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val key = firstVisible?.let { info -> rows.getOrNull(info.index)?.let(::keyOf) }
            if (key != null) {
                olderScrollAnchor = key to listState.firstVisibleItemScrollOffset
            }
            requestedOlderThisVisit = true
            onLoadOlder()
        }
    }
    LaunchedEffect(entries, loadingOlderEntries, olderScrollAnchor) {
        val anchor = olderScrollAnchor ?: return@LaunchedEffect
        if (loadingOlderEntries) return@LaunchedEffect
        val index = rows.indexOfFirst { keyOf(it) == anchor.first }
        if (index >= 0) {
            // The restore owns the viewport for one jump; a running
            // scroll-to-end must not fight it for position. A drag in
            // progress cancels this jump instead (the scroll mutex gives the
            // finger priority); the anchor stays set and the next entries or
            // loading flip re-runs it.
            scrollJob?.cancel()
            listState.scrollToItem(index, anchor.second)
            olderScrollAnchor = null
        }
    }

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
                        markdownCodeFontSizeSp = markdownCodeFontSizeSp,
                        toolOutputFontSizeSp = toolOutputFontSizeSp,
                        toolExpanded = { toolId -> isToolExpanded(toolId) },
                        resultToolKey = { entry -> toolResultKey(entry, entries) },
                        resultHasCall = { entry -> inferredToolCallKey(entry, entries) != null },
                        fileEditFor = { toolId -> fileEdits[toolId] },
                        onToggleTool = { toolId -> toggleTool(toolId) },
                        modifier = Modifier.padding(top = entrySpacing(row.entry)).animateItem(
                            fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                            placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                            fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
                        )
                    )
                    is ChatRow.Preamble -> AskPreamble(
                        text = row.text,
                        markdownCodeFontSizeSp = markdownCodeFontSizeSp,
                        modifier = Modifier.animateItem(
                            fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                            placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                            fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
                        ),
                    )
                    is ChatRow.Questions -> {
                        val callId = row.group.first().callId
                        val itemModifier = Modifier.animateItem(
                            fadeInSpec = ScoutrMotion.itemSpec(reduceMotion),
                            placementSpec = ScoutrMotion.itemPlacementSpec(reduceMotion),
                            fadeOutSpec = ScoutrMotion.itemSpec(reduceMotion),
                        )
                        // One ask is one card and, once answered, one bubble —
                        // never a stack of cards the user has to scroll.
                        if (row.group.all { it.answered }) {
                            AskAnswerBubble(row.group, itemModifier)
                        } else {
                            AskCard(
                                group = row.group,
                                draft = askDrafts[callId] ?: AskDraft(),
                                submitting = submittingCallId == callId,
                                submitIsSlow = submitIsSlow && submittingCallId == callId,
                                error = questionError.takeIf { submittingCallId == null },
                                onAnswer = { questionId, answer -> onAskAnswer(callId, questionId, answer) },
                                onPage = { page -> onAskPage(callId, page) },
                                onSubmit = { onAskSubmit(callId) },
                                onDismiss = { onAskDismiss(callId) },
                                modifier = itemModifier,
                            )
                        }
                    }
                    is ChatRow.Pending -> PendingUserBubble(
                        message = row.message,
                        agentKind = agentKind,
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
        // The FAB shows whenever the list is not fully scrolled down — the
        // same measured predicate that decides whether new content follows.
        val notAtBottom by remember(listState) {
            derivedStateOf { !isChatListAtEnd(listState) }
        }
        AnimatedVisibility(
            // Hidden while a programmatic scroll settles so rapid taps spawn
            // no additional work (plan 007); the onClick guard below also
            // covers the exit-animation window, when the button is still in
            // the tree. The accessible name and 48dp target are unchanged.
            visible = notAtBottom && scrollJob?.isActive != true,
            enter = fadeIn(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE)),
            exit = fadeOut(animationSpec = tween(ScoutrMotion.DURATION_ARRIVE)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    if (scrollJob?.isActive != true) scrollToEnd()
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
 * True when the transcript is fully scrolled down: nothing left to scroll
 * forward and the tail row composed flush with the viewport. This is the one
 * contract both follow-the-tail and the scroll-to-end button read.
 *
 * `canScrollForward` alone is not trusted: LazyColumn computes it against
 * estimated extents, so it can read false mid-list while a tall unmeasured
 * tail item sits below the viewport. The tail-visible check is measured fact
 * about the last layout and anchors the estimate read.
 */
private fun isChatListAtEnd(state: LazyListState): Boolean {
    val info = state.layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
    return !state.canScrollForward && lastVisible == info.totalItemsCount - 1
}

/**
 * Move to the true bottom of [listState]: the tail row's bottom edge flush
 * with the viewport end (trailing content padding included). Runs only from
 * the single [ChatList] scroll owner, which cancels a superseded request;
 * cancellation is rethrown, never retried.
 *
 * LazyColumn measures items lazily and estimates the height of items outside
 * the viewport, so one scroll can undershoot the true end. This converges
 * with current layout info instead of fixed sleeps: position the tail in the
 * viewport once (so it measures at its real height), then scroll by the
 * remaining viewport distance, letting the layout correct its estimate each
 * frame. scrollBy clamps at the real end, so it cannot overshoot or pin the
 * tail back to the viewport top.
 */
private suspend fun scrollChatToEnd(listState: LazyListState, lastIndex: Int) {
    if (lastIndex < 0) return
    try {
        if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
            listState.scrollToItem(lastIndex)
            withFrameNanos { }
        }
        repeat(8) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return
            if (lastVisible.index < lastIndex) {
                // The tail left the viewport (rows shrank or a jump landed
                // short); re-position once, then continue converging.
                listState.scrollToItem(lastIndex)
            } else {
                val remaining = info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
                if (remaining > 0) {
                    listState.scrollBy(minOf(remaining, info.viewportSize.height).toFloat())
                } else {
                    // The tail's bottom sits at or below the viewport end: a
                    // tall tail, the bottom content padding, or a stale extent
                    // estimate (a cancelled scroll can leave the scrollable's
                    // estimate behind while the tail is still cut below the
                    // viewport end). Scroll down a viewport; scrollBy clamps
                    // at the real end, so it cannot overshoot or pin the tail
                    // back to the viewport top.
                    val consumed = listState.scrollBy(info.viewportSize.height.toFloat())
                    // Only a tail cut below the viewport end by more than the
                    // bottom content padding is the stale-estimate case worth
                    // repairing: re-snap so the measure corrects the estimate,
                    // then the next iteration converges. At the true bottom
                    // the tail is not cut (the negative remainder is just the
                    // padding), so this never repositions a settled list or
                    // pins a viewport-taller tail back to the top.
                    if (consumed == 0f && remaining < -1f && lastVisible.index == lastIndex) {
                        listState.scrollToItem(lastIndex)
                    }
                }
            }
            withFrameNanos { }
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        // Rows shrank mid-scroll (a pending bubble confirmed, a session
        // switched); the next append or tap re-runs the owner with the new
        // tail index.
    }
}

@Composable
private fun MessageRow(
    entry: SessionEntry,
    showThinking: Boolean,
    markdownCodeFontSizeSp: Float,
    toolOutputFontSizeSp: Float,
    toolExpanded: (String) -> Boolean,
    onToggleTool: (String) -> Unit,
    resultToolKey: (SessionEntry) -> String,
    resultHasCall: (SessionEntry) -> Boolean,
    fileEditFor: (String) -> ContentBlock?,
    modifier: Modifier = Modifier,
) {
    when (entry.role) {
        "user" -> UserBubble(entry, modifier)
        "assistant" -> AssistantBubble(
            entry = entry,
            showThinking = showThinking,
            markdownCodeFontSizeSp = markdownCodeFontSizeSp,
            toolExpanded = toolExpanded,
            fileEditFor = fileEditFor,
            onToggleTool = onToggleTool,
            modifier = modifier,
        )
        "toolResult" -> ToolResultChip(
            entry = entry,
            toolOutputFontSizeSp = toolOutputFontSizeSp,
            toolExpanded = toolExpanded,
            resultToolKey = resultToolKey,
            hasCall = resultHasCall(entry),
            onToggleTool = onToggleTool,
            modifier = modifier,
        )
        else -> {}
    }
}

@Composable
private fun UserBubble(entry: SessionEntry, modifier: Modifier = Modifier) {
    val presentation = userPromptPresentation(entry.content)
    if (presentation.skill == null && presentation.text.isBlank()) return
    UserTurn(
        skill = presentation.skill,
        text = presentation.text,
        bubbleTestTag = "user_bubble",
        bubbleShape = RoundedCornerShape(4.dp),
        selectable = true,
        modifier = modifier,
    )
}

@Composable
private fun PendingUserBubble(
    message: PendingUserMessage,
    agentKind: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = typedPromptPresentation(message.text, agentKind)
    UserTurn(
        skill = presentation.skill,
        text = presentation.text,
        bubbleTestTag = "pending_user_bubble",
        bubbleShape = RoundedCornerShape(8.dp),
        selectable = false,
        modifier = modifier,
    ) {
        when (message.state) {
            MessageDeliveryState.SENT -> Unit
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

@Composable
private fun UserTurn(
    skill: SkillInvocation?,
    text: String,
    bubbleTestTag: String,
    bubbleShape: RoundedCornerShape,
    selectable: Boolean,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            if (skill != null) {
                SkillInvocationChip(
                    skill,
                    Modifier.padding(end = 4.dp, bottom = if (text.isNotBlank()) 6.dp else 0.dp),
                )
            }
            if (text.isNotBlank()) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .widthIn(max = 288.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            bubbleShape,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag(bubbleTestTag),
                ) {
                    val style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                    if (selectable) {
                        SelectionContainer {
                            Text(text, style = style, color = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        Text(text, style = style, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            footer()
        }
    }
}

/**
 * An open ask's background, rendered exactly like the agent prose it is. The
 * bridge reads it off the pane while Claude still holds the assistant turn
 * back (ADR 0012), so it arrives as plain text rather than markdown; the
 * markdown renderer handles it either way, and the styling has to match the
 * real entry that replaces it when the round lands.
 */
@Composable
private fun AskPreamble(
    text: String,
    markdownCodeFontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("ask_preamble")) {
        SelectionContainer(modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
            AssistantMarkdown(content = text, codeFontSizeSp = markdownCodeFontSizeSp)
        }
    }
}

@Composable
private fun AssistantBubble(
    entry: SessionEntry,
    showThinking: Boolean,
    markdownCodeFontSizeSp: Float,
    toolExpanded: (String) -> Boolean,
    fileEditFor: (String) -> ContentBlock?,
    onToggleTool: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Prose runs the full column width; only tool calls hang off the spine, and
    // consecutive calls share one rail so the line is unbroken between them (§7a).
    Column(modifier.fillMaxWidth().testTag("assistant_bubble")) {
        var index = 0
        while (index < entry.content.size) {
            val block = entry.content[index]
            when (block.type) {
                "text" -> {
                    val text = block.text?.trim()
                    if (!text.isNullOrBlank()) {
                        // Long-press selects, then the system toolbar offers Copy.
                        // Only the prose is selectable — tool chips keep their
                        // tap-to-expand gesture without selection fighting it.
                        SelectionContainer(modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
                            AssistantMarkdown(content = text, codeFontSizeSp = markdownCodeFontSizeSp)
                        }
                    }
                    index++
                }

                "thinking" -> {
                    val thinking = block.thinking
                    if (!thinking.isNullOrBlank() && showThinking) {
                        ThinkingBlock(thinking, Modifier.padding(top = 4.dp))
                    }
                    index++
                }

                "toolCall" -> {
                    val start = index
                    while (index < entry.content.size && entry.content[index].type == "toolCall") index++
                    // Prose above means a fresh run (16dp); starting the turn on a
                    // call means the rail is continuing from the entry before, so
                    // it keeps the ordinary row gap.
                    TimelineRail(topGap = if (start == 0) SPINE_ROW_GAP else SPINE_RUN_GAP) {
                        for (i in start until index) {
                            val call = entry.content[i]
                            val key = toolCallKey(entry.entryId, call, i)
                            ToolCallChip(
                                block = call,
                                fileEdit = fileEditFor(key),
                                isExpanded = toolExpanded(key),
                                onToggle = { onToggleTool(key) },
                                modifier = if (i == start) Modifier else Modifier.padding(top = SPINE_ROW_GAP),
                            )
                        }
                    }
                }

                else -> index++
            }
        }
    }
}

/**
 * The tool-call spine: a hairline at 5dp with its content 15dp clear of it, so a
 * 7dp node offset back by 19dp lands centred on the line (§7a).
 *
 * The line is drawn rather than laid out because a `fillMaxHeight` child of a
 * wrap-content parent measures against infinite constraints and collapses to
 * nothing; at draw time the rail's real height is known.
 */
@Composable
private fun TimelineRail(
    topGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val x = SPINE_X.toPx()
                drawLine(
                    color = line,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            // Only the top gap is the rail's; each row insets its own content, so
            // a row's background and its node both stay inside its bounds — a
            // clipping surface would otherwise eat a node hung out on the line.
            .padding(top = topGap)
            .testTag("assistant_spine"),
        content = content,
    )
}

@Composable
private fun ThinkingBlock(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("thinking_block"),
    ) {
        Text(
            "thinking",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text.trim(),
            style = MaterialTheme.typography.bodyMedium,
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
/**
 * The file change an agent reported for this tool result, or null when the
 * tool did not edit a file. The bridge decides that from the patch the agent
 * wrote, so nothing here depends on the tool's name.
 */
internal fun fileEditOf(entry: SessionEntry): ContentBlock? =
    entry.content.firstOrNull { it.type == "fileEdit" && !it.path.isNullOrBlank() }

/** The chip's label: the file name alone, which is what identifies the edit. */
internal fun fileEditFileName(block: ContentBlock): String =
    block.path.orEmpty().substringAfterLast('/').ifEmpty { block.path.orEmpty() }

/**
 * The expanded diff's header. Absolute agent paths are far too long for a phone
 * column, and the leading directories are the least informative part, so this
 * keeps the last two segments — enough to tell `bridge/src/index.ts` from
 * `android/src/index.ts`.
 */
internal fun fileEditDisplayPath(block: ContentBlock): String {
    val path = block.path.orEmpty()
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size <= 2) return path
    return "…/${segments.takeLast(2).joinToString("/")}"
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
/**
 * Entries that live on the tool rail carry their own gaps inside the rail, so the
 * list must not add one — an outer gap is a gap in the drawn spine.
 */
private fun entrySpacing(entry: SessionEntry): Dp = when {
    entry.role == "toolResult" -> 0.dp
    entry.role == "assistant" && entry.content.none { it.type == "text" } -> 0.dp
    else -> 14.dp
}

@Composable
private fun ToolCallChip(
    block: ContentBlock,
    fileEdit: ContentBlock?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = isExpanded
    // An edit names its file, not its arguments: the full path ellipsizes away
    // the only part that identifies it, so the chip carries the file name and
    // the expanded diff below carries the path.
    val command = if (fileEdit != null) fileEditFileName(fileEdit) else toolCallCommand(block)
    val name = block.name ?: "tool"
    // Tool calls remain one-line machine facts; the linked result owns the
    // expandable filled tile surface below. The row spans the rail so its node
    // sits on the spine within its own bounds rather than outside the surface.
    Box(modifier.fillMaxWidth()) {
        PressTintSurface(
            onClick = onToggle,
            color = androidx.compose.ui.graphics.Color.Transparent,
            pressedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth().testTag("tool_chip"),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = SPINE_CONTENT_INSET, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append(name)
                    }
                    append(" ")
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            fontWeight = FontWeight.Normal,
                        ),
                    ) {
                        append(command)
                    }
                },
                style = ScoutrType.monoTool,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (fileEdit != null) {
                Spacer(Modifier.width(8.dp))
                DiffStatBadge(fileEdit)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse $name" else "Expand $name",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp),
            )
        }
        }
        TimelineNode(
            isError = false,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = SPINE_NODE_X),
        )
    }
}

/**
 * The 7dp node that pins one call to the spine. A settled call is canvas-filled
 * with a hairline ring so it reads as a station on the line rather than a bullet
 * in the text; a failed one goes solid red, because an error is the one thing on
 * this rail that should stop the eye (§7a).
 */
@Composable
private fun TimelineNode(isError: Boolean, modifier: Modifier = Modifier) {
    // `requiredSize`, not `size`: the node is measured inside a zero-width box so
    // it claims no room in the row, and `size` would coerce it to that 0.
    val node = modifier.requiredSize(SPINE_NODE_SIZE)
    if (isError) {
        Box(node.background(MaterialTheme.colorScheme.error, CircleShape))
    } else {
        Box(
            node
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

/** Spine geometry: 5dp margin, 1dp line, 15dp padding — content lands at 21dp. */
private val SPINE_X = 5.5.dp
private val SPINE_CONTENT_INSET = 21.dp
private val SPINE_NODE_SIZE = 7.dp

/** The node's left edge, so its 7dp centre lands on the 5.5dp line. */
private val SPINE_NODE_X = 2.dp

/** Gap between rows on the rail, and between the rail and the prose above it. */
private val SPINE_ROW_GAP = 11.dp
private val SPINE_RUN_GAP = 16.dp

@Composable
private fun ToolResultChip(
    entry: SessionEntry,
    toolOutputFontSizeSp: Float,
    toolExpanded: (String) -> Boolean,
    resultToolKey: (SessionEntry) -> String,
    hasCall: Boolean,
    onToggleTool: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = toolExpanded(resultToolKey(entry))
    val output = entryText(entry.content)
    val isError = entry.isError == true
    val tool = entry.toolName ?: "tool"
    // An edit's diff replaces its result text: "the file has been updated
    // successfully" says nothing the diff below does not say better.
    val edit = fileEditOf(entry)
    // A collapsed call is one line on the spine with "no tile of their own" (§7a):
    // the chevron is the affordance, and a preview under every call turns the rail
    // into a ladder of boxes. Two results still show unasked — a failure, which is
    // the one thing worth interrupting the scan for, and an orphan whose call is
    // missing from the transcript, since nothing else could ever expand it.
    if (!isError && !expanded && hasCall) return
    if (output.isBlank() && !isError && edit == null) return
    // Result = evidence: it stays on the rail under the call it belongs to, so
    // the indent and the continuing spine already say "this came from above."
    TimelineRail(topGap = 7.dp, modifier = modifier) {
        ToolChipContainer(
            onClick = { onToggleTool(resultToolKey(entry)) },
            isError = isError,
            modifier = Modifier.padding(start = SPINE_CONTENT_INSET).testTag("tool_result"),
        ) {
            if (isError) {
                Text(
                    "▸ $tool (error)",
                    style = ScoutrType.monoTool,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (edit != null) {
                FileEditDiff(edit, toolOutputFontSizeSp)
            } else if (output.isNotBlank()) {
                Text(
                    output,
                    style = ScoutrType.monoCode(toolOutputFontSizeSp),
                    color = if (isError) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * `+12 −3` on the call chip. Counts describe the whole edit even when the diff
 * below is capped, so the badge never understates what the agent changed.
 */
@Composable
private fun DiffStatBadge(edit: ContentBlock, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = DiffPalette.Added)) { append("+${edit.added}") }
            append(" ")
            withStyle(SpanStyle(color = DiffPalette.Deleted)) { append("−${edit.removed}") }
        },
        style = ScoutrType.monoTool,
        maxLines = 1,
        modifier = modifier.testTag("diff_stat_badge"),
    )
}

/**
 * The expanded edit: its file, then the agent's own hunks in the same diff
 * colors the review screen uses. Lines scroll horizontally rather than wrap so
 * indentation survives, matching the review diff's no-wrap default.
 */
@Composable
private fun FileEditDiff(
    edit: ContentBlock,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val path = edit.path.orEmpty()
    val language = remember(path) { languageForPath(path) }
    Column(modifier.fillMaxWidth().testTag("file_edit_diff")) {
        Text(
            fileEditDisplayPath(edit),
            style = ScoutrType.monoCode(fontSizeSp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        val lines = buildList {
            edit.hunks.forEachIndexed { index, hunk ->
                if (index > 0) add("⋯")
                hunk.header?.let(::add)
                addAll(hunk.lines)
            }
        }
        DiffLines(lines, language, wrapLines = false, horizontalPadding = 0.dp, style = ScoutrType.monoCode(fontSizeSp))
        if (edit.truncated) {
            Spacer(Modifier.height(4.dp))
            Text(
                "⋯ diff truncated",
                style = ScoutrType.monoCode(fontSizeSp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * The inline result tile. The spine carries structure, so the tile surface is
 * reserved for content: a 4dp fill under the call it belongs to, tinted red when
 * the call failed so an error breaks the pattern loudly (§7a).
 */
@Composable
private fun ToolChipContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    content: @Composable () -> Unit,
) {
    PressTintSurface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        else MaterialTheme.colorScheme.surfaceContainer,
        pressedColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
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
        // The composer owns its own border and lays the actions out itself.
        // Material's trailing-icon slot sizes to the icon and leaves no room
        // before the stroke, which put the filled send square on top of the
        // field's own border; an explicit row makes the 4dp end inset
        // structural (reference §7a).
        val canSend = enabled && (value.text.isNotBlank() || attachment != null)
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    // Focus is the one accent stroke allowed here; the filled
                    // send square stays the only accent *surface*.
                    color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(6.dp),
                )
                .heightIn(min = 52.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                minLines = 1,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
                interactionSource = interactionSource,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 15.dp)
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
            // Both actions stay centred in one 52dp rail so they hold their
            // alignment as the field grows to six lines.
            Row(
                modifier = Modifier.height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPickAttachment,
                    enabled = enabled,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Attach image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable(enabled = canSend) { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        // Send is an upward arrow, not a paper plane (§7a).
                        imageVector = if (acceptingCompletion) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.ArrowUpward,
                        contentDescription = when {
                            acceptingMention -> "Complete path"
                            acceptingCompletion -> "Complete command"
                            else -> "Send"
                        },
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
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
