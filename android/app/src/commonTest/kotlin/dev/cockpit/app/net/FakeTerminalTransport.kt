package dev.cockpit.app.net

import java.io.IOException

/**
 * Controllable [TerminalTransport] for unit/instrumentation tests (mirror of
 * FakeCockpitApi). Every [open] produces one [FakeSocket] whose listener the
 * test drives directly (ready/ownership/closed/error/bytes/failure), so the
 * ViewModel's socket-instance tagging and gating can be asserted without a
 * server.
 */
class FakeTerminalTransport : TerminalTransport {

    val openedRequests = mutableListOf<TerminalOpenRequest>()
    val sockets = mutableListOf<FakeSocket>()

    /** The most recent socket (the one the VM currently holds). */
    val lastSocket: FakeSocket get() = sockets.last()

    override fun open(request: TerminalOpenRequest, listener: TerminalTransportListener): TerminalSocket {
        openedRequests += request
        val socket = FakeSocket(listener)
        sockets += socket
        return socket
    }

    class FakeSocket(val listener: TerminalTransportListener) : TerminalSocket {
        val inputFrames = mutableListOf<ByteArray>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        var released = false
        var cancelled = false
        var writable = false
        var ended = false

        override fun sendInput(bytes: ByteArray): Boolean {
            if (ended || !writable) return false
            inputFrames += bytes
            return true
        }

        override fun resize(cols: Int, rows: Int): Boolean {
            if (ended || !writable) return false
            resizes += cols to rows
            return true
        }

        override fun release() {
            released = true
            ended = true
        }

        override fun cancel() {
            cancelled = true
            ended = true
        }

        // --- Test drivers ---

        fun ready(generation: Long, mode: TerminalMode = TerminalMode.CONTROL, cols: Int = 80, rows: Int = 24) {
            writable = mode == TerminalMode.CONTROL
            listener.onReady(
                TerminalServerMessage.Ready(
                    generation = generation,
                    paneId = "p1",
                    mode = if (mode == TerminalMode.OBSERVE) "observe" else "control",
                    cols = cols,
                    rows = rows,
                ),
            )
        }

        fun ownership(canTakeover: Boolean) {
            listener.onOwnership(TerminalServerMessage.Ownership(generation = 1, mode = "observe", canTakeover = canTakeover))
        }

        fun bytes(data: ByteArray) = listener.onBytes(data)

        fun closed(reason: String) =
            listener.onClosed(TerminalServerMessage.Closed(generation = 1, reason = reason))

        fun error(code: String, message: String = "boom", retryable: Boolean = false) =
            listener.onError(TerminalServerMessage.Error(generation = 1, code = code, message = message, retryable = retryable))

        fun failure() = listener.onFailure(IOException("fake transport failure"))
    }
}
