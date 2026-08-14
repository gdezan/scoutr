package dev.scoutr.app.net

import dev.scoutr.app.data.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

/**
 * Production [TopologyFeed] on the bridge's /ws route with a topology-kind
 * filter (plan: route-scoped, one consumer, never the global Android feed).
 *
 * The bridge's /ws forwards herdr feed messages as `{type:"feed",payload}`;
 * payloads are either `{kind,data}` events or `{type:"snapshot",snapshot}`
 * resyncs. This client only decodes enough to classify: any event with a
 * kind string is an invalidation, a snapshot payload is a resync. The
 * ViewModel owns refresh timing (throttled) and never sees raw frames.
 *
 * Reconnect policy: on abrupt EOF/IO failure the client re-subscribes with
 * bounded exponential backoff (base 500 ms, factor 2, ceiling 8 s, ±20%
 * jitter). When the upgrade itself is rejected (HTTP status), the failure
 * is typed through [TopologyFeed.Listener.onFeedFailure] and retries stop —
 * a rejected upgrade means auth/capability trouble that retrying will not
 * fix.
 */
class TopologyFeedClient(
    private val okHttp: OkHttpClient,
    private val connectionStore: ConnectionStore,
    private val listener: TopologyFeed.Listener,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val backoffBaseMs: Long = 500L,
    private val backoffMaxMs: Long = 8_000L,
    private val performanceCounters: PerformanceCounters? = null,
) : TopologyFeed {

    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var webSocket: WebSocket? = null
    private data class PerformanceSocket(
        val webSocket: WebSocket,
        val handle: PerformanceCounters.SocketHandle,
    )

    private var performanceSocket: PerformanceSocket? = null
    private var reconnectJob: Job? = null

    override fun start(): Boolean {
        if (started) return true
        val saved = connectionStore.saved
        if (saved == null) return false
        started = true
        closed = false
        openSocket(saved)
        return true
    }

    override fun stop() {
        val socket = synchronized(this) {
            started = false
            closed = true
            reconnectJob?.cancel()
            closePerformanceSocket()
            webSocket.also { webSocket = null }
        }
        socket?.cancel()
    }

    private fun openSocket(saved: ConnectionStore.Saved) {
        if (!started || closed) return
        val ws = okHttp.newWebSocket(
            Request.Builder()
                .url(TerminalSocketClient.wsUrl(saved.host) + "/ws")
                .header("Authorization", "Bearer ${saved.token}")
                .build(),
            feedListener,
        )
        val cancel = synchronized(this) {
            if (!started || closed) {
                true
            } else {
                webSocket = ws
                false
            }
        }
        if (cancel) ws.cancel()
    }

    @Synchronized
    private fun beginPerformanceSocket(webSocket: WebSocket): Boolean {
        if (closed || !started || this.webSocket !== webSocket) return false
        if (performanceSocket?.webSocket === webSocket) return true
        if (performanceSocket != null) return false
        val handle = performanceCounters?.beginSocket(PerformanceCounters.SocketKind.Feed)
            ?: return true
        performanceSocket = PerformanceSocket(webSocket, handle)
        return true
    }

    @Synchronized
    private fun closePerformanceSocket(webSocket: WebSocket? = null) {
        val current = performanceSocket ?: return
        if (webSocket != null && current.webSocket !== webSocket) return
        performanceSocket = null
        current.handle.close()
    }

    private val feedListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!beginPerformanceSocket(webSocket)) {
                webSocket.cancel()
                return
            }
            webSocket.send(subscribeJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed) return
            classify(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closePerformanceSocket(webSocket)
            if (closed) return
            if (response != null) {
                // The upgrade was rejected (401/403/503/...): retrying will not help.
                val rejected = synchronized(this@TopologyFeedClient) {
                    if (closed || this@TopologyFeedClient.webSocket !== webSocket) {
                        false
                    } else {
                        closed = true
                        started = false
                        true
                    }
                }
                if (rejected) {
                    listener.onFeedFailure(IOException("terminal feed rejected: HTTP ${response.code}"))
                }
            } else {
                scheduleReconnect(webSocket)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (closed) return
            // Abrupt server close (no feed_error): reconnect.
            closePerformanceSocket(webSocket)
            scheduleReconnect(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closePerformanceSocket(webSocket)
            if (closed) return
            if (code != NORMAL_CLOSE) scheduleReconnect(webSocket)
        }
    }

    private var attempt = 0

    @Synchronized
    private fun scheduleReconnect(socket: WebSocket? = null) {
        if (!started || closed) return
        if (socket != null && webSocket !== socket) return
        val saved = connectionStore.saved ?: return
        webSocket = null
        val nextDelay = backoffDelayMs(attempt++)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(nextDelay)
            if (started && !closed) openSocket(saved)
        }
    }

    private fun backoffDelayMs(attempt: Int): Long {
        val exp = backoffBaseMs * (1L shl attempt.coerceAtMost(4))
        val jitter = 0.8 + ThreadLocalRandom.current().nextDouble(0.4)
        return (exp.coerceAtMost(backoffMaxMs) * jitter).toLong().coerceAtLeast(1L)
    }

    private fun classify(text: String) {
        if (closed) return
        val root = runCatching {
            protocolJson.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return
        if ((root["type"] as? JsonPrimitive)?.content != "feed") return
        val payload = root["payload"] as? JsonObject ?: return
        when ((payload["type"] as? JsonPrimitive)?.content) {
            "snapshot" -> listener.onSnapshot()
            null -> {
                val kind = (payload["kind"] as? JsonPrimitive)?.content
                if (kind != null && kind != "feed_error") {
                    listener.onTopologyEvent(kind)
                }
            }
            else -> Unit // ack/other control payloads are not topology.
        }
    }

    private fun subscribeJson(): String =
        buildJsonObject {
            put("type", "subscribe")
            put("filter", JsonArray(TOPOLOGY_KINDS.map(::JsonPrimitive)))
        }.toString()

    companion object {
        const val NORMAL_CLOSE = 1000

        /**
         * Topology kinds that can change the pane catalog or pane identity.
         * The bridge feeds herdr event kinds verbatim and herdr itself is
         * historically inconsistent (snake vs dot), so both spellings are
         * subscribed; extra matches are harmless because every event here is
         * only an invalidation.
         */
        val TOPOLOGY_KINDS: List<String> = listOf(
            "workspace.created", "workspace.updated", "workspace.closed",
            "workspace.focused", "workspace.renamed",
            "tab.created", "tab.closed", "tab.focused", "tab.renamed",
            "pane.created", "pane.closed", "pane.updated", "pane.focused",
            "pane.moved", "pane.exited", "layout.updated",
            "workspace_created", "workspace_updated", "workspace_closed",
            "workspace_focused", "workspace_renamed",
            "tab_created", "tab_closed", "tab_focused", "tab_renamed",
            "pane_created", "pane_closed", "pane_updated", "pane_focused",
            "pane_moved", "pane_exited", "layout_updated",
        )
    }
}
