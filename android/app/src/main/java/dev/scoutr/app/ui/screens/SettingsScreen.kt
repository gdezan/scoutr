package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.theme.ScoutrMono
import dev.scoutr.app.ui.theme.ScoutrType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.rememberCoroutineScope
import dev.scoutr.app.data.UpdateHostDisposition
import dev.scoutr.app.state.HostAvailability
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.layout.Box
import dev.scoutr.app.state.HostRowUi
import dev.scoutr.app.state.HostsViewModel
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.scoutr.app.data.AppearancePreferencesStore
import dev.scoutr.app.data.NotificationPreferencesStore
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.ui.components.ConfirmDialog
import dev.scoutr.app.ui.components.SectionLabel
import dev.scoutr.app.ui.components.StatusRing
import dev.scoutr.app.ui.components.StatusRingAnimation
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.scoutr.app.BuildConfig
import dev.scoutr.app.data.UpdateStatusResponse
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.update.ApkInstallOutcome
import dev.scoutr.app.update.ApkInstaller
import dev.scoutr.app.update.AppUpdater
import dev.scoutr.app.update.UpdateProgress
import kotlin.math.roundToInt

/**
 * Settings: the home for durable device preferences and the only place the
 * saved pairing can be managed.
 *
 * Eight sections on one scroll — Connection, Update, Notifications, Chat,
 * Typography, Terminal, Haptics, Motion. Deliberately not a session-admin page:
 * launch
 * defaults, the Chat header toggles, and pin/archive stay in the flow that owns
 * them. Chat here only supplies the seed a *new* visit starts from; Terminal
 * writes the same per-connection store that pinch and the extra-keys strip write.
 *
 * Only reachable from the tab shell, which exists only while paired — after
 * Forget the graph is Connect-only, so there is no unpaired Settings.
 */
data class SettingsConnection(val host: String, val hostId: String)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /**
     * The container's store, not a fresh one: an open Terminal observes its
     * revision, and a second instance would tick a flow nobody is collecting.
     */
    terminalPreferences: TerminalPreferencesStore,
    api: ScoutrApi?,
    trackUpdateWork: suspend (suspend () -> Unit) -> Unit = { work -> work() },
    modifier: Modifier = Modifier,
    /** Multi-host management; null only before the first pairing. */
    hostsViewModel: HostsViewModel? = null,
    updateHostId: String? = null,
    updateHostAlias: String? = null,
    updateHostOptions: Map<String, String> = emptyMap(),
    onSelectUpdateHost: (String) -> Unit = {},
    onDisableUpdates: () -> Unit = {},
    onAddHost: () -> Unit = {},
    onAllHostsForgotten: () -> Unit = {},
) {
    val context = LocalContext.current
    val appearance = remember(context) { AppearancePreferencesStore(context) }
    val notificationPrefs = remember(context) { NotificationPreferencesStore(context) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(20.dp))

        if (hostsViewModel != null) {
            val scope = rememberCoroutineScope()
            HostsSection(
                viewModel = hostsViewModel,
                onAddHost = onAddHost,
                onAllHostsForgotten = {
                    scope.launch { onAllHostsForgotten() }
                },
            )
        }

        UpdateSection(api = api, trackUpdateWork = trackUpdateWork)
        UpdateHostSection(
            currentHostId = updateHostId,
            currentHostAlias = updateHostAlias,
            options = updateHostOptions,
            onSelect = onSelectUpdateHost,
            onDisable = onDisableUpdates,
        )

        NotificationsSection(notificationPrefs = notificationPrefs)
        ChatSection(appearance = appearance)

        TypographySection(appearance = appearance)
        // Terminal display preferences are host-scoped; the section shows the
        // default host's set (the one a fresh install would use).
        hostsViewModel?.ui?.collectAsState()?.value?.rows
            ?.firstOrNull { it.isDefault }
            ?.hostId
            ?.let { hostId ->
                TerminalSection(
                    preferences = remember(terminalPreferences, hostId) {
                        terminalPreferences.forHost(hostId)
                    },
                )
            }

        HapticsSection(appearance = appearance)
        MotionSection(appearance = appearance)
    }
}

/**
 * Host vs installed build identity, plus the self-update trigger.
 *
 * The update pulls: the host builds an APK, this app downloads it over the
 * exposed bridge API and installs it through PackageInstaller. Nothing here
 * needs adb, so an update works from anywhere the bridge is reachable — at the
 * cost of one system
 * confirmation sheet per install, which Android does not let an app skip.
 *
 * The status ring and rows are display-only: the update signal stays
 * commit-based, so the semver shown here never gates the button. The host's
 * dirty flag still counts toward `updateAvailable` but is never shown.
 */
@Composable
private fun UpdateSection(
    api: ScoutrApi?,
    trackUpdateWork: suspend (suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateStatusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<UpdateProgress?>(null) }
    var canInstall by remember { mutableStateOf(ApkInstaller.canInstall(context)) }

    // Returning from the "install unknown apps" screen carries no result, so
    // re-read the permission rather than trusting the launcher's callback.
    val grantInstallPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { canInstall = ApkInstaller.canInstall(context) }

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching {
            api.updateStatus(
                commit = BuildConfig.GIT_COMMIT,
                version = BuildConfig.VERSION_NAME,
                dirty = BuildConfig.GIT_DIRTY,
            )
        }.onSuccess { status = it }
            .onFailure { error = it.message ?: "could not read host version" }
    }

    // A failed install session reports back through the receiver long after the
    // download coroutine finished, so the section listens for it separately.
    val installOutcome by ApkInstaller.outcome.collectAsStateWithLifecycle()
    LaunchedEffect(installOutcome) {
        (installOutcome as? ApkInstallOutcome.Failed)?.let {
            error = it.message
            progress = null
            ApkInstaller.clearOutcome()
        }
    }

    if (confirming) {
        ConfirmDialog(
            title = "Update app?",
            text = "The host builds the latest version, this phone downloads and installs it. " +
                "Building takes about a minute. Keep this screen open until Android asks you " +
                "to confirm the install.",
            confirmLabel = "Update now",
            onConfirm = {
                confirming = false
                error = null
                val updateApi = api ?: return@ConfirmDialog
                scope.launch {
                    try {
                        trackUpdateWork {
                            val updater = AppUpdater(
                                api = updateApi,
                                installer = ApkInstaller.forContext(context),
                                cacheDir = context.cacheDir,
                            )
                            updater.run { progress = it }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "update failed"
                    } finally {
                        // The system sheet owns the flow after install starts; otherwise clear stale progress.
                        progress = null
                    }
                }
            },
            onDismiss = { confirming = false },
        )
    }

    SettingsSection(
        "Update",
        footnote = "Checks the host checkout for a newer build, then downloads and installs it " +
            "over your bridge connection. Only the app is updated, never the bridge.",
    ) {
        val current = status
        val stage = progress
        val statusLabel = when {
            api == null -> "Updates disabled"
            stage != null -> progressLabel(stage)
            error != null -> error!!
            current?.updateAvailable == true -> "Update available"
            current != null -> "Up to date"
            else -> "Checking…"
        }
        val statusColor = when {
            stage != null -> MaterialTheme.colorScheme.onSurfaceVariant
            error != null -> MaterialTheme.colorScheme.error
            current?.updateAvailable == true -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("settings_update_status"),
            ) {
                StatusRing(
                    color = statusColor,
                    animation = if (stage == null) StatusRingAnimation.Static else StatusRingAnimation.Live,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("Installed", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                installedIdentity(),
                style = ScoutrType.monoMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current != null) {
                Spacer(Modifier.height(14.dp))
                Text("Host", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    hostIdentity(current),
                    style = ScoutrType.monoMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (current?.updateAvailable == true) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // PackageInstaller refuses the session without the "install unknown
            // apps" grant, so the one button routes to that screen first and
            // becomes the real trigger once the user comes back.
            TextButton(
                onClick = {
                    if (canInstall) {
                        confirming = true
                    } else {
                        grantInstallPermission.launch(ApkInstaller.unknownSourcesSettings(context))
                    }
                },
                enabled = stage == null,
                modifier = Modifier.fillMaxWidth().testTag("settings_update_button"),
            ) {
                Text(
                    when {
                        stage != null -> progressLabel(stage)
                        !canInstall -> "Allow installs, then update"
                        else -> "Update app"
                    },
                )
            }
        }
    }
}

@Composable
private fun UpdateHostSection(
    currentHostId: String?,
    currentHostAlias: String?,
    options: Map<String, String>,
    onSelect: (String) -> Unit,
    onDisable: () -> Unit,
) {
    if (options.isEmpty()) return
    var pendingHostId by remember { mutableStateOf<String?>(null) }
    var confirmingDisable by remember { mutableStateOf(false) }

    pendingHostId?.let { hostId ->
        val alias = options.getValue(hostId)
        ConfirmDialog(
            title = "Use $alias for updates?",
            text = "Only choose a host you trust. Android will reject an APK signed with a different key, but this host controls which app build is offered.",
            confirmLabel = "Use $alias",
            onConfirm = {
                pendingHostId = null
                onSelect(hostId)
            },
            onDismiss = { pendingHostId = null },
        )
    }
    if (confirmingDisable) {
        ConfirmDialog(
            title = "Disable in-app updates?",
            text = "You can choose a trusted host and turn updates back on later.",
            confirmLabel = "Disable",
            onConfirm = {
                confirmingDisable = false
                onDisable()
            },
            onDismiss = { confirmingDisable = false },
        )
    }

    SettingsSection(
        label = "Update source",
        footnote = "Updates are fetched from one explicitly selected host. Switching hosts may also switch signing keys.",
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                currentHostAlias?.let { "Updates use $it" } ?: "In-app updates are disabled",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("settings_update_host"),
            )
            options.forEach { (hostId, alias) ->
                if (hostId != currentHostId) {
                    TextButton(
                        onClick = { pendingHostId = hostId },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use $alias")
                    }
                }
            }
            if (currentHostId != null) {
                TextButton(
                    onClick = { confirmingDisable = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disable in-app updates", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Notifications: per-kind toggles for ringing, and a link to system channels.
 * Blocked stays on by default; done is opt-in.
 */
@Composable
private fun NotificationsSection(notificationPrefs: NotificationPreferencesStore) {
    var blockedEnabled by remember { mutableStateOf(notificationPrefs.blockedEnabled) }
    var doneEnabled by remember { mutableStateOf(notificationPrefs.doneEnabled) }

    SettingsSection(
        label = "Notifications",
        footnote = "High-priority channels. System Settings lets you control sound per-channel too.",
    ) {
        SettingsSwitchRow(
            title = "Ring for needs you",
            subtitle = "When an agent needs your input.",
            checked = blockedEnabled,
            onCheckedChange = {
                blockedEnabled = it
                notificationPrefs.blockedEnabled = it
            },
            testTag = "settings_notifications_blocked",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "Ring for finished",
            subtitle = "When an agent finishes.",
            checked = doneEnabled,
            onCheckedChange = {
                doneEnabled = it
                notificationPrefs.doneEnabled = it
            },
            testTag = "settings_notifications_done",
        )
    }
}

private fun progressLabel(progress: UpdateProgress): String = when (progress) {
    is UpdateProgress.Building -> "Building on host… (~1 min)"
    is UpdateProgress.Downloading ->
        if (progress.total > 0) {
            "Downloading… ${(progress.bytes * 100 / progress.total)}%"
        } else {
            "Downloading…"
        }
    is UpdateProgress.Installing -> "Installing…"
}

private fun installedIdentity(): String =
    "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})"

private fun hostIdentity(status: UpdateStatusResponse): String =
    "${status.host.version} (${status.host.commit})"
/**
 * How a *new* Chat visit starts. The header toggles still win for the visit
 * they belong to, and changing these never rewrites a chat that is already
 * open — that would move the transcript under the user's thumb.
 */
@Composable
private fun ChatSection(appearance: AppearancePreferencesStore) {
    var showThinking by remember { mutableStateOf(appearance.showThinkingDefault) }
    var expandTools by remember { mutableStateOf(appearance.expandToolsDefault) }

    SettingsSection(
        "Chat",
        footnote = "Applies to sessions you open from now on. The controls in a session's header still override it for that visit.",
    ) {
        SettingsSwitchRow(
            title = "Show thinking",
            subtitle = "Reasoning blocks are visible when a session opens.",
            checked = showThinking,
            onCheckedChange = {
                showThinking = it
                appearance.showThinkingDefault = it
            },
            testTag = "settings_chat_thinking",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "Expand tool details",
            subtitle = "Tool chips start open instead of collapsed.",
            checked = expandTools,
            onCheckedChange = {
                expandTools = it
                appearance.expandToolsDefault = it
            },
            testTag = "settings_chat_tools",
        )
    }
}

/** Global machine-text size controls for Chat and Review. */
@Composable
private fun TypographySection(appearance: AppearancePreferencesStore) {
    var markdownCodeFontSizeSp by remember(appearance) { mutableFloatStateOf(appearance.markdownCodeFontSizeSp) }
    var reviewFontSizeSp by remember(appearance) { mutableFloatStateOf(appearance.reviewFontSizeSp) }
    var toolOutputFontSizeSp by remember(appearance) { mutableFloatStateOf(appearance.toolOutputFontSizeSp) }

    fun setMarkdownCodeFontSize(value: Float) {
        appearance.markdownCodeFontSizeSp = value
        markdownCodeFontSizeSp = appearance.markdownCodeFontSizeSp
    }
    fun setReviewFontSize(value: Float) {
        appearance.reviewFontSizeSp = value
        reviewFontSizeSp = appearance.reviewFontSizeSp
    }

    fun setToolOutputFontSize(value: Float) {
        appearance.toolOutputFontSizeSp = value
        toolOutputFontSizeSp = appearance.toolOutputFontSizeSp
    }

    SettingsSection(
        "Typography",
        footnote = "Review diff and file content use their own adjustable font size."
    ) {
        FontSizeStepperRow(
            title = "Markdown code",
            subtitle = "Inline and fenced code in assistant messages.",
            fontSizeSp = markdownCodeFontSizeSp,
            minFontSizeSp = AppearancePreferencesStore.MIN_CODE_FONT_SIZE_SP,
            maxFontSizeSp = AppearancePreferencesStore.MAX_CODE_FONT_SIZE_SP,
            onFontSizeChange = ::setMarkdownCodeFontSize,
            minusTag = "settings_markdown_code_minus",
            valueTag = "settings_markdown_code_value",
            plusTag = "settings_markdown_code_plus",
            smallerContentDescription = "Smaller Markdown code",
            largerContentDescription = "Larger Markdown code",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FontSizeStepperRow(
            title = "Review code",
            subtitle = "Diff and file content in the Review screen.",
            fontSizeSp = reviewFontSizeSp,
            minFontSizeSp = AppearancePreferencesStore.MIN_CODE_FONT_SIZE_SP,
            maxFontSizeSp = AppearancePreferencesStore.MAX_CODE_FONT_SIZE_SP,
            onFontSizeChange = ::setReviewFontSize,
            minusTag = "settings_review_code_minus",
            valueTag = "settings_review_code_value",
            plusTag = "settings_review_code_plus",
            smallerContentDescription = "Smaller Review code",
            largerContentDescription = "Larger Review code",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FontSizeStepperRow(
            title = "Tool output",
            subtitle = "Expanded command results and inline file-edit diffs.",
            fontSizeSp = toolOutputFontSizeSp,
            minFontSizeSp = AppearancePreferencesStore.MIN_CODE_FONT_SIZE_SP,
            maxFontSizeSp = AppearancePreferencesStore.MAX_CODE_FONT_SIZE_SP,
            stepSp = 0.5f,
            onFontSizeChange = ::setToolOutputFontSize,
            minusTag = "settings_tool_output_minus",
            valueTag = "settings_tool_output_value",
            plusTag = "settings_tool_output_plus",
            smallerContentDescription = "Smaller tool output",
            largerContentDescription = "Larger tool output",
        )
    }
}

@Composable
private fun FontSizeStepperRow(
    title: String,
    subtitle: String,
    fontSizeSp: Float,
    minFontSizeSp: Float,
    maxFontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    minusTag: String,
    valueTag: String,
    plusTag: String,
    smallerContentDescription: String,
    largerContentDescription: String,
    stepSp: Float = FONT_STEP_SP,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onFontSizeChange((fontSizeSp - stepSp).coerceAtLeast(minFontSizeSp)) },
            enabled = fontSizeSp > minFontSizeSp,
            modifier = Modifier.testTag(minusTag),
        ) {
            Icon(Icons.Default.Remove, contentDescription = smallerContentDescription)
        }
        Text(
            fontSizeLabel(fontSizeSp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = ScoutrMono,
            modifier = Modifier.width(36.dp).testTag(valueTag),
        )
        IconButton(
            onClick = { onFontSizeChange((fontSizeSp + stepSp).coerceAtMost(maxFontSizeSp)) },
            enabled = fontSizeSp < maxFontSizeSp,
            modifier = Modifier.testTag(plusTag),
        ) {
            Icon(Icons.Default.Add, contentDescription = largerContentDescription)
        }
    }
}

private fun fontSizeLabel(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else value.toString()


/**
 * Terminal look, for this connection. These are the same values pinch and the
 * extra-keys strip write, so an open terminal follows along and the shortcuts
 * keep working.
 */
@Composable
private fun TerminalSection(preferences: TerminalPreferencesStore.ConnectionPreferences) {
    var fontSizeSp by remember(preferences) { mutableFloatStateOf(preferences.fontSizeSp) }
    var extraKeys by remember(preferences) { mutableStateOf(preferences.extraKeysVisible) }

    // The buttons disable at the bounds and the store clamps on write, so the
    // step itself needs no third copy of the range.
    val setFont = { value: Float ->
        preferences.fontSizeSp = value
        fontSizeSp = preferences.fontSizeSp
    }

    SettingsSection("Terminal") {
        FontSizeStepperRow(
            title = "Font size",
            subtitle = "Pinch in the terminal does the same thing.",
            fontSizeSp = fontSizeSp,
            minFontSizeSp = TerminalPreferencesStore.ConnectionPreferences.MIN_FONT_SIZE_SP,
            maxFontSizeSp = TerminalPreferencesStore.ConnectionPreferences.MAX_FONT_SIZE_SP,
            onFontSizeChange = setFont,
            minusTag = "settings_font_minus",
            valueTag = "settings_font_value",
            plusTag = "settings_font_plus",
            smallerContentDescription = "Smaller terminal font",
            largerContentDescription = "Larger terminal font",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsSwitchRow(
            title = "Extra keys",
            subtitle = "The Esc / Ctrl / arrows strip above the keyboard.",
            checked = extraKeys,
            onCheckedChange = {
                extraKeys = it
                preferences.extraKeysVisible = it
            },
            testTag = "settings_extra_keys",
        )
    }
}

/** One switch for the whole tactile vocabulary — BEL and needs-you included. */
@Composable
private fun HapticsSection(appearance: AppearancePreferencesStore) {
    var haptics by remember { mutableStateOf(appearance.hapticsEnabled) }

    SettingsSection("Haptics") {
        SettingsSwitchRow(
            title = "Vibration",
            subtitle = "Taps, confirmations, terminal bell, and needs-you nudges.",
            checked = haptics,
            onCheckedChange = {
                haptics = it
                appearance.hapticsEnabled = it
            },
            testTag = "settings_haptics",
        )
    }
}

/** App-level motion preference; Android's system Remove animations setting still takes precedence. */
@Composable
private fun MotionSection(appearance: AppearancePreferencesStore) {
    var reduceMotion by remember { mutableStateOf(appearance.reduceMotionEnabled) }

    SettingsSection(
        label = "Motion",
        footnote = "Android's system Remove animations setting still takes precedence.",
    ) {
        SettingsSwitchRow(
            title = "Reduce motion",
            subtitle = "Use static status indicators and skip decorative transitions.",
            checked = reduceMotion,
            onCheckedChange = {
                reduceMotion = it
                appearance.reduceMotionEnabled = it
            },
            testTag = "settings_reduce_motion",
        )
    }
}

/** Quiet label + one card + an optional footnote. Headers are muted; the accent stays AI-owned. */
@Composable
private fun SettingsSection(
    label: String,
    footnote: String? = null,
    content: @Composable () -> Unit,
) {
    SectionLabel(label, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
    if (footnote != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            footnote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
}

/**
 * The page's one switch-row geometry. No leading icons: every row here is a
 * static preference, and the accent is reserved for AI-owned states, so a
 * tinted glyph on one row would both break DESIGN.md and single that row out
 * for no reason.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    rowTestTag: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (rowTestTag != null) Modifier.testTag(rowTestTag) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

private const val FONT_STEP_SP = 1f


/**
 * The multi-host management section: one row per paired profile with status,
 * Default/Updates badges, an overflow menu, and tap-for-details. Every
 * destructive flow runs through [HostsViewModel], which alone routes through
 * the lifecycle coordinator so worker retirement and cleanup stay ordered.
 */
@Composable
private fun HostsSection(
    viewModel: HostsViewModel,
    onAddHost: () -> Unit,
    onAllHostsForgotten: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val scope = rememberCoroutineScope()

    var renaming by remember { mutableStateOf<HostRowUi?>(null) }
    var updateWarningFor by remember { mutableStateOf<HostRowUi?>(null) }
    var forgetting by remember { mutableStateOf<HostRowUi?>(null) }
    var replacingIdentityFor by remember { mutableStateOf<HostRowUi?>(null) }

    SettingsSection("Hosts") {
        ui.rows.forEachIndexed { index, row ->
            HostRow(
                row = row,
                checking = row.hostId in ui.checking,
                onRename = { renaming = row },
                onSetDefault = { viewModel.setDefault(row.hostId) },
                onRefresh = { viewModel.refresh(row.hostId) },
                onUseForUpdates = { updateWarningFor = row },
                onForget = { forgetting = row },
                onReplaceIdentity = { replacingIdentityFor = row },
            )
            if (index != ui.rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (ui.rows.isEmpty()) {
            Text(
                "No bridge is paired.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = onAddHost, modifier = Modifier.fillMaxWidth().testTag("settings_add_host")) {
            Text("Add host")
        }
        ui.transientError?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("settings_hosts_error"),
            )
        }
    }

    renaming?.let { row ->
        RenameHostDialog(
            row = row,
            onConfirm = { pair ->
                viewModel.rename(pair.first, pair.second)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    updateWarningFor?.let { row ->
        ConfirmDialog(
            title = "Use ${row.alias} for updates?",
            text = "Hosts may build APKs with different signing keys. An APK signed " +
                "with another key may not install over the current app.",
            confirmLabel = "Use for updates",
            onConfirm = {
                viewModel.useForUpdates(row.hostId)
                updateWarningFor = null
            },
            onDismiss = { updateWarningFor = null },
        )
    }
    forgetting?.let { row ->
        ForgetHostDialog(
            viewModel = viewModel,
            row = row,
            scope = scope,
            onForgotten = { allGone ->
                forgetting = null
                if (allGone) onAllHostsForgotten()
            },
            onDismiss = { forgetting = null },
        )
    }
    replacingIdentityFor?.let { row ->
        ReplaceIdentityDialog(
            viewModel = viewModel,
            row = row,
            scope = scope,
            onAddAsNew = {
                replacingIdentityFor = null
                onAddHost()
            },
            onDone = { replaced ->
                replacingIdentityFor = null
                if (replaced) {
                    // Replacement retires the old id entirely; nothing else to do.
                }
            },
            onDismiss = { replacingIdentityFor = null },
        )
    }
}

/** Status vocabulary shared by rows and detail areas. */
internal fun hostStatusLabel(status: HostAvailability): String = when (status) {
    is HostAvailability.Online -> "Online"
    is HostAvailability.Offline -> "Offline"
    is HostAvailability.Incompatible -> "Incompatible"
    is HostAvailability.IdentityChanged -> "Identity changed"
    HostAvailability.Unknown -> "Checking"
}

@Composable
private fun hostStatusColor(status: HostAvailability) = when (status) {
    is HostAvailability.Online -> MaterialTheme.colorScheme.primary
    is HostAvailability.Offline -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    is HostAvailability.Incompatible, is HostAvailability.IdentityChanged -> MaterialTheme.colorScheme.error
    HostAvailability.Unknown -> MaterialTheme.colorScheme.outline
}

@Composable
private fun HostRow(
    row: HostRowUi,
    checking: Boolean,
    onRename: () -> Unit,
    onSetDefault: () -> Unit,
    onRefresh: () -> Unit,
    onUseForUpdates: () -> Unit,
    onForget: () -> Unit,
    onReplaceIdentity: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .testTag("host_row_${row.alias}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusRing(
                color = hostStatusColor(row.status),
                animation = if (checking) StatusRingAnimation.Live else StatusRingAnimation.Static,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.alias,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.isDefault) HostBadge("Default")
                    if (row.isUpdateHost) HostBadge("Updates")
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    row.url,
                    style = ScoutrType.monoMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val statusLine = when (val status = row.status) {
                    is HostAvailability.Online -> "Online · checked ${relativeTimeLabel(status.checkedAtMs)}"
                    is HostAvailability.Offline ->
                        status.lastSuccessAtMs
                            ?.let { "Offline · last ok ${relativeTimeLabel(it)}" }
                            ?: "Offline"
                    else -> hostStatusLabel(status)
                }
                Text(
                    statusLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = hostStatusColor(row.status),
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("host_menu_${row.alias}"),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Host actions for ${row.alias}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Refresh") }, onClick = { menuOpen = false; onRefresh() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    if (!row.isDefault) {
                        DropdownMenuItem(text = { Text("Set default") }, onClick = { menuOpen = false; onSetDefault() })
                    }
                    if (!row.isUpdateHost) {
                        DropdownMenuItem(text = { Text("Use for updates") }, onClick = { menuOpen = false; onUseForUpdates() })
                    }
                    if (row.status is HostAvailability.IdentityChanged) {
                        DropdownMenuItem(text = { Text("Fix identity") }, onClick = { menuOpen = false; onReplaceIdentity() })
                    }
                    DropdownMenuItem(text = { Text("Forget") }, onClick = { menuOpen = false; onForget() })
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("Exposure: ")
                    append(row.exposure.toString().substringAfterLast('.').lowercase())
                    append(" · id ").append(row.hostId)
                    when (val status = row.status) {
                        is HostAvailability.Incompatible -> append("\n").append(status.message)
                        is HostAvailability.IdentityChanged -> {
                            append("\nBridge now reports id ")
                            append(status.reportedHostId.ifBlank { "(unknown)" })
                            append(". Use Fix identity to repair or replace this profile.")
                        }
                        is HostAvailability.Offline -> append("\n").append(status.message)
                        else -> Unit
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun HostBadge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private fun relativeTimeLabel(epochMs: Long): String =
    dev.scoutr.app.ui.relativeTime(epochMs.toDouble())

@Composable
private fun RenameHostDialog(
    row: HostRowUi,
    onConfirm: (Pair<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(row.alias) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename host") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("rename_host_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(row.hostId to name.trim()) }, enabled = name.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ForgetHostDialog(
    viewModel: HostsViewModel,
    row: HostRowUi,
    scope: CoroutineScope,
    onForgotten: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val needsDisposition = viewModel.forgetRequiresUpdateDisposition(row.hostId)
    val otherIds = viewModel.otherHostAliases(row.hostId).keys.toList()
    var choice by remember { mutableStateOf<Int?>(null) } // index into otherIds, or -1 = disable updates

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget ${row.alias}?") },
        text = {
            Column {
                if (needsDisposition) {
                    Text(
                        "${row.alias} is the update host. Choose a replacement update host, or disable in-app updates.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    otherIds.forEachIndexed { index, hostId ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choice = index },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == index, onClick = { choice = index })
                            Text(
                                "Move updates to ${viewModel.otherHostAliases(row.hostId)[hostId]}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { choice = -1 },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == -1, onClick = { choice = -1 })
                        Text("Disable in-app updates", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        "You'll need to pair again. Notifications from this host will stop.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !needsDisposition || choice != null,
                onClick = {
                    val disposition = when {
                        !needsDisposition -> null
                        choice == -1 -> UpdateHostDisposition.Disable
                        else -> UpdateHostDisposition.UseExisting(otherIds[choice ?: -1])
                    }
                    scope.launch { onForgotten(viewModel.forget(row.hostId, disposition)) }
                },
                modifier = Modifier.testTag("settings_forget_confirm"),
            ) {
                Text("Forget", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReplaceIdentityDialog(
    viewModel: HostsViewModel,
    row: HostRowUi,
    scope: CoroutineScope,
    onAddAsNew: () -> Unit,
    onDone: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val reportedPaired = viewModel.reportedIdIsPaired(row.hostId)
    val reportedId = viewModel.reportedHostId(row.hostId).orEmpty()

    if (reportedPaired) {
        // The reported id is already a profile; Replace/Add would only make a
        // duplicate, so offer the same-id repair instead.
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Refresh existing profile") },
            text = {
                Column {
                    Text(
                        "The bridge at ${row.url} now reports id $reportedId, which is already paired. " +
                            "Pair again with its current token to refresh URL/token/exposure without touching alias, default, updates or pins.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        singleLine = true,
                        label = { Text("Current bridge token") },
                        modifier = Modifier.fillMaxWidth().testTag("identity_token_field"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = token.isNotBlank(),
                    onClick = {
                        scope.launch { onDone(viewModel.refreshExistingProfile(row.hostId, token.trim())) }
                    },
                ) { Text("Refresh") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    var token by remember { mutableStateOf("") }
    var copyFlags by remember { mutableStateOf(false) }
    // Replacing an enabled update host needs the same signing-key disposition
    // as forgetting it: trust the replacement, or disable updates.
    val needsDisposition = viewModel.forgetRequiresUpdateDisposition(row.hostId)
    val otherIds = viewModel.otherHostAliases(row.hostId).keys.toList()
    var choice by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bridge identity changed") },
        text = {
            Column {
                Text(
                    "The bridge now reports id ${reportedId.ifBlank { "(unknown)" }}, which is not paired. " +
                        "Replace this profile's identity with the new one, or pair it as a new host instead.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    label = { Text("New bridge token") },
                    modifier = Modifier.fillMaxWidth().testTag("identity_token_field"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = copyFlags, onCheckedChange = { copyFlags = it })
                    Text(
                        "Move pin and archive flags",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (needsDisposition) {
                    Spacer(Modifier.height(8.dp))
                    otherIds.forEachIndexed { index, id ->
                        val alias = viewModel.otherHostAliases(row.hostId)[id]
                        Row(
                            Modifier.fillMaxWidth().clickable { choice = index },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == index, onClick = { choice = index })
                            Text("Use $alias for updates", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { choice = -1 },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == -1, onClick = { choice = -1 })
                        Text("Disable in-app updates", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotBlank() && (!needsDisposition || choice != null),
                onClick = {
                    val disposition = when {
                        !needsDisposition -> null
                        choice == -1 -> UpdateHostDisposition.Disable
                        else -> UpdateHostDisposition.UseExisting(otherIds[choice ?: -1])
                    }
                    scope.launch {
                        val replaced = viewModel.replaceIdentity(
                            previousHostId = row.hostId,
                            newToken = token.trim(),
                            copyRetained = copyFlags,
                            updateHostDisposition = disposition,
                        )
                        onDone(replaced)
                    }
                },
            ) { Text("Replace") }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                onAddAsNew()
            }) { Text("Add as new host") }
        },
    )
}
