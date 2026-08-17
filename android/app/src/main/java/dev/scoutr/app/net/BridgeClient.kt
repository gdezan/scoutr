package dev.scoutr.app.net

import dev.scoutr.app.data.CatalogAction
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.AgentKindsResponse
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.AttachmentResponse
import dev.scoutr.app.data.ConnectionStore
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
import dev.scoutr.app.data.UpdateApkStatusResponse
import dev.scoutr.app.data.UpdateBuildResponse
import dev.scoutr.app.data.UpdateStatusResponse
import dev.scoutr.app.data.WsFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A non-2xx bridge response: the HTTP [status] plus the bridge's own error
 * reason. Callers can distinguish 401 (re-pair) from 403 (not allowed) from
 * 5xx (retry) without parsing message strings. Extends [IOException] so the
 * existing offline/connection handling keeps catching it.
 */
class BridgeException(val status: Int, reason: String) : IOException("bridge $status: $reason")

/**
 * Typed client for the scoutr bridge HTTP + WebSocket API. The one
 * production [ScoutrApi] implementation.
 *
 * Base URL is built from the stored connection (e.g. https://artemis.tail…ts.net:8737).
 * Every request carries the pairing token as a Bearer header; the WS upgrade uses
 * a query-param token (tailscale serve passes headers through, but query params are
 * simplest to keep working across proxies).
 */
class BridgeClient(
    private val okHttp: OkHttpClient,
    private val connectionStore: ConnectionStore,
    private val performanceCounters: PerformanceCounters? = null,
) : ScoutrApi {
    private val json = Json { ignoreUnknownKeys = true }

    /** Bridge failure body: {"ok":false,"error":"..."} (error optional). */
    @Serializable
    private data class BridgeErrorBody(val ok: Boolean = false, val error: String? = null)

    /**
     * The bridge's own `error` reason (e.g. the review 403 explanation) over
     * OkHttp's generic status line, so the app can surface *why* a request
     * failed.
     */
    private fun bridgeReason(response: Response, body: String?): String {
        return body
            ?.let { runCatching { json.decodeFromString(BridgeErrorBody.serializer(), it).error }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: response.message
    }

    override val connectedHost: String? get() = connectionStore.saved?.host

    private fun baseUrl(): String {
        val saved = connectionStore.saved ?: throw IOException("no connection configured")
        return saved.host.trimEnd('/')
    }

    private fun token(): String {
        return connectionStore.saved?.token ?: throw IOException("no connection configured")
    }

    private fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        host: String? = null,
        token: String? = null,
        body: RequestBody? = null,
    ): Request {
        val base = (host?.trimEnd('/') ?: baseUrl())
        val auth = token ?: token()
        val url = (base + path).toHttpUrl().newBuilder().apply {
            for ((key, value) in query) addQueryParameter(key, value)
        }.build()
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $auth")
        return if (body == null) builder.get().build() else builder.post(body).build()
    }

    /** Calls the bridge and decodes the response body as [T]. */
    private suspend fun <T> call(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        host: String? = null,
        token: String? = null,
        decode: (String) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val requestMetric = performanceCounters?.beginHttpRequest(path)
            val call = okHttp.newCall(request(path, query, host, token, body))
            continuation.invokeOnCancellation {
                requestMetric?.fail(cancelled = true)
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    requestMetric?.fail(cancelled = call.isCanceled())
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val bodyText = it.body?.string()
                            if (continuation.isCancelled) {
                                requestMetric?.fail(cancelled = true)
                                return
                            }
                            requestMetric?.complete(
                                status = it.code,
                                bodyBytes = bodyText?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L,
                            )
                            if (it.isSuccessful) {
                                resumeDecoded(continuation, bodyText, decode)
                            } else {
                                continuation.resumeWithException(
                                    BridgeException(it.code, bridgeReason(it, bodyText)),
                                )
                            }
                        } catch (error: Throwable) {
                            requestMetric?.fail(cancelled = call.isCanceled())
                            if (!continuation.isCancelled) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    /** List subdirectories for the folder picker (rooted at home by the bridge). */
    override suspend fun dirs(path: String?): DirListingResponse =
        call("/api/dirs", query = if (path == null) emptyMap() else mapOf("path" to path)) {
            json.decodeFromString(DirListingResponse.serializer(), it)
        }

    /** Lists files in a workspace, including hidden and ignored files only in browser mode. */
    override suspend fun files(cwd: String, includeHidden: Boolean): FileListingResponse =
        call("/api/files", query = buildMap {
            put("cwd", cwd)
            if (includeHidden) put("hidden", "1")
        }) {
            json.decodeFromString(FileListingResponse.serializer(), it)
        }

    /** Reads a bounded page from a working-tree file through the active-agent authorization surface. */
    override suspend fun file(path: String, offset: Long, limit: Int): FileReadResponse =
        call("/api/file", query = mapOf("path" to path, "offset" to offset.toString(), "limit" to limit.toString())) {
            json.decodeFromString(FileReadResponse.serializer(), it)
        }

    /** Read-only git overview (branch, status, recent log) for an allowed repo. */
    override suspend fun repoOverview(path: String): RepoOverviewResponse =
        call("/api/repo", query = mapOf("path" to path)) {
            json.decodeFromString(RepoOverviewResponse.serializer(), it)
        }

    /** Bounded, read-only git diff. kind "commit" diffs ref^..ref; "working" (default) diffs the working tree against ref. */
    override suspend fun repoDiff(path: String, base: String, kind: String): RepoDiffResponse =
        call("/api/repo/diff", query = mapOf("path" to path, "base" to base, "kind" to kind)) {
            json.decodeFromString(RepoDiffResponse.serializer(), it)
        }

    /** Per-file diff (stat entry picked from the repoDiff listing); capped at 64 KiB / ~800 lines. */
    override suspend fun repoFileDiff(path: String, base: String, kind: String, file: String): RepoFileDiffResponse =
        call("/api/repo/diff/file", query = mapOf("path" to path, "base" to base, "kind" to kind, "file" to file)) {
            json.decodeFromString(RepoFileDiffResponse.serializer(), it)
        }

    /** Complete file content at a ref (commit kind) or the working tree; capped at 256 KiB. */
    override suspend fun repoFile(path: String, base: String, kind: String, file: String): RepoFileResponse =
        call("/api/repo/file", query = mapOf("path" to path, "base" to base, "kind" to kind, "file" to file)) {
            json.decodeFromString(RepoFileResponse.serializer(), it)
        }

    /** Bounded listing of generated artifacts (build outputs, deps, test reports). */
    override suspend fun repoArtifacts(path: String): RepoArtifactsResponse =
        call("/api/repo/artifacts", query = mapOf("path" to path)) {
            json.decodeFromString(RepoArtifactsResponse.serializer(), it)
        }

    /**
     * Upload an image attachment for the chat composer; returns the host path
     * pi can attach via its `@path` prompt syntax.
     */
    override suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): AttachmentResponse =
        call("/api/attachments", query = mapOf("name" to name), body = bytes.toRequestBody(mime.toMediaType())) {
            json.decodeFromString(AttachmentResponse.serializer(), it)
        }

    /** Slice 4: mutate the terminal hierarchy; 409 close conflicts surface as BridgeException(409). */
    override suspend fun terminalHierarchy(command: TerminalHierarchyCommand): TerminalHierarchyResponse =
        call(
            "/api/terminal/hierarchy",
            body = Json.encodeToString(TerminalHierarchyCommand.serializer(), command)
                .toRequestBody("application/json".toMediaType()),
        ) { json.decodeFromString(TerminalHierarchyResponse.serializer(), it) }

    /** Slice 6: fresh herdr hierarchy snapshot; 503 (no snapshot yet) surfaces as BridgeException(503). */
    override suspend fun snapshot(): SnapshotResponse =
        call("/api/snapshot") { json.decodeFromString(SnapshotResponse.serializer(), it) }
    /** Model catalog for a backend (defaults to pi); catalog-less backends return an empty catalog. */
    override suspend fun models(agent: String?): ModelsCatalogResponse =
        call("/api/models", query = if (agent == null) emptyMap() else mapOf("agent" to agent)) {
            json.decodeFromString(ModelsCatalogResponse.serializer(), it)
        }

    /** Slash commands for a backend (defaults to pi); catalog-less backends return an empty catalog. */
    override suspend fun commands(cwd: String?, agent: String?): CommandsCatalogResponse =
        call("/api/commands", query = buildMap {
            if (cwd != null) put("cwd", cwd)
            if (agent != null) put("agent", agent)
        }) {
            json.decodeFromString(CommandsCatalogResponse.serializer(), it)
        }

    /** Registered agent backends for the new-session sheet's backend selector. */
    override suspend fun agentKinds(): AgentKindsResponse =
        call("/api/agents/kinds") { json.decodeFromString(AgentKindsResponse.serializer(), it) }

    /** Bounded list of persisted pi sessions, joined with live pane state. */
    override suspend fun sessionCatalog(query: String?, limit: Int?): SessionCatalogResponse =
        call("/api/session-catalog", query = buildMap {
            if (query != null) put("q", query)
            if (limit != null) put("limit", limit.toString())
        }) { json.decodeFromString(SessionCatalogResponse.serializer(), it) }

    /** Resume/fork/rename/delete a stored session. Resume returns pane+workspace ids. */
    override suspend fun sessionCatalogAction(
        action: CatalogAction,
        path: String,
        text: String?,
    ): CreatedSessionResponse = call(
        "/api/session-catalog/${action.wire}",
        body = buildJsonObject {
            put("path", JsonPrimitive(path))
            if (text != null) put("text", JsonPrimitive(text))
        }.toString().toRequestBody("application/json".toMediaType()),
    ) { json.decodeFromString(CreatedSessionResponse.serializer(), it) }

    /** Create a pane-native agent session and deliver its optional first prompt in one bridge call. */
    override suspend fun createSession(
        cwd: String,
        model: String,
        name: String?,
        initialPrompt: String?,
        thinkingLevel: String?,
        agent: String?,
    ): CreatedSessionResponse = call(
        "/api/sessions",
        body = buildJsonObject {
            put("cwd", JsonPrimitive(cwd))
            put("model", JsonPrimitive(model))
            if (name != null) put("name", JsonPrimitive(name))
            if (initialPrompt != null) put("initialPrompt", JsonPrimitive(initialPrompt))
            if (thinkingLevel != null) put("thinkingLevel", JsonPrimitive(thinkingLevel))
            if (agent != null) put("agent", JsonPrimitive(agent))
        }.toString().toRequestBody("application/json".toMediaType()),
    ) { json.decodeFromString(CreatedSessionResponse.serializer(), it) }

    /**
     * Resume a pending bridge call, turning a decode failure into the
     * coroutine's exception — otherwise the caller hangs forever on a
     * malformed response.
     */
    private fun <T> resumeDecoded(
        continuation: kotlinx.coroutines.CancellableContinuation<T>,
        body: String?,
        decode: (String) -> T,
    ) {
        try {
            continuation.resume(decode(body ?: "{}"))
        } catch (t: Throwable) {
            if (!continuation.isCancelled) continuation.resumeWithException(t)
        }
    }

    /** One pane control action: abort/retry/compact/fork/rename/close/set_model/set_thinking. */
    override suspend fun controlSession(paneId: String, action: SessionAction, text: String?): ControlResponse =
        call(
            "/api/sessions/${paneId}/control",
            body = buildJsonObject {
                put("action", JsonPrimitive(action.wire))
                if (text != null) put("text", JsonPrimitive(text))
            }.toString().toRequestBody("application/json".toMediaType()),
        ) { json.decodeFromString(ControlResponse.serializer(), it) }

    /**
     * Probe the bridge health endpoint. Optional host/token overrides let the
     * connect screen verify a candidate connection before saving it.
     */
    override suspend fun health(host: String?, token: String?): HealthResponse =
        call("/api/health", host = host, token = token) {
            json.decodeFromString(HealthResponse.serializer(), it)
        }

    override suspend fun agents(): AgentsResponse =
        call("/api/agents") { json.decodeFromString(AgentsResponse.serializer(), it) }

    override suspend fun session(path: String, since: String?): SessionReadResponse =
        call("/api/sessions", query = buildMap {
            put("path", path)
            if (since != null) put("since", since)
        }) { json.decodeFromString(SessionReadResponse.serializer(), it) }

    override suspend fun usage(): UsageResponse =
        call("/api/usage") { json.decodeFromString(UsageResponse.serializer(), it) }

    /**
     * Opens a short-lived WS, sends one command, and waits for the first ack frame.
     * Used for steering / answering questions where the app needs confirmation.
     */
    override suspend fun sendCommand(command: Map<String, String>): WsFrame = sendCommandJson(
        buildJsonObject { for ((k, v) in command) put(k, JsonPrimitive(v)) },
    )

    /** sendCommand with structured values (e.g. key arrays); see sendCommand. */
    override suspend fun sendCommandJson(command: JsonObject): WsFrame = suspendCancellableCoroutine { continuation ->
        val saved = connectionStore.saved
        if (saved == null) {
            continuation.resumeWithException(IOException("no connection configured"))
            return@suspendCancellableCoroutine
        }
        val base = saved.host.trimEnd('/')
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?token=" +
            java.net.URLEncoder.encode(saved.token, "UTF-8")
        val payload = json.encodeToString(command)
        val settled = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = json.decodeFromString(WsFrame.serializer(), text)
                    if (frame.type == "feed") return
                    if (settled.compareAndSet(false, true)) {
                        if (frame.type == "error") continuation.resumeWithException(IOException(frame.error ?: "Bridge command failed"))
                        else continuation.resume(frame)
                        webSocket.close(1000, null)
                    }
                } catch (_: Exception) {
                    // Ignore malformed frames and keep waiting for the ack.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (settled.compareAndSet(false, true)) continuation.resumeWithException(t)
            }
        }

        val ws = okHttp.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        continuation.invokeOnCancellation {
            settled.set(true)
            ws.cancel()
        }
    }

    override suspend fun steer(target: String, text: String): WsFrame =
        sendCommand(mapOf("type" to "steer", "target" to target, "text" to text))

    override suspend fun runSlashCommand(paneId: String, text: String): WsFrame =
        sendCommand(mapOf("type" to "slash_command", "paneId" to paneId, "text" to text))

    override suspend fun answerAsk(
        paneId: String,
        callId: String,
        answers: List<AskAnswer>,
        text: String,
    ): WsFrame = sendCommandJson(
        buildJsonObject {
            put("type", "answer_ask")
            put("paneId", paneId)
            if (callId.isNotEmpty()) put("callId", callId)
            if (answers.isNotEmpty()) {
                put(
                    "answers",
                    JsonArray(
                        answers.map { answer ->
                            buildJsonObject {
                                put("questionId", answer.questionId)
                                put("text", answer.text)
                                if (answer.selectedLabels.isNotEmpty()) {
                                    put(
                                        "selectedLabels",
                                        JsonArray(answer.selectedLabels.map { JsonPrimitive(it) }),
                                    )
                                }
                            }
                        },
                    ),
                )
            }
            put("text", text)
        },
    )

    override suspend fun dismissAsk(paneId: String): WsFrame =
        sendCommand(mapOf("type" to "dismiss_ask", "paneId" to paneId))

    override suspend fun updateStatus(commit: String, version: String, dirty: Boolean): UpdateStatusResponse =
        call("/api/update/status", query = mapOf("commit" to commit, "version" to version, "dirty" to dirty.toString())) {
            json.decodeFromString(UpdateStatusResponse.serializer(), it)
        }

    override suspend fun updateBuild(): UpdateBuildResponse =
        call("/api/update/apk/build", body = EMPTY_JSON_BODY) {
            json.decodeFromString(UpdateBuildResponse.serializer(), it)
        }

    override suspend fun updateApkStatus(): UpdateApkStatusResponse =
        call("/api/update/apk/status") { json.decodeFromString(UpdateApkStatusResponse.serializer(), it) }

    /**
     * Streams the APK to disk rather than through [call], which buffers the
     * whole body as a String — an APK is tens of megabytes and is binary.
     * Runs on the IO dispatcher because OkHttp's blocking `execute` plus the
     * copy loop must not sit on the caller's thread.
     */
    override suspend fun downloadApk(destination: File, onProgress: (Long, Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val response = okHttp.newCall(request("/api/update/apk")).execute()
            response.use {
                val body = it.body ?: throw BridgeException(it.code, "empty APK response")
                if (!it.isSuccessful) throw BridgeException(it.code, bridgeReason(it, body.string()))
                val total = body.contentLength().coerceAtLeast(0L)
                var written = 0L
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            // The coroutine may be cancelled mid-download (the
                            // user leaves Settings); stop copying rather than
                            // finishing a file nobody will install.
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                    }
                }
                if (total > 0 && written != total) {
                    throw BridgeException(it.code, "APK download truncated at $written of $total bytes")
                }
                written
            }
        }

    private companion object {
        val EMPTY_JSON_BODY: RequestBody = "{}".toRequestBody("application/json".toMediaType())
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}
