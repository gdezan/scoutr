package dev.cockpit.app.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import org.json.JSONObject
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.ReviewStore
import dev.cockpit.app.state.ReviewViewModel
import dev.cockpit.app.ui.screens.ReviewScreen
import dev.cockpit.app.ui.theme.CockpitTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.FileOutputStream
/**
 * Read-only review center against a local mock bridge: repo picker, branch +
 * status + log overview, and a bounded diff of the working tree.
 */
class ReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun compactReviewPickerKeepsSixteenDpGutters() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.width(320.dp)) {
                        ReviewScreen(vm)
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("review_picker_content")).fetchSemanticsNodes().isNotEmpty()
        }
        val contentBounds = compose.onNodeWithTag("review_picker_content").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("compact review picker should start at 16dp", kotlin.math.abs(contentBounds.left.value - 16f) <= 1f)
        org.junit.Assert.assertTrue("compact review picker should be 288dp wide", kotlin.math.abs((contentBounds.right - contentBounds.left).value - 288f) <= 1f)
    }

    @Test
    fun wideReviewPickerUsesReadableBound() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ReviewScreen(vm)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("review_picker_content")).fetchSemanticsNodes().isNotEmpty()
        }
        val contentBounds = compose.onNodeWithTag("review_picker_content").getUnclippedBoundsInRoot()
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        if (rootBounds.right - rootBounds.left > 1008.dp) {
            org.junit.Assert.assertTrue("wide review picker should be 960dp", kotlin.math.abs((contentBounds.right - contentBounds.left).value - 960f) <= 1f)
            org.junit.Assert.assertTrue("wide review picker should be centered", kotlin.math.abs(((contentBounds.left + contentBounds.right) - (rootBounds.left + rootBounds.right)).value) <= 2f)
            capture(if (largeFontEnabled()) "review-wide-large-font" else "review-wide", "review_picker_content")
        }
    }

    private fun largeFontEnabled(): Boolean {
        val requested = InstrumentationRegistry.getArguments().getString("fontScale") == "1.3"
        if (requested) {
            val applied = Settings.System.getFloat(
                InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
                Settings.System.FONT_SCALE,
            )
            org.junit.Assert.assertTrue("large-font evidence requires font_scale 1.3, was $applied", kotlin.math.abs(applied - 1.3f) <= 0.01f)
        }
        return requested
    }
    private fun capture(name: String, tag: String = "review_capture_root", overlay: Boolean = false) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!.resolve("$name.png")
        FileOutputStream(file).use { output ->
            val bitmap = if (overlay) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            } else {
                compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        println("REVIEW_SCREENSHOT=${file.absolutePath}")
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun stubApi() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path == "/api/dirs" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"ok":true,"listing":{"path":"/home/user","dirs":["worktrees","projects"]}}""",
                        )
                    path == "/api/repo" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"ok":true,"path":"/home/user/worktrees/cockpit","root":"/home/user/worktrees/cockpit","branch":"main",
                               "status":[{"code":" M","path":"src/server.ts"},{"code":"??","path":"notes.txt"}],"statusTruncated":false,
                               "log":[{"hash":"a1b2c3d4","subject":"feat: add review center","author":"Ada","date":${System.currentTimeMillis() / 1000},"body":"Adds a read-only review center.\nShows status, log, and per-file diffs."},
                                      {"hash":"e5f6a7b8","subject":"fix: poll loop","author":"Bob","date":${System.currentTimeMillis() / 1000 - 3600}}],
                               "logTruncated":false}""",
                        )
                    path == "/api/repo/diff" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"ok":true,"truncated":false,"stat":[{"path":"src/server.ts","additions":1,"deletions":0},{"path":"notes.txt","additions":1,"deletions":0}]}""",
                        )
                    path == "/api/repo/diff/file" -> {
                        val file = request.requestUrl?.queryParameter("file") ?: ""
                        val diff = if (file == "notes.txt") {
                            """--- a/notes.txt
+++ b/notes.txt
@@ -0,0 +1 @@
+note
"""
                        } else {
                            """--- a/src/server.ts
+++ b/src/server.ts
@@ -1,3 +1,4 @@
 import x
+new line
"""
                        }
                        MockResponse().setResponseCode(200).setBody(
                            """{"ok":true,"diff":${JSONObject.quote(diff)},"truncated":false}""",
                        )
                    }
                    path == "/api/repo/file" -> {
                        val file = request.requestUrl?.queryParameter("file") ?: ""
                        val content = if (file == "notes.txt") "note\n" else "import x\nnew line\n"
                        MockResponse().setResponseCode(200).setBody(
                            """{"ok":true,"content":${JSONObject.quote(content)},"truncated":false,"binary":false,"exists":true}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"not found"}""")
                }
            }
        }
    }

    private fun viewModel(): ReviewViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = ConnectionStore(context).apply {
            save(server.url("/").toString().trimEnd('/'), "test-token", null, null)
        }
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connection)
        return ReviewViewModel(bridge, connection, ReviewStore(context))
    }

    @Test
    fun pickerShowsDirectoriesAndSelectsRepo() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ReviewScreen(vm)
                }
            }
        }
        compose.onNodeWithText("worktrees").assertIsDisplayed()
        compose.onNodeWithText("projects").assertIsDisplayed()
    }

    @Test
    fun wideReviewOverviewIsBoundedAndDiffStaysFullWidth() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ReviewScreen(vm)
                }
            }
        }
        compose.onNodeWithText("worktrees").performClick()
        compose.onNodeWithTag("review_select").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Working tree")).fetchSemanticsNodes().isNotEmpty()
        }
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        val overviewBounds = compose.onNodeWithTag("review_capture_root").getUnclippedBoundsInRoot()
        if (rootBounds.right - rootBounds.left > 1008.dp) {
            org.junit.Assert.assertTrue("wide review overview should be 960dp", kotlin.math.abs((overviewBounds.right - overviewBounds.left).value - 960f) <= 1f)
        }

        compose.onNodeWithText("diff vs HEAD").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("+new line")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("diff_file_selector")).fetchSemanticsNodes().isNotEmpty()
        }
        val diffBounds = compose.onNodeWithTag("review_capture_root").getUnclippedBoundsInRoot()
        if (rootBounds.right - rootBounds.left > 1008.dp) {
            org.junit.Assert.assertTrue(
                "Review diff should span the widened root",
                kotlin.math.abs((diffBounds.right - diffBounds.left).value - (rootBounds.right - rootBounds.left).value) <= 1f,
            )
        }
    }

    @Test
    fun overviewShowsBranchStatusAndCommits() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ReviewScreen(vm)
                }
            }
        }
        // Drill to the repo dir and select it as the review target.
        compose.onNodeWithText("worktrees").performClick()
        compose.onNodeWithTag("review_select").performClick()

        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Working tree")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("branch main · 2 changed").assertIsDisplayed()
        compose.onNodeWithText("src/server.ts").assertIsDisplayed()
        compose.onNodeWithText("notes.txt").assertIsDisplayed()
        compose.onNodeWithText("feat: add review center").assertIsDisplayed()
        capture("diff-overview")
    }

    @Test
    fun workingTreeDiffShowsBoundedLines() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ReviewScreen(vm)
                }
            }
        }
        compose.onNodeWithText("worktrees").performClick()
        compose.onNodeWithTag("review_select").performClick()

        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Working tree")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("diff vs HEAD").performClick()

        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("+new line")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("+new line").assertIsDisplayed()
        capture("diff-file-1")
    }

    @Test
    fun filePickerSheetListsFilesAndSwitchesSelection() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ReviewScreen(vm)
                }
            }
        }
        compose.onNodeWithText("worktrees").performClick()
        compose.onNodeWithTag("review_select").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Working tree")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("diff vs HEAD").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("diff_file_selector")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("diff_file_selector").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("diff_file_sheet")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("diff_file_0").assertIsDisplayed()
        compose.onNodeWithTag("diff_file_1").assertIsDisplayed()

        compose.onNodeWithTag("diff_file_1").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("2 / 2  notes.txt", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5000) {
            // The per-file fetch must actually swap the body to notes.txt's
            // hunks — a stale-body regression would still show "+new line".
            compose.onAllNodes(androidx.compose.ui.test.hasText("+note", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        capture("diff-file-2")
    }

    @Test
    fun commitSheetShowsBodyAndOpensDiff() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ReviewScreen(vm)
                }
            }
        }
        compose.onNodeWithText("worktrees").performClick()
        compose.onNodeWithTag("review_select").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Working tree")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("feat: add review center").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("commit_body")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Adds a read-only review center.", substring = true).assertIsDisplayed()
        capture("commit-sheet", overlay = true)

        compose.onNodeWithTag("commit_diff_button").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("diff_file_selector")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
