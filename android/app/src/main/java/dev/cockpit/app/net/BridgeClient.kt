package dev.cockpit.app.net

import dev.cockpit.app.data.CatalogAction
import dev.cockpit.app.data.SessionAction
import dev.cockpit.app.data.AgentKindsResponse
import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.data.AttachmentResponse
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.CommandsCatalogResponse
import dev.cockpit.app.data.ControlResponse
import dev.cockpit.app.data.CreatedSessionResponse
import dev.cockpit.app.data.DirListingResponse
import dev.cockpit.app.data.HealthResponse
import dev.cockpit.app.data.LiveOutputResponse
import dev.cockpit.app.data.ModelsCatalogResponse
import dev.cockpit.app.data.RepoArtifactsResponse
import dev.cockpit.app.data.RepoDiffResponse
import dev.cockpit.app.data.RepoOverviewResponse
import dev.cockpit.app.data.SessionCatalogResponse
import dev.cockpit.app.data.SessionReadResponse
import dev.cockpit.app.data.UsageResponse
import dev.cockpit.app.data.WsFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
 * Typed client for the cockpit bridge HTTP + WebSocket API. The one
 * production [CockpitApi] implementation.
 *
 * Base URL is built from the stored connection (e.g. https://artemis.tail…ts.net:8737).
 * Every request carries the pairing token as a Bearer header; the WS upgrade uses
 * a query-param token (tailscale serve passes headers through, but query params are
 * simplest to keep working across proxies).
 */
class BridgeClient(
    private val okHttp: OkHttpClient,
    private val connectionStore: ConnectionStore,
) : CockpitApi {
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
            val call = okHttp.newCall(request(path, query, host, token, body))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isCancelled) {
                            if (it.isSuccessful) {
                                resumeDecoded(continuation, it.body?.string(), decode)
                            } else {
                                continuation.resumeWithException(
                                    BridgeException(it.code, bridgeReason(it, it.body?.string())),
                                )
                            }
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

    /** Bounded ANSI-free terminal snapshot for a live agent pane. */
    override suspend fun liveOutput(paneId: String, lines: Int): LiveOutputResponse =
        call("/api/agents/$paneId/read", query = mapOf("lines" to lines.toString())) {
            json.decodeFromString(LiveOutputResponse.serializer(), it)
        }

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

    override suspend fun answerQuestion(
        paneId: String,
        text: String,
        keys: List<String>,
        trailingKeys: List<String>,
    ): WsFrame = sendCommandJson(
        buildJsonObject {
            put("type", "answer_question")
            put("paneId", paneId)
            put("text", text)
            if (keys.isNotEmpty()) put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
            if (trailingKeys.isNotEmpty()) put("trailingKeys", JsonArray(trailingKeys.map { JsonPrimitive(it) }))
        },
    )
}
