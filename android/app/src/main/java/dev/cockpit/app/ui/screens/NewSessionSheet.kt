package dev.cockpit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cockpit.app.state.NewSessionUiState
import dev.cockpit.app.state.NewSessionViewModel
import dev.cockpit.app.state.breadcrumb
import dev.cockpit.app.state.crumbLabel
import dev.cockpit.app.state.quickPicks
import androidx.compose.runtime.collectAsState

/**
 * The new-session create flow: name (optional), folder picker (bridge
 * /api/dirs rooted at home, with ~ and ~/Dev quick picks) and model picker
 * (full catalog from pi's models-store.json, grouped by provider). Create
 * spawns a pane-native pi session and reports the new pane via [onCreated].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionSheet(
    viewModel: NewSessionViewModel,
    onDismiss: () -> Unit,
    onCreated: (paneId: String) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    LaunchedEffect(ui.created) {
        ui.created?.let { onCreated(it.paneId) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("New session", style = MaterialTheme.typography.titleLarge)
            Text(
                "Folder + model → a fresh pi pane in its own workspace",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // ── Folder picker ──
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Folder", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::goUp, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "Up one level",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                quickPicks(ui.home).forEach { pick ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clickable { viewModel.jumpTo(pick) }
                            .testTag("quick_pick_${crumbLabel(pick, ui.home)}"),
                    ) {
                        Text(
                            crumbLabel(pick, ui.home),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                ui.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("folder_path"),
            )
            LazyColumn(modifier = Modifier.height(180.dp).testTag("folder_list")) {
                if (ui.loadingDirs) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(22.dp)) }
                } else {
                    items(ui.dirs, key = { it }) { dir ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.enterDir(dir) }
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .testTag("folder_item_$dir"),
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                dir,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))

            // ── Model picker ──
            Text("Model", style = MaterialTheme.typography.labelLarge)
            LazyColumn(modifier = Modifier.height(200.dp).testTag("model_list")) {
                if (ui.loadingModels) {
                    item { CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(22.dp)) }
                } else {
                    ui.providers.forEach { provider ->
                        item(key = "h_${provider.name}") {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(provider.models, key = { "m_${provider.name}_${it.id}" }) { model ->
                            val selected = ui.selectedModel == model.id
                            Surface(
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable { viewModel.selectModel(model.id) }
                                    .testTag("model_item_${model.id}"),
                            ) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            model.name.ifBlank { model.id },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            model.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (selected) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Default.Create,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── Name + create ──
            OutlinedTextField(
                value = ui.name,
                onValueChange = viewModel::setName,
                label = { Text("Session name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("session_name"),
            )
            Spacer(Modifier.height(12.dp))
            ui.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Button(
                onClick = viewModel::create,
                enabled = ui.selectedModel != null && !ui.creating,
                modifier = Modifier.fillMaxWidth().testTag("create_session"),
            ) {
                if (ui.creating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text("Create session")
                }
            }
        }
    }
}

