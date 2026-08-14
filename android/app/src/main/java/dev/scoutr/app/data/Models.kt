package dev.scoutr.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ── Bridge HTTP API DTOs (mirrors bridge/src/server.ts) ───────────────

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String? = null,
    val version: String? = null,
    val herdr: HerdrInfo? = null,
    val terminal: TerminalCapabilityInfo? = null,
    val ntfy: NtfyInfo? = null,
)

/** The bridge's terminal capability cache entry (health surface). */
@Serializable
data class TerminalCapabilityInfo(
    val status: String? = null,
    val herdrVersion: String? = null,
    val protocol: Int? = null,
    val installedVersion: String? = null,
    val required: String? = null,
    val reason: String? = null,
) {
    /**
     * Only a settled `unsupported` blocks the terminal route. `unverified`
     * (the bridge probed before herdr had a pane) is provisional: the bridge
     * re-probes on the next `/ws/terminal` upgrade and answers a non-101 with
     * the reason if it turns out to be unsupported, so the app must not
     * pre-emptively refuse to connect on it.
     */
    val isUnsupported: Boolean get() = status == "unsupported"
}

@Serializable
data class NtfyInfo(
    val url: String? = null,
    val topic: String? = null,
)

@Serializable
data class HerdrInfo(
    val connected: Boolean = false,
    val version: String? = null,
    val protocol: Int? = null,
)

@Serializable
data class AgentsResponse(
    val ok: Boolean = true,
    val agents: List<AgentCard> = emptyList(),
)

/** Registry of available agent backends (GET /api/agents/kinds). */
@Serializable
data class AgentKindsResponse(
    val ok: Boolean = true,
    val kinds: List<AgentKindInfo> = emptyList(),
    val error: String? = null,
)

@Serializable
data class AgentKindInfo(
    val id: String,
    val displayName: String,
    val capabilities: List<String> = emptyList(),
    val hasModelCatalog: Boolean = false,
    val hasSlashCommands: Boolean = false,
) {
    /** Derived: the app only offers thinking-level controls when the backend supports them. */
    val supportsThinking: Boolean get() = "set_thinking" in capabilities
}

@Serializable
data class AgentCard(
    val paneId: String,
    val workspaceId: String,
    val tabId: String,
    val agent: String,
    /** Registry backend id (same as `agent` for known backends). */
    val agentKind: String = agent,
    /** Human-readable backend name (e.g. "Claude Code"). Null when unknown. */
    val displayName: String? = null,
    /**
     * Control actions the backend supports; the app gates its menus on this.
     * Null when the bridge omitted it (older bridge or unknown agent).
     */
    val capabilities: List<String>? = null,
    val status: String,
    val cwd: String? = null,
    val title: String? = null,
    val terminalTitle: String? = null,
    val sessionPath: String? = null,
    /** Epoch ms when the agent entered its current status (bridge-stamped). */
    val statusSinceMs: Double? = null,
    /** Active model from the session file (bounded tail read). */
    val model: String? = null,
    /** Latest meaningful transcript line (bounded). */
    val latestActivity: String? = null,
    /** Epoch ms of the latest activity record. */
    val latestActivityAtMs: Double? = null,
) {
    /** Derived: blocked agents are the ones that need the user. */
    val blocked: Boolean get() = status == "blocked"
}

/** A message delivered by the self-hosted ntfy server (layer 5 push). */
@Serializable
data class NtfyMessage(
    val id: String,
    val time: Long = 0,
    val event: String = "message",
    val topic: String = "",
    val title: String? = null,
    val message: String? = null,
    val priority: Int = 3,

    /** Bridge pane id, carried so a pushed event can deep-link to the session. */
    val paneId: String? = null,
    /** ntfy 'click' URL (survives ntfy storage, unlike custom fields): scoutr://chat/<paneId>. */
    val click: String? = null,
)

// ── Bridge WS DTOs ────────────────────────────────────────────────────

/** Top-level frames the bridge sends on /ws: {"type":"feed"|"pong"|..., "payload":...} */
@Serializable
data class WsFrame(
    val type: String,
    val payload: FeedMessage? = null,
    val ts: Double? = null,  // tolerant: bridge may emit fractional millis
    val target: String? = null,
    val paneId: String? = null,
    val text: String? = null,
    val error: String? = null,
)

/** A herdr feed message forwarded by the bridge (event or snapshot). */
@Serializable
data class FeedMessage(
    val type: String? = null, // "snapshot" for snapshots
    val kind: String? = null, // event kind, e.g. "pane_agent_status_changed"
    val data: JsonObject? = null,
    val snapshot: JsonObject? = null,
    val resync: Boolean? = null,
)

@Serializable
data class SessionReadResponse(
    val ok: Boolean = true,
    val path: String = "",
    val agentKind: String? = null,
    val name: String = "",
    val exists: Boolean = false,
    val since: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val entries: List<SessionEntry> = emptyList(),
    val questions: List<QuestionEntry> = emptyList(),
    val preview: String? = null,
    val lastEntryId: String? = null,
    // JS Date.now() returns a float; keep Double so fractional millis parse.
    val mtimeMs: Double = 0.0,
)

/** Structured ask_user_question card derived by the bridge from session events. */
@Serializable
data class QuestionEntry(
    val id: String,
    /** Tool call id this question came from; groups a multi-question ask. */
    val callId: String = "",
    /** Transcript entry id that made the call; the card's list position. */
    val entryId: String = "",
    val question: String = "",
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val answered: Boolean = false,
    val answerText: String? = null,
    val selected: List<String> = emptyList(),
    val timestamp: String = "",
)

@Serializable
data class QuestionOption(
    val label: String = "",
    val description: String = "",
)


@Serializable
data class SessionEntry(
    val entryId: String,
    val parentId: String? = null,
    val timestamp: String = "",
    val role: String = "",
    val content: List<ContentBlock> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean? = null,
    val stopReason: String? = null,
    val model: String? = null,
    val usage: EntryUsage? = null,
)

@Serializable
data class ContentBlock(
    val type: String = "unknown",
    val text: String? = null,
    val thinking: String? = null,
    val id: String? = null,
    val name: String? = null,
    /** Tool-call arguments as sent by the agent (e.g. {command: "..."} for bash). */
    val arguments: JsonObject? = null,
    /**
     * `fileEdit` blocks only: the file change the bridge normalized out of
     * whichever patch the agent's CLI wrote. Sits on the tool-result entry.
     */
    val path: String? = null,
    val changeKind: String? = null,
    val added: Int = 0,
    val removed: Int = 0,
    val hunks: List<FileEditHunk> = emptyList(),
    /** The diff exceeded the bridge's inline caps; [hunks] holds its head. */
    val truncated: Boolean = false,
)

/** One run of unified-diff lines, each prefixed with ` `, `+`, or `-`. */
@Serializable
data class FileEditHunk(
    /** `@@ -a,b +c,d @@`, or null when the agent reports no line numbers. */
    val header: String? = null,
    val lines: List<String> = emptyList(),
)

@Serializable
data class EntryUsage(
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val totalTokens: Long? = null,
    val cost: JsonObject? = null,
)

@Serializable
data class UsageResponse(
    val ok: Boolean = true,
    val usage: List<UsageSnapshot> = emptyList(),
)

@Serializable
data class UsageSnapshot(
    val provider: String = "",
    val label: String = "",
    val windows: List<UsageWindow> = emptyList(),
    val updatedAt: Long = 0,
    val error: String? = null,
)

@Serializable
data class UsageWindow(
    val label: String = "",
    val usedPercent: Double = 0.0,
    val amount: Double? = null,
    val limitAmount: Double? = null,
    val currency: String? = null,
    val windowSeconds: Long? = null,
    val resetAt: Long? = null,
)

// ── Board grouping (pure logic, unit-testable) ────────────────────────

/** Plain-text rendering of an entry's content blocks (mirrors the bridge's entryText). */
fun entryText(content: List<ContentBlock>): String {
    val parts = content.mapNotNull { block ->
        when (block.type) {
            "text" -> block.text
            "toolCall" -> "[${block.name}]"
            else -> null
        }
    }
    return parts.joinToString("\n").replace(Regex("\\s+"), " ").trim()
}

enum class AgentStatus(val wireName: String) {
    NeedsYou("blocked"),
    Working("working"),
    Done("done"),
    Idle("idle"),
    Unknown("unknown");

    companion object {
        fun fromWire(name: String): AgentStatus = when (name) {
            "completed" -> Done
            else -> entries.firstOrNull { it.wireName == name } ?: Unknown
        }
    }
}

data class BoardState(
    val needsYou: List<AgentCard> = emptyList(),
    val working: List<AgentCard> = emptyList(),
    val done: List<AgentCard> = emptyList(),
    val idle: List<AgentCard> = emptyList(),
    val unknown: List<AgentCard> = emptyList(),
) {
    val total: Int get() = needsYou.size + working.size + done.size + idle.size + unknown.size

    companion object {
        fun group(cards: List<AgentCard>): BoardState {
            val buckets = mutableMapOf<AgentStatus, MutableList<AgentCard>>()
            for (card in cards) {
                buckets.getOrPut(AgentStatus.fromWire(card.status), ::mutableListOf).add(card)
            }
            return BoardState(
                needsYou = buckets[AgentStatus.NeedsYou]?.toList() ?: emptyList(),
                working = buckets[AgentStatus.Working]?.toList() ?: emptyList(),
                done = buckets[AgentStatus.Done]?.toList() ?: emptyList(),
                idle = buckets[AgentStatus.Idle]?.toList() ?: emptyList(),
                unknown = buckets[AgentStatus.Unknown]?.toList() ?: emptyList(),
            )
        }
    }
}

// ── Sessions v2 (layer 3): folder + model pickers, pane-native creation ──

@Serializable
data class DirListingResponse(
    val ok: Boolean,
    val listing: DirListing? = null,
    val error: String? = null,
)

@Serializable
data class DirListing(
    val path: String,
    val dirs: List<String>,
)

/** Candidate paths for the composer's `@` mention completion. */
@Serializable
data class FileListingResponse(
    val ok: Boolean,
    val listing: FileListing? = null,
    val error: String? = null,
)

@Serializable
data class FileListing(
    val path: String,
    /** Slash-separated paths relative to [path]; directories are derived, not listed. */
    val files: List<String>,
    val truncated: Boolean = false,
)

@Serializable
data class ModelsCatalogResponse(
    val ok: Boolean,
    val catalog: ModelsCatalog? = null,
    val error: String? = null,
)

@Serializable
data class ModelsCatalog(
    val providers: List<ModelProvider>,
)

@Serializable
data class ModelProvider(
    val name: String,
    val models: List<ModelInfo>,
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String = "",
    val provider: String = "",
    val reasoning: Boolean = false,
    val thinkingLevels: List<String> = emptyList(),
    val contextWindow: Long? = null,
)

@Serializable
data class CommandsCatalogResponse(
    val ok: Boolean,
    val catalog: CommandsCatalog? = null,
    val error: String? = null,
)

@Serializable
data class CommandsCatalog(
    val commands: List<SlashCommandInfo>,
)

@Serializable
data class SlashCommandInfo(
    val name: String,
    val description: String,
    val source: String,
    val argumentHint: String? = null,
)

@Serializable
data class CreatedSessionResponse(
    val ok: Boolean,
    val workspaceId: String? = null,
    val paneId: String? = null,
    val error: String? = null,
)

@Serializable
data class SessionCatalogResponse(
    val ok: Boolean,
    val sessions: List<SessionCatalogItem> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SessionCatalogItem(
    val id: String,
    val path: String,
    val agentKind: String = "pi",
    val cwd: String,
    val title: String,
    val preview: String = "",
    val createdAt: Double = 0.0,
    val updatedAt: Double,
    val model: String? = null,
    val active: Boolean = false,
    val paneId: String? = null,
    val workspaceId: String? = null,
    val status: String? = null,
)

@Serializable
data class ControlResponse(
    val ok: Boolean,
    val error: String? = null,
)

@Serializable
data class RepoOverviewResponse(
    val ok: Boolean = true,
    val path: String = "",
    val root: String = "",
    val branch: String? = null,

    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val status: List<RepoStatusEntry> = emptyList(),
    val statusTruncated: Boolean = false,
    val log: List<RepoCommit> = emptyList(),
    val logTruncated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class RepoStatusEntry(
    val code: String = "",
    val path: String = "",
)

@Serializable
data class RepoCommit(
    val hash: String = "",
    val subject: String = "",
    val author: String = "",
    val date: Long = 0,
    val body: String = "",
)

@Serializable
data class RepoDiffResponse(
    val ok: Boolean = true,
    val stat: List<RepoDiffFileStat> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class RepoDiffFileStat(
    val path: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
data class RepoFileDiffResponse(
    val ok: Boolean = true,
    val diff: String = "",
    val truncated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class RepoFileResponse(
    val ok: Boolean = true,
    val content: String = "",
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val exists: Boolean = true,
    val error: String? = null,
)

@Serializable
data class RepoArtifactsResponse(
    val ok: Boolean = true,
    val artifacts: List<RepoArtifact> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class AttachmentResponse(
    val ok: Boolean = true,
    val path: String = "",
    val error: String? = null,
)

@Serializable
data class RepoArtifact(
    val path: String = "",
    val size: Long = 0,
    val mtimeMs: Double = 0.0,
)
