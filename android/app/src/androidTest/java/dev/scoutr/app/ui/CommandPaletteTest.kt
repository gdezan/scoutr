package dev.scoutr.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.net.BridgeClient
import dev.scoutr.app.state.CommandPaletteViewModel
import dev.scoutr.app.ui.screens.CommandPalette
import dev.scoutr.app.ui.theme.ScoutrTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.FileOutputStream

/** Command palette: search, results, and inline actions. */
class CommandPaletteTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var controlBodies: MutableList<String>
    private var controlDelayMs: Long = 0

    private fun agentDescriptor(
        paneId: String,
        tabId: String,
        path: String,
        cwd: String,
        title: String,
        status: String,
    ): String = """{"key":{"agentKind":"pi","path":"$path"},"agentKind":"pi","displayName":"Pi","title":"$title","cwd":"$cwd","capabilities":["abort","retry","compact","fork","rename","close"],"live":{"paneId":"$paneId","workspaceId":"ws1","tabId":"$tabId","status":"$status","statusSinceMs":${System.currentTimeMillis() - 90_000}.0}}"""

    private val agentsBody: String
        get() = """{"ok":true,"agents":[${agentDescriptor("pane1", "t1", "/root/sessions/abc.jsonl", "/repo/a", "Fix billing bug", "blocked")},${agentDescriptor("pane2", "t2", "/root/sessions/def.jsonl", "/repo/b", "Fix auth bug", "blocked")}]}"""

    private val singleAgentBody: String
        get() = """{"ok":true,"agents":[${agentDescriptor("pane1", "t1", "/root/sessions/abc.jsonl", "/repo/a", "Fix billing bug", "blocked")}]}"""

    private val busyAgentsBody: String
        get() = """{"ok":true,"agents":[${agentDescriptor("pane1", "t1", "/root/sessions/abc.jsonl", "/repo/a", "Fix billing bug", "working")}]}"""

    private fun manyAgentsBody(count: Int = 35): String = buildString {
        append("{\"ok\":true,\"agents\":[")
        repeat(count) { index ->
            if (index > 0) append(',')
            append(agentDescriptor("pane$index", "t$index", "/sessions/$index.jsonl", "/repo/$index", "Agent $index", "blocked"))
        }
        append("]}")
    }
    private fun capture(name: String, confirmation: Boolean = false) {
        val file = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)!!.resolve("$name.png")
        FileOutputStream(file).use { output ->
            if (confirmation) {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
            } else {
                compose.onNodeWithTag("command_palette").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        println("COMMAND_PALETTE_SCREENSHOT=${file.absolutePath}")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        controlBodies = mutableListOf()
        controlDelayMs = 0
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun assertNoText(text: String) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().let { nodes ->
            assertEquals("Expected no visible '$text' nodes", 0, nodes.size)
        }
    }

    private fun stubAgents(body: String = agentsBody) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/agents") ->
                    MockResponse().setResponseCode(200).setBody(body)
                request.path!!.startsWith("/api/session-catalog") ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true,"truncated":false,"sessions":[]}""")
                request.path!!.contains("/control") -> {
                    controlBodies += request.body.readUtf8()
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                        .setBodyDelay(controlDelayMs, TimeUnit.MILLISECONDS)
                }
                else -> MockResponse().setResponseCode(404).setBody("""{"ok":false}""")
            }
        }
    }

    private fun viewModel(): CommandPaletteViewModel {
        val connection = ConnectionStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
        connection.save(server.url("/").toString().trimEnd('/'), "test-token")
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connection)
        return CommandPaletteViewModel(bridge, connection)
    }

    @Test
    fun openListsRunningAgentsAndOpensOnTap() {
        stubAgents()
        val vm = viewModel()
        var opened: String? = null
        compose.setContent {
            ScoutrTheme {
                CommandPalette(
                    viewModel = vm,
                    onOpen = { _, livePaneId -> opened = livePaneId },
                )
            }
        }
        vm.open()
        compose.waitUntil(5_000) { opened != null || runBlocking { vm.ui.value.results.isNotEmpty() } }
        compose.onNodeWithText("Fix billing bug").assertIsDisplayed()
        compose.onNodeWithText("/repo/a").assertIsDisplayed()
        compose.onAllNodesWithText("Abort").onFirst().assertIsDisplayed()
        capture("palette-populated")
        compose.onNodeWithText("Fix billing bug").performClick()
        compose.waitUntil(2_000) { opened != null }
        assertEquals("pane1", opened)
    }

    @Test
    fun typingQueriesTheBridge() {
        stubAgents()
        val vm = viewModel()
        compose.setContent {
            ScoutrTheme {
                CommandPalette(
                    viewModel = vm,
                    onOpen = { _, _ -> },
                )
            }
        }
        vm.open()
        compose.onNodeWithTag("palette_search").performTextInput("billing")
        compose.waitUntil(5_000) {
            runBlocking {
                vm.ui.value.results.any { it.title.contains("billing", ignoreCase = true) }
            }
        }
        compose.onNodeWithText("Fix billing bug").assertIsDisplayed()
    }

    @Test
    fun queryChangesResetResultsButSameQueryRefreshKeepsPosition() {
        stubAgents(body = manyAgentsBody())
        val vm = viewModel()
        val listState = LazyListState()
        compose.setContent {
            ScoutrTheme {
                CommandPalette(
                    viewModel = vm,
                    onOpen = { _, _ -> },
                    resultListState = listState,
                )
            }
        }
        vm.open()
        compose.waitUntil(5_000) { vm.ui.value.results.size == 35 }
        compose.onNodeWithTag("palette_results").performScrollToNode(hasText("Agent 25"))
        compose.onNodeWithText("Agent 25").assertIsDisplayed()

        vm.open()
        compose.waitUntil(5_000) { !vm.ui.value.loading }
        compose.onNodeWithText("Agent 25").assertIsDisplayed()

        compose.onNodeWithTag("palette_search").performTextInput("Agent 3")
        compose.waitUntil(5_000) {
            vm.ui.value.query == "Agent 3" && !vm.ui.value.loading && listState.firstVisibleItemIndex == 0
        }
        compose.onNodeWithTag("palette_search").assertIsFocused()
        compose.onAllNodesWithText("Agent 3").onLast().assertIsDisplayed()

        compose.onNodeWithTag("palette_results").performScrollToNode(hasText("Agent 34"))
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitUntil(5_000) {
            vm.ui.value.query.isEmpty() && vm.ui.value.results.size == 35
        }
        // The reset fires when the clear's search completes and the full
        // results attach: wait for the visible row, not just the VM state.
        compose.waitUntil(5_000) {
            listState.firstVisibleItemIndex == 0 &&
                compose.onAllNodesWithText("Agent 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Agent 0").assertIsDisplayed()
        compose.onNodeWithTag("palette_search").assertIsFocused()
    }
    @Test
    fun closeIsConfirmedAndAbortRemainsDirect() {
        stubAgents(body = singleAgentBody)
        val vm = viewModel()
        compose.setContent {
            ScoutrTheme {
                CommandPalette(vm, onOpen = { _, _ -> })
            }
        }
        vm.open()
        compose.waitUntil(5_000) { runBlocking { vm.ui.value.results.isNotEmpty() } }
        compose.onNodeWithText("Fix billing bug").assertIsDisplayed()
        compose.onAllNodesWithText("Close").onFirst().performClick()
        compose.onNodeWithText("Close agent?").assertIsDisplayed()
        capture("palette-close-confirm", confirmation = true)
        compose.onNodeWithText("Closing “Fix billing bug” stops its live pane. The transcript is preserved and can be resumed from Sessions.").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.runOnIdle { assertEquals(emptyList<String>(), controlBodies) }
        assertNoText("Close agent?")

        compose.onAllNodesWithText("Close").onFirst().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), controlBodies) }

        compose.onAllNodesWithText("Close").onFirst().performClick()
        compose.onAllNodesWithText("Close", useUnmergedTree = true)[1].performClick()
        compose.waitUntil(5_000) { controlBodies.size == 1 }
        compose.runOnIdle { assertEquals(1, controlBodies.count { "close" in it }) }

        compose.onAllNodesWithText("Abort").onFirst().performClick()
        compose.waitUntil(5_000) { controlBodies.size == 2 }
        compose.runOnIdle {
            assertEquals(1, controlBodies.count { "abort" in it })
        }
        assertNoText("Close agent?")
    }

    @Test
    fun busyRowHidesLifecycleActions() {
        stubAgents(body = busyAgentsBody)
        controlDelayMs = 2_000
        val vm = viewModel()
        compose.setContent {
            ScoutrTheme {
                CommandPalette(vm, onOpen = { _, _ -> })
            }
        }
        vm.open()
        compose.waitUntil(5_000) { runBlocking { vm.ui.value.results.isNotEmpty() } }
        compose.onAllNodesWithText("Abort").onFirst().performClick()
        compose.waitUntil(1_000) {
            compose.onAllNodesWithText("Abort").fetchSemanticsNodes().isEmpty()
        }
        assertNoText("Abort")
        assertNoText("Close")
        capture("palette-busy")
    }
}
