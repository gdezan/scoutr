package dev.scoutr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.scoutr.app.ui.imeOrNavigationBarsPadding
import dev.scoutr.app.state.ModelPickerMatch
import dev.scoutr.app.state.NewSessionUiState
import dev.scoutr.app.state.NewSessionViewModel
import dev.scoutr.app.ui.motion.HapticEvent
import dev.scoutr.app.ui.motion.rememberHaptic
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
@Composable
internal fun ModelPickerDialog(
    ui: NewSessionUiState,
    viewModel: NewSessionViewModel,
    modelListState: LazyListState = rememberLazyListState(),
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptic()
    ResetLazyListOnQueryChange(
        query = ui.modelFilters.query,
        contentAvailable = !ui.loadingModels && ui.modelError == null && ui.modelMatches.isNotEmpty(),
        listState = modelListState,
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().imeOrNavigationBarsPadding().testTag("model_picker"),
        ) {
            Column {
                PickerHeader("Choose a model", onDismiss)
                OutlinedTextField(
                    value = ui.modelFilters.query,
                    onValueChange = viewModel::setModelQuery,
                    placeholder = { Text("Provider, model, or ID") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.modelFilters.query.isNotBlank()) {
                            IconButton(onClick = { viewModel.setModelQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear model search")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "Search models" }.testTag("model_search"),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    ui.loadingModels -> PickerLoading()
                    ui.modelError != null -> PickerError(ui.modelError, viewModel::retryModels)
                    ui.providers.isEmpty() -> ModelPickerEmpty("No models available")
                    ui.modelMatches.isEmpty() -> ModelPickerEmpty("No models match")
                    else -> ProviderModelCatalog(
                        models = ui.modelMatches,
                        listState = modelListState,
                        selectedKey = ui.selectedModelKey,
                        onSelect = { match ->
                            haptics(HapticEvent.Select)
                            viewModel.selectModel(match.key)
                            onDismiss()
                        },
                        onToggleFavorite = viewModel::toggleFavorite,
                        modifier = Modifier.fillMaxSize(),
                        testTag = "model_list",
                        selectionDescription = "Selected model",
                        modelTagPrefix = "model_item_",
                    )
                }
            }
        }
    }
}

@Composable
internal fun FolderPickerDialog(
    ui: NewSessionUiState,
    viewModel: NewSessionViewModel,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().imeOrNavigationBarsPadding().testTag("folder_picker"),
        ) {
            Column {
                PickerHeader("Choose a folder", onDismiss)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = viewModel::goUp, enabled = ui.path != ui.home) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up one folder")
                    }
                    Text(
                        ui.path,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("folder_path"),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        ui.loadingDirs -> PickerLoading()
                        ui.folderError != null -> PickerError(ui.folderError, viewModel::retryFolders)
                        ui.dirs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No subfolders", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("folder_list"),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        ) {
                            items(ui.dirs, key = { it }) { folder ->
                                Surface(
                                    onClick = { viewModel.enterDir(folder) },
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("folder_item_$folder"),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(12.dp))
                                        Text(folder, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = onDismiss,
                    enabled = ui.path.isNotBlank() && !ui.loadingDirs && ui.folderError == null,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag("use_folder"),
                ) {
                    Text("Use this folder")
                }
            }
        }
    }
}

@Composable
internal fun ProviderModelCatalog(
    models: List<ModelPickerMatch>,
    listState: LazyListState,
    selectedKey: String?,
    onSelect: (ModelPickerMatch) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String,
    selectionDescription: String,
    modelTagPrefix: String = "model_item_",
    enabled: Boolean = true,
    onToggleFavorite: ((String) -> Unit)? = null,
) {
    val providerGroups = models.groupBy { it.provider }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(testTag),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        providerGroups.forEach { (provider, matches) ->
            item(key = "provider:$provider") {
                ProviderHeader(name = provider, count = matches.size)
            }
            items(matches, key = { it.key }) { match ->
                ModelPickerCatalogRow(
                    match = match,
                    selected = match.key == selectedKey,
                    onSelect = { onSelect(match) },
                    onToggleFavorite = onToggleFavorite?.let { { it(match.key) } },
                    selectionDescription = selectionDescription,
                    modelTagPrefix = modelTagPrefix,
                    enabled = enabled,
                )
            }
        }
    }
}

/** Search starts a new result set at the top; same-query refreshes keep the current anchor. */
@Composable
internal fun ResetLazyListOnQueryChange(
    query: String,
    contentAvailable: Boolean,
    listState: LazyListState,
) {
    val latestContentAvailable by rememberUpdatedState(contentAvailable)
    LaunchedEffect(query) {
        snapshotFlow { latestContentAvailable }
            .filter { it }
            .first()
        listState.scrollToItem(0)
    }
}

@Composable
private fun ProviderHeader(name: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 4.dp)
            .testTag("provider_header_$name"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.testTag("provider_count_$name"),
        )
    }
}

@Composable
private fun ModelPickerCatalogRow(
    match: ModelPickerMatch,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    selectionDescription: String,
    modelTagPrefix: String,
    enabled: Boolean,
) {
    Surface(
        onClick = onSelect,
        enabled = enabled,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$modelTagPrefix${match.key}")
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(match.model.name.ifBlank { match.model.id })
                    append(", ")
                    append(match.key)
                    if (selected) append(", $selectionDescription")
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        match.model.name.ifBlank { match.model.id },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (match.default) {
                        Spacer(Modifier.width(8.dp))
                        Text("default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    } else if (match.recent) {
                        Spacer(Modifier.width(8.dp))
                        Text("recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    match.key,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = buildList {
                    if (match.model.reasoning) add("reasoning")
                    match.model.contextWindow?.let { add(formatContextWindow(it)) }
                }.joinToString("  •  ")
                if (metadata.isNotBlank()) {
                    Text(
                        metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = selectionDescription, tint = MaterialTheme.colorScheme.primary)
            }
            onToggleFavorite?.let { toggle ->
                IconButton(onClick = toggle) {
                    Icon(
                        if (match.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (match.favorite) "Remove ${match.model.name} from favorites" else "Add ${match.model.name} to favorites",
                        tint = if (match.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerLoading() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(6) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f - index * 0.03f),
                        RoundedCornerShape(10.dp),
                    ),
            )
        }
    }
}

@Composable
private fun PickerError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun ModelPickerEmpty(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PickerHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatContextWindow(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M context"
    tokens >= 1_000 -> "${tokens / 1_000}K context"
    else -> "$tokens context"
}
