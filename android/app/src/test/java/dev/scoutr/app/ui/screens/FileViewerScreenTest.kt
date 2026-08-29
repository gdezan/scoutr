package dev.scoutr.app.ui.screens

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.scoutr.app.data.FileReadResponse
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.state.FileViewerViewModel
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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

    private fun showFile(file: String, content: String) {
        val fake = FakeScoutrApi()
        fake.fileResult = Result.success(FileReadResponse(content = content))
        compose.setContent {
            ScoutrTheme {
                FileViewerScreen(
                    viewModel = FileViewerViewModel(fake, cwd = "/repo", file = file),
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
}
