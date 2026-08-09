package dev.cockpit.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun parsesFullV1Payload() {
        val parsed = PairingPayloadParser.parse(
            """{"v":1,"host":"https://artemis.tail7dc568.ts.net","token":"cockpit_secret","ntfy":{"url":"https://artemis.tail7dc568.ts.net/ntfy","topic":"cockpit_topic"}}""",
        )
        assertEquals(
            PairingPayload(
                host = "https://artemis.tail7dc568.ts.net",
                token = "cockpit_secret",
                ntfyUrl = "https://artemis.tail7dc568.ts.net/ntfy",
                ntfyTopic = "cockpit_topic",
            ),
            parsed,
        )
    }

    @Test
    fun parsesMinimalPayloadWithoutNtfy() {
        val parsed = PairingPayloadParser.parse("""{"v":1,"host":"http://127.0.0.1:8737","token":"cockpit_abc"}""")
        assertEquals(PairingPayload(host = "http://127.0.0.1:8737", token = "cockpit_abc"), parsed)
    }

    @Test
    fun prependsHttpsToSchemeLessHost() {
        val parsed = PairingPayloadParser.parse("""{"v":1,"host":"artemis.tail7dc568.ts.net","token":"t"}""")
        assertEquals("https://artemis.tail7dc568.ts.net", parsed?.host)
    }

    @Test
    fun rejectsGarbageWrongVersionAndMissingFields() {
        assertNull(PairingPayloadParser.parse("not json"))
        assertNull(PairingPayloadParser.parse("""{"v":2,"host":"h","token":"t"}"""))
        assertNull(PairingPayloadParser.parse("""{"v":1,"host":"","token":"t"}"""))
        assertNull(PairingPayloadParser.parse("""{"v":1,"host":"h"}"""))
        assertNull(PairingPayloadParser.parse("""{"v":1,"token":"t"}"""))
        // ntfy with only one field is dropped, not fatal; the scheme-less
        // host is normalized to https://
        assertEquals(
            PairingPayload(host = "https://h", token = "t"),
            PairingPayloadParser.parse("""{"v":1,"host":"h","token":"t","ntfy":{"url":"u"}}"""),
        )
    }
}
