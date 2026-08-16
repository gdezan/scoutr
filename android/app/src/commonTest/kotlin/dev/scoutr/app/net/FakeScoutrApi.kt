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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One recorded [ScoutrApi] invocation: the method name plus its arguments. */
data class ApiCall(val name: String, val args: Map<String, Any?> = emptyMap())

/**
 * In-memory [ScoutrApi] for tests (unit and instrumented, via the
 * commonTest source set). Every HTTP method records itself in [calls] and
 * returns its configured [Result], defaulting to an empty success. The WS
 * surface records each sent command into [sentCommands] and returns
 * [wsResult], or throws [wsFailure] when set.
 *
 * Deliberately a plain data holder: it carries no logic of its own, so it
 * cannot drift from BridgeClient's behaviour — the real transport stays
 * covered by the contract tests in BridgeClientUploadTest.
 */
class FakeScoutrApi : ScoutrApi {
    val calls = mutableListOf<ApiCall>()
    val sentCommands = mutableListOf<JsonObject>()

    var healthResult: Result<HealthResponse> = Result.success(HealthResponse(ok = true))
    var agentsResult: Result<AgentsResponse> = Result.success(AgentsResponse())
    var sessionResult: Result<SessionReadResponse> = Result.success(SessionReadResponse())
    var sessionCatalogResult: Result<SessionCatalogResponse> = Result.success(SessionCatalogResponse(ok = true))
    var catalogActionResult: Result<CreatedSessionResponse> = Result.success(CreatedSessionResponse(ok = true))
    var createSessionResult: Result<CreatedSessionResponse> = Result.success(CreatedSessionResponse(ok = true))
    var controlResult: Result<ControlResponse> = Result.success(ControlResponse(ok = true))
    var modelsResult: Result<ModelsCatalogResponse> = Result.success(ModelsCatalogResponse(ok = true))
    var commandsResult: Result<CommandsCatalogResponse> = Result.success(CommandsCatalogResponse(ok = true))
    var agentKindsResult: Result<AgentKindsResponse> = Result.success(AgentKindsResponse())
    var dirsResult: Result<DirListingResponse> = Result.success(DirListingResponse(ok = true))
    var filesResult: Result<FileListingResponse> = Result.success(FileListingResponse(ok = true))
    var fileResult: Result<FileReadResponse> = Result.success(FileReadResponse())
    val filePageResults = mutableMapOf<Long, Result<FileReadResponse>>()
    var repoOverviewResult: Result<RepoOverviewResponse> = Result.success(RepoOverviewResponse())
    var repoDiffResult: Result<RepoDiffResponse> = Result.success(RepoDiffResponse())
    var repoFileDiffResult: Result<RepoFileDiffResponse> = Result.success(RepoFileDiffResponse())
    var repoFileResult: Result<RepoFileResponse> = Result.success(RepoFileResponse())
    var repoArtifactsResult: Result<RepoArtifactsResponse> = Result.success(RepoArtifactsResponse())
    var usageResult: Result<UsageResponse> = Result.success(UsageResponse())
    var uploadResult: Result<AttachmentResponse> = Result.success(AttachmentResponse())
    var updateStatusResult: Result<UpdateStatusResponse> = Result.success(UpdateStatusResponse())
    var updateInstallResult: Result<UpdateInstallResponse> = Result.success(UpdateInstallResponse())
    var terminalHierarchyResult: Result<TerminalHierarchyResponse> = Result.success(TerminalHierarchyResponse(ok = true))

    var wsResult: Result<WsFrame> = Result.success(WsFrame(type = "ack"))
    var wsFailure: Exception? = null

    /**
     * Optional per-method delays (ms) applied before returning, so tests can
     * simulate slow responses and exercise out-of-order arrival guards.
     */
    val callDelays = mutableMapOf<String, Long>()

    /**
     * Optional per-args responder, consulted before the fixed results. Return
     * a Result<T> matching the called method to override, or null to fall
     * through to the fixed result field.
     */
    var onCall: ((name: String, args: Map<String, Any?>) -> Any?)? = null

    /**
     * Gates per method name: while a gate is pending, calls to that method
     * suspend until the test completes the deferred; completed gates let calls
     * through. Lets tests observe pre-poll state before releasing the first
     * response (the real client's network hop provided that gap naturally).
     */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override val connectedHost: String? get() = "http://fake-bridge"

    override suspend fun health(host: String?, token: String?): HealthResponse =
        record("health", mapOf("host" to host, "token" to token)) { healthResult }

    override suspend fun agents(): AgentsResponse = record("agents") { agentsResult }

    override suspend fun session(path: String, since: String?): SessionReadResponse =
        record("session", mapOf("path" to path, "since" to since)) { sessionResult }

    override suspend fun sessionCatalog(query: String?, limit: Int?): SessionCatalogResponse =
        record("sessionCatalog", mapOf("query" to query, "limit" to limit)) { sessionCatalogResult }

    override suspend fun sessionCatalogAction(action: CatalogAction, path: String, text: String?): CreatedSessionResponse =
        record("sessionCatalogAction", mapOf("action" to action, "path" to path, "text" to text)) { catalogActionResult }

    override suspend fun createSession(
        cwd: String,
        model: String,
        name: String?,
        initialPrompt: String?,
        thinkingLevel: String?,
        agent: String?,
    ): CreatedSessionResponse = record(
        "createSession",
        mapOf(
            "cwd" to cwd,
            "model" to model,
            "name" to name,
            "initialPrompt" to initialPrompt,
            "thinkingLevel" to thinkingLevel,
            "agent" to agent,
        ),
    ) { createSessionResult }

    override suspend fun controlSession(paneId: String, action: SessionAction, text: String?): ControlResponse =
        record("controlSession", mapOf("paneId" to paneId, "action" to action, "text" to text)) { controlResult }

    override suspend fun models(agent: String?): ModelsCatalogResponse =
        record("models", mapOf("agent" to agent)) { modelsResult }

    override suspend fun commands(cwd: String?, agent: String?): CommandsCatalogResponse =
        record("commands", mapOf("cwd" to cwd, "agent" to agent)) { commandsResult }

    override suspend fun agentKinds(): AgentKindsResponse = record("agentKinds") { agentKindsResult }

    override suspend fun dirs(path: String?): DirListingResponse =
        record("dirs", mapOf("path" to path)) { dirsResult }

    override suspend fun files(cwd: String, includeHidden: Boolean): FileListingResponse =
        record("files", mapOf("cwd" to cwd, "includeHidden" to includeHidden)) { filesResult }

    override suspend fun file(path: String, offset: Long, limit: Int): FileReadResponse =
        record("file", mapOf("path" to path, "offset" to offset, "limit" to limit)) {
            filePageResults[offset] ?: fileResult
        }

    override suspend fun repoOverview(path: String): RepoOverviewResponse =
        record("repoOverview", mapOf("path" to path)) { repoOverviewResult }

    override suspend fun repoDiff(path: String, base: String, kind: String): RepoDiffResponse =
        record("repoDiff", mapOf("path" to path, "base" to base, "kind" to kind)) { repoDiffResult }

    override suspend fun repoFileDiff(path: String, base: String, kind: String, file: String): RepoFileDiffResponse =
        record("repoFileDiff", mapOf("path" to path, "base" to base, "kind" to kind, "file" to file)) { repoFileDiffResult }

    override suspend fun repoFile(path: String, base: String, kind: String, file: String): RepoFileResponse =
        record("repoFile", mapOf("path" to path, "base" to base, "kind" to kind, "file" to file)) { repoFileResult }

    override suspend fun repoArtifacts(path: String): RepoArtifactsResponse =
        record("repoArtifacts", mapOf("path" to path)) { repoArtifactsResult }

    override suspend fun usage(): UsageResponse = record("usage") { usageResult }

    override suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): AttachmentResponse =
        record("uploadAttachment", mapOf("name" to name, "mime" to mime, "bytes" to bytes)) { uploadResult }

    override suspend fun terminalHierarchy(command: TerminalHierarchyCommand): TerminalHierarchyResponse =
        record("terminalHierarchy", mapOf("command" to command)) { terminalHierarchyResult }

    var snapshotResult: Result<SnapshotResponse> = Result.success(SnapshotResponse(ok = true))

    override suspend fun snapshot(): SnapshotResponse = record("snapshot") { snapshotResult }

    override suspend fun sendCommand(command: Map<String, String>): WsFrame {
        val json = buildJsonObject { for ((k, v) in command) put(k, JsonPrimitive(v)) }
        return send("sendCommand", json)
    }

    override suspend fun sendCommandJson(command: JsonObject): WsFrame = send("sendCommandJson", command)

    override suspend fun steer(target: String, text: String): WsFrame =
        send("steer", buildJsonObject {
            put("type", "steer")
            put("target", target)
            put("text", text)
        })

    override suspend fun runSlashCommand(paneId: String, text: String): WsFrame =
        send("runSlashCommand", buildJsonObject {
            put("type", "slash_command")
            put("paneId", paneId)
            put("text", text)
        })

    override suspend fun answerAsk(
        paneId: String,
        callId: String,
        answers: List<AskAnswer>,
        text: String,
    ): WsFrame = send("answerAsk", buildJsonObject {
        put("type", "answer_ask")
        put("paneId", paneId)
        if (callId.isNotEmpty()) put("callId", callId)
        if (answers.isNotEmpty()) {
            put(
                "answers",
                kotlinx.serialization.json.JsonArray(
                    answers.map { answer ->
                        buildJsonObject {
                            put("questionId", answer.questionId)
                            put("text", answer.text)
                            if (answer.selectedLabels.isNotEmpty()) {
                                put(
                                    "selectedLabels",
                                    kotlinx.serialization.json.JsonArray(
                                        answer.selectedLabels.map { JsonPrimitive(it) },
                                    ),
                                )
                            }
                        }
                    },
                ),
            )
        }
        put("text", text)
    })

    override suspend fun dismissAsk(paneId: String): WsFrame =
        send("dismissAsk", buildJsonObject {
            put("type", "dismiss_ask")
            put("paneId", paneId)
        })

    override suspend fun updateStatus(commit: String, version: String, dirty: Boolean): UpdateStatusResponse =
        record("updateStatus", mapOf("commit" to commit, "version" to version, "dirty" to dirty)) { updateStatusResult }

    override suspend fun updateInstall(deviceModel: String): UpdateInstallResponse =
        record("updateInstall", mapOf("deviceModel" to deviceModel)) { updateInstallResult }

    private fun send(name: String, command: JsonObject): WsFrame {
        calls += ApiCall(name, mapOf("command" to command))
        sentCommands += command
        wsFailure?.let { throw it }
        return wsResult.getOrThrow()
    }

    private suspend fun <T> record(name: String, args: Map<String, Any?> = emptyMap(), result: () -> Result<T>): T {
        calls += ApiCall(name, args)
        onCall?.invoke(name, args)?.let { return (it as Result<T>).getOrThrow() }
        gates[name]?.let { it.await() }
        callDelays[name]?.let { kotlinx.coroutines.delay(it) }
        return result().getOrThrow()
    }
}