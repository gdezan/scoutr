package dev.scoutr.app.ui.screens

import dev.scoutr.app.ui.theme.ScoutrMono
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scoutr.app.state.NewSessionUiState
import dev.scoutr.app.ui.components.SectionLabel
import dev.scoutr.app.state.NewSessionViewModel
import dev.scoutr.app.state.crumbLabel
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions

/** Fast session launcher with focused folder and model pickers. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionSheet(
    viewModel: NewSessionViewModel,
    onDismiss: () -> Unit,
    onCreated: (paneId: String) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showFolderPicker by rememberSaveable { mutableStateOf(false) }
    var showPresetDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ui.created) {
        ui.created?.let {
            viewModel.consumeCreatedSession()
            onCreated(it.paneId)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,

        // At the top scroll position the sheet's drag gesture would fight the
        // inner list and flicker; the header close button handles dismissal.
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding(),
        ) {
            LauncherHeader(onDismiss)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("new_session_content"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (ui.agentKinds.size >= 2) {
                    item { BackendSection(ui, viewModel::selectAgent) }
                }
                if (ui.presets.any { (it.agent ?: "pi") == ui.selectedAgent }) {
                    item { PresetsSection(ui, viewModel) }
                }
                item {
                    FolderSummary(
                        ui = ui,
                        onOpenPicker = { showFolderPicker = true },
                        onJumpTo = viewModel::jumpTo,
                    )
                }
                if (ui.selectedAgentHasModelCatalog) {
                    item {
                        ModelSummary(
                            ui = ui,
                            onOpenPicker = { showModelPicker = true },
                            onToggleDefault = { ui.selectedModelKey?.let(viewModel::setDefaultModel) },
                        )
                    }
                    item { ThinkingLevelSection(ui, viewModel::setThinkingLevel) }
                }
                item {
                    OutlinedTextField(
                        value = ui.name,
                        onValueChange = viewModel::setName,
                        label = { Text("Session name") },
                        supportingText = { Text("Optional. Defaults to the folder name.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("session_name"),
                    )
                }
            }
            ui.launcherError?.let { LauncherError(it) }
            LauncherActions(
                ui = ui,
                onSavePreset = { showPresetDialog = true },
                onCreate = viewModel::create,
            )
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            ui = ui,
            viewModel = viewModel,
            onDismiss = { showModelPicker = false },
        )
    }
    if (showFolderPicker) {
        FolderPickerDialog(
            ui = ui,
            viewModel = viewModel,
            onDismiss = { showFolderPicker = false },
        )
    }
    if (showPresetDialog) {
        SavePresetDialog(
            onDismiss = { showPresetDialog = false },
            onSave = {
                viewModel.savePreset(it)
                showPresetDialog = false
            },
        )
    }
}

@Composable
private fun LauncherHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Start a session", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose the workspace and agent settings, then start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close session launcher")
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsSection(ui: NewSessionUiState, viewModel: NewSessionViewModel) {
    // Presets are backend-scoped: a saved model + thinking level only make
    // sense for the agent they were saved under.
    val presets = ui.presets.filter { (it.agent ?: "pi") == ui.selectedAgent }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Saved presets")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.testTag("preset_${preset.id}"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(48.dp)
                                .widthIn(max = 180.dp)
                                .clickable { viewModel.applyPreset(preset.id) }
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(preset.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { viewModel.deletePreset(preset.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete ${preset.title} preset")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderSummary(
    ui: NewSessionUiState,
    onOpenPicker: () -> Unit,
    onJumpTo: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Folder")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenPicker, modifier = Modifier.testTag("open_folder_picker")) {
                Text("Browse")
            }
        }
        Surface(
            onClick = onOpenPicker,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    ui.path.ifBlank { "Loading folders…" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = ScoutrMono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("folder_path"),
                )
            }
        }
        if (ui.folderChoices.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.folderChoices.forEach { folder ->
                    FilterChip(
                        shape = RoundedCornerShape(6.dp),
                        selected = folder == ui.path,
                        onClick = { onJumpTo(folder) },
                        label = { Text(crumbLabel(folder, ui.home), maxLines = 1) },
                        modifier = Modifier.testTag("quick_pick_${crumbLabel(folder, ui.home)}"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackendSection(ui: NewSessionUiState, onSelect: (String) -> Unit) {
    if (ui.agentKinds.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Agent")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.agentKinds.forEach { kind ->
                FilterChip(
                    shape = RoundedCornerShape(6.dp),
                    selected = kind.id == ui.selectedAgent,
                    onClick = { onSelect(kind.id) },
                    label = { Text(kind.displayName) },
                    modifier = Modifier.testTag("agent_kind_${kind.id}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSummary(
    ui: NewSessionUiState,
    onOpenPicker: () -> Unit,
    onToggleDefault: () -> Unit,
) {
    val selected = ui.selectedModel
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Model")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenPicker, modifier = Modifier.testTag("open_model_picker")) {
                Text("Change")
            }
        }
        Surface(
            onClick = onOpenPicker,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            ui.loadingModels -> "Loading models…"
                            selected == null -> "Choose a model"
                            else -> selected.model.name.ifBlank { selected.model.id }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    selected?.let {
                        Text(
                            it.key,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = ScoutrMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected != null) {
                    TextButton(onClick = onToggleDefault, modifier = Modifier.testTag("toggle_default_model")) {
                        Icon(
                            if (selected.default) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (selected.default) "Default" else "Make default")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThinkingLevelSection(ui: NewSessionUiState, onSelect: (String?) -> Unit) {
    val levels = ui.selectedModel?.model?.thinkingLevels.orEmpty()
    if (levels.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Thinking")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                shape = RoundedCornerShape(6.dp),
                selected = ui.selectedThinkingLevel == null,
                onClick = { onSelect(null) },
                label = { Text("Model default") },
            )
            levels.forEach { level ->
                FilterChip(
                    shape = RoundedCornerShape(6.dp),
                    selected = ui.selectedThinkingLevel == level,
                    onClick = { onSelect(level) },
                    label = { Text(level) },
                    modifier = Modifier.testTag("thinking_$level"),
                )
            }
        }
    }
}

@Composable
private fun LauncherError(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun LauncherActions(
    ui: NewSessionUiState,
    onSavePreset: () -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            shape = MaterialTheme.shapes.small,
            onClick = onSavePreset,
            enabled = (!ui.selectedAgentHasModelCatalog || ui.selectedModel != null) && ui.path.isNotBlank() && !ui.loadingDirs,
            modifier = Modifier.heightIn(min = 48.dp).testTag("save_preset"),
        ) {
            Text("Save preset")
        }
        Button(
            shape = MaterialTheme.shapes.small,
            onClick = onCreate,
            enabled = ui.canCreate,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("create_session"),
        ) {
            if (ui.creating) {
                Spacer(Modifier.width(10.dp))
                Text("Starting…")
            } else {
                Text(if (ui.initialPrompt.isBlank()) "Start session" else "Start and send task")
            }
        }
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save launcher preset") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(60) },
                label = { Text("Preset name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) onSave(title) }),
                modifier = Modifier.fillMaxWidth().testTag("preset_name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("confirm_preset")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

