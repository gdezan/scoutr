package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var connectionStore: ConnectionStore
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        connectionStore = ConnectionStore(RuntimeEnvironment.getApplication())
        saveConnection(server.url("/").toString().trimEnd('/'))
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connectionStore)
        viewModel = ReviewViewModel(bridge, connectionStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun saveConnection(baseUrl: String) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cockpit_connection", android.content.Context.MODE_PRIVATE).edit()
            .putString("host", baseUrl)
            .putString("token", "test-token")
            .apply()
    }

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun waitFor(timeoutMs: Long = 4000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            runBlocking { delay(25) }
            if (condition()) return true
        }
        return false
    }

    @Test
    fun openPickerLoadsDirectoryListing() {
        enqueueJson("""{"ok":true,"listing":{"path":"/home/test","dirs":["repo-a","repo-b"]}}""")
        viewModel.openPicker()

        val settled = waitFor { viewModel.ui.value.dirs.isNotEmpty() }
        assertTrue("dirs never loaded", settled)
        assertEquals(listOf("repo-a", "repo-b"), viewModel.ui.value.dirs)
        assertEquals("/home/test", viewModel.ui.value.dirPath)
        assertNull(viewModel.ui.value.repoPath)
    }

    @Test
    fun browseIntoAppendsToCurrentPath() {
        enqueueJson("""{"ok":true,"listing":{"path":"/home/test","dirs":["repo-a"]}}""")
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        enqueueJson("""{"ok":true,"listing":{"path":"/home/test/repo-a","dirs":[]}}""")
        viewModel.browseInto("repo-a")

        val settled = waitFor { viewModel.ui.value.dirPath == "/home/test/repo-a" }
        assertTrue("browseInto never applied", settled)
    }

    @Test
    fun selectRepoLoadsOverview() {
        enqueueJson("""{"ok":true,"listing":{"path":"/home/test","dirs":[]}}""")
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        enqueueJson(
            """{"ok":true,"path":"/home/test/repo-a","root":"/home/test/repo-a","branch":"main",
               "status":[{"code":" M","path":"a.txt"}],"statusTruncated":false,
               "log":[{"hash":"abc123","subject":"initial commit","author":"A","date":1700000000}],
               "logTruncated":false}""",
        )
        viewModel.selectRepo("/home/test/repo-a")

        val settled = waitFor { viewModel.ui.value.overview != null }
        assertTrue("overview never loaded", settled)
        assertEquals("main", viewModel.ui.value.overview?.branch)
        assertEquals(1, viewModel.ui.value.overview?.status?.size)
        assertEquals("initial commit", viewModel.ui.value.overview?.log?.first()?.subject)
        assertEquals("/home/test/repo-a", viewModel.ui.value.repoPath)
    }

    @Test
    fun selectRepoErrorSurfaces() {
        enqueueJson("""{"ok":true,"listing":{"path":"/home/test","dirs":[]}}""")
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        enqueueJson("""{"ok":false,"error":"path outside allowed repo roots"}""", code = 403)
        viewModel.selectRepo("/etc/passwd")

        val settled = waitFor { viewModel.ui.value.error != null }
        assertTrue("error never surfaced", settled)
        assertTrue(viewModel.ui.value.error.orEmpty().contains("bridge 403"))
        assertNull(viewModel.ui.value.repoPath)
    }

    @Test
    fun loadDiffFetchesBoundedDiff() {
        enqueueJson("""{"ok":true,"listing":{"path":"/home/test","dirs":["repo-a"]}}""")
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        enqueueJson(
            """{"ok":true,"path":"/home/test/repo-a","root":"/home/test/repo-a","branch":"main",
               "status":[],"statusTruncated":false,"log":[],"logTruncated":false}""",
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { viewModel.ui.value.overview != null }

        enqueueJson(
            """{"ok":true,"diff":"--- a/a.txt\n+++ b/a.txt\n@@ -1 +1,2 @@\n hello\n+world\n",
               "truncated":false,"stat":[{"path":"a.txt","additions":1,"deletions":0}]}""",
        )
        viewModel.loadDiff("HEAD")

        val settled = waitFor { viewModel.ui.value.diff != null }
        assertTrue("diff never loaded", settled)
        assertEquals("HEAD", viewModel.ui.value.diffRef)
        assertTrue(viewModel.ui.value.diff?.diff.orEmpty().contains("+world"))
        assertEquals(1, viewModel.ui.value.diff?.stat?.size)
    }

    @Test
    fun refreshReloadsCurrentRepo() {
        enqueueJson("""{"ok":true,"listing":{"path":"/home/test","dirs":[]}}""")
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        enqueueJson(
            """{"ok":true,"path":"/home/test/repo-a","root":"/home/test/repo-a","branch":"main",
               "status":[],"statusTruncated":false,"log":[],"logTruncated":false}""",
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { viewModel.ui.value.overview != null }

        enqueueJson(
            """{"ok":true,"path":"/home/test/repo-a","root":"/home/test/repo-a","branch":"main",
               "status":[{"code":"??","path":"new.txt"}],"statusTruncated":false,
               "log":[],"logTruncated":false}""",
        )
        viewModel.refresh()

        val settled = waitFor { viewModel.ui.value.overview?.status?.isNotEmpty() == true }
        assertTrue("refresh never applied", settled)
        assertEquals("??", viewModel.ui.value.overview?.status?.first()?.code)
    }
}
