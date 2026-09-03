package dev.scoutr.app.net

import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.AgentKindsResponse
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.PiSubagentProgress
import dev.scoutr.app.data.AttachmentResponse
import dev.scoutr.app.data.CommandResponse
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
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.SessionReadResponse
import dev.scoutr.app.data.SnapshotResponse
import dev.scoutr.app.data.TerminalHierarchyCommand
import dev.scoutr.app.data.TerminalHierarchyResponse
import dev.scoutr.app.data.UsageResponse
import dev.scoutr.app.data.UpdateApkStatusResponse
import dev.scoutr.app.data.UpdateBuildResponse
import dev.scoutr.app.data.UpdateStatusResponse
import java.io.File

/**
 * The typed bridge surface ViewModels consume. [BridgeClient] is the one
 * production implementation, bound to immutable host credentials; tests use
 * [FakeScoutrApi] so behaviour can be asserted without an HTTP server.
 */
interface ScoutrApi {
    val connectedHost: String?

    suspend fun health(): HealthResponse
    suspend fun agents(): AgentsResponse
    /** One PI-workflow run's progress.json + result.json, joined by runId. */
    suspend fun subagentProgress(runId: String): PiSubagentProgress
    /** Registers this phone using the profile generation local to this installation. */
    suspend fun registerDevice(fcmToken: String, profileGeneration: Long)
    /** Removes this phone's token from the current bridge; absence is successful. */
    suspend fun unregisterDevice(fcmToken: String)
    suspend fun session(
        key: SessionKey,
        since: String? = null,
        before: String? = null,
        limit: Int? = null,
    ): SessionReadResponse
    suspend fun sessionCatalog(query: String? = null, limit: Int? = null): SessionCatalogResponse
    suspend fun sessionCatalogAction(action: CatalogAction, key: SessionKey, text: String? = null): CreatedSessionResponse
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
    /** Working-tree file content under an active agent workspace; pages are capped by the bridge. */
    suspend fun file(path: String, offset: Long = 0, limit: Int = 256 * 1024): FileReadResponse
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

    /**
     * One-shot session commands. Each is an authenticated HTTP POST under
     * `/api/sessions/...` (`commands.http.v1`); callers see ordinary
     * BridgeException statuses and ordinary coroutine cancellation, and never
     * need to know how the command reaches the pane.
     */
    suspend fun steer(target: String, text: String): CommandResponse
    suspend fun runSlashCommand(paneId: String, text: String): CommandResponse
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
    ): CommandResponse

    /** Cancel the ask on screen without answering it. */
    suspend fun dismissAsk(paneId: String): CommandResponse

    /** Host vs installed build identity; updateAvailable = commit differs or the host tree is dirty. */
    suspend fun updateStatus(commit: String, version: String, dirty: Boolean): UpdateStatusResponse

    /**
     * Starts a host-side APK build and returns immediately. A build already in
     * flight is reused, so a double tap cannot queue a second gradle.
     */
    suspend fun updateBuild(): UpdateBuildResponse

    /** Host identity plus the current build state; polled until it leaves "building". */
    suspend fun updateApkStatus(): UpdateApkStatusResponse

    /**
     * Streams the built APK into [destination]. [onProgress] reports bytes now
     * in the file against the full APK size (0 when the bridge sent no
     * content-length). Returns the number of bytes now in the file.
     *
     * [resumeFrom] is the number of bytes already staged in [destination]. At 0
     * the file is truncated and the whole APK is fetched. Above 0 the client
     * asks for `Range: bytes=<resumeFrom>-` and appends the tail; a bridge too
     * old to honour ranges answers 200 with the whole APK, which restarts the
     * transfer from zero rather than corrupting the file.
     */
    suspend fun downloadApk(
        destination: File,
        resumeFrom: Long = 0,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long

    /**
     * Streams one workspace file's raw bytes into [destination] via
     * `GET /api/file/bytes?path=`. Mirrors [downloadApk]'s resume/restart/
     * truncation rules: [resumeFrom] bytes are assumed already staged, `206`
     * appends the tail, and a `200` answering a Range restarts from zero.
     * Returns the number of bytes now in the file.
     */
    suspend fun downloadWorkspaceFile(
        destination: File,
        path: String,
        resumeFrom: Long = 0,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long
}

/** One question's answer inside a batched ask. */
data class AskAnswer(
    val questionId: String,
    val text: String = "",
    val selectedLabels: List<String> = emptyList(),
)
