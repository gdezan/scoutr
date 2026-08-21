package dev.scoutr.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ── Bridge HTTP API DTOs (mirrors bridge/src/server.ts) ───────────────

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val service: String? = null,
    val version: String? = null,
    val api: ScoutrApiInfo? = null,
    val herdr: HerdrInfo? = null,
    val terminal: TerminalHealthInfo? = null,
    val push: PushInfo? = null,
)

/** Scoutr Android-to-bridge API metadata advertised by the health handshake. */
@Serializable
data class ScoutrApiInfo(
    val protocol: Int? = null,
    val features: List<String> = emptyList(),
)

/**
 * The health surface's `terminal` object. The bridge nests the cache entry one
 * level down (`terminal: { capability: … }`, `routes/health.ts`); decoding it
 * flat silently yields a null status, which reads as "supported" and leaves the
 * route reconnecting forever against a bridge that can never serve it.
 */
@Serializable
data class TerminalHealthInfo(
    val capability: TerminalCapabilityInfo? = null,
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

/** Whether the bridge can send push at all (health surface). */
@Serializable
data class PushInfo(
    val fcm: Boolean = false,
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
    val agents: List<SessionDescriptor> = emptyList(),
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

/** Durable identity for one backend-owned coding-agent transcript. */
@Serializable
data class SessionKey(
    val agentKind: String,
    val path: String,
)

/** Ephemeral Herdr attachment for a session that is currently running. */
@Serializable
data class SessionLiveAttachment(
    val paneId: String,
    val workspaceId: String,
    val tabId: String,
    val status: String,
    val statusSinceMs: Double? = null,
)

/**
 * Why a session is waiting on the user, normalized and bounded by the bridge
 * (mirrors `AttentionSummary` in bridge/src/board-detail.ts). `kind == "ask"`
 * carries the open question's ids and authored options — the same ones Chat
 * answers with; `kind == "prompt"` is a blocked pane with no structured ask,
 * where [currentQuestion] is null and the card's latest activity is the only
 * preview. The app never parses raw tool arguments to build this.
 */
@Serializable
data class AttentionSummary(
    val kind: String = "prompt",
    /** Tool call id grouping the open ask; null for a plain prompt. */
    val callId: String? = null,
    /** Unanswered questions in the open group; 0 for a plain prompt. */
    val questionCount: Int = 0,
    val currentQuestion: AttentionQuestion? = null,
    /** The bridge's verdict that one option tap submits the whole ask. */
    val canQuickAnswer: Boolean = false,
) {
    val isAsk: Boolean get() = kind == "ask"
}

/** The one open question a waiting session is showing, as the bridge normalized it. */
@Serializable
data class AttentionQuestion(
    val id: String,
    val header: String = "",
    val question: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)
/**
 * Deterministic git evidence for a Done Board card (mirrors `DoneRepoSummary` in
 * bridge/src/board-repo-summary.ts). Every field is a git fact; nothing here
 * claims tests passed or code is safe to ship.
 */
@Serializable
data class DoneRepoSummary(
    val repoRoot: String = "",
    val branch: String? = null,
    /** Upstream tracking branch when known; ahead/behind are meaningful only then. */
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    /** Union of status and diff paths, so untracked files count. */
    val changedFiles: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** Porcelain status has entries (includes untracked files). */
    val dirty: Boolean = false,
    val statusTruncated: Boolean = false,
    val diffTruncated: Boolean = false,
)

/** The one session model shared by Board, history, palette, and Chat. */
@Serializable
data class SessionDescriptor(
    val key: SessionKey? = null,
    val agentKind: String,
    val displayName: String,
    val title: String,
    val cwd: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val capabilities: List<String> = emptyList(),
    val updatedAtMs: Double? = null,
    /** Transcript revision used to order model metadata across API responses. */
    val transcriptMtimeMs: Double? = null,
    val transcriptSize: Double? = null,
    val latestActivity: String? = null,
    val attention: AttentionSummary? = null,
    /** Git evidence for Done cards; the bridge fills it only for done agents. */
    val doneSummary: DoneRepoSummary? = null,
    val live: SessionLiveAttachment? = null,
) {
    val status: String get() = live?.status ?: "done"
    val statusSinceMs: Double? get() = live?.statusSinceMs
    val active: Boolean get() = live != null
    val blocked: Boolean get() = status == "blocked"
}

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
    val size: Double = 0.0,
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
    /**
     * `skill` blocks only: the slash command that re-invokes it, spelled the
     * way its agent expects (`/skill:name` on pi, `/name` on Claude Code).
     */
    val command: String? = null,
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
            "skill" -> block.name?.let { skillInvocationPreview(it) }
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
    val needsYou: List<SessionDescriptor> = emptyList(),
    val working: List<SessionDescriptor> = emptyList(),
    val done: List<SessionDescriptor> = emptyList(),
    val idle: List<SessionDescriptor> = emptyList(),
    val unknown: List<SessionDescriptor> = emptyList(),
) {
    val total: Int get() = needsYou.size + working.size + done.size + idle.size + unknown.size
    val sessions: List<SessionDescriptor>
        get() = needsYou + working + done + idle + unknown

    companion object {
        fun group(cards: List<SessionDescriptor>): BoardState {
            val buckets = mutableMapOf<AgentStatus, MutableList<SessionDescriptor>>()
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
    val session: SessionDescriptor,
    val createdAtMs: Double = 0.0,
) {
    val key: SessionKey get() = requireNotNull(session.key) { "Catalog session is missing its canonical key" }
    /** Presentation/list key only; durable identity remains [key]. */
    val id: String get() = key.path.substringAfterLast('/').removeSuffix(".jsonl")
    val path: String get() = key.path
    val agentKind: String get() = session.agentKind
    val cwd: String get() = session.cwd.orEmpty()
    val title: String get() = session.title
    val preview: String get() = session.latestActivity.orEmpty()
    val createdAt: Double get() = createdAtMs
    val updatedAt: Double get() = session.updatedAtMs ?: 0.0
    val model: String? get() = session.model
    val live: SessionLiveAttachment? get() = session.live
    val active: Boolean get() = session.active
    val status: String get() = session.status
}

/**
 * Reply to a one-shot session command (steer, slash command, ask answer,
 * dismiss): the bridge answers `{ ok: true, ... }` and failures arrive as a
 * non-2xx BridgeException, so callers rarely read anything but `ok`.
 */
@Serializable
data class CommandResponse(
    val ok: Boolean = true,
    val paneId: String? = null,
    val callId: String? = null,
    val text: String? = null,
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

/** Working-tree file content returned by the active-agent workspace viewer. */
@Serializable
data class FileReadResponse(
    val ok: Boolean = true,
    val content: String = "",
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val exists: Boolean = true,
    val offset: Long = 0,
    val nextOffset: Long? = null,
    val totalBytes: Long? = null,
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

// ── Self-update DTOs (GET/POST /api/update/*) ─────────────────────────

@Serializable
data class UpdateStatusResponse(
    val ok: Boolean = true,
    val host: UpdateIdentity = UpdateIdentity(),
    val installed: UpdateInstalled = UpdateInstalled(),
    val updateAvailable: Boolean = false,
)

@Serializable
data class UpdateIdentity(
    val version: String = "",
    val versionCode: Int = 0,
    val commit: String = "",
    val dirty: Boolean = false,
    val buildTime: String = "",
)

@Serializable
data class UpdateInstalled(
    val version: String = "",
    val commit: String = "",
    val dirty: Boolean = false,
)

/**
 * A host-side APK build. The phone starts one, polls [state] until it leaves
 * "building", then downloads [apk] and installs it itself — no adb involved.
 * [state] is one of idle / building / ready / failed.
 */
@Serializable
data class ApkBuild(
    val state: String = "idle",
    val buildId: Int = 0,
    val error: String? = null,
    val apk: ApkArtifact? = null,
)

/** The built file's size and hash, plus the identity gradle stamped into it. */
@Serializable
data class ApkArtifact(
    val size: Long = 0,
    val sha256: String = "",
    val commit: String = "",
    val version: String = "",
    val versionCode: Int = 0,
)

@Serializable
data class UpdateBuildResponse(
    val ok: Boolean = true,
    val build: ApkBuild = ApkBuild(),
)

@Serializable
data class UpdateApkStatusResponse(
    val ok: Boolean = true,
    val host: UpdateIdentity = UpdateIdentity(),
    val build: ApkBuild = ApkBuild(),
)
