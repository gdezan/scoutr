package dev.cockpit.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * /ws/terminal wire protocol v1 (mirror of bridge/src/terminal/protocol.ts —
 * the bridge is the contract source; these DTOs must stay in lockstep).
 *
 * Client → server JSON text: hello (first, accepted once), resize, release.
 * Server → client JSON text: ready (reset:true precedes the generation's
 * first binary frame), ownership (observe fallback), closed (ends socket),
 * error (ends socket). Binary frames are raw terminal bytes in both
 * directions; opcode determines meaning, there is no discriminator byte.
 */
object TerminalProtocol {
    /** The only protocol version the bridge speaks (v1). */
    const val VERSION = 1

    /** One client JSON control message, bytes on the wire (server bound). */
    const val JSON_MAX_BYTES = 64 * 1024

    /** One binary input frame (server bound; mirrors process maxInputBytes). */
    const val INPUT_MAX_BYTES = 64 * 1024

    const val MAX_COLS = 500
    const val MAX_ROWS = 500

    /** Server error codes the VM maps to state (protocol.ts). */
    const val ERROR_UNSUPPORTED = "unsupported"
    const val ERROR_PROTOCOL = "protocol_error"

    /** Server closed reasons (protocol.ts). */
    const val CLOSED_RELEASED = "released"
    const val CLOSED_REPLACED = "replaced"
    const val CLOSED_PANE_CLOSED = "pane_closed"
    const val CLOSED_TAKEN_OVER = "taken_over"
    const val CLOSED_SHUTDOWN = "shutdown"
}

enum class TerminalIntent { AUTO, TAKEOVER }

enum class TerminalMode { CONTROL, OBSERVE }

/** One client JSON control message, serialized for the wire. */
sealed interface TerminalClientMessage {
    val type: String

    @Serializable
    data class Hello(
        override val type: String = "hello",
        val version: Int = TerminalProtocol.VERSION,
        val paneId: String,
        val cols: Int,
        val rows: Int,
        val intent: String,
    ) : TerminalClientMessage {
        fun intentEnum(): TerminalIntent =
            if (intent == "takeover") TerminalIntent.TAKEOVER else TerminalIntent.AUTO
    }

    @Serializable
    data class Resize(
        override val type: String = "resize",
        val cols: Int,
        val rows: Int,
    ) : TerminalClientMessage

    @Serializable
    data class Release(override val type: String = "release") : TerminalClientMessage
}

/** One server JSON control message for the current generation. */
sealed interface TerminalServerMessage {
    val type: String
    val generation: Long

    /** Writable or read-only controller; always precedes this generation's first binary frame. */
    @Serializable
    data class Ready(
        override val type: String = "ready",
        val version: Int = TerminalProtocol.VERSION,
        override val generation: Long,
        val paneId: String,
        val mode: String,
        val cols: Int,
        val rows: Int,
        val reset: Boolean = true,
    ) : TerminalServerMessage {
        val modeEnum: TerminalMode get() = if (mode == "observe") TerminalMode.OBSERVE else TerminalMode.CONTROL
    }

    /** Sent right after ready when the settled mode is observe. */
    @Serializable
    data class Ownership(
        override val type: String = "ownership",
        override val generation: Long,
        val mode: String,
        val canTakeover: Boolean,
    ) : TerminalServerMessage {
        val modeEnum: TerminalMode get() = if (mode == "observe") TerminalMode.OBSERVE else TerminalMode.CONTROL
    }

    /** The socket ends after closed; nothing else follows on it. */
    @Serializable
    data class Closed(
        override val type: String = "closed",
        override val generation: Long,
        val reason: String,
    ) : TerminalServerMessage

    /** The socket ends after error; nothing else follows on it. */
    @Serializable
    data class Error(
        override val type: String = "error",
        override val generation: Long,
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : TerminalServerMessage
}

/** Result of parsing one server text frame: a message or a stable protocol violation. */
sealed interface ServerFrameParse {
    data class Message(val message: TerminalServerMessage) : ServerFrameParse
    data class Malformed(val reason: String) : ServerFrameParse
}

internal val protocolJson = Json {
    ignoreUnknownKeys = true
    // The wire contract requires `type` (and hello's `version`) on every frame.
    encodeDefaults = true
}

/**
 * Parse one server text frame in order. Malformed JSON, an unknown message
 * type, or missing fields yields [ServerFrameParse.Malformed] — the client
 * reports it as error(protocol_error) and closes, mirroring the bridge's own
 * malformed-frame handling.
 */
fun parseServerMessage(text: String): ServerFrameParse {
    val element = runCatching { protocolJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return ServerFrameParse.Malformed("invalid JSON")
    val type = (element["type"] as? JsonPrimitive)?.content
        ?: return ServerFrameParse.Malformed("missing type")
    val generation = (element["generation"] as? JsonPrimitive)?.content
        ?.toLongOrNull()
        ?: return ServerFrameParse.Malformed("missing generation")
    return try {
        when (type) {
            "ready" -> ServerFrameParse.Message(
                protocolJson.decodeFromJsonElement(TerminalServerMessage.Ready.serializer(), element),
            )
            "ownership" -> ServerFrameParse.Message(
                protocolJson.decodeFromJsonElement(TerminalServerMessage.Ownership.serializer(), element),
            )
            "closed" -> ServerFrameParse.Message(
                protocolJson.decodeFromJsonElement(TerminalServerMessage.Closed.serializer(), element),
            )
            "error" -> ServerFrameParse.Message(
                protocolJson.decodeFromJsonElement(TerminalServerMessage.Error.serializer(), element),
            )
            else -> ServerFrameParse.Malformed("unknown message type $type")
        }
    } catch (t: Throwable) {
        ServerFrameParse.Malformed("invalid $type message: ${t.message ?: "decode failed"}")
    }
}

/**
 * Pure outbound-queue bound check shared by the socket client and its tests:
 * a frame is rejected (never silently truncated) when the socket's current
 * queue plus the frame exceeds the budget, matching the bridge's
 * input_backpressure policy.
 */
fun outboundQueueAllows(queuedBytes: Long, frameBytes: Int, maxBytes: Long): Boolean =
    frameBytes in 1..TerminalProtocol.INPUT_MAX_BYTES && queuedBytes + frameBytes <= maxBytes
