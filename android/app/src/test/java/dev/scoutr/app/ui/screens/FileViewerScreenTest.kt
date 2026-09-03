package dev.scoutr.app.ui.screens

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.state.FileViewerViewModel
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Viewer rendering policy for non-markdown files: they are shown as source, and
 * [CodeLines] emits one row per line into its parent scope — so the viewer must
 * lay those rows out in a column instead of stacking them at the same origin.
 * The markdown branch is not covered here; its renderer cannot load under the
 * JVM test runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileViewerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()
    private fun showFile(file: String, content: String) {
        showTriage(file, FileReadResponse(content = content), ByteArray(0))
    }

    private fun showTriage(file: String, triage: FileReadResponse, bytes: ByteArray) {
        val fake = FakeScoutrApi()
        fake.fileResult = Result.success(triage)
        fake.workspaceFileBytes = bytes
        compose.setContent {
            ScoutrTheme {
                FileViewerScreen(
                    viewModel = FileViewerViewModel(fake, cwd = "/repo", file = file, imageCacheDir = java.io.File(temp.root, "images"), hostKey = "test-host"),
                    onBack = {},
                )
            }
        }
    }
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun sourceFileStacksEveryLineOnItsOwnRow() {
        showFile("src/Beta.kt", "package alpha\n\nfun beta() = 1\n")

        awaitText("package alpha")
        compose.onNodeWithText("SOURCE").assertExists()
        val first = compose.onNodeWithText("package alpha").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithText("fun beta() = 1").getUnclippedBoundsInRoot()
        assertTrue("source lines overlap: $first vs $second", second.top >= first.bottom)
    }

    @Test
    fun imageTriageRendersZoomableSurfaceWithActions() {
        // 1x1 transparent PNG; the viewer downloads it to cache and Coil renders the file.
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        showTriage(
            "pic.png",
            FileReadResponse(binary = true, exists = true, mime = "image/png", sizeBytes = png.size.toLong()),
            png,
        )

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("file_viewer_image").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("file_viewer_open_with").assertExists()
        compose.onNodeWithTag("file_viewer_save").assertExists()
    }

    @Test
    fun svgAsTextStillFallsThroughToBinaryTriage() {
        // The bridge sniffs SVG as text (valid UTF-8); the viewer keeps it in
        // binary triage until an SVG renderer exists.
        showTriage(
            "logo.svg",
            FileReadResponse(content = "<svg/>", binary = false, exists = true, mime = "image/svg+xml"),
            ByteArray(0),
        )

        awaitText("Binary file")
        assertTrue(compose.onAllNodesWithTag("file_viewer_image").fetchSemanticsNodes().isEmpty())
    }
    @Test
    fun pdfKeepsHandoffTriage() {
        showTriage(
            "doc.pdf",
            FileReadResponse(binary = true, exists = true, mime = "application/pdf"),
            ByteArray(0),
        )

        awaitText("PDF handoff is coming")
    }
}
