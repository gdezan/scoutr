package dev.scoutr.app.net

import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.AgentKindsResponse
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.AttachmentResponse
import dev.scoutr.app.data.CommandsCatalogResponse
import dev.scoutr.app.data.ControlResponse
import dev.scoutr.app.data.CreatedSessionResponse
import dev.scoutr.app.data.DirListingResponse
import dev.scoutr.app.data.FileListingResponse
import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.ModelsCatalogResponse
import dev.scoutr.app.data.RepoArtifactsResponse
import dev.scoutr.app.data.RepoDiffResponse
import dev.scoutr.app.data.RepoFileDiffResponse
import dev.scoutr.app.data.RepoFileResponse
import dev.scoutr.app.data.RepoOverviewResponse
import dev.scoutr.app.data.SessionCatalogResponse
import dev.scoutr.app.data.SessionReadResponse
import dev.scoutr.app.data.SnapshotResponse
import dev.scoutr.app.data.TerminalHierarchyCommand
import dev.scoutr.app.data.TerminalHierarchyResponse
import dev.scoutr.app.data.UsageResponse
import dev.scoutr.app.data.UpdateInstallResponse
import dev.scoutr.app.data.UpdateStatusResponse
import dev.scoutr.app.data.WsFrame
import kotlinx.serialization.json.JsonObject

/**
 * The typed bridge surface ViewModels consume. [BridgeClient] is the one
 * production implementation (OkHttp + ConnectionStore); tests use
 * [FakeScoutrApi] so behaviour can be asserted without an HTTP server.
 */
interface ScoutrApi {
    val connectedHost: String?

    suspend fun health(host: String? = null, token: String? = null): HealthResponse
    suspend fun agents(): AgentsResponse
    suspend fun session(path: String, since: String? = null): SessionReadResponse
    suspend fun sessionCatalog(query: String? = null, limit: Int? = null): SessionCatalogResponse
    suspend fun sessionCatalogAction(action: CatalogAction, path: String, text: String? = null): CreatedSessionResponse
    suspend fun createSession(
        cwd: String,
        model: String,
        name: String? = null,
        initialPrompt: String? = null,
        thinkingLevel: String? = null,
        agent: String? = null,
    ): CreatedSessionResponse
    suspend fun controlSession(paneId: String, action: SessionAction, text: String? = null): ControlResponse
    suspend fun models(agent: String? = null): ModelsCatalogResponse
    suspend fun commands(cwd: String? = null, agent: String? = null): CommandsCatalogResponse
    suspend fun agentKinds(): AgentKindsResponse
    suspend fun dirs(path: String? = null): DirListingResponse
    /** File listing for a session workspace; hidden mode is used by the file browser. */
    suspend fun files(cwd: String, includeHidden: Boolean = false): FileListingResponse
    suspend fun repoOverview(path: String): RepoOverviewResponse
    suspend fun repoDiff(path: String, base: String = "HEAD", kind: String = "working"): RepoDiffResponse
    suspend fun repoFileDiff(path: String, base: String, kind: String, file: String): RepoFileDiffResponse
    /** Working-tree file content under an active agent workspace. */
    suspend fun file(path: String): FileReadResponse
    suspend fun repoFile(path: String, base: String, kind: String, file: String): RepoFileResponse
    suspend fun repoArtifacts(path: String): RepoArtifactsResponse
    suspend fun usage(): UsageResponse
    suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): AttachmentResponse

    /**
     * Mutates the terminal hierarchy (Slice 4). Returns the deterministic
     * next selection plus the fresh herdr snapshot; a stale pane count on a
     * close surfaces as BridgeException(409).
     */
    suspend fun terminalHierarchy(command: TerminalHierarchyCommand): TerminalHierarchyResponse

    /**
     * Slice 6: fresh herdr hierarchy snapshot (GET /api/snapshot). The
     * terminal route refreshes this on entry, on topology-feed
     * invalidation, and after hierarchy actions; 503 when the bridge has
     * no snapshot yet surfaces as BridgeException(503).
     */
    suspend fun snapshot(): SnapshotResponse

    /** Opens a short-lived WS, sends one command, and waits for the first ack frame. */
    suspend fun sendCommand(command: Map<String, String>): WsFrame
    suspend fun sendCommandJson(command: JsonObject): WsFrame
    suspend fun steer(target: String, text: String): WsFrame
    suspend fun runSlashCommand(paneId: String, text: String): WsFrame
    /**
     * Answer a whole ask. The app sends intent only — which questions, which
     * option labels, what text — and the bridge drives the agent's own
     * questionnaire, delivering the round in one pass. `callId` is empty when
     * the pane is blocked on a plain prompt rather than a question card, and
     * then `text` carries the answer on its own.
     */
    suspend fun answerAsk(
        paneId: String,
        callId: String = "",
        answers: List<AskAnswer> = emptyList(),
        text: String = "",
    ): WsFrame

    /** Cancel the ask on screen without answering it. */
    suspend fun dismissAsk(paneId: String): WsFrame

    /** Host vs installed build identity; updateAvailable = commit differs or the host tree is dirty. */
    suspend fun updateStatus(commit: String, version: String, dirty: Boolean): UpdateStatusResponse

    /** Fire-and-forget app install; the bridge resolves the adb device and returns 202. */
    suspend fun updateInstall(deviceModel: String): UpdateInstallResponse
}

/** One question's answer inside a batched ask. */
data class AskAnswer(
    val questionId: String,
    val text: String = "",
    val selectedLabels: List<String> = emptyList(),
)
