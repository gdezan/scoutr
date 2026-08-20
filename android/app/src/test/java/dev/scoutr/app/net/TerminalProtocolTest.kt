package dev.scoutr.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-format contract for /ws/terminal text frames (mirror of bridge protocol.ts). */
class TerminalProtocolTest {

    // --- parseServerMessage: server → client ---

    @Test
    fun ready_frame_parses() {
        val parsed = parseServerMessage(
            """{"type":"ready","version":1,"generation":7,"paneId":"w1:p1","mode":"control","cols":80,"rows":24,"reset":true}""",
        )
        val ready = parsed as ServerFrameParse.Message
        val message = ready.message as TerminalServerMessage.Ready
        assertEquals(7, message.generation)
        assertEquals("w1:p1", message.paneId)
        assertEquals(TerminalMode.CONTROL, message.modeEnum)
        assertEquals(80, message.cols)
        assertEquals(24, message.rows)
        assertTrue(message.reset)
    }

    @Test
    fun ownership_and_closed_and_error_parse() {
        val ownership = (parseServerMessage(
            """{"type":"ownership","generation":3,"mode":"observe","canTakeover":true}""",
        ) as ServerFrameParse.Message).message as TerminalServerMessage.Ownership
        assertEquals(TerminalMode.OBSERVE, ownership.modeEnum)
        assertTrue(ownership.canTakeover)

        val closed = (parseServerMessage(
            """{"type":"closed","generation":3,"reason":"released"}""",
        ) as ServerFrameParse.Message).message as TerminalServerMessage.Closed
        assertEquals(TerminalProtocol.CLOSED_RELEASED, closed.reason)

        val error = (parseServerMessage(
            """{"type":"error","generation":3,"code":"pane_not_found","message":"gone","retryable":true}""",
        ) as ServerFrameParse.Message).message as TerminalServerMessage.Error
        assertEquals("pane_not_found", error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun unknown_fields_are_tolerated() {
        val ready = (parseServerMessage(
            """{"type":"ready","generation":1,"paneId":"p","mode":"observe","cols":80,"rows":24,"futureField":42}""",
        ) as ServerFrameParse.Message).message as TerminalServerMessage.Ready
        assertEquals(1, ready.generation)
    }

    @Test
    fun malformed_frames_are_stable_errors() {
        assertTrue(parseServerMessage("not json") is ServerFrameParse.Malformed)
        assertTrue(parseServerMessage("""{"generation":1}""") is ServerFrameParse.Malformed)
        assertTrue(parseServerMessage("""{"type":"ready"}""") is ServerFrameParse.Malformed)
        assertTrue(parseServerMessage("""{"type":"mystery","generation":1}""") is ServerFrameParse.Malformed)
        // Wrong field type for a required field is malformed too.
        assertTrue(
            parseServerMessage("""{"type":"closed","generation":"nope","reason":"released"}""") is
                ServerFrameParse.Malformed,
        )
    }

    // --- Client messages encode to the exact wire shape ---

    @Test
    fun hello_encodes_exact_shape() {
        val json = protocolJson.encodeToString(
            TerminalClientMessage.Hello.serializer(),
            TerminalClientMessage.Hello(
                paneId = "w1:p1",
                cols = 80,
                rows = 24,
                intent = "takeover",
            ),
        )
        val element = protocolJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        assertEquals("hello", element["type"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("w1:p1", element["paneId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(80, element["cols"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }?.toInt())
        assertEquals("takeover", element["intent"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun resize_and_release_encode_exact_shape() {
        val resize = protocolJson.encodeToString(
            TerminalClientMessage.Resize.serializer(),
            TerminalClientMessage.Resize(cols = 100, rows = 40),
        )
        val element = protocolJson.parseToJsonElement(resize) as kotlinx.serialization.json.JsonObject
        assertEquals("resize", element["type"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(100, element["cols"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }?.toInt())

        val release = protocolJson.encodeToString(
            TerminalClientMessage.Release.serializer(),
            TerminalClientMessage.Release(),
        )
        assertEquals(
            "release",
            (protocolJson.parseToJsonElement(release) as kotlinx.serialization.json.JsonObject)["type"]
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content },
        )
    }

    // --- Rejected upgrades (non-101; no socket ever opened) ---

    @Test
    fun upgradeRejection_carrying_an_unsupported_capability_is_final() {
        // Verbatim shape of server.ts rejectUpgrade's 503 body.
        val rejection = parseUpgradeRejection(
            status = 503,
            body = """{"ok":false,"error":"herdr 0.7.0 is too old",
                "terminal":{"capability":{"status":"unsupported","installedVersion":"0.7.0",
                "required":"0.8.0 or newer","reason":"herdr 0.7.0 is too old"}}}""",
        )
        assertEquals(TerminalProtocol.ERROR_UNSUPPORTED, rejection.code)
        assertEquals("herdr 0.7.0 is too old", rejection.message)
        // The bridge never re-probes a settled capability, so a reconnect cannot help.
        assertFalse(rejection.retryable)
    }

    @Test
    fun anyOtherRejection_is_a_retryable_verdict_that_names_the_status() {
        val probeFailed = parseUpgradeRejection(
            status = 503,
            body = """{"ok":false,"error":"terminal capability check failed"}""",
        )
        assertEquals(TerminalProtocol.ERROR_UPGRADE_REJECTED, probeFailed.code)
        assertTrue(probeFailed.message.contains("503"))
        assertTrue(probeFailed.message.contains("terminal capability check failed"))
        assertTrue(probeFailed.retryable)

        // 401 carries no body at all.
        val unauthorized = parseUpgradeRejection(status = 401, body = null)
        assertEquals(TerminalProtocol.ERROR_UPGRADE_REJECTED, unauthorized.code)
        assertTrue(unauthorized.message.contains("401"))
    }

    @Test
    fun aRejectionBodyThatIsNotJson_still_yields_a_usable_message() {
        val rejection = parseUpgradeRejection(status = 502, body = "<html>nginx</html>")
        assertEquals(TerminalProtocol.ERROR_UPGRADE_REJECTED, rejection.code)
        assertTrue(rejection.message.contains("502"))
    }

    // --- Outbound queue bound (shared with TerminalSocketClient) ---

    @Test
    fun outboundQueueAllows_enforces_frame_and_budget_bounds() {
        assertTrue(outboundQueueAllows(queuedBytes = 0, frameBytes = 1, maxBytes = 256))
        assertTrue(outboundQueueAllows(queuedBytes = 100, frameBytes = 156, maxBytes = 256))
        assertFalse(outboundQueueAllows(queuedBytes = 100, frameBytes = 157, maxBytes = 256))
        assertFalse(outboundQueueAllows(queuedBytes = 0, frameBytes = 0, maxBytes = 256))
        assertFalse(outboundQueueAllows(queuedBytes = 0, frameBytes = TerminalProtocol.INPUT_MAX_BYTES + 1, maxBytes = 1_000_000))
    }
}
