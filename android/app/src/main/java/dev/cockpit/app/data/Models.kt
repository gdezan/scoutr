package dev.cockpit.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ── Bridge HTTP API DTOs (mirrors bridge/src/server.ts) ───────────────

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String? = null,
    val version: String? = null,
    val herdr: HerdrInfo? = null,
    val ntfy: NtfyInfo? = null,
)

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

@Serializable
data class AgentCard(
    val paneId: String,
    val workspaceId: String,
    val tabId: String,
    val agent: String,
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
data class LiveOutputResponse(
    val ok: Boolean = true,
    val output: LiveOutputSnapshot? = null,
    val error: String? = null,
)

@Serializable
data class LiveOutputSnapshot(
    val paneId: String,
    val text: String = "",
    val revision: Long = 0,
    val truncated: Boolean = false,
    val lineLimit: Int = 0,
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
        fun fromWire(name: String): AgentStatus = entries.firstOrNull { it.wireName == name } ?: Unknown
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
    val path: String,
    val sessionId: String,
    val title: String,
    val cwd: String,
    val model: String? = null,
    val updatedAt: Double,
    val messageCount: Int,
    val preview: String = "",
    val active: Boolean = false,
    val paneId: String? = null,
    val workspaceId: String? = null,
    val agentStatus: String? = null,
)

@Serializable
data class ControlResponse(
    val ok: Boolean,
    val error: String? = null,
)
