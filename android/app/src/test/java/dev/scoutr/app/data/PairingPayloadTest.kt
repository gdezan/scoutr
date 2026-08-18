package dev.scoutr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun parsesFullV1PayloadAsTailscale() {
        val parsed = PairingPayloadParser.parse(
            """{"v":1,"host":"https://artemis.tail7dc568.ts.net","token":"scoutr_secret","ntfy":{"url":"https://artemis.tail7dc568.ts.net/ntfy","topic":"scoutr_topic"}}""",
        )
        assertEquals(
            PairingPayload(
                host = "https://artemis.tail7dc568.ts.net",
                token = "scoutr_secret",
                exposure = ExposureKind.Tailscale,
                ntfyUrl = "https://artemis.tail7dc568.ts.net/ntfy",
                ntfyTopic = "scoutr_topic",
            ),
            parsed,
        )
    }

    @Test
    fun parsesMinimalPayloadWithoutNtfy() {
        val parsed = PairingPayloadParser.parse("""{"v":1,"host":"http://127.0.0.1:8737","token":"scoutr_abc"}""")
        assertEquals(
            PairingPayload(host = "http://127.0.0.1:8737", token = "scoutr_abc", exposure = ExposureKind.Tailscale),
            parsed,
        )
    }

    @Test
    fun prependsHttpsToSchemeLessHost() {
        val parsed = PairingPayloadParser.parse("""{"v":1,"host":"artemis.tail7dc568.ts.net","token":"t"}""")
        assertEquals("https://artemis.tail7dc568.ts.net", parsed?.host)
    }

    @Test
    fun parsesV2CloudflarePayload() {
        val parsed = PairingPayloadParser.parse(
            """{"v":2,"host":"https://scoutr.example.com","token":"scoutr_secret","exposure":{"kind":"cloudflare"},"ntfy":{"url":"https://scoutr-ntfy.example.com","topic":"scoutr_topic"}}""",
        )
        assertEquals(
            PairingPayload(
                host = "https://scoutr.example.com",
                token = "scoutr_secret",
                exposure = ExposureKind.Cloudflare,
                ntfyUrl = "https://scoutr-ntfy.example.com",
                ntfyTopic = "scoutr_topic",
            ),
            parsed,
        )
    }

    @Test
    fun parsesV2CustomPayload() {
        val parsed = PairingPayloadParser.parse(
            """{"v":2,"host":"https://bridge.example.org","token":"scoutr_secret","exposure":{"kind":"custom"}}""",
        )
        assertEquals(
            PairingPayload(
                host = "https://bridge.example.org",
                token = "scoutr_secret",
                exposure = ExposureKind.Custom,
            ),
            parsed,
        )
    }

    @Test
    fun rejectsV2WithMissingUnknownOrMalformedExposure() {
        val host = """"host":"https://scoutr.example.com","token":"t""""
        assertNull("no exposure at all", PairingPayloadParser.parse("""{"v":2,$host}"""))
        assertNull("unknown kind", PairingPayloadParser.parse("""{"v":2,$host,"exposure":{"kind":"ngrok"}}"""))
        assertNull("empty kind", PairingPayloadParser.parse("""{"v":2,$host,"exposure":{"kind":""}}"""))
        assertNull("exposure without a kind", PairingPayloadParser.parse("""{"v":2,$host,"exposure":{}}"""))
        assertNull("exposure is not an object", PairingPayloadParser.parse("""{"v":2,$host,"exposure":"cloudflare"}"""))
        // v2 exists for the providers that skip Tailscale discovery; the
        // bridge never emits a v2 tailscale payload, so neither is accepted.
        assertNull("v2 tailscale", PairingPayloadParser.parse("""{"v":2,$host,"exposure":{"kind":"tailscale"}}"""))
    }

    @Test
    fun rejectsGarbageWrongVersionAndMissingFields() {
        assertNull(PairingPayloadParser.parse("not json"))
        assertNull(PairingPayloadParser.parse("""{"v":3,"host":"h","token":"t"}"""))
        assertNull(PairingPayloadParser.parse("""{"host":"h","token":"t"}"""))
        assertNull(PairingPayloadParser.parse("""{"v":1,"host":"","token":"t"}"""))
        assertNull(PairingPayloadParser.parse("""{"v":1,"host":"h"}"""))
        assertNull(PairingPayloadParser.parse("""{"v":1,"token":"t"}"""))
        assertNull(PairingPayloadParser.parse("""{"v":2,"host":"h","exposure":{"kind":"custom"}}"""))
        // ntfy with only one field is dropped, not fatal; the scheme-less
        // host is normalized to https://
        assertEquals(
            PairingPayload(host = "https://h", token = "t", exposure = ExposureKind.Tailscale),
            PairingPayloadParser.parse("""{"v":1,"host":"h","token":"t","ntfy":{"url":"u"}}"""),
        )
    }
}
