package dev.scoutr.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryState
import dev.scoutr.app.state.HostAvailability

/**
 * The global Terminal action's compact host target sheet. It starts on the
 * default host and requires an explicit Continue, so the WebSocket target is
 * visible before any request goes out; Continue stays disabled while the
 * chosen bridge cannot accept a terminal.
 */
@Composable
fun TerminalTargetSheet(
    registryState: HostRegistryState,
    statuses: Map<String, HostAvailability>,
    initialSelection: HostProfileKey?,
    onSelect: (HostProfileKey) -> Unit,
    onConfirm: (HostProfileKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultProfile = registryState.defaultHostId?.let { id ->
        registryState.profiles.firstOrNull { it.hostId == id }
    }
    val initial = initialSelection
        ?: defaultProfile?.let { HostProfileKey(it.hostId, it.profileGeneration) }

    var selected by remember(initial) {
        mutableStateOf(
            initial ?: registryState.profiles.firstOrNull()
                ?.let { HostProfileKey(it.hostId, it.profileGeneration) },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open terminal on") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                registryState.profiles.forEach { entry ->
                    val profile = HostProfileKey(entry.hostId, entry.profileGeneration)
                    val status = statuses[entry.hostId]
                    val online = status is HostAvailability.Online
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = online) { onSelect(profile) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == profile, onClick = null, enabled = online)
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(entry.alias, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when (status) {
                                    is HostAvailability.Online -> "Online"
                                    is HostAvailability.Offline -> "Offline"
                                    is HostAvailability.Incompatible -> "Incompatible"
                                    is HostAvailability.IdentityChanged -> "Identity changed"
                                    HostAvailability.Unknown, null -> "Checking"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && statuses[selected?.hostId] is HostAvailability.Online,
                onClick = { selected?.let(onConfirm) },
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
