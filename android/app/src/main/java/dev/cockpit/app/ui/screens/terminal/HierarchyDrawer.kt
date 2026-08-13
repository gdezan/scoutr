package dev.cockpit.app.ui.screens.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cockpit.app.data.DirListingResponse
import dev.cockpit.app.data.TerminalPane
import dev.cockpit.app.data.TerminalSnapshot
import dev.cockpit.app.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * Slice 7 hierarchy drawer: a button-only modal side drawer (edge-swipe
 * opening disabled, never resizes the grid), searchable, with a collapsible
 * workspace -> tab -> pane tree. Panes use the settled display-name
 * precedence (label -> OSC title -> cwd -> id); workspaces and tabs have no
 * snapshot names, so they show their short id in mono. Create offers New tab
 * in the active workspace and New workspace (server-side directory browse +
 * optional name); rename and close work on any listed pane/tab/workspace.
 * Closing names the target and shows the exact pane termination count; a
 * stale count (bridge 409) refreshes the snapshot and the user confirms
 * again. Post-close selection is decided server-side ([onResult]).
 */
@Composable
internal fun HierarchyDrawer(
    snapshot: TerminalSnapshot?,
    busy: Boolean,
    error: String?,
    activePaneId: String?,
    dirs: (suspend (String?) -> DirListingResponse)?,
    onCreateTab: (workspaceId: String) -> Unit,
    onCreateWorkspace: (cwd: String, label: String?) -> Unit,
    onRenamePane: (paneId: String, label: String) -> Unit,
    onRenameTab: (tabId: String, label: String) -> Unit,
    onRenameWorkspace: (workspaceId: String, label: String) -> Unit,
    onClosePane: (paneId: String) -> Unit,
    onCloseTab: (tabId: String, expectedPaneCount: Int) -> Unit,
    onCloseWorkspace: (workspaceId: String, expectedPaneCount: Int) -> Unit,
    onResult: (selectedPaneId: String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var dialog by remember { mutableStateOf<DrawerDialog>(DrawerDialog.None) }

    val panes = snapshot?.panes.orEmpty()
    val workspaces = panes.groupBy { it.workspaceId }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Terminal panes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Create, rename and close panes, tabs and workspaces.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onResult(null) }) {
                Icon(Icons.Default.Close, contentDescription = "Close hierarchy")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search panes…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    val target = workspaces.keys.firstOrNull { ws ->
                        panes.any { it.workspaceId == ws && it.paneId == activePaneId }
                    } ?: workspaces.keys.firstOrNull()
                    if (target != null) dialog = DrawerDialog.NewTab(target)
                },
                enabled = !busy && workspaces.isNotEmpty(),
            ) { Text("New tab") }
            TextButton(onClick = { dialog = DrawerDialog.NewWorkspace }, enabled = !busy) { Text("New workspace") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        if (busy) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            workspaces.forEach { (workspaceId, panesInWorkspace) ->
                val matching = panesInWorkspace.filter { matches(it, query) }
                if (matching.isEmpty()) return@forEach
                val isExpanded = workspaceId in expanded
                item(key = "ws-$workspaceId") {
                    WorkspaceRow(
                        label = shortId(workspaceId),
                        paneCount = panesInWorkspace.size,
                        isExpanded = isExpanded,
                        isActive = panesInWorkspace.any { it.paneId == activePaneId },
                        onToggle = {
                            expanded = if (isExpanded) expanded - workspaceId else expanded + workspaceId
                        },
                        onRename = { dialog = DrawerDialog.RenameWorkspace(workspaceId, shortId(workspaceId)) },
                        onClose = { dialog = DrawerDialog.CloseWorkspace(workspaceId, shortId(workspaceId), panesInWorkspace.size) },
                        onNewTab = { dialog = DrawerDialog.NewTab(workspaceId) },
                    )
                }
                if (isExpanded) {
                    panesInWorkspace.groupBy { it.tabId }.forEach { (tabId, panesInTab) ->
                        val tabMatches = panesInTab.filter { matches(it, query) }
                        if (tabMatches.isEmpty()) return@forEach
                        item(key = "tab-$tabId") {
                            TabRow(
                                label = shortId(tabId),
                                paneCount = panesInTab.size,
                                isActive = panesInTab.any { it.paneId == activePaneId },
                                onRename = { dialog = DrawerDialog.RenameTab(tabId, shortId(tabId)) },
                                onClose = { dialog = DrawerDialog.CloseTab(tabId, shortId(tabId), panesInTab.size) },
                            )
                        }
                        items(tabMatches, key = { "pane-${it.paneId}" }) { pane ->
                            PaneRow(
                                pane = pane,
                                isActive = pane.paneId == activePaneId,
                                onRename = { dialog = DrawerDialog.RenamePane(pane.paneId, pane.displayName) },
                                onClose = { dialog = DrawerDialog.ClosePane(pane.paneId, pane.displayName) },
                                onSelect = { onResult(pane.paneId) },
                            )
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is DrawerDialog.NewTab -> TextInputDialog(
            title = "New tab",
            label = "Workspace",
            initial = shortId(d.workspaceId),
            confirmLabel = "Create",
            onConfirm = { onCreateTab(d.workspaceId); dialog = DrawerDialog.None },
            onDismiss = { dialog = DrawerDialog.None },
        )
        DrawerDialog.NewWorkspace -> NewWorkspaceDialog(
            dirs = dirs,
            onConfirm = { cwd, label -> onCreateWorkspace(cwd, label); dialog = DrawerDialog.None },
            onDismiss = { dialog = DrawerDialog.None },
        )
        is DrawerDialog.RenamePane -> TextInputDialog(
            title = "Rename pane",
            label = "New name (blank to clear)",
            initial = d.current,
            confirmLabel = "Rename",
            onConfirm = { onRenamePane(d.paneId, it); dialog = DrawerDialog.None },
            onDismiss = { dialog = DrawerDialog.None },
        )
        is DrawerDialog.RenameTab -> TextInputDialog(
            title = "Rename tab",
            label = "New name (blank to clear)",
            initial = d.current,
            confirmLabel = "Rename",
            onConfirm = { onRenameTab(d.tabId, it); dialog = DrawerDialog.None },
            onDismiss = { dialog = DrawerDialog.None },
        )
        is DrawerDialog.RenameWorkspace -> TextInputDialog(
            title = "Rename workspace",
            label = "New name (blank to clear)",
            initial = d.current,
            confirmLabel = "Rename",
            onConfirm = { onRenameWorkspace(d.workspaceId, it); dialog = DrawerDialog.None },
            onDismiss = { dialog = DrawerDialog.None },
        )
        is DrawerDialog.ClosePane -> ConfirmDialog(
            title = "Close pane?",
            text = "\"${d.label}\" will be terminated. Its shell process is killed.",
            confirmLabel = "Close pane",
            destructive = true,
            onConfirm = {
                onClosePane(d.paneId)
                dialog = DrawerDialog.None
            },
            onDismiss = { dialog = DrawerDialog.None },
        )
        is DrawerDialog.CloseTab -> ConfirmDialog(
            title = "Close tab?",
            text = "\"${d.label}\" holds ${d.paneCount} pane${if (d.paneCount == 1) "" else "s"} — all will be terminated.",
            confirmLabel = "Close tab",
            destructive = true,
            onConfirm = {
                onCloseTab(d.tabId, d.paneCount)
                dialog = DrawerDialog.None
            },
            onDismiss = { dialog = DrawerDialog.None },
        )
        is DrawerDialog.CloseWorkspace -> ConfirmDialog(
            title = "Close workspace?",
            text = "\"${d.label}\" holds ${d.paneCount} pane${if (d.paneCount == 1) "" else "s"} — all will be terminated.",
            confirmLabel = "Close workspace",
            destructive = true,
            onConfirm = {
                onCloseWorkspace(d.workspaceId, d.paneCount)
                dialog = DrawerDialog.None
            },
            onDismiss = { dialog = DrawerDialog.None },
        )
        DrawerDialog.None -> Unit
    }
}

/** Search matches the settled pane name or the pane id. */
private fun matches(pane: TerminalPane, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return pane.displayName.contains(q, ignoreCase = true) ||
        pane.paneId.contains(q, ignoreCase = true)
}

/** Workspaces/tabs have no snapshot names; show the id tail as a mono handle. */
private fun shortId(id: String): String = id.takeLast(12)

private sealed interface DrawerDialog {
    data object None : DrawerDialog
    data class NewTab(val workspaceId: String) : DrawerDialog
    data object NewWorkspace : DrawerDialog
    data class RenamePane(val paneId: String, val current: String) : DrawerDialog
    data class RenameTab(val tabId: String, val current: String) : DrawerDialog
    data class RenameWorkspace(val workspaceId: String, val current: String) : DrawerDialog
    data class ClosePane(val paneId: String, val label: String) : DrawerDialog
    data class CloseTab(val tabId: String, val label: String, val paneCount: Int) : DrawerDialog
    data class CloseWorkspace(val workspaceId: String, val label: String, val paneCount: Int) : DrawerDialog
}

@Composable
private fun WorkspaceRow(
    label: String,
    paneCount: Int,
    isExpanded: Boolean,
    isActive: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onNewTab: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$paneCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowActions(
            actions = listOf(
                "Rename" to onRename,
                "New tab" to onNewTab,
                "Close" to onClose,
            ),
        )
    }
}

@Composable
private fun TabRow(
    label: String,
    paneCount: Int,
    isActive: Boolean,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$paneCount", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowActions(
            actions = listOf(
                "Rename" to onRename,
                "Close" to onClose,
            ),
        )
    }
}

@Composable
private fun PaneRow(
    pane: TerminalPane,
    isActive: Boolean,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 44.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pane.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            pane.paneId.takeLast(6),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowActions(
            actions = listOf(
                "Rename" to onRename,
                "Close" to onClose,
            ),
        )
    }
}

@Composable
private fun RowActions(actions: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * New-workspace dialog: optional name plus a working directory chosen by
 * browsing the bridge's server-side listing ([dirs]), with a manual path
 * fallback. The server's cwd takes precedence for the pane working directory
 * (plan "Hierarchy UX").
 */
@Composable
private fun NewWorkspaceDialog(
    dirs: (suspend (String?) -> DirListingResponse)?,
    onConfirm: (cwd: String, label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var subfolders by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun browse(from: String?) {
        val api = dirs ?: return
        scope.launch {
            loading = true
            browseError = null
            try {
                val response = api(from)
                if (response.ok && response.listing != null) {
                    path = response.listing.path
                    subfolders = response.listing.dirs
                } else {
                    browseError = response.error ?: "Could not list folders"
                }
            } catch (e: Exception) {
                browseError = e.message ?: "Could not list folders"
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { browse(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Working directory") },
                    placeholder = { Text("e.g. ~/projects") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { browse(path.ifBlank { null }) },
                        enabled = !loading,
                    ) { Text(if (loading) "Loading…" else "Browse") }
                    if (path.isNotBlank()) {
                        TextButton(onClick = { browse(null) }) { Text("Home") }
                    }
                }
                if (browseError != null) {
                    Text(browseError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (subfolders.isNotEmpty()) {
                    Text("Subfolders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.height(140.dp)) {
                        items(subfolders) { folder ->
                            Row(
                                Modifier.fillMaxWidth().clickable { browse("${path.trimEnd('/')}/$folder") }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(folder, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path.trim(), label.trim().ifBlank { null }) }, enabled = path.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
