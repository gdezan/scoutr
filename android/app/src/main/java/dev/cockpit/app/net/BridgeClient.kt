package dev.cockpit.app.net

import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.FeedMessage
import dev.cockpit.app.data.HealthResponse
import dev.cockpit.app.data.RpcEntriesResponse
import dev.cockpit.app.data.RpcPromptResponse
import dev.cockpit.app.data.RpcRespondResponse
import dev.cockpit.app.data.RpcSessionInfo
import dev.cockpit.app.data.RpcSessionResponse
import dev.cockpit.app.data.SessionReadResponse
import dev.cockpit.app.data.UsageResponse
import dev.cockpit.app.data.WsFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
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
        method: String = "GET",
        body: String? = null,
    ): Request {
        val base = (host?.trimEnd('/') ?: baseUrl())
        val auth = token ?: token()
        val url = (base + path).toHttpUrl().newBuilder().apply {
            for ((key, value) in query) addQueryParameter(key, value)
        }.build()
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $auth")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
        return builder.build()
    }

    /** Calls the bridge and decodes the response body as [T]. */
    suspend fun <T> call(
        path: String,
        query: Map<String, String> = emptyMap(),
        host: String? = null,
        token: String? = null,
        method: String = "GET",
        body: String? = null,
        decode: (String) -> T,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val call = okHttp.newCall(request(path, query, host, token, method, body))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isCancelled) {
                            if (it.isSuccessful) {
                                continuation.resume(decode(it.body?.string() ?: "{}"))
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

    // ---- bridge-owned pi --mode rpc sessions ----

    /** Spawn a new pi session on the bridge and return its id. */
    suspend fun rpcCreate(name: String = "cockpit-app"): RpcSessionInfo =
        call("/api/rpc", method = "POST", body = jsonBody("name" to name)) {
            json.decodeFromString(RpcSessionResponse.serializer(), it)
                .session ?: throw IOException("bridge did not return an rpc session")
        }

    /** Session info: status, last entry id, pending dialog requests. */
    suspend fun rpcSession(id: String): RpcSessionInfo =
        call("/api/rpc/$id") {
            json.decodeFromString(RpcSessionResponse.serializer(), it)
                .session ?: throw IOException("bridge did not return an rpc session")
        }

    suspend fun rpcEntries(id: String, since: String? = null): RpcEntriesResponse =
        call("/api/rpc/$id/entries", query = buildMap {
            if (since != null) put("since", since)
        }) { json.decodeFromString(RpcEntriesResponse.serializer(), it) }

    suspend fun rpcPrompt(id: String, message: String): Unit =
        call("/api/rpc/$id/prompt", method = "POST", body = jsonBody("message" to message)) { Unit }

    /** Answer a pending dialog request with a value (or cancelled). */
    suspend fun rpcRespond(id: String, uiId: String, value: String? = null, cancelled: Boolean = false): Unit =
        call("/api/rpc/$id/respond", method = "POST", body = jsonBody(
            "uiId" to uiId,
            "value" to value,
            "cancelled" to cancelled.takeIf { it },
        )) { Unit }

    /** Encode a JSON body, dropping null values and keeping primitives typed. */
    private fun jsonBody(vararg pairs: Pair<String, Any?>): String =
        json.encodeToString(kotlinx.serialization.json.buildJsonObject {
            for ((key, value) in pairs) {
                if (value != null) {
                    put(
                        key,
                        when (value) {
                            is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
                            else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
                        },
                    )
                }
            }
        })

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

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = json.decodeFromString(WsFrame.serializer(), text)
                    webSocket.close(1000, null)
                    if (!continuation.isCancelled) continuation.resume(frame)
                } catch (_: Exception) {
                    // ignore malformed frames; keep waiting for the ack
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!continuation.isCancelled) continuation.resumeWithException(t)
            }
        }

        val ws = okHttp.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        continuation.invokeOnCancellation { ws.cancel() }
    }

    suspend fun steer(target: String, text: String): WsFrame =
        sendCommand(mapOf("type" to "steer", "target" to target, "text" to text))

    suspend fun answerQuestion(paneId: String, text: String): WsFrame =
        sendCommand(mapOf("type" to "answer_question", "paneId" to paneId, "text" to text))
}
