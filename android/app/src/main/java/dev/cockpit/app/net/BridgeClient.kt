package dev.cockpit.app.net

import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.CommandsCatalogResponse
import dev.cockpit.app.data.ControlResponse
import dev.cockpit.app.data.CreatedSessionResponse
import dev.cockpit.app.data.DirListingResponse
import dev.cockpit.app.data.HealthResponse
import dev.cockpit.app.data.LiveOutputResponse
import dev.cockpit.app.data.ModelsCatalogResponse
import dev.cockpit.app.data.SessionCatalogResponse
import dev.cockpit.app.data.SessionReadResponse
import dev.cockpit.app.data.UsageResponse
import dev.cockpit.app.data.WsFrame
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
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
 * Typed client for the cockpit bridge HTTP + WebSocket API.
 *
 * Base URL is built from the stored connection (e.g. https://artemis.tail…ts.net:8737).
 * Every request carries the pairing token as a Bearer header; the WS upgrade uses
 * a query-param token (tailscale serve passes headers through, but query params are
 * simplest to keep working across proxies).
 */
class BridgeClient(
    private val okHttp: OkHttpClient,
    private val connectionStore: ConnectionStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val connectedHost: String? get() = connectionStore.saved?.host

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
    ): Request {
        val base = (host?.trimEnd('/') ?: baseUrl())
        val auth = token ?: token()
        val url = (base + path).toHttpUrl().newBuilder().apply {
            for ((key, value) in query) addQueryParameter(key, value)
        }.build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $auth")
            .get()
            .build()
    }

    /** POST JSON to the bridge and decode the response as [T]. */
    suspend fun <T> post(
        path: String,
        body: JsonObject,
        decode: (String) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val url = (baseUrl() + path).toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${token()}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = okHttp.newCall(request)
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
                                continuation.resumeWithException(IOException("bridge ${it.code}: ${it.message}"))
                            }
                        }
                    }
                }
            })
        }

    /** List subdirectories for the folder picker (rooted at home by the bridge). */
    suspend fun dirs(path: String? = null): DirListingResponse =
        call("/api/dirs", query = if (path == null) emptyMap() else mapOf("path" to path)) {
            json.decodeFromString(DirListingResponse.serializer(), it)
        }

    /** Full model catalog from pi's models-store.json. */
    suspend fun models(): ModelsCatalogResponse =
        call("/api/models") { json.decodeFromString(ModelsCatalogResponse.serializer(), it) }

    /** Built-in pi commands and installed skill commands for autocomplete. */
    suspend fun commands(cwd: String? = null): CommandsCatalogResponse =
        call("/api/commands", query = if (cwd == null) emptyMap() else mapOf("cwd" to cwd)) {
            json.decodeFromString(CommandsCatalogResponse.serializer(), it)
        }
    /** Bounded list of persisted pi sessions, joined with live pane state. */
    suspend fun sessionCatalog(query: String? = null, limit: Int? = null): SessionCatalogResponse =
        call("/api/session-catalog", query = buildMap {
            if (query != null) put("q", query)
            if (limit != null) put("limit", limit.toString())
        }) { json.decodeFromString(SessionCatalogResponse.serializer(), it) }

    /** Resume/fork/rename/delete a stored session. Resume returns pane+workspace ids. */
    suspend fun sessionCatalogAction(
        action: String,
        path: String,
        text: String? = null,
    ): CreatedSessionResponse = post("/api/session-catalog/$action", buildJsonObject {
        put("path", JsonPrimitive(path))
        if (text != null) put("text", JsonPrimitive(text))
    }) { json.decodeFromString(CreatedSessionResponse.serializer(), it) }

    /** Bounded ANSI-free terminal snapshot for a live agent pane. */
    suspend fun liveOutput(paneId: String, lines: Int = 80): LiveOutputResponse =
        call("/api/agents/$paneId/read", query = mapOf("lines" to lines.toString())) {
            json.decodeFromString(LiveOutputResponse.serializer(), it)
        }

    /** Create a pane-native pi session and deliver its optional first prompt in one bridge call. */
    suspend fun createSession(
        cwd: String,
        model: String,
        name: String? = null,
        initialPrompt: String? = null,
        thinkingLevel: String? = null,
    ): CreatedSessionResponse = post("/api/sessions", buildJsonObject {
        put("cwd", JsonPrimitive(cwd))
        put("model", JsonPrimitive(model))
        if (name != null) put("name", JsonPrimitive(name))
        if (initialPrompt != null) put("initialPrompt", JsonPrimitive(initialPrompt))
        if (thinkingLevel != null) put("thinkingLevel", JsonPrimitive(thinkingLevel))
    }) { json.decodeFromString(CreatedSessionResponse.serializer(), it) }

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


    /** One pane control action: abort/retry/compact/fork/rename/cycle_thinking. */
    suspend fun controlSession(paneId: String, action: String, text: String? = null): ControlResponse =
        post("/api/sessions/${paneId}/control", buildJsonObject {
            put("action", JsonPrimitive(action))
            if (text != null) put("text", JsonPrimitive(text))
        }) { json.decodeFromString(ControlResponse.serializer(), it) }

    /** Calls the bridge and decodes the response body as [T]. */
    suspend fun <T> call(
        path: String,
        query: Map<String, String> = emptyMap(),
        host: String? = null,
        token: String? = null,
        decode: (String) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val call = okHttp.newCall(request(path, query, host, token))
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
                                continuation.resumeWithException(IOException("bridge ${it.code}: ${it.message}"))
                            }
                        }
                    }
                }
            })
        }

    /**
     * Probe the bridge health endpoint. Optional host/token overrides let the
     * connect screen verify a candidate connection before saving it.
     */
    suspend fun health(host: String? = null, token: String? = null): HealthResponse =
        call("/api/health", host = host, token = token) {
            json.decodeFromString(HealthResponse.serializer(), it)
        }

    suspend fun agents(): AgentsResponse =
        call("/api/agents") { json.decodeFromString(AgentsResponse.serializer(), it) }

    suspend fun session(path: String, since: String? = null): SessionReadResponse =
        call("/api/sessions", query = buildMap {
            put("path", path)
            if (since != null) put("since", since)
        }) { json.decodeFromString(SessionReadResponse.serializer(), it) }

    suspend fun usage(): UsageResponse =
        call("/api/usage") { json.decodeFromString(UsageResponse.serializer(), it) }

    /**
     * Opens a short-lived WS, sends one command, and waits for the first ack frame.
     * Used for steering / answering questions where the app needs confirmation.
     */
    suspend fun sendCommand(command: Map<String, String>): WsFrame = suspendCancellableCoroutine { continuation ->
        val saved = connectionStore.saved
        if (saved == null) {
            continuation.resumeWithException(IOException("no connection configured"))
            return@suspendCancellableCoroutine
        }
        val base = saved.host.trimEnd('/')
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?token=" +
            java.net.URLEncoder.encode(saved.token, "UTF-8")
        val payload = json.encodeToString(kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in command) put(k, kotlinx.serialization.json.JsonPrimitive(v))
        })
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

    suspend fun steer(target: String, text: String): WsFrame =
        sendCommand(mapOf("type" to "steer", "target" to target, "text" to text))

    suspend fun runSlashCommand(paneId: String, text: String): WsFrame =
        sendCommand(mapOf("type" to "slash_command", "paneId" to paneId, "text" to text))

    suspend fun answerQuestion(paneId: String, text: String): WsFrame =
        sendCommand(mapOf("type" to "answer_question", "paneId" to paneId, "text" to text))
}
