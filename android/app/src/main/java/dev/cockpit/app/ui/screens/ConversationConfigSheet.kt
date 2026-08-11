package dev.cockpit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import dev.cockpit.app.ui.components.SectionLabel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cockpit.app.state.ChatUiState
import dev.cockpit.app.state.ModelPickerFilters
import dev.cockpit.app.state.searchModelCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationConfigSheet(
    ui: ChatUiState,
    onSelectModel: (String) -> Unit,
    onSelectThinking: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val models = remember(ui.modelProviders, query, ui.model) {
        searchModelCatalog(
            providers = ui.modelProviders,
            filters = ModelPickerFilters(query = query),
            selectedKey = ui.model,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("conversation_config_sheet"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Conversation setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Changes apply to the next turn",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.configurationLoading) {
                    CircularProgressIndicator(Modifier.padding(12.dp).width(20.dp), strokeWidth = 2.dp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close conversation setup")
                }
            }

            if (ui.canSetThinking) {
                SectionLabel("Thinking level", Modifier.padding(horizontal = 20.dp))
                Text(
                    ui.thinkingLevel?.replaceFirstChar(Char::uppercase) ?: "Not reported yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (ui.availableThinkingLevels.isEmpty()) {
                    Text(
                        "Thinking options appear after the active model is loaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("thinking_level_options"),
                    ) {
                        items(ui.availableThinkingLevels, key = { it }) { level ->
                            FilterChip(
                                selected = level == ui.thinkingLevel,
                                enabled = !ui.configurationLoading,
                                onClick = { onSelectThinking(level) },
                                label = { Text(level.replaceFirstChar(Char::uppercase)) },
                                leadingIcon = if (level == ui.thinkingLevel) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                modifier = Modifier.testTag("thinking_level_$level"),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))

            SectionLabel("Model", Modifier.padding(horizontal = 20.dp))
            Text(
                ui.model ?: "Not reported yet",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            // A catalog-less backend (claude) has no pickable models; the
            // value line stays informative, the dead search does not render.
            if (ui.modelProviders.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search provider or model") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("conversation_model_search"),
            )

            when {
                ui.configurationError != null -> {
                    Text(
                        ui.configurationError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                models.isEmpty() -> Box(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No models match", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("conversation_model_list"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(models, key = { it.key }) { match ->
                        val selected = match.key == ui.model
                        Surface(
                            onClick = { onSelectModel(match.key) },
                            enabled = !ui.configurationLoading,
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else Color.Transparent,
                            modifier = Modifier.fillMaxWidth().testTag("conversation_model_${match.model.id}"),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        match.model.name.ifBlank { match.model.id },
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        match.key,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Check, contentDescription = "Current model", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
