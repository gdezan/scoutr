package dev.cockpit.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.ChatViewModel
import dev.cockpit.app.ui.screens.ChatScreen
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

class ChatControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private val liveOutputRequests = AtomicInteger()
    private val controlBodies = CopyOnWriteArrayList<String>()
    @Volatile private var agentStatus = "working"
    @Volatile private var agentCardJson: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        liveOutputRequests.set(0)
        controlBodies.clear()
        agentStatus = "working"
        agentCardJson = null
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when {
                    path == "/api/sessions" ->
                        """{"ok":true,"entries":[],"since":null,"lastEntryId":null,"preview":"","exists":true,"mtimeMs":0,"model":"openai-codex/gpt-5.4","thinkingLevel":"high"}"""
                    path == "/api/models" ->
                        // Catalog-less backends get an empty catalog; the app
                        // must hide the model search instead of looping.
                        if (request.requestUrl?.queryParameter("agent") == "claude")
                            """{"ok":true,"catalog":{"providers":[]}}"""
                        else
                            """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":200000},{"id":"gpt-5.3","name":"GPT-5.3","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":128000}]}]}}"""
                    path == "/api/agents" ->
                        agentCardJson
                            ?: """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","status":"$agentStatus","sessionPath":"/tmp/session.jsonl"}]}"""
                    path == "/api/agents/w1:p1/read" -> {
                        liveOutputRequests.incrementAndGet()
                        """{"ok":true,"output":{"paneId":"w1:p1","text":"build running\n42 tests passed","revision":2,"truncated":false,"lineLimit":80}}"""
                    }
                    path == "/api/sessions/w1:p1/control" -> {
                        controlBodies += request.body.readUtf8()
                        """{"ok":true}"""
                    }
                    else -> """{"ok":false,"error":"unexpected $path"}"""
                }
                return MockResponse().setHeader("content-type", "application/json").setBody(body)
            }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun controlsMenuShowsLifecycleActionsAndOpensRenameDialog() {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent {
            ChatScreen(viewModel = vm, onBack = {})
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("chat_controls")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat_controls").assertIsDisplayed().performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Abort response")).fetchSemanticsNodes().isNotEmpty()
        }
        listOf("Abort response", "Retry last message", "Compact context", "Fork session", "Rename session…", "Close session…").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }

        compose.onNodeWithText("Rename session…").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Rename session")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Rename session").assertIsDisplayed()
    }

    @Test
    fun closeSessionRequiresConfirmationBeforeStoppingTheWorkspace() {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")
        val backCalls = AtomicInteger()

        compose.setContent { ChatScreen(viewModel = vm, onBack = { backCalls.incrementAndGet() }) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("chat_controls")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat_controls").performClick()
        compose.onNodeWithText("Close session…").performClick()

        compose.onNodeWithTag("close_session_dialog").assertIsDisplayed()
        compose.onNodeWithText("This stops the running agent and closes its workspace. The transcript stays on disk so you can resume it later.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(false, controlBodies.any { "close" in it }) }
        compose.onNodeWithText("Close session").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { controlBodies.any { "close" in it } }
        compose.runOnIdle { assertEquals(1, backCalls.get()) }
    }


    @Test
    fun drawerStillFetchesWhenAgentIsNotWorking() {
        agentStatus = "idle"
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "idle")

        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        // Not working -> no inline card; the strip remains and opening the
        // drawer must still fetch (the user opens it to see what the agent did).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("live_output_toggle")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("live_output_toggle").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.liveOutputText.contains("42 tests passed") }
        compose.onNodeWithTag("live_output_drawer").assertIsDisplayed()
        compose.onAllNodesWithText("42 tests passed", substring = true)[0].assertIsDisplayed()
    }
    @Test
    fun configurationSheetShowsAndSelectsExactThinkingAndModel() {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.modelProviders.isNotEmpty() && vm.ui.value.model != null }

        compose.onNodeWithTag("chat_thinking_config").assertIsDisplayed().performClick()
        compose.onNodeWithTag("conversation_config_sheet").assertIsDisplayed()
        compose.onNodeWithTag("thinking_level_high").assertIsDisplayed()
        compose.onNodeWithTag("thinking_level_low").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { controlBodies.any { "set_thinking" in it && "low" in it } }

        compose.onNodeWithTag("conversation_model_gpt-5.3").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { controlBodies.any { "set_model" in it && "openai-codex/gpt-5.3" in it } }
    }
    @Test
    fun configurationSheetHidesThinkingForBackendsWithoutTheCapability() {
        agentCardJson = """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"claude","agentKind":"claude","displayName":"Claude Code","capabilities":["abort","compact","close","set_model"],"status":"working","sessionPath":"/tmp/claude.jsonl"}]}"""
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.agentKind == "claude" }

        // The header shows the backend identity, but no Thinking chip for an
        // agent without set_thinking.
        compose.onNodeWithTag("chat_agent_config").assertIsDisplayed()
        compose.onNodeWithText("Claude Code").assertIsDisplayed()
        compose.onNodeWithTag("chat_thinking_config").assertDoesNotExist()

        // The conversation setup sheet skips the thinking section entirely.
        compose.onNodeWithTag("chat_model_config").performClick()
        compose.onNodeWithTag("conversation_config_sheet").assertIsDisplayed()
        compose.onNodeWithTag("thinking_level_options").assertDoesNotExist()
        compose.onNodeWithText("Thinking level").assertDoesNotExist()
        compose.onNodeWithText("Model").assertIsDisplayed()
        // An empty backend catalog hides the model search entirely.
        compose.onNodeWithTag("conversation_model_search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close conversation setup").performClick()

        // The action menu shows only the backend's advertised verbs.
        compose.onNodeWithTag("chat_controls").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Abort response")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Abort response").assertIsDisplayed()
        compose.onNodeWithText("Compact context").assertIsDisplayed()
        compose.onNodeWithText("Close session…").assertIsDisplayed()
        compose.onNodeWithText("Retry last message").assertDoesNotExist()
        compose.onNodeWithText("Fork session").assertDoesNotExist()
        compose.onNodeWithText("Rename session…").assertDoesNotExist()
    }

    @Test
    fun liveOutputStreamsInlineWhileWorkingAndKeepsPolling() {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        // Fix 10: while the agent works, real output streams inline at the
        // bottom of the transcript — no expand needed, polling just runs.
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.liveOutputText.contains("42 tests passed") }
        // The inline card is a lazy item: it enters composition on the first
        // recomposition after the text lands, so wait for it to exist before
        // asserting bounds (layout can lag the state update by a frame).
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("inline_live_output").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("inline_live_output").assertIsDisplayed()
        compose.onAllNodesWithText("42 tests passed", substring = true)[0].assertIsDisplayed()
        assertTrue("polling must run while working without interaction", liveOutputRequests.get() > 0)
        Thread.sleep(1_800)
        val beforeExpand = liveOutputRequests.get()
        assertTrue("inline streaming keeps polling while working", beforeExpand > 0)

        // Tapping the inline card expands the full drawer; inline hides while
        // the drawer owns the surface.
        compose.onNodeWithTag("inline_live_output").performClick()
        compose.onNodeWithTag("live_output_drawer").assertIsDisplayed()
        compose.onAllNodesWithText("42 tests passed", substring = true)[0].assertIsDisplayed()

        // Collapsing the drawer brings the inline card back and polling keeps
        // running — the screen-visibility/work-state lifecycle owns it now,
        // not the panel toggle.
        compose.onNodeWithTag("live_output_toggle").performClick()
        compose.onNodeWithTag("inline_live_output").assertIsDisplayed()
        val afterCollapse = liveOutputRequests.get()
        Thread.sleep(1_800)
        assertTrue(
            "streaming must continue after the drawer closes while the agent works",
            liveOutputRequests.get() > afterCollapse,
        )
    }
}
