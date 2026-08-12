package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.DirListing
import dev.cockpit.app.data.DirListingResponse
import dev.cockpit.app.data.RepoCommit
import dev.cockpit.app.data.RepoDiffFileStat
import dev.cockpit.app.data.RepoDiffResponse
import dev.cockpit.app.data.RepoOverviewResponse
import dev.cockpit.app.data.RepoStatusEntry
import dev.cockpit.app.net.BridgeException
import dev.cockpit.app.net.FakeCockpitApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewViewModelTest {

    private lateinit var fake: FakeCockpitApi
    private lateinit var connectionStore: ConnectionStore
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
        connectionStore = ConnectionStore(RuntimeEnvironment.getApplication())
        saveConnection()
        viewModel = ReviewViewModel(fake, connectionStore, ReviewStore(RuntimeEnvironment.getApplication()))
    }

    private fun saveConnection() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cockpit_connection", android.content.Context.MODE_PRIVATE).edit()
            .putString("host", "http://test-bridge")
            .putString("token", "test-token")
            .apply()
    }

    private fun stubDirs(path: String = "/home/test", dirs: List<String> = emptyList()) {
        fake.dirsResult = Result.success(DirListingResponse(ok = true, listing = DirListing(path = path, dirs = dirs)))
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
        stubDirs(dirs = listOf("repo-a", "repo-b"))
        viewModel.openPicker()

        val settled = waitFor { viewModel.ui.value.dirs.isNotEmpty() }
        assertTrue("dirs never loaded", settled)
        assertEquals(listOf("repo-a", "repo-b"), viewModel.ui.value.dirs)
        assertEquals("/home/test", viewModel.ui.value.dirPath)
        assertNull(viewModel.ui.value.repoPath)
    }

    @Test
    fun browseIntoAppendsToCurrentPath() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        stubDirs(path = "/home/test/repo-a")
        viewModel.browseInto("repo-a")

        val settled = waitFor { viewModel.ui.value.dirPath == "/home/test/repo-a" }
        assertTrue("browseInto never applied", settled)
    }

    @Test
    fun selectRepoLoadsOverview() {
        stubDirs()
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(
                ok = true,
                path = "/home/test/repo-a",
                root = "/home/test/repo-a",
                branch = "main",
                status = listOf(RepoStatusEntry(code = " M", path = "a.txt")),
                log = listOf(RepoCommit(hash = "abc123", subject = "initial commit", author = "A", date = 1700000000)),
            ),
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
        stubDirs()
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        fake.repoOverviewResult = Result.failure(BridgeException(403, "path outside allowed repo roots"))
        viewModel.selectRepo("/etc/passwd")

        val settled = waitFor { viewModel.ui.value.error != null }
        assertTrue("error never surfaced", settled)
        assertTrue(viewModel.ui.value.error.orEmpty().contains("bridge 403"))
        assertNull(viewModel.ui.value.repoPath)
    }

    @Test
    fun loadDiffFetchesBoundedDiff() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(ok = true, path = "/home/test/repo-a", root = "/home/test/repo-a", branch = "main"),
        )
        fake.repoArtifactsResult = Result.success(
            dev.cockpit.app.data.RepoArtifactsResponse(ok = true),
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { viewModel.ui.value.overview != null }

        fake.repoDiffResult = Result.success(
            RepoDiffResponse(
                ok = true,
                diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1 +1,2 @@\n hello\n+world\n",
                truncated = false,
                stat = listOf(RepoDiffFileStat(path = "a.txt", additions = 1, deletions = 0)),
            ),
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
        stubDirs()
        viewModel.openPicker()
        waitFor { viewModel.ui.value.dirs.isNotEmpty() }

        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(ok = true, path = "/home/test/repo-a", root = "/home/test/repo-a", branch = "main"),
        )
        fake.repoArtifactsResult = Result.success(
            dev.cockpit.app.data.RepoArtifactsResponse(ok = true),
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { viewModel.ui.value.overview != null }

        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(
                ok = true,
                path = "/home/test/repo-a",
                root = "/home/test/repo-a",
                branch = "main",
                status = listOf(RepoStatusEntry(code = "??", path = "new.txt")),
            ),
        )
        viewModel.refresh()

        val settled = waitFor { viewModel.ui.value.overview?.status?.isNotEmpty() == true }
        assertTrue("refresh never applied", settled)
        assertEquals("??", viewModel.ui.value.overview?.status?.first()?.code)
    }
}