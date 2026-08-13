package dev.cockpit.app.ui.screens

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.cockpit.app.data.AppearancePreferencesStore
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.TerminalPreferencesStore
import dev.cockpit.app.service.CockpitMonitorService
import dev.cockpit.app.state.MonitoringStore
import dev.cockpit.app.ui.components.ConfirmDialog
import dev.cockpit.app.ui.components.SectionLabel
import kotlin.math.roundToInt

/**
 * Settings: the home for durable device preferences and the only place the
 * saved pairing can be managed.
 *
 * Five sections on one scroll — Connection, Notifications, Chat, Terminal,
 * Haptics. Deliberately not a session-admin page: launch defaults, the Chat
 * header toggles, and pin/archive stay in the flow that owns them. Chat here
 * only supplies the seed a *new* visit starts from; Terminal writes the same
 * per-connection store that pinch and the extra-keys strip write.
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

        MonitoringSection(onMonitoringChanged = onMonitoringChanged)

        ChatSection(appearance = appearance)

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
            Text("Bridge", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                saved.host,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                modifier = Modifier.testTag("settings_host"),
            )
            Spacer(Modifier.height(14.dp))
            Text("Push", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            // ntfy is configured by the bridge during the health handshake, so
            // it is status, not a form. Either half missing means no push.
            val push = saved.ntfyUrl?.let { url -> saved.ntfyTopic?.let { topic -> "$url\n$topic" } }
            Text(
                push ?: "Push not configured.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (push != null) FontFamily.Monospace else FontFamily.Default,
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
 * The opt-in background monitor. When on, a foreground service keeps the ntfy
 * poll alive so blocked/done events push to the shade with a deep link and an
 * inline reply, even when the app is closed.
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
                android.content.Intent(context, CockpitMonitorService::class.java),
            )
        }
    }

    SettingsSection(
        "Notifications",
        footnote = "Monitoring only works while a connection is saved. Notifications deep-link to the exact session and support an inline Reply that steers the agent.",
    ) {
        SettingsSwitchRow(
            title = "Background monitoring",
            subtitle = "Watch agents for blocked / done events while the app is closed. Uses a foreground service.",
            checked = monitoring,
            onCheckedChange = { value ->
                monitoring = value
                store.enabled = value
                if (onMonitoringChanged != null) {
                    onMonitoringChanged(value)
                } else {
                    val serviceIntent = android.content.Intent(context, CockpitMonitorService::class.java)
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
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Font size", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Pinch in the terminal does the same thing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { setFont(fontSizeSp - FONT_STEP_SP) },
                enabled = fontSizeSp > TerminalPreferencesStore.ConnectionPreferences.MIN_FONT_SIZE_SP,
                modifier = Modifier.testTag("settings_font_minus"),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Smaller terminal font")
            }
            Text(
                "${fontSizeSp.roundToInt()}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(28.dp).testTag("settings_font_value"),
            )
            IconButton(
                onClick = { setFont(fontSizeSp + FONT_STEP_SP) },
                enabled = fontSizeSp < TerminalPreferencesStore.ConnectionPreferences.MAX_FONT_SIZE_SP,
                modifier = Modifier.testTag("settings_font_plus"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Larger terminal font")
            }
        }
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
    SectionLabel(label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
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
