package dev.scoutr.app.state

import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.DirListing
import dev.scoutr.app.data.DirListingResponse
import dev.scoutr.app.data.RepoCommit
import dev.scoutr.app.data.RepoDiffFileStat
import dev.scoutr.app.data.RepoDiffResponse
import dev.scoutr.app.data.RepoOverviewResponse
import dev.scoutr.app.data.RepoStatusEntry
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.state.Loadable
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

    private lateinit var fake: FakeScoutrApi
    private lateinit var connectionStore: ConnectionStore
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        connectionStore = ConnectionStore(RuntimeEnvironment.getApplication())
        saveConnection()
        viewModel = ReviewViewModel(fake, connectionStore, ReviewStore(RuntimeEnvironment.getApplication()))
    }

    private fun saveConnection() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("scoutr_connection", android.content.Context.MODE_PRIVATE).edit()
            .putString("host", "http://test-bridge")
            .putString("token", "test-token")
            .apply()
    }

    private fun stubDirs(path: String = "/home/test", dirs: List<String> = emptyList()) {
        fake.dirsResult = Result.success(DirListingResponse(ok = true, listing = DirListing(path = path, dirs = dirs)))
    }

    private fun <T> readyOf(load: Loadable<T>): T? = (load as? Loadable.Ready)?.value

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

        val settled = waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }
        assertTrue("dirs never loaded", settled)
        assertEquals(listOf("repo-a", "repo-b"), readyOf(viewModel.ui.value.dirs))
        assertEquals("/home/test", viewModel.ui.value.dirPath)
        assertNull(viewModel.ui.value.repoPath)
    }

    @Test
    fun browseIntoAppendsToCurrentPath() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }

        stubDirs(path = "/home/test/repo-a")
        viewModel.browseInto("repo-a")

        val settled = waitFor { viewModel.ui.value.dirPath == "/home/test/repo-a" }
        assertTrue("browseInto never applied", settled)
    }

    @Test
    fun selectRepoLoadsOverview() {
        stubDirs()
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }

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

        val settled = waitFor { viewModel.ui.value.overview is Loadable.Ready }
        assertTrue("overview never loaded", settled)
        val overview = readyOf(viewModel.ui.value.overview)
        assertEquals("main", overview?.branch)
        assertEquals(1, overview?.status?.size)
        assertEquals("initial commit", overview?.log?.first()?.subject)
        assertEquals("/home/test/repo-a", viewModel.ui.value.repoPath)

        // Review lands on the working tree's file list, so the stat listing is
        // part of arriving rather than a second tap (§9c).
        val diffSettled = waitFor { viewModel.ui.value.diff is Loadable.Ready }
        assertTrue("working diff never auto-loaded", diffSettled)
        assertEquals("HEAD", viewModel.ui.value.diffRef)
        assertEquals("working", viewModel.ui.value.diffKind)
    }

    @Test
    fun selectRepoErrorSurfaces() {
        stubDirs()
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }

        fake.repoOverviewResult = Result.failure(BridgeException(403, "path outside allowed repo roots"))
        viewModel.selectRepo("/etc/passwd")

        val settled = waitFor { (viewModel.ui.value.overview as? Loadable.Failed) != null }
        assertTrue("error never surfaced", settled)
        assertTrue((viewModel.ui.value.overview as? Loadable.Failed)?.reason.orEmpty().contains("bridge 403"))
        assertNull(viewModel.ui.value.repoPath)
    }

    @Test
    fun loadDiffFetchesBoundedDiff() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }

        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(ok = true, path = "/home/test/repo-a", root = "/home/test/repo-a", branch = "main"),
        )
        fake.repoDiffResult = Result.success(
            RepoDiffResponse(
                ok = true,
                truncated = false,
                stat = listOf(RepoDiffFileStat(path = "a.txt", additions = 1, deletions = 0)),
            ),
        )
        fake.repoFileDiffResult = Result.success(
            dev.scoutr.app.data.RepoFileDiffResponse(ok = true, diff = "+world\n", truncated = false),
        )
        viewModel.selectRepo("/home/test/repo-a")

        val settled = waitFor { readyOf(viewModel.ui.value.diff)?.stat?.isNotEmpty() == true }
        assertTrue("diff never loaded", settled)
        assertEquals("HEAD", viewModel.ui.value.diffRef)
        assertEquals(1, readyOf(viewModel.ui.value.diff)?.stat?.size)

        // Selecting a file lazily fetches its per-file diff.
        viewModel.selectFile("a.txt")
        val fileSettled = waitFor { viewModel.ui.value.fileDiff is Loadable.Ready }
        assertTrue("file diff never loaded", fileSettled)
        assertTrue(readyOf(viewModel.ui.value.fileDiff)?.diff.orEmpty().contains("+world"))
    }

    @Test
    fun selectFileCachesPerFileDiff() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }
        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(ok = true, path = "/home/test/repo-a", root = "/home/test/repo-a", branch = "main"),
        )
        fake.repoDiffResult = Result.success(
            RepoDiffResponse(
                ok = true,
                truncated = false,
                stat = listOf(RepoDiffFileStat(path = "a.txt", additions = 1, deletions = 0)),
            ),
        )
        fake.repoFileDiffResult = Result.success(
            dev.scoutr.app.data.RepoFileDiffResponse(ok = true, diff = "+world\n", truncated = false),
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { readyOf(viewModel.ui.value.diff)?.stat?.isNotEmpty() == true }

        viewModel.selectFile("a.txt")
        waitFor { viewModel.ui.value.fileDiff is Loadable.Ready }
        val afterFirst = fake.calls.count { it.name == "repoFileDiff" }
        assertEquals("first selection should fetch the file diff", 1, afterFirst)

        viewModel.selectFile("a.txt")
        waitFor { viewModel.ui.value.fileDiff is Loadable.Ready }
        assertEquals("cached file diff should not refetch", afterFirst, fake.calls.count { it.name == "repoFileDiff" })
    }

    @Test
    fun fileViewLazilyLoadsContentAndCloseResets() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }
        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(ok = true, path = "/home/test/repo-a", root = "/home/test/repo-a", branch = "main"),
        )
        fake.repoDiffResult = Result.success(
            RepoDiffResponse(
                ok = true,
                truncated = false,
                stat = listOf(RepoDiffFileStat(path = "a.txt", additions = 1, deletions = 0)),
            ),
        )
        fake.repoFileDiffResult = Result.success(
            dev.scoutr.app.data.RepoFileDiffResponse(ok = true, diff = "+world\n", truncated = false),
        )
        fake.repoFileResult = Result.success(
            dev.scoutr.app.data.RepoFileResponse(ok = true, content = "hello\nworld\n", truncated = false, binary = false, exists = true),
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { readyOf(viewModel.ui.value.diff)?.stat?.isNotEmpty() == true }
        viewModel.selectFile("a.txt")
        waitFor { viewModel.ui.value.fileDiff is Loadable.Ready }
        viewModel.setViewMode(DiffViewMode.File)
        val contentSettled = waitFor { viewModel.ui.value.fileContent is Loadable.Ready }
        assertTrue("file content never loaded", contentSettled)
        assertEquals(DiffViewMode.File, viewModel.ui.value.viewMode)

        viewModel.closeFile()
        assertNull(viewModel.ui.value.selectedFile)
        assertEquals(DiffViewMode.Diff, viewModel.ui.value.viewMode)
    }

    @Test
    fun selectingAnotherFileRefetchesContentForTheNewFile() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }
        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(ok = true, path = "/home/test/repo-a", root = "/home/test/repo-a", branch = "main"),
        )
        fake.repoDiffResult = Result.success(
            RepoDiffResponse(
                ok = true,
                truncated = false,
                stat = listOf(
                    RepoDiffFileStat(path = "a.txt", additions = 1, deletions = 0),
                    RepoDiffFileStat(path = "b.txt", additions = 1, deletions = 0),
                ),
            ),
        )
        fake.repoFileDiffResult = Result.success(
            dev.scoutr.app.data.RepoFileDiffResponse(ok = true, diff = "+world\n", truncated = false),
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { readyOf(viewModel.ui.value.diff)?.stat?.isNotEmpty() == true }

        fake.repoFileResult = Result.success(
            dev.scoutr.app.data.RepoFileResponse(ok = true, content = "content of a\n", truncated = false, binary = false, exists = true),
        )
        viewModel.selectFile("a.txt")
        waitFor { viewModel.ui.value.fileDiff is Loadable.Ready }
        viewModel.setViewMode(DiffViewMode.File)
        waitFor { viewModel.ui.value.fileContent is Loadable.Ready }

        fake.repoFileResult = Result.success(
            dev.scoutr.app.data.RepoFileResponse(ok = true, content = "content of b\n", truncated = false, binary = false, exists = true),
        )
        viewModel.selectFile("b.txt")
        waitFor { viewModel.ui.value.fileDiff is Loadable.Ready }
        viewModel.setViewMode(DiffViewMode.File)
        waitFor { viewModel.ui.value.fileContent is Loadable.Ready }

        assertEquals("file view must show the newly selected file's content", "content of b\n", readyOf(viewModel.ui.value.fileContent)?.content)
        assertEquals("each file selection should fetch its own content", 2, fake.calls.count { it.name == "repoFile" })
    }

    @Test
    fun untrackedFileOpensOnContentNotHunks() {
        stubDirs(dirs = listOf("repo-a"))
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }
        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(
                ok = true,
                path = "/home/test/repo-a",
                root = "/home/test/repo-a",
                branch = "main",
                status = listOf(RepoStatusEntry(code = "??", path = "new.txt")),
            ),
        )
        fake.repoFileResult = Result.success(
            dev.scoutr.app.data.RepoFileResponse(ok = true, content = "brand new\n", truncated = false, binary = false, exists = true),
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { viewModel.ui.value.diff is Loadable.Ready }

        // git has no diff for a path it has never seen, so the tile opens on
        // the file itself and must not spend a per-file diff read.
        viewModel.selectFile("new.txt", DiffViewMode.File)
        val settled = waitFor { viewModel.ui.value.fileContent is Loadable.Ready }
        assertTrue("untracked file content never loaded", settled)
        assertEquals("brand new\n", readyOf(viewModel.ui.value.fileContent)?.content)
        assertEquals(0, fake.calls.count { it.name == "repoFileDiff" })
    }

    @Test
    fun refreshReloadsCurrentRepo() {
        stubDirs()
        viewModel.openPicker()
        waitFor { readyOf(viewModel.ui.value.dirs)?.isNotEmpty() == true }

        fake.repoOverviewResult = Result.success(
            RepoOverviewResponse(ok = true, path = "/home/test/repo-a", root = "/home/test/repo-a", branch = "main"),
        )
        viewModel.selectRepo("/home/test/repo-a")
        waitFor { viewModel.ui.value.overview is Loadable.Ready }

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

        val settled = waitFor { readyOf(viewModel.ui.value.overview)?.status?.isNotEmpty() == true }
        assertTrue("refresh never applied", settled)
        assertEquals("??", readyOf(viewModel.ui.value.overview)?.status?.first()?.code)
    }
}