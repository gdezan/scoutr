package dev.scoutr.app.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import android.content.ClipboardManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.SessionCatalogStore
import dev.scoutr.app.net.BridgeClient
import dev.scoutr.app.state.SessionHistoryViewModel
import dev.scoutr.app.ui.screens.HistoryScreen
import dev.scoutr.app.ui.theme.ScoutrTheme
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
import java.io.FileOutputStream

/**
 * Session history surface against a local mock bridge: catalog rows, search,
 * pin/archive toggles, and destructive-action dialogs.
 */
class HistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, overlay: Boolean = false) {
        val file = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!.resolve("$name.png")
        FileOutputStream(file).use { output ->
            val bitmap = if (overlay) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            } else {
                compose.onNodeWithTag("history_content").captureToImage().asAndroidBitmap()
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        println("HISTORY_SCREENSHOT=${file.absolutePath}")
    }

    private fun largeFontEnabled(): Boolean {
        val requested = InstrumentationRegistry.getArguments().getString("fontScale") == "1.3"
        if (requested) {
            val applied = Settings.System.getFloat(
                InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
                Settings.System.FONT_SCALE,
            )
            assertTrue("large-font evidence requires font_scale 1.3, was $applied", abs(applied - 1.3f) <= 0.01f)
        }
        return requested
    }


    @Test
    fun compactHistoryContentKeepsSixteenDpGutters() {
        stubCatalog()
        val vm = viewModel()
        compose.setContent {
            ScoutrTheme {
                Box(Modifier.width(320.dp)) {
                    HistoryScreen(onOpenSession = {}, viewModel = vm)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        val contentBounds = compose.onNodeWithTag("history_content").getUnclippedBoundsInRoot()
        assertTrue("compact sessions should start at 12dp", abs(contentBounds.left.value - 12f) <= 1f)
        assertTrue("compact sessions should be 296dp wide", abs((contentBounds.right - contentBounds.left).value - 296f) <= 1f)
    }

    @Test
    fun wideHistoryContentUsesReadableBound() {
        stubCatalog()
        val vm = viewModel()
        compose.setContent {
            ScoutrTheme {
                HistoryScreen(onOpenSession = {}, viewModel = vm)
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        val contentBounds = compose.onNodeWithTag("history_content").getUnclippedBoundsInRoot()
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        if (rootBounds.right - rootBounds.left > 1008.dp) {
            assertTrue("wide sessions should be 960dp", abs((contentBounds.right - contentBounds.left).value - 960f) <= 1f)
            assertTrue("wide sessions should be centered", abs(((contentBounds.left + contentBounds.right) - (rootBounds.left + rootBounds.right)).value) <= 2f)
            capture(if (largeFontEnabled()) "sessions-wide-large-font" else "sessions-wide")
        }
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

    private fun catalogSession(
        path: String,
        title: String,
        cwd: String,
        updatedAtMs: Long,
        preview: String,
        agentKind: String = "pi",
        model: String? = null,
        paneId: String? = null,
        workspaceId: String = "ws",
        status: String = "working",
    ): String {
        val live = if (paneId == null) {
            "null"
        } else {
            """{"paneId":"$paneId","workspaceId":"$workspaceId","tabId":"tab-$paneId","status":"$status"}"""
        }
        val encodedModel = model?.let { "\"$it\"" } ?: "null"
        val displayName = if (agentKind == "claude") "Claude" else "Pi"
        return """{"session":{"key":{"agentKind":"$agentKind","path":"$path"},"agentKind":"$agentKind","displayName":"$displayName","title":"$title","cwd":"$cwd","model":$encodedModel,"updatedAtMs":$updatedAtMs,"latestActivity":"$preview","live":$live},"createdAtMs":0}"""
    }

    private fun longCatalogBody(removed: Set<String> = emptySet()): String {
        val sessions = buildList {
            repeat(30) { index ->
                val path = "/sessions/active-${index.toString().padStart(2, '0')}.jsonl"
                if (path !in removed) add(catalogSession(path, "Active $index", "/repo/active-$index", 100_000L - index, "active preview $index", paneId = "pane-active-$index"))
            }
            repeat(30) { index ->
                val path = "/sessions/completed-${index.toString().padStart(2, '0')}.jsonl"
                if (path !in removed) add(catalogSession(path, "Completed $index", "/repo/completed-$index", 90_000L - index, "completed preview $index"))
            }
        }
        return """{"ok":true,"truncated":false,"sessions":[${sessions.joinToString(",")}] }"""
    }

    private fun firstVisibleSessionPath(listState: LazyListState): String? =
        listState.layoutInfo.visibleItemsInfo
            .filter { it.index >= listState.firstVisibleItemIndex }
            .mapNotNull { it.key as? String }
            .mapNotNull { dev.scoutr.app.data.decodeSessionKey(it)?.path }
            .firstOrNull()

    private val catalogBody: String
        get() = """{"ok":true,"truncated":false,"sessions":[${catalogSession("/root/sessions/abc.jsonl", "Fix billing bug", "/repo/a", System.currentTimeMillis(), "User asked to fix the billing math", model = "openai-codex/gpt-5.4", paneId = "pane1", workspaceId = "ws1", status = "blocked")},${catalogSession("/root/sessions/def.jsonl", "Docs refresh", "/repo/b", System.currentTimeMillis() - 3_600_000, "Update the README", agentKind = "claude", model = "anthropic/claude-sonnet-4-6")}]}"""

    private val repositoryFilterCatalogBody: String
        get() = """{"ok":true,"truncated":false,"sessions":[${catalogSession("/root/sessions/active-a.jsonl", "Active A", "/repo/a", System.currentTimeMillis(), "active", paneId = "pane-a", workspaceId = "ws1")},${catalogSession("/root/sessions/done-b.jsonl", "Done B", "/repo/b", System.currentTimeMillis() - 3_600_000, "done b")},${catalogSession("/root/sessions/done-c.jsonl", "Done C", "/repo/c", System.currentTimeMillis() - 7_200_000, "done c")}]}"""

    private fun stubCatalog(
        deleteOk: Boolean = true,
        catalog: () -> String = { catalogBody },
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when {
                    path == "/api/session-catalog" ->
                        MockResponse().setResponseCode(200).setBody(catalog())
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

    private fun viewModel(store: SessionCatalogStore = RecordingStore()): SessionHistoryViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = ConnectionStore(context)
        connection.save(server.url("/").toString().trimEnd('/'), "test_token", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connection)
        return SessionHistoryViewModel(bridge, connection, store)
    }

    private fun setContent(vm: SessionHistoryViewModel, listState: LazyListState? = null) {
        compose.setContent {
            ScoutrTheme {
                if (listState == null) {
                    HistoryScreen(onOpenSession = {}, viewModel = vm)
                } else {
                    HistoryScreen(onOpenSession = {}, viewModel = vm, historyListState = listState)
                }
            }
        }
    }

    private fun selectScope(label: String) {
        compose.onNodeWithTag("history_scope_filter").performClick()
        compose.onNodeWithTag("history_scope_$label").performClick()
    }

    @Test
    fun rendersActiveRowsWithTitleModelAndCwd() {
        stubCatalog()
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Fix billing bug").assertIsDisplayed()
        // The status word gave way to the ring plus time-in-state, and the path
        // and model are now one mono machine-fact line (§9c).
        compose.onNodeWithText("/repo/a \u00b7 gpt-5.4").assertIsDisplayed()
        // Only the tile carries the full path now — the repo filter chip is
        // named for the repository, not its path (§9c).
        assertEquals(1, compose.onAllNodesWithText("/repo/a", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("history_repo_/repo/a").assertIsDisplayed()
    }

    @Test
    fun repositoryTabFiltersRowsWithinSelectedScope() {
        stubCatalog(catalog = { repositoryFilterCatalogBody })
        setContent(viewModel())
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_active-a")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("history_row_done-b").assertIsDisplayed()
        compose.onNodeWithTag("history_row_done-c").assertIsDisplayed()
        selectScope("Completed")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_done-b")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_row_done-c").assertIsDisplayed()
        compose.onNodeWithTag("history_repo_/repo/b").performClick()
        compose.onNodeWithTag("history_row_done-b").assertIsDisplayed()
        compose.onNodeWithTag("history_row_done-c").assertDoesNotExist()
    }
    @Test
    fun searchFiltersThroughTheBridge() {
        stubCatalog()
        setContent(viewModel())
        selectScope("Completed")
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
        selectScope("Completed")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_def")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_row_menu_def").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete session?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
    }


    @Test
    fun scopeMenuOffersEveryHistoryScopeAtNarrowWidth() {
        stubCatalog()
        val vm = viewModel() // build before composition: MockWebServer.url() reverse-DNS
        compose.setContent {
            ScoutrTheme {
                Box(Modifier.width(320.dp)) {
                    HistoryScreen(onOpenSession = {}, viewModel = vm)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_abc")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("history_scope_filter").performClick()
        listOf("All", "Active", "Completed", "Pinned", "Archived").forEach { scope ->
            assertTrue(compose.onAllNodesWithText(scope).fetchSemanticsNodes().isNotEmpty())
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
    fun claudeSessionHidesRenameAndFork() {
        stubCatalog()
        setContent(viewModel())
        selectScope("Completed")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("history_row_def")).fetchSemanticsNodes().isNotEmpty()
        }
        // Claude sessions reject rename (title lives in a pi session file) and
        // fork (no fork-at-path launch), so the menu must not offer them.
        compose.onNodeWithTag("history_row_menu_def").performClick()
        compose.onNodeWithText("Fork").assertDoesNotExist()
        compose.onNodeWithText("Rename").assertDoesNotExist()
        compose.onNodeWithText("Copy path").assertIsDisplayed()
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
        selectScope("Completed")
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
    fun eachSessionsTabRestoresItsOwnStableAnchorAndOffset() {
        val pinned = (0 until 15).map { "/sessions/active-${it.toString().padStart(2, '0')}.jsonl" }.toSet()
        val archived = (15 until 30).map { "/sessions/completed-${it.toString().padStart(2, '0')}.jsonl" }.toSet()
        stubCatalog(catalog = ::longCatalogBody)
        val listState = LazyListState()
        setContent(viewModel(RecordingStore(pinned = pinned, archived = archived)), listState)
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/active-00.jsonl" }

        compose.onNodeWithTag("history_list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.waitUntil(2_000) { firstVisibleSessionPath(listState) != "/sessions/active-00.jsonl" }
        compose.waitForIdle()
        val activeAnchor = firstVisibleSessionPath(listState) to listState.firstVisibleItemScrollOffset

        selectScope("Completed")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/completed-00.jsonl" }
        compose.onNodeWithTag("history_list").performTouchInput { swipeUp() }
        compose.waitUntil(2_000) { firstVisibleSessionPath(listState) != "/sessions/completed-00.jsonl" }
        compose.waitForIdle()
        val completedAnchor = firstVisibleSessionPath(listState) to listState.firstVisibleItemScrollOffset

        selectScope("Active")
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == activeAnchor.first }
        assertTrue(kotlin.math.abs(listState.firstVisibleItemScrollOffset - activeAnchor.second) <= 1)
        selectScope("Completed")
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == completedAnchor.first }
        assertTrue(kotlin.math.abs(listState.firstVisibleItemScrollOffset - completedAnchor.second) <= 1)

        selectScope("Pinned")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/active-00.jsonl" }
        compose.onNodeWithTag("history_list").performTouchInput { swipeUp() }
        compose.waitUntil(2_000) { firstVisibleSessionPath(listState) != "/sessions/active-00.jsonl" }
        compose.waitForIdle()
        val pinnedAnchor = firstVisibleSessionPath(listState) to listState.firstVisibleItemScrollOffset

        selectScope("Archived")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/completed-15.jsonl" }
        compose.onNodeWithTag("history_list").performTouchInput { swipeUp() }
        compose.waitUntil(2_000) { firstVisibleSessionPath(listState) != "/sessions/completed-15.jsonl" }
        compose.waitForIdle()
        val archivedAnchor = firstVisibleSessionPath(listState) to listState.firstVisibleItemScrollOffset

        selectScope("Pinned")
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == pinnedAnchor.first }
        selectScope("Archived")
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == archivedAnchor.first }
    }

    @Test
    fun emptyTabRoundTripKeepsOutgoingAnchor() {
        stubCatalog(catalog = ::longCatalogBody)
        val listState = LazyListState()
        setContent(viewModel(), listState)
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/active-00.jsonl" }
        compose.onNodeWithTag("history_list").performTouchInput { swipeUp() }
        compose.waitUntil(2_000) { firstVisibleSessionPath(listState) != "/sessions/active-00.jsonl" }
        compose.waitForIdle()
        val activeAnchor = firstVisibleSessionPath(listState)

        selectScope("Archived")
        compose.onNodeWithTag("history_empty").assertIsDisplayed()
        selectScope("All")
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == activeAnchor }
    }

    @Test
    fun removedSessionsAnchorFallsForwardToNextStablePath() {
        var removed = emptySet<String>()
        stubCatalog(catalog = { longCatalogBody(removed) })
        val listState = LazyListState()
        val vm = viewModel()
        setContent(vm, listState)
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/active-00.jsonl" }
        // Anchor by index, not by a pixel swipe: a swipe travels a fixed
        // distance, so how many rows it crosses depends on row height, and the
        // scroll landed deep enough after the tiles tightened that switching
        // scopes no longer started at the top. The sibling anchor test scrolls
        // by index for the same reason.
        compose.onNodeWithTag("history_list").performScrollToIndex(6)
        compose.waitUntil(2_000) { firstVisibleSessionPath(listState) != "/sessions/active-00.jsonl" }
        compose.waitForIdle()
        val removedPath = firstVisibleSessionPath(listState)!!
        val removedIndex = removedPath.substringAfter("active-").substringBefore('.').toInt()
        val expectedPath = "/sessions/active-${(removedIndex + 1).toString().padStart(2, '0')}.jsonl"
        selectScope("Completed")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/completed-00.jsonl" }

        removed = setOf(removedPath)
        vm.retry()
        compose.waitUntil(5_000) { vm.ui.value.items.none { it.session.path in removed } }
        compose.waitForIdle()
        selectScope("Active")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == expectedPath }
        assertEquals(expectedPath, firstVisibleSessionPath(listState))
    }

    @Test
    fun removedAnchorAndNeighborsFallBackToSavedIndex() {
        var removed = emptySet<String>()
        stubCatalog(catalog = { longCatalogBody(removed) })
        val listState = LazyListState()
        val vm = viewModel()
        setContent(vm, listState)
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/active-00.jsonl" }
        // Anchor deterministically a few rows in — away from the top and far from the
        // end (scrollToItem cannot place a near-bottom index at the top: the list
        // clamps) — then remove the anchor and both of its neighbors.
        compose.onNodeWithTag("history_list").performScrollToIndex(6)
        compose.waitForIdle()
        val anchorPath = firstVisibleSessionPath(listState)!!
        val anchorIndex = anchorPath.substringAfter("active-").substringBefore('.').toInt()
        // Remove the anchor and both of its neighbors; the saved old index must
        // then land on the next surviving item (old anchorIndex + removed count).
        val removedIndexes = (anchorIndex - 1..anchorIndex + 1).filter { it in 0..29 }
        val expectedIndex = (anchorIndex + removedIndexes.size).coerceAtMost(27)
        val expectedPath = "/sessions/active-${expectedIndex.toString().padStart(2, '0')}.jsonl"
        selectScope("Completed")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/completed-00.jsonl" }

        removed = removedIndexes.map { "/sessions/active-${it.toString().padStart(2, '0')}.jsonl" }.toSet()
        vm.retry()
        compose.waitUntil(5_000) { vm.ui.value.items.none { it.session.path in removed } }
        compose.waitForIdle()
        selectScope("Active")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == expectedPath }
        assertEquals(expectedPath, firstVisibleSessionPath(listState))
    }

    @Test
    fun removedAnchorPrefersLaterSurvivorOverPreviousNeighbor() {
        var removed = emptySet<String>()
        stubCatalog(catalog = { longCatalogBody(removed) })
        val listState = LazyListState()
        val vm = viewModel()
        setContent(vm, listState)
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/active-00.jsonl" }
        compose.onNodeWithTag("history_list").performScrollToIndex(6)
        compose.waitForIdle()
        val anchorPath = firstVisibleSessionPath(listState)!!
        val anchorIndex = anchorPath.substringAfter("active-").substringBefore('.').toInt()
        assertEquals("fixture assumption: index 5 must be the anchored row", 5, anchorIndex)
        // Remove the anchor and its next neighbor while the previous neighbor
        // survives: the saved index must prefer the later surviving row over the prior.
        val removedIndexes = setOf(anchorIndex, anchorIndex + 1)
        val expectedIndex = anchorIndex + removedIndexes.size
        val expectedPath = "/sessions/active-${expectedIndex.toString().padStart(2, '0')}.jsonl"
        selectScope("Completed")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == "/sessions/completed-00.jsonl" }

        removed = removedIndexes.map { "/sessions/active-${it.toString().padStart(2, '0')}.jsonl" }.toSet()
        vm.retry()
        compose.waitUntil(5_000) { vm.ui.value.items.none { it.session.path in removed } }
        compose.waitForIdle()
        selectScope("Active")
        compose.waitForIdle()
        compose.waitUntil(5_000) { firstVisibleSessionPath(listState) == expectedPath }
        assertEquals(expectedPath, firstVisibleSessionPath(listState))
    }



    @Test
    fun emptyStateShowsForArchivedView() {
        stubCatalog()
        setContent(viewModel())
        selectScope("Archived")
        compose.onNodeWithTag("history_empty").assertIsDisplayed()
    }
}

/** In-memory catalog store for instrumentation tests. */
private class RecordingStore(
    pinned: Set<String> = emptySet(),
    archived: Set<String> = emptySet(),
) : SessionCatalogStore {
    private val pinned = pinned.mapTo(mutableSetOf()) { dev.scoutr.app.data.SessionKey("pi", it) }
    private val archived = archived.mapTo(mutableSetOf()) { dev.scoutr.app.data.SessionKey("pi", it) }

    override fun pinnedKeys(catalogKeys: Collection<dev.scoutr.app.data.SessionKey>) = pinned.toSet()
    override fun archivedKeys(catalogKeys: Collection<dev.scoutr.app.data.SessionKey>) = archived.toSet()
    override fun setPinned(key: dev.scoutr.app.data.SessionKey, pinned: Boolean) {
        if (pinned) this.pinned.add(key) else this.pinned.remove(key)
    }

    override fun setArchived(key: dev.scoutr.app.data.SessionKey, archived: Boolean) {
        if (archived) this.archived.add(key) else this.archived.remove(key)
    }
}
