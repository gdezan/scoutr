package dev.scoutr.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Slice 4 bridge contract: POST /api/terminal/hierarchy (design contract in
 * .plans/full-screen-interactive-terminal.md). One serializable class with
 * optional operation-specific fields — kotlinx omits nulls (encodeDefaults is
 * false), so the wire body carries exactly `operation` plus the fields the
 * operation needs.
 *
 * `selectedPaneId` is the app's selection at tap/confirmation time. It only
 * steers the bridge's cwd fallback and post-mutation selection; it never
 * moves herdr desktop focus.
 */
@Serializable
data class TerminalHierarchyCommand(
    val operation: String,
    val workspaceId: String? = null,
    val cwd: String? = null,
    val label: String? = null,
    val paneId: String? = null,
    val tabId: String? = null,
    val expectedPaneCount: Int? = null,
    val selectedPaneId: String? = null,
) {
    companion object {
        fun createTab(workspaceId: String, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "create_tab", workspaceId = workspaceId, selectedPaneId = selectedPaneId)

        fun createWorkspace(cwd: String, label: String? = null, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "create_workspace", cwd = cwd, label = label, selectedPaneId = selectedPaneId)

        fun renamePane(paneId: String, label: String, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "rename_pane", paneId = paneId, label = label, selectedPaneId = selectedPaneId)

        fun renameTab(tabId: String, label: String, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "rename_tab", tabId = tabId, label = label, selectedPaneId = selectedPaneId)

        fun renameWorkspace(workspaceId: String, label: String, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "rename_workspace", workspaceId = workspaceId, label = label, selectedPaneId = selectedPaneId)

        fun closePane(paneId: String, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "close_pane", paneId = paneId, selectedPaneId = selectedPaneId)

        fun closeTab(tabId: String, expectedPaneCount: Int, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "close_tab", tabId = tabId, expectedPaneCount = expectedPaneCount, selectedPaneId = selectedPaneId)

        fun closeWorkspace(workspaceId: String, expectedPaneCount: Int, selectedPaneId: String? = null) =
            TerminalHierarchyCommand(operation = "close_workspace", workspaceId = workspaceId, expectedPaneCount = expectedPaneCount, selectedPaneId = selectedPaneId)
    }
}

/**
 * Result of a hierarchy mutation: the deterministic next selection plus the
 * fresh herdr snapshot (same shape as GET /api/snapshot). When a close's pane
 * count changed the bridge answers 409 with {ok:false, error, id, name,
 * count, expectedPaneCount} instead — BridgeClient surfaces that as
 * BridgeException(409), and the caller refreshes and re-asks.
 */
@Serializable
data class TerminalHierarchyResponse(
    val ok: Boolean,
    val selectedPaneId: String? = null,
    val snapshot: JsonObject? = null,
    val error: String? = null,
)
