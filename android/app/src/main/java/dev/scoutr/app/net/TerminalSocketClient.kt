package dev.scoutr.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException

/**
 * Production [TerminalTransport]: one OkHttp WebSocket per [TerminalSocket]
 * on the dedicated /ws/terminal route.
 *
 * Ownership rules implemented here (plan "Transport and security"):
 *  - bearer Authorization header only — never a query token (the bridge
 *    rejects query tokens on /ws/terminal);
 *  - `hello` is the first and only client message we send unprompted; resize
 *    and release are gated on writable state exactly like binary input;
 *  - text frames are JSON control messages parsed in order, binary frames
 *    are raw terminal bytes (opcode classification, no discriminator byte);
 *  - the outbound queue is bounded: a frame that would exceed the budget is
 *    rejected and the socket ends with error(input_backpressure) instead of
 *    buffering without limit (never silently drop bytes inside a live
 *    generation);
 *  - malformed/unknown server frames close with error(protocol_error);
 *  - after closed/error/failure/cancel no further listener events are
 *    delivered and sendInput/resize return false (callbacks cannot escape).
 *
 * All listener calls happen on OkHttp's WebSocket thread, serialized per
 * socket; the ViewModel re-dispatches terminal bytes onto its own IO
 * dispatcher.
 */
class TerminalSocketClient(
    private val okHttp: OkHttpClient,
    private val outboundQueueMaxBytes: Long = DEFAULT_OUTBOUND_QUEUE_MAX_BYTES,
    private val performanceCounters: PerformanceCounters? = null,
) : TerminalTransport {

    override fun open(request: TerminalOpenRequest, listener: TerminalTransportListener): TerminalSocket {
        val wsUrl = wsUrl(request.host) + "/ws/terminal"
        val socket = Socket(request, listener)
        val webSocket = okHttp.newWebSocket(
            Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer ${request.token}")
                .build(),
            socket.wsListener,
        )
        socket.attach(webSocket)
        return socket
    }

    private inner class Socket(
        private val request: TerminalOpenRequest,
        private val transportListener: TerminalTransportListener,
    ) : TerminalSocket {

        @Volatile private var webSocket: WebSocket? = null
        @Volatile private var writable = false
        @Volatile private var ended = false
        private var performanceSocket: PerformanceCounters.SocketHandle? = null
        internal val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!beginPerformanceSocket()) {
                    webSocket.close(NORMAL_CLOSE, "replaced")
                    return
                }
                webSocket.send(encode(TerminalClientMessage.Hello(
                    paneId = request.paneId,
                    cols = request.cols,
                    rows = request.rows,
                    intent = if (request.intent == TerminalIntent.TAKEOVER) "takeover" else "auto",
                )))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended) return
                when (val parsed = parseServerMessage(text)) {
                    is ServerFrameParse.Message -> when (val message = parsed.message) {
                        is TerminalServerMessage.Ready -> {
                            writable = message.modeEnum == TerminalMode.CONTROL
                            transportListener.onReady(message)
                        }
                        is TerminalServerMessage.Ownership -> transportListener.onOwnership(message)
                        is TerminalServerMessage.Closed -> {
                            transportListener.onClosed(message)
                            endSocket()
                        }
                        is TerminalServerMessage.Error -> {
                            transportListener.onError(message)
                            endSocket()
                        }
                    }
                    is ServerFrameParse.Malformed -> {
                        // Mirror the bridge: a malformed frame is a stable protocol error.
                        transportListener.onError(TerminalServerMessage.Error(
                            generation = -1,
                            code = TerminalProtocol.ERROR_PROTOCOL,
                            message = parsed.reason,
                            retryable = false,
                        ))
                        endSocket()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (ended) return
                transportListener.onBytes(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ended) return
                endSocket()
                // A rejected upgrade (the bridge answers non-101 when the
                // capability re-probe fails) arrives here as a plain protocol
                // exception; keep the status code so the route can say more
                // than "socket failed".
                val rejection = response?.let { IOException("terminal unavailable (HTTP ${it.code})") }
                transportListener.onFailure(
                    rejection ?: if (t is IOException) t else IOException("terminal socket failure: ${t.message}", t),
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Server-initiated close without a closed/error frame: treat as abrupt EOF.
                if (ended) return
                endSocket()
                transportListener.onFailure(IOException("terminal socket closed (code $code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ended) return
                endSocket()
                if (code != NORMAL_CLOSE) {
                    transportListener.onFailure(IOException("terminal socket closed (code $code)"))
                }
            }
        }

        fun attach(webSocket: WebSocket) {
            this.webSocket = webSocket
        }

        override fun sendInput(bytes: ByteArray): Boolean {
            val ws = webSocket ?: return false
            if (ended || !writable) return false
            if (!outboundQueueAllows(ws.queueSize(), bytes.size, outboundQueueMaxBytes)) {
                // Never buffer without limit: end with the bridge's backpressure code
                // so the caller recovers through a fresh generation.
                transportListener.onError(TerminalServerMessage.Error(
                    generation = -1,
                    code = "input_backpressure",
                    message = "terminal outbound queue overflow",
                    retryable = false,
                ))
                endSocket()
                return false
            }
            return ws.send(bytes.toByteString())
        }

        override fun resize(cols: Int, rows: Int): Boolean {
            val ws = webSocket ?: return false
            if (ended || !writable) return false
            return ws.send(encode(TerminalClientMessage.Resize(cols = cols, rows = rows)))
        }

        override fun release() {
            val ws = webSocket ?: return
            if (!ended) {
                // release is valid from hello onward (not gated on writable).
                ws.send(encode(TerminalClientMessage.Release()))
            }
            endSocket()
        }

        override fun cancel() {
            endSocket()
            webSocket?.cancel()
        }

        private fun beginPerformanceSocket(): Boolean = synchronized(this) {
            if (ended) return@synchronized false
            performanceSocket = performanceCounters?.beginSocket(PerformanceCounters.SocketKind.Terminal)
            true
        }

        @Synchronized
        private fun endSocket() {
            ended = true
            writable = false
            performanceSocket?.close()
            performanceSocket = null
        }
    }

    private inline fun <reified T : TerminalClientMessage> encode(message: T): String =
        protocolJson.encodeToString(message)

    companion object {
        /** Plan "Backpressure": bound the client outbound queue before it can grow unbounded. */
        const val DEFAULT_OUTBOUND_QUEUE_MAX_BYTES = 256L * 1024

        /** OkHttp's normal WebSocket close code (RFC 6455). */
        const val NORMAL_CLOSE = 1000

        internal fun wsUrl(host: String): String {
            val base = host.trim().trimEnd('/')
            return when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> "ws://$base"
            }
        }
    }
}
