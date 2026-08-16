package dev.scoutr.app.state

import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileViewerViewModelTest {
    private lateinit var fake: FakeScoutrApi

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
        val viewModel = FileViewerViewModel(fake, "/workspace", ".plans/notes.md")

        assertTrue("file never loaded", waitFor { viewModel.ui.value.content is Loadable.Ready })
        assertEquals("/workspace/.plans/notes.md", fake.calls.single { it.name == "file" }.args["path"])
        val body = (viewModel.ui.value.content as Loadable.Ready).value
        assertEquals("# Notes\n", body.content)
        assertTrue(body.truncated)
    }

    @Test
    fun exposesMissingAndBinaryResponsesAsReadyData() {
        fake.fileResult = Result.success(FileReadResponse(exists = false))
        val missing = FileViewerViewModel(fake, "/workspace", "missing.md")
        assertTrue("missing response never loaded", waitFor { missing.ui.value.content is Loadable.Ready })
        assertEquals(false, (missing.ui.value.content as Loadable.Ready).value.exists)

        fake.fileResult = Result.success(FileReadResponse(binary = true, exists = true))
        val binary = FileViewerViewModel(fake, "/workspace", "image.dat")
        assertTrue("binary response never loaded", waitFor { binary.ui.value.content is Loadable.Ready })
        assertTrue((binary.ui.value.content as Loadable.Ready).value.binary)
    }

    @Test
    fun mapsBridgeFailureToRetryableFailure() {
        fake.fileResult = Result.failure(BridgeException(500, "permission denied"))
        val viewModel = FileViewerViewModel(fake, "/workspace", "secret.txt")

        assertTrue("failure never surfaced", waitFor { viewModel.ui.value.content is Loadable.Failed })
        assertTrue((viewModel.ui.value.content as Loadable.Failed).reason.contains("bridge 500"))
    }

    @Test
    fun blankPathFailsWithoutCallingBridge() {
        val viewModel = FileViewerViewModel(fake, "/workspace", "")

        assertTrue(viewModel.ui.value.content is Loadable.Failed)
        assertTrue(fake.calls.none { it.name == "file" })
    }
}
