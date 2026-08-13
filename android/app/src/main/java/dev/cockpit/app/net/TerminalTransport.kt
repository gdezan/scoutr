package dev.cockpit.app.net

import java.io.IOException

/**
 * Transport seams for the terminal route (plan "Android transport modules").
 *
 * [TerminalTransport] is the small surface the ViewModel talks to; the one
 * production implementation is [TerminalSocketClient] (OkHttp). The fake
 * lives in commonTest and is shared by unit/instrumentation tests. The
 * interface deliberately never exposes OkHttp callbacks: everything arrives
 * through [TerminalTransportListener] on the transport's own thread, in
 * per-socket order.
 *
 * Generation safety is a two-layer contract:
 *  - the bridge sends exactly one generation per WebSocket and tags every
 *    message with its generation;
 *  - the ViewModel tags callbacks by socket instance and ignores callbacks
 *    from a replaced socket (`open` returns a new instance; the old one must
 *    stop delivering after it was closed/replaced).
 */

/** One terminal pane stream. Exactly one generation lives on this socket. */
interface TerminalSocket {
    /**
     * Send raw widget-produced input bytes (ordered, never replayed after
     * reconnect). Returns false when the socket is not writable yet/already
     * (no hello-ready, observe mode, closed) or the bounded outbound queue
     * rejects the frame; false means "not sent, do not retry it later".
     */
    fun sendInput(bytes: ByteArray): Boolean

    /** Send a coalesced controller resize. False when not writable. */
    fun resize(cols: Int, rows: Int): Boolean

    /** Explicit end: sends `release` and tears the socket down. Idempotent. */
    fun release()

    /**
     * Hard teardown without release (app backgrounding / route cleared):
     * the bridge keeps the child in grace and discards output. Idempotent.
     */
    fun cancel()
}

/** Callback slots for one [TerminalSocket]; invoked in frame order. */
interface TerminalTransportListener {
    /** ready(reset=true) — a new generation; must reset emulator state before feeding bytes. */
    fun onReady(message: TerminalServerMessage.Ready)

    /** Observe fallback announcement (right after ready when observing). */
    fun onOwnership(message: TerminalServerMessage.Ownership)

    /** The socket ended (released/replaced/pane_closed/taken_over/shutdown); nothing follows. */
    fun onClosed(message: TerminalServerMessage.Closed)

    /** The socket ended with a stable error (protocol_error/unsupported/...); nothing follows. */
    fun onError(message: TerminalServerMessage.Error)

    /** Raw terminal bytes, exactly as received. The receiver must not retain the array. */
    fun onBytes(bytes: ByteArray)

    /**
     * Transport-level failure (abrupt EOF, IO error, rejected upgrade)
     * without a server message. The socket is dead; the caller decides
     * whether to reconnect.
     */
    fun onFailure(error: IOException)
}

data class TerminalOpenRequest(
    val host: String,
    val token: String,
    val paneId: String,
    val cols: Int,
    val rows: Int,
    val intent: TerminalIntent,
)

interface TerminalTransport {
    fun open(request: TerminalOpenRequest, listener: TerminalTransportListener): TerminalSocket
}

/**
 * Route-scoped /ws feed client for topology invalidations (plan: never a
 * global Android feed; runs only while Terminal is started). Feed events are
 * invalidations: the ViewModel refreshes GET /api/snapshot rather than
 * maintaining a second partial catalog. The bridge re-emits a snapshot after
 * (re)subscribe, so a reconnect surfaces through [Listener.onSnapshot].
 */
interface TopologyFeed {
    /** Begin streaming. Safe to call when already running; false when no connection is configured. */
    fun start(): Boolean

    /** Stop streaming and cancel any reconnect backoff. Idempotent. */
    fun stop()

    interface Listener {
        /** A topology kind arrived (workspace/tab/pane/layout); refresh the snapshot. */
        fun onTopologyEvent(kind: String)

        /** The bridge re-sent its snapshot (initial subscribe or reconnect resync). */
        fun onSnapshot()

        /**
         * Permanent failure (e.g. rejected upgrade). Abrupt EOF is retried
         * internally with bounded backoff and only surfaces here once retries
         * are exhausted; typed so the ViewModel never sees an escaped
         * exception.
         */
        fun onFeedFailure(error: IOException)
    }

    fun interface Factory {
        fun create(listener: Listener): TopologyFeed
    }
}
