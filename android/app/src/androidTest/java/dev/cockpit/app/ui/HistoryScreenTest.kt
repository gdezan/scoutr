package dev.cockpit.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import android.content.ClipboardManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.SessionCatalogStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.SessionHistoryViewModel
import dev.cockpit.app.ui.screens.HistoryScreen
import dev.cockpit.app.ui.theme.CockpitTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Session history surface against a local mock bridge: catalog rows, search,
 * pin/archive toggles, and destructive-action dialogs.
 */
class HistoryScreenTest {

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

    private val catalogBody = """
        {"ok":true,"truncated":false,"sessions":[
          {"id":"abc","path":"/root/sessions/abc.jsonl","title":"Fix billing bug","cwd":"/repo/a","model":"openai-codex/gpt-5.4","updatedAt":${System.currentTimeMillis()}.0,"preview":"User asked to fix the billing math","active":true,"paneId":"pane1","workspaceId":"ws1","status":"blocked"},
          {"id":"def","path":"/root/sessions/def.jsonl","title":"Docs refresh","cwd":"/repo/b","model":"anthropic/claude-sonnet-4-6","updatedAt":${System.currentTimeMillis() - 3_600_000}.0,"preview":"Update the README","active":false}
        ]}
    """.trimIndent()

    private fun stubCatalog(deleteOk: Boolean = true) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path == "/api/session-catalog" ->
                        MockResponse().setResponseCode(200).setBody(catalogBody)
                    path == "/api/session-catalog/delete" ->
                        MockResponse().setResponseCode(200).setBody("""{"ok":$deleteOk}""")
                    path == "/api/session-catalog/rename" ->
                        MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                    path == "/api/session-catalog/close" || path.contains("/control") ->
                        MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"ok":false}""")
                }
            }
        }
    }

    private fun viewModel(): SessionHistoryViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = ConnectionStore(context)
        connection.save(server.url("/").toString().trimEnd('/'), "test_token", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connection)
        return SessionHistoryViewModel(bridge, connection, RecordingStore())
    }

    private fun setContent(vm: SessionHistoryViewModel) {
        compose.setContent {
            CockpitTheme {
                HistoryScreen(
                    onOpenSession = {},
                    viewModel = vm,
                )
            }
        }
    }

    @Test
    fun rendersActiveRowsWithTitleModelAndCwd() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Fix billing bug").assertIsDisplayed()
        compose.onNodeWithText("gpt-5.4").assertIsDisplayed()
        compose.onNodeWithText("/repo/a").assertIsDisplayed()
    }

    @Test
    fun searchFiltersThroughTheBridge() {
        stubCatalog()
        setContent(viewModel())
        compose.onNodeWithText("Completed").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_def")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_search").performClick().performTextInput("docs")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_def")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Docs refresh").assertIsDisplayed()
    }

    @Test
    fun deleteFlowShowsConfirmationAndConfirms() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        // Switch to the Completed view where delete is enabled for the def row.
        compose.onNodeWithText("Completed").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_def")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_row_menu_def").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete session?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
    }


    @Test
    fun filterChipsStaySingleLineAtNarrowWidth() {
        stubCatalog()
        val vm = viewModel() // build before composition: MockWebServer.url() reverse-DNS
        compose.setContent {
            CockpitTheme {
                // 320dp forces the four chips to overflow a fixed row: the old
                // code wrapped "Archived" onto two lines here, doubling its
                // height; the scrollable row keeps every chip single-line.
                Box(Modifier.width(320.dp)) {
                    HistoryScreen(onOpenSession = {}, viewModel = vm)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        val heights = listOf("Active", "Completed", "Pinned", "Archived").map { name ->
            compose.onNodeWithTag("history_view_$name")
                .getUnclippedBoundsInRoot()
                .let { (it.bottom - it.top).value }
        }
        val max = heights.max()
        heights.forEach { h ->
            assertTrue("chips must be equal height (wrapped chip is ~2x): $heights", abs(h - max) < 1f)
        }
    }

    @Test
    fun copyPathMenuItemCopiesPath() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_row_menu_abc").performClick()
        compose.onNodeWithText("Copy path").performClick()
        compose.waitForIdle()
        val clip = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("clipboard should hold the session cwd", "/repo/a", clip)
    }

    @Test
    fun closeConfirmationDistinguishesFromDelete() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        // Active view: the abc row is active and offers Close.
        compose.onNodeWithTag("history_row_menu_abc").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Close session?").assertIsDisplayed()
    }

    @Test
    fun swipeLeftRevealsRenameAndOpensDialog() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        // The action bar is covered by the card until the row is swiped open.
        compose.onNodeWithTag("history_row_abc").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        // Review (fix 5) is the first revealed action and leads to the session workspace.
        compose.onNodeWithTag("history_row_action_review_abc").assertIsDisplayed()
        compose.onNodeWithTag("history_row_action_rename_abc").performClick()
        compose.onNodeWithText("Rename session").assertIsDisplayed()
    }

    @Test
    fun coveredRowActionIsNotTappable() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        // Without a swipe the card sits on top of the action bar, so a tap at
        // the action's coordinates hits the card instead.
        compose.onNodeWithTag("history_row_action_rename_abc").performClick()
        compose.onNodeWithText("Rename session").assertDoesNotExist()
    }

    @Test
    fun swipeRevealsDeleteOnInactiveRowAndConfirms() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        // The def row is inactive, so it lives in the Completed view, where its
        // revealed bar ends in Delete.
        compose.onNodeWithText("Completed").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_def")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_row_def").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("history_row_action_delete_def").performClick()
        compose.onNodeWithText("Delete session?").assertIsDisplayed()
    }

    @Test
    fun revealedActionBarMatchesRowHeight() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        // The action bar sizes itself from the card (matchParentSize), so a
        // regression where the bar collapses to icon height shows up here.
        val row = compose.onNodeWithTag("history_row_abc").getUnclippedBoundsInRoot()
        val rename = compose.onNodeWithTag("history_row_action_rename_abc").getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((row.bottom - row.top).value - (rename.bottom - rename.top).value) < 1f)
    }

    @Test
    fun emptyStateShowsForArchivedView() {
        stubCatalog()
        setContent(viewModel())
        compose.onNodeWithText("Archived").performClick()
        compose.onNodeWithTag("history_empty").assertIsDisplayed()
    }
}

/** In-memory catalog store for instrumentation tests. */
private class RecordingStore : SessionCatalogStore {
    private val pinned = mutableSetOf<String>()
    private val archived = mutableSetOf<String>()

    override fun pinnedPaths(): Set<String> = pinned.toSet()
    override fun archivedPaths(): Set<String> = archived.toSet()
    override fun setPinned(path: String, pinned: Boolean) {
        if (pinned) this.pinned.add(path) else this.pinned.remove(path)
    }

    override fun setArchived(path: String, archived: Boolean) {
        if (archived) this.archived.add(path) else this.archived.remove(path)
    }
}
