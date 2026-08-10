package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * Read-only review center against a local mock bridge: repo picker, branch +
 * status + log overview, and a bounded diff of the working tree.
 */
class ReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

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
                               "log":[{"hash":"a1b2c3d4","subject":"feat: add review center","author":"Ada","date":${System.currentTimeMillis() / 1000}},
                                      {"hash":"e5f6a7b8","subject":"fix: poll loop","author":"Bob","date":${System.currentTimeMillis() / 1000 - 3600}}],
                               "logTruncated":false}""",
                        )
                    path == "/api/repo/diff" ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"ok":true,"diff":"--- a/src/server.ts\n+++ b/src/server.ts\n@@ -1,3 +1,4 @@\n import x\n+new line\n",
                               "truncated":false,"stat":[{"path":"src/server.ts","additions":1,"deletions":0}]}""",
                        )
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
                ReviewScreen(vm)
            }
        }
        compose.onNodeWithText("worktrees").assertIsDisplayed()
        compose.onNodeWithText("projects").assertIsDisplayed()
    }

    @Test
    fun overviewShowsBranchStatusAndCommits() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                ReviewScreen(vm)
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
    }

    @Test
    fun workingTreeDiffShowsBoundedLines() {
        stubApi()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                ReviewScreen(vm)
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
        compose.onNodeWithText("1 files").assertIsDisplayed()
    }
}
