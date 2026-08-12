package dev.cockpit.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.cockpit.app.service.CockpitMonitorService
import dev.cockpit.app.state.MonitoringStore

/**
 * Settings: the opt-in background monitor. When on, a foreground service keeps
 * the ntfy poll alive so blocked/done events push to the shade with a deep
 * link and an inline reply, even when the app is closed.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,

    onMonitoringChanged: ((Boolean) -> Unit)? = null,
) {
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("settings_monitoring_row")
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Background monitoring",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Watch agents for blocked / done events while the app is closed. Uses a foreground service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
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
                modifier = Modifier.testTag("settings_monitoring_switch"),
            )
        }
        }
        HorizontalDivider(Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
        Text(
            "Monitoring only works while a connection is saved. Notifications deep-link to the exact session and support an inline Reply that steers the agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
