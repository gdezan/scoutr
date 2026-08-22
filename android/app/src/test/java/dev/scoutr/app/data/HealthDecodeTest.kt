package dev.scoutr.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes `/api/health` exactly as the bridge emits it (bridge/src/routes/health.ts).
 *
 * Hand-built DTO fixtures cannot catch a shape drift between the two halves:
 * the terminal capability lives one level down, under `terminal.capability`,
 * and decoding it flat yields a null status that reads as "supported" — which
 * left the terminal route reconnecting forever against a bridge that had
 * already settled on `unsupported`.
 */
class HealthDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Captured verbatim from a live bridge whose herdr client failed its handshake. */
    private val unsupportedHealth = """
        {"ok":true,"service":"scoutr-bridge","version":"0.1.0",
         "api":{"protocol":2,"features":["terminal.v1"]},
         "herdr":{"connected":true,"version":"0.8.2","protocol":20},
         "terminal":{"capability":{"status":"unsupported","installedVersion":"0.8.0",
           "required":"0.8.0","reason":"observer handshake failed: herdr exited before first frame"}},
         "push":{"fcm":true}}
    """.trimIndent()

    @Test
    fun `the bridge's nested terminal capability decodes as unsupported`() {
        val health = json.decodeFromString(HealthResponse.serializer(), unsupportedHealth)
        val capability = health.terminal?.capability
        assertTrue(capability?.isUnsupported == true)
        assertEquals("0.8.0", capability?.installedVersion)
        assertTrue(capability?.reason?.startsWith("observer handshake failed") == true)
    }

    @Test
    fun `a provisional unverified capability does not block the route`() {
        val health = json.decodeFromString(
            HealthResponse.serializer(),
            """{"ok":true,"terminal":{"capability":{"status":"unverified","herdrVersion":"0.8.2",
               "protocol":20,"reason":"no-pane"}}}""",
        )
        assertFalse(health.terminal?.capability?.isUnsupported == true)
    }

    @Test
    fun `a bridge that reports no terminal object at all is not treated as unsupported`() {
        val health = json.decodeFromString(HealthResponse.serializer(), """{"ok":true}""")
        assertFalse(health.terminal?.capability?.isUnsupported == true)
    }

    @Test
    fun `the bridge installation identity decodes and older bridges stay null`() {
        val withHostId = json.decodeFromString(
            HealthResponse.serializer(),
            """{"ok":true,"hostId":"host_live1"}""",
        )
        assertEquals("host_live1", withHostId.hostId)

        val withoutHostId = json.decodeFromString(HealthResponse.serializer(), """{"ok":true}""")
        assertEquals(null, withoutHostId.hostId)
    }
}
