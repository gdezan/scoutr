package dev.cockpit.app.net

import android.content.Context
import kotlinx.coroutines.runBlocking
import dev.cockpit.app.data.ConnectionStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the image-upload 401: `uploadAttachment` must send
 * the real saved token in the Authorization header — not the literal string
 * `Bearer ${'$'}{token()}` that shipped in 2b26111 and made every upload fail
 * with `bridge 401: unauthorized`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeClientUploadTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cockpit_connection", Context.MODE_PRIVATE).edit()
            .putString("host", server.url("/").toString().trimEnd('/'))
            .putString("token", "test-token")
            .apply()
        client = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            ConnectionStore(app))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun uploadAttachment_sendsRealBearerTokenAndBodyBytes() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"path":"/tmp/uploads/abc.png"}""")
        )
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        val response = runBlocking {
            client.uploadAttachment("pic.png", "image/png", pngBytes)
        }

        assertEquals(true, response.ok)
        assertEquals("/tmp/uploads/abc.png", response.path)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/attachments?name=pic.png", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertEquals("image/png", recorded.getHeader("Content-Type"))
        assertArrayEquals(pngBytes, recorded.body.readByteArray())
        assertNotNull(recorded.getHeader("Authorization"))
    }
}
