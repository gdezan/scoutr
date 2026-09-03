package dev.scoutr.app.state

import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileViewerViewModelTest {
    private lateinit var fake: FakeScoutrApi

    @get:Rule
    val temp = TemporaryFolder()

    private fun viewer(cwd: String, file: String): FileViewerViewModel =
        FileViewerViewModel(fake, cwd, file, java.io.File(temp.root, "images"), "test-host")

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
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
    fun readsCwdRelativeFileAndPreservesResponseFlags() {
        fake.fileResult = Result.success(
            FileReadResponse(content = "# Notes\n", truncated = true, binary = false, exists = true),
        )
        val viewModel = viewer("/workspace", ".plans/notes.md")

        assertTrue("file never loaded", waitFor { viewModel.ui.value.content is Loadable.Ready })
        assertEquals("/workspace/.plans/notes.md", fake.calls.single { it.name == "file" }.args["path"])
        val body = (viewModel.ui.value.content as Loadable.Ready).value
        assertEquals("# Notes\n", body.content)
        assertTrue(body.truncated)
    }

    @Test
    fun joinsAllFilePagesBeforePublishingContent() {
        fake.filePageResults[0L] = Result.success(
            FileReadResponse(content = "head", truncated = true, exists = true, offset = 0L, nextOffset = 4L, totalBytes = 8L),
        )
        fake.filePageResults[4L] = Result.success(
            FileReadResponse(content = "tail", exists = true, offset = 4L, totalBytes = 8L),
        )

        val viewModel = viewer("/workspace", "notes.md")

        assertTrue("paged file never loaded", waitFor { viewModel.ui.value.content is Loadable.Ready })
        val body = (viewModel.ui.value.content as Loadable.Ready).value
        assertEquals("headtail", body.content)
        assertEquals(false, body.truncated)
        assertEquals(2, fake.calls.count { it.name == "file" })
    }
    @Test
    fun capsAccumulatedPagesBeforePublishingVeryLargeFiles() {
        val pageBytes = 256 * 1024
        val pageContent = "x".repeat(pageBytes)
        repeat(16) { index ->
            val offset = index.toLong() * pageBytes
            fake.filePageResults[offset] = Result.success(
                FileReadResponse(
                    content = pageContent,
                    exists = true,
                    offset = offset,
                    nextOffset = offset + pageBytes,
                    truncated = true,
                ),
            )
        }

        val viewModel = viewer("/workspace", "large.md")

        assertTrue("large file never loaded", waitFor { viewModel.ui.value.content is Loadable.Ready })
        val body = (viewModel.ui.value.content as Loadable.Ready).value
        assertEquals(4 * 1024 * 1024, body.content.toByteArray().size)
        assertTrue(body.truncated)
        assertEquals(16, fake.calls.count { it.name == "file" })
    }

    @Test
    fun exposesMissingAndBinaryResponsesAsReadyData() {
        fake.fileResult = Result.success(FileReadResponse(exists = false))
        val missing = viewer("/workspace", "missing.md")
        assertTrue("missing response never loaded", waitFor { missing.ui.value.content is Loadable.Ready })
        assertEquals(false, (missing.ui.value.content as Loadable.Ready).value.exists)

        fake.fileResult = Result.success(FileReadResponse(binary = true, exists = true))
        val binary = viewer("/workspace", "image.dat")
        assertTrue("binary response never loaded", waitFor { binary.ui.value.content is Loadable.Ready })
        assertTrue((binary.ui.value.content as Loadable.Ready).value.binary)
    }

    @Test
    fun mapsBridgeFailureToRetryableFailure() {
        fake.fileResult = Result.failure(BridgeException(500, "permission denied"))
        val viewModel = viewer("/workspace", "secret.txt")

        assertTrue("failure never surfaced", waitFor { viewModel.ui.value.content is Loadable.Failed })
        assertTrue((viewModel.ui.value.content as Loadable.Failed).reason.contains("bridge 500"))
    }

    @Test
    fun blankPathFailsWithoutCallingBridge() {
        val viewModel = viewer("/workspace", "")

        assertTrue(viewModel.ui.value.content is Loadable.Failed)
        assertTrue(fake.calls.none { it.name == "file" })
    }

    @Test
    fun imageTriageDownloadsToCacheAndExposesReady() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 4),
        )
        fake.workspaceFileBytes = png
        val viewModel = viewer("/workspace", "pic.png")

        assertTrue("image never downloaded", waitFor { viewModel.ui.value.imageFile is Loadable.Ready })
        val file = (viewModel.ui.value.imageFile as Loadable.Ready).value
        assertEquals(png.toList(), file.readBytes().toList())
        assertEquals("/workspace/pic.png", fake.calls.single { it.name == "downloadWorkspaceFile" }.args["path"])
    }

    @Test
    fun textTriageNeverDownloads() {
        fake.fileResult = Result.success(FileReadResponse(content = "hello", binary = false, exists = true))
        val viewModel = viewer("/workspace", "notes.md")

        assertTrue("file never loaded", waitFor { viewModel.ui.value.content is Loadable.Ready })
        assertTrue(viewModel.ui.value.imageFile is Loadable.Idle)
        assertTrue(fake.calls.none { it.name == "downloadWorkspaceFile" })
    }

    @Test
    fun overCapTriageShowsTooLargeWithoutDownloading() {
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 21L * 1024 * 1024),
        )
        val viewModel = viewer("/workspace", "huge.png")

        assertTrue("too-large never surfaced", waitFor { viewModel.ui.value.imageFile is Loadable.Failed })
        assertTrue((viewModel.ui.value.imageFile as Loadable.Failed).reason.contains("too large"))
        assertTrue(fake.calls.none { it.name == "downloadWorkspaceFile" })
    }

    @Test
    fun download413MapsToTooLarge() {
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 4),
        )
        fake.downloadWorkspaceFileFailure = BridgeException(413, "file too large")
        val viewModel = viewer("/workspace", "pic.png")

        assertTrue("too-large never surfaced", waitFor { viewModel.ui.value.imageFile is Loadable.Failed })
        assertTrue((viewModel.ui.value.imageFile as Loadable.Failed).reason.contains("too large"))
    }

    @Test
    fun download403MapsToRejected() {
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 4),
        )
        fake.downloadWorkspaceFileFailure = BridgeException(403, "file is outside an active agent workspace")
        val viewModel = viewer("/workspace", "pic.png")

        assertTrue("failure never surfaced", waitFor { viewModel.ui.value.imageFile is Loadable.Failed })
        assertEquals(FailureKind.Rejected, (viewModel.ui.value.imageFile as Loadable.Failed).kind)
    }

    @Test
    fun download404MapsToUnavailable() {
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 4),
        )
        fake.downloadWorkspaceFileFailure = BridgeException(404, "no such file")
        val viewModel = viewer("/workspace", "pic.png")

        assertTrue("failure never surfaced", waitFor { viewModel.ui.value.imageFile is Loadable.Failed })
        assertEquals("File is unavailable", (viewModel.ui.value.imageFile as Loadable.Failed).reason)
    }
    @Test
    fun refreshWhileLoadingSupersedesStaleImage() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.gates["file"] = gate
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 4),
        )
        fake.workspaceFileBytes = byteArrayOf(9, 9, 9)
        val viewModel = viewer("/workspace", "pic.png")

        assertTrue("first read never parked", waitFor { fake.calls.count { it.name == "file" } == 1 })
        fake.fileResult = Result.success(FileReadResponse(content = "fresh", binary = false, exists = true))
        viewModel.refresh()
        assertTrue("second read never started", waitFor { fake.calls.count { it.name == "file" } == 2 })
        gate.complete(Unit)

        assertTrue("fresh content never won", waitFor {
            (viewModel.ui.value.content as? Loadable.Ready)?.value?.content == "fresh"
        })
        assertTrue(viewModel.ui.value.imageFile is Loadable.Idle)
        assertTrue(fake.calls.none { it.name == "downloadWorkspaceFile" })
    }

    @Test
    fun partialPrefixResumesFromStagedBytes() {
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 4),
        )
        fake.workspaceFileBytes = byteArrayOf(1, 2, 3, 4)
        val staged = dev.scoutr.app.state.ImageFileCache(java.io.File(temp.root, "images"))
            .cacheFileFor("test-host", "/workspace/pic.png", 4L, "pic.png")
        staged.parentFile?.mkdirs()
        staged.writeBytes(byteArrayOf(9, 9))
        val viewModel = viewer("/workspace", "pic.png")

        assertTrue("image never downloaded", waitFor { viewModel.ui.value.imageFile is Loadable.Ready })
        assertEquals(2L, fake.calls.single { it.name == "downloadWorkspaceFile" }.args["resumeFrom"])
        assertEquals(4, (viewModel.ui.value.imageFile as Loadable.Ready).value.length().toInt())
    }

    @Test
    fun completePrefixRestartsFromZero() {
        fake.fileResult = Result.success(
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = 4),
        )
        fake.workspaceFileBytes = byteArrayOf(1, 2, 3, 4)
        val staged = dev.scoutr.app.state.ImageFileCache(java.io.File(temp.root, "images"))
            .cacheFileFor("test-host", "/workspace/pic.png", 4L, "pic.png")
        staged.parentFile?.mkdirs()
        staged.writeBytes(byteArrayOf(9, 9, 9, 9))
        val viewModel = viewer("/workspace", "pic.png")

        assertTrue("image never downloaded", waitFor { viewModel.ui.value.imageFile is Loadable.Ready })
        assertEquals(0L, fake.calls.single { it.name == "downloadWorkspaceFile" }.args["resumeFrom"])
        assertEquals(listOf<Byte>(1, 2, 3, 4), (viewModel.ui.value.imageFile as Loadable.Ready).value.readBytes().toList())
    }
}
