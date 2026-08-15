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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.scoutr.app.data.AppearancePreferencesStore
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.service.ScoutrMonitorService
import dev.scoutr.app.state.MonitoringStore
import dev.scoutr.app.ui.components.ConfirmDialog
import dev.scoutr.app.ui.components.SectionLabel
import dev.scoutr.app.ui.components.StatusRing
import dev.scoutr.app.ui.components.StatusRingAnimation
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import dev.scoutr.app.BuildConfig
import dev.scoutr.app.data.UpdateStatusResponse
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.launch
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
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /**
     * The container's store, not a fresh one: an open Terminal observes its
     * revision, and a second instance would tick a flow nobody is collecting.
     */
    terminalPreferences: TerminalPreferencesStore,
    onForget: () -> Unit,
    api: ScoutrApi,
    modifier: Modifier = Modifier,
    /** Null only before the first pairing, which the tab shell cannot reach. */
    saved: ConnectionStore.Saved? = null,
    onMonitoringChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appearance = remember(context) { AppearancePreferencesStore(context) }

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

        if (saved != null) {
            ConnectionSection(saved = saved, onForget = onForget)
        }

        UpdateSection(api = api)

        MonitoringSection(onMonitoringChanged = onMonitoringChanged)

        ChatSection(appearance = appearance)

        TypographySection(appearance = appearance)
        if (saved != null) {
            TerminalSection(
                preferences = remember(terminalPreferences, saved) {
                    terminalPreferences.forConnection(saved.host, saved.token)
                },
            )
        }

        HapticsSection(appearance = appearance)
    }
}

/**
 * The saved pairing, read-only, plus Forget. The token is never composed —
 * not as text, not as a content description: Settings is a screen that gets
 * screenshotted and screen-shared.
 */
@Composable
private fun ConnectionSection(
    saved: ConnectionStore.Saved,
    onForget: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        ConfirmDialog(
            title = "Forget connection?",
            text = "Forget ${saved.host}? You'll need to pair again. " +
                "Background monitoring will turn off.",
            confirmLabel = "Forget",
            destructive = true,
            onConfirm = {
                confirming = false
                onForget()
            },
            onDismiss = { confirming = false },
        )
    }

    SettingsSection("Connection") {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("settings_connection_status"),
            ) {
                StatusRing(
                    color = MaterialTheme.colorScheme.primary,
                    animation = StatusRingAnimation.Static,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("Bridge", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                saved.host,
                style = ScoutrType.monoMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                modifier = Modifier.testTag("settings_host"),
            )
            Spacer(Modifier.height(14.dp))
            Text("Push", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            // ntfy is configured by the bridge during the health handshake, so
            // it is status, not a form. Either half missing means no push.
            val push = saved.ntfyUrl?.let { url -> saved.ntfyTopic?.let { topic -> "$url\n$topic" } }
            // A URL and topic are machine facts; "not configured" is a sentence.
            // Mono is for the former only — never as decoration (§9d).
            Text(
                push ?: "Push not configured.",
                style = if (push != null) ScoutrType.monoMeta
                else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3,
                modifier = Modifier.testTag("settings_ntfy"),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth().testTag("settings_forget"),
        ) {
            Text("Forget this connection", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Host vs installed build identity, plus a fire-and-forget install trigger.
 * The status ring and rows are display-only: the update signal stays
 * commit-based, so the semver shown here never gates the button. The host's
 * dirty flag still counts toward `updateAvailable` but is never shown.
 */
@Composable
private fun UpdateSection(api: ScoutrApi) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateStatusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            api.updateStatus(
                commit = BuildConfig.GIT_COMMIT,
                version = BuildConfig.VERSION_NAME,
                dirty = BuildConfig.GIT_DIRTY,
            )
        }.onSuccess { status = it }
            .onFailure { error = it.message ?: "could not read host version" }
    }

    if (confirming) {
        ConfirmDialog(
            title = "Update app?",
            text = "Build and install the latest version, then restart the app. " +
                "This takes about a minute.",
            confirmLabel = "Install now",
            onConfirm = {
                confirming = false
                installing = true
                scope.launch {
                    runCatching { api.updateInstall(deviceModel = Build.MODEL) }
                        .onFailure {
                            installing = false
                            error = it.message ?: "update failed"
                        }
                    // Success is silent: adb install -r kills this process and
                    // the bridge relaunches it afterwards.
                }
            },
            onDismiss = { confirming = false },
        )
    }

    SettingsSection(
        "Update",
        footnote = "Checks the host checkout for a newer build and reinstalls this app. " +
            "Only the app is updated, never the bridge.",
    ) {
        val current = status
        val statusLabel = when {
            error != null -> error!!
            current?.updateAvailable == true -> "Update available"
            current != null -> "Up to date"
            else -> "Checking…"
        }
        val statusColor = when {
            error != null -> MaterialTheme.colorScheme.error
            current?.updateAvailable == true -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("settings_update_status"),
            ) {
                StatusRing(color = statusColor, animation = StatusRingAnimation.Static)
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
            TextButton(
                onClick = { confirming = true },
                enabled = !installing,
                modifier = Modifier.fillMaxWidth().testTag("settings_update_button"),
            ) {
                Text(if (installing) "Installing… (~1 min)" else "Update app")
            }
        }
    }
}

private fun installedIdentity(): String =
    "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})"

private fun hostIdentity(status: UpdateStatusResponse): String =
    "${status.host.version} (${status.host.commit})"
/**
 * The opt-in, time-bounded background monitor. A foreground service keeps the
 * ntfy poll alive so blocked/done events reach the notification shade with a
 * deep link and inline reply, even when the app is closed. Android 15+ stops
 * this session after six background hours and Scoutr will not restart it.
 */
@Composable
private fun MonitoringSection(onMonitoringChanged: ((Boolean) -> Unit)?) {
    val context = LocalContext.current
    val store = remember { MonitoringStore(context) }
    var monitoring by remember { mutableStateOf(store.enabled) }

    // Enabling monitoring needs POST_NOTIFICATIONS (the foreground service
    // cannot start without it on 33+); request it before starting the service
    // so the toggle never crashes into a SecurityException.
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ContextCompat.startForegroundService(
                context,
                android.content.Intent(context, ScoutrMonitorService::class.java),
            )
        }
    }

    SettingsSection(
        "Notifications",
        footnote = "Monitoring only works while a connection is saved. On Android 15+, the system stops this background session after six hours in a 24-hour period. Notifications deep-link to the exact session and support an inline Reply that steers the agent.",
    ) {
        SettingsSwitchRow(
            title = "Background monitoring",
            subtitle = "Watch agents for blocked / done events while the app is closed. Android 15+ limits data-sync monitoring to six hours in a 24-hour period.",
            checked = monitoring,
            onCheckedChange = { value ->
                monitoring = value
                store.enabled = value
                if (onMonitoringChanged != null) {
                    onMonitoringChanged(value)
                } else {
                    val serviceIntent = android.content.Intent(context, ScoutrMonitorService::class.java)
                    if (value) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            ContextCompat.startForegroundService(context, serviceIntent)
                        } else {
                            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        context.stopService(serviceIntent)
                    }
                }
            },
            testTag = "settings_monitoring_switch",
            rowTestTag = "settings_monitoring_row",
        )
    }
}

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
