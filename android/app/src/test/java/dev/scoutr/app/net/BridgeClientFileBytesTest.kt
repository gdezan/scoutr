package dev.scoutr.app.net

import android.content.Context
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
 * `GET /api/file/bytes` streams binary to disk like the APK download, so it
 * shares the resume/restart/truncation contract: the bytes must land intact,
 * `?path=` must be query-encoded (never concatenated), the bearer token must
 * travel, and bridge failures must surface as [BridgeException].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeClientFileBytesTest {

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
            ConnectionStore(app, FakeConnectionCipher()),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadWorkspaceFile streams the body and encodes the path`() = runBlocking {
        val png = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("content-type", "image/png")
                .setBody(Buffer().write(png)),
        )
        val destination = File(temp.root, "pic.png")
        val progress = mutableListOf<Pair<Long, Long>>()

        val written = client.downloadWorkspaceFile(destination, "/workspace/my pic.png") { bytes, total ->
            progress += bytes to total
        }

        assertEquals(png.size.toLong(), written)
        assertEquals(png.toList(), destination.readBytes().toList())
        assertTrue("progress must be reported more than once", progress.size > 1)

        val request = server.takeRequest()
        assertEquals("/api/file/bytes?path=%2Fworkspace%2Fmy%20pic.png", request.path)
        assertEquals("/workspace/my pic.png", request.requestUrl?.queryParameter("path"))
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `downloadWorkspaceFile resumes from the staged bytes`() = runBlocking {
        val png = ByteArray(200_000) { (it % 251).toByte() }
        val staged = 80_000
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("content-range", "bytes $staged-${png.size - 1}/${png.size}")
                .setBody(Buffer().write(png.copyOfRange(staged, png.size))),
        )
        val destination = File(temp.root, "pic.png")
        destination.writeBytes(png.copyOfRange(0, staged))

        val written = client.downloadWorkspaceFile(destination, "/workspace/pic.png", staged.toLong())

        assertEquals(png.size.toLong(), written)
        assertEquals(png.toList(), destination.readBytes().toList())

        val request = server.takeRequest()
        assertEquals("bytes=$staged-", request.getHeader("Range"))
    }

    @Test
    fun `downloadWorkspaceFile restarts from zero when a range is answered with 200`() = runBlocking {
        val png = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(png)),
        )
        val destination = File(temp.root, "pic.png")
        destination.writeBytes(png.copyOfRange(0, 80_000))

        val written = client.downloadWorkspaceFile(destination, "/workspace/pic.png", 80_000L)

        assertEquals(png.size.toLong(), written)
        assertEquals(png.toList(), destination.readBytes().toList())
    }

    @Test
    fun `downloadWorkspaceFile rejects a resumed body that stops short`() = runBlocking {
        val png = ByteArray(200_000) { (it % 251).toByte() }
        val staged = 80_000
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(Buffer().write(png.copyOfRange(staged, png.size - 5_000)))
                .setHeader("content-length", (png.size - staged).toString())
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
        val destination = File(temp.root, "pic.png")
        destination.writeBytes(png.copyOfRange(0, staged))

        val failure = runCatching {
            client.downloadWorkspaceFile(destination, "/workspace/pic.png", staged.toLong())
        }.exceptionOrNull()

        assertTrue("a short resumed body must fail, not silently stage a partial file", failure != null)
    }

    @Test
    fun `downloadWorkspaceFile surfaces the bridge reason`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"ok":false,"error":"no such file"}"""),
        )
        val destination = File(temp.root, "pic.png")

        val failure = runCatching {
            client.downloadWorkspaceFile(destination, "/workspace/missing.png")
        }.exceptionOrNull()

        assertTrue("must be a BridgeException", failure is BridgeException)
        assertEquals(404, (failure as BridgeException).status)
        assertTrue(failure.message!!.contains("no such file"))
    }
}
