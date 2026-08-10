package dev.cockpit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cockpit.app.CockpitApp
import dev.cockpit.app.ui.components.CockpitTextField
import dev.cockpit.app.state.CommandPaletteViewModel
import dev.cockpit.app.state.PaletteResult
import dev.cockpit.app.state.PaletteResultKind

/**
 * Global command palette: search live agents and stored sessions from one
 * field, then open (chat / steer), abort, close, or resume. Rendered as a
 * full-screen dialog above the top-level routes.
 */
@Composable
fun CommandPalette(
    viewModel: CommandPaletteViewModel,
    onOpenAgent: (paneId: String, sessionPath: String?) -> Unit,
    onOpenSession: (paneId: String, sessionPath: String?) -> Unit,

    onDismiss: () -> Unit = viewModel::close,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                .testTag("command_palette"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                CockpitTextField(
                    value = ui.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search agents and sessions…",
                    leadingIcon = Icons.Default.Search,
                    trailingIcon = {
                        IconButton(
                            onClick = { if (ui.query.isNotBlank()) viewModel.clearQuery() else viewModel.close() },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("palette_search"),
                )
            }
            HorizontalDivider()
            if (ui.error != null) {
                Text(
                    ui.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (ui.loading && ui.results.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (ui.results.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (ui.query.isBlank()) "No agents running" else "No matches",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.results, key = { "${it.kind}-${it.paneId ?: it.sessionPath}" }) { result ->
                        PaletteRow(
                            result = result,
                            busy = ui.busyPath == result.sessionPath || ui.busyPaneId == result.paneId,
                            onOpen = {
                                when (result.kind) {
                                    PaletteResultKind.Agent -> {
                                        viewModel.openResult(result) {
                                            onOpenAgent(result.paneId ?: "", result.sessionPath)
                                        }
                                    }
                                    PaletteResultKind.Session -> {
                                        viewModel.openResult(result) {
                                            onOpenSession(result.paneId ?: "", result.sessionPath)
                                        }
                                    }
                                }
                            },
                            onResume = { viewModel.resume(result.sessionPath ?: "") },
                            onAbort = { viewModel.control(result.paneId ?: "", "abort") },
                            onClose = { viewModel.control(result.paneId ?: "", "close") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(
    result: PaletteResult,
    busy: Boolean,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onAbort: () -> Unit,
    onClose: () -> Unit,
) {
    val isAgent = result.kind == PaletteResultKind.Agent
    val running = isAgent
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isAgent) Icons.Default.SmartToy else Icons.Default.History,
            contentDescription = null,
            tint = if (isAgent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.width(18.dp).height(18.dp),
                strokeWidth = 2.dp,
            )
        } else if (running) {
            TextButton(onClick = onAbort) { Text("Abort") }
            TextButton(onClick = onClose) { Text("Close") }
        } else {
            TextButton(onClick = onResume) { Text("Resume") }
        }
    }
}

@Composable
fun rememberCommandPaletteViewModel(): CommandPaletteViewModel {
    val app = LocalContext.current.applicationContext as CockpitApp
    return androidx.lifecycle.viewmodel.compose.viewModel(
        factory = CommandPaletteViewModel.factory(app.container.bridge, app.container.connectionStore),
    )
}
