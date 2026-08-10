package dev.cockpit.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    @Before
    fun setUp() {
        server = MockWebServer()
        liveOutputRequests.set(0)
        controlBodies.clear()
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when {
                    path == "/api/sessions" ->
                        """{"ok":true,"entries":[],"since":null,"lastEntryId":null,"preview":"","exists":true,"mtimeMs":0,"model":"openai-codex/gpt-5.4","thinkingLevel":"high"}"""
                    path == "/api/models" ->
                        """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":200000},{"id":"gpt-5.3","name":"GPT-5.3","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":128000}]}]}}"""
                    path == "/api/agents" ->
                        """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","status":"working","sessionPath":"/tmp/session.jsonl"}]}"""
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
        listOf("Abort response", "Retry last message", "Compact context", "Fork session", "Rename session…").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }

        compose.onNodeWithText("Rename session…").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Rename session")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Rename session").assertIsDisplayed()
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
    fun liveOutputPollsOnlyWhileExpanded() {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        compose.onNodeWithTag("live_output_toggle").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.liveOutputText.contains("42 tests passed") }
        compose.onNodeWithTag("live_output_drawer").assertIsDisplayed()
        compose.onAllNodesWithText("42 tests passed", substring = true)[0].assertIsDisplayed()

        compose.onNodeWithTag("live_output_toggle").performClick()
        Thread.sleep(250)
        val requestsAfterCollapse = liveOutputRequests.get()
        Thread.sleep(1_800)
        assertEquals(requestsAfterCollapse, liveOutputRequests.get())
    }
}
