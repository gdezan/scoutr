package dev.scoutr.app.state

import dev.scoutr.app.data.FileListing
import dev.scoutr.app.data.FileListingResponse
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
class FileBrowserViewModelTest {
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
    fun loadsHiddenListingAndDrillsDirectChildren() {
        val cwd = "/workspace"
        fake.filesResult = Result.success(
            FileListingResponse(
                ok = true,
                listing = FileListing(
                    path = cwd,
                    files = listOf("README.md", ".plans/notes.md", "src/main.kt", "src/test.kt"),
                ),
            ),
        )
        val viewModel = FileBrowserViewModel(fake, cwd)

        assertTrue("listing never loaded", waitFor { viewModel.ui.value.listing is Loadable.Ready })
        assertEquals(true, fake.calls.single { it.name == "files" }.args["includeHidden"])
        assertEquals(listOf(".plans", "src", "README.md"), viewModel.ui.value.children.map { it.name })

        viewModel.drill(viewModel.ui.value.children.first { it.name == "src" })
        assertEquals("src/", viewModel.ui.value.directory)
        assertEquals(listOf("main.kt", "test.kt"), viewModel.ui.value.children.map { it.name })

        viewModel.backDirectory()
        assertEquals("", viewModel.ui.value.directory)
    }

    @Test
    fun exposesBridgeFailureForRetry() {
        fake.filesResult = Result.success(FileListingResponse(ok = false, error = "workspace closed"))
        val viewModel = FileBrowserViewModel(fake, "/workspace")

        assertTrue("failure never surfaced", waitFor { viewModel.ui.value.listing is Loadable.Failed })
        assertEquals("workspace closed", (viewModel.ui.value.listing as Loadable.Failed).reason)
    }

    @Test
    fun blankWorkspaceFailsWithoutCallingBridge() {
        val viewModel = FileBrowserViewModel(fake, "")

        assertTrue(viewModel.ui.value.listing is Loadable.Failed)
        assertTrue(fake.calls.none { it.name == "files" })
    }
}
