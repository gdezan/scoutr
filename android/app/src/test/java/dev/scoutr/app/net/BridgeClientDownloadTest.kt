package dev.scoutr.app.net

import android.content.Context
import dev.scoutr.app.data.ConnectionStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The APK download is the one bridge call that streams binary to disk instead
 * of decoding a JSON string, so it needs its own coverage: the bytes must land
 * intact, the bearer token must travel, and a bridge 409 (nothing built yet)
 * must surface as a [BridgeException] rather than a corrupt file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeClientDownloadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("scoutr_connection", Context.MODE_PRIVATE).edit()
            .putString("host", server.url("/").toString().trimEnd('/'))
            .putString("token", "test-token")
            .apply()
        client = BridgeClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            ConnectionStore(app),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadApk streams the body to disk and reports progress`() = runBlocking {
        // Larger than the client's 64 KiB copy buffer, so the loop really loops.
        val apk = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "application/vnd.android.package-archive")
                .setBody(Buffer().write(apk)),
        )
        val destination = File(temp.root, "scoutr-update.apk")
        val progress = mutableListOf<Pair<Long, Long>>()

        val written = client.downloadApk(destination) { bytes, total -> progress += bytes to total }

        assertEquals(apk.size.toLong(), written)
        assertEquals(apk.toList(), destination.readBytes().toList())
        assertTrue("progress must be reported more than once", progress.size > 1)
        assertEquals(apk.size.toLong(), progress.last().first)
        assertEquals(apk.size.toLong(), progress.last().second)

        val request = server.takeRequest()
        assertEquals("/api/update/apk", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `downloadApk surfaces the bridge's reason when no APK is built`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"ok":false,"error":"no APK has been built yet"}"""),
        )
        val destination = File(temp.root, "scoutr-update.apk")

        val failure = runCatching { client.downloadApk(destination) }.exceptionOrNull()

        assertTrue("must be a BridgeException", failure is BridgeException)
        assertEquals(409, (failure as BridgeException).status)
        assertTrue(failure.message!!.contains("no APK has been built yet"))
    }
}
