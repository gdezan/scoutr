package dev.cockpit.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Slice 6 wire/domain types for GET /api/snapshot — the terminal hierarchy
 * source (design contract in .plans/full-screen-interactive-terminal.md).
 *
 * The bridge answers `{ok:true, snapshot: SessionSnapshot}` where
 * SessionSnapshot is the herdr snapshot shape (snake_case). The domain
 * [TerminalSnapshot] is a deliberately small projection: pane ids, tab and
 * workspace membership, focus, and the fields that resolve a pane's display
 * name (label → live OSC title → foreground cwd/cwd → id, slice 7 drawer).
 */
@Serializable
data class SnapshotResponse(
    val ok: Boolean = true,
    val snapshot: JsonObject? = null,
    val error: String? = null,
)

@Serializable
data class TerminalSnapshot(
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val panes: List<TerminalPane> = emptyList(),
) {
    fun pane(id: String): TerminalPane? = panes.firstOrNull { it.paneId == id }

    /** The pane herdr currently focuses, if the snapshot lists it. */
    fun focusedPane(): TerminalPane? = focusedPaneId?.let(::pane)
}

@Serializable
data class TerminalPane(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean = false,
    val label: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
) {
    /**
     * Settled name precedence (plan "Hierarchy UX"): user-assigned herdr
     * label, live OSC title, foreground cwd/cwd, then the pane id.
     */
    val displayName: String
        get() = label
            ?: terminalTitle
            ?: foregroundCwd
            ?: cwd
            ?: paneId
}

/** Decodes the snapshot payload embedded in [SnapshotResponse] (or any JsonElement). */
fun JsonElement.toTerminalSnapshot(): TerminalSnapshot =
    Json { ignoreUnknownKeys = true }.decodeFromJsonElement(TerminalSnapshot.serializer(), this)
