package dev.cockpit.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
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

class ChatControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private val liveOutputRequests = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        liveOutputRequests.set(0)
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when {
                    path == "/api/sessions" ->
                        """{"ok":true,"entries":[],"since":null,"lastEntryId":null,"preview":"","exists":false,"mtimeMs":0}"""
                    path == "/api/agents" ->
                        """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","status":"working"}]}"""
                    path == "/api/agents/w1:p1/read" -> {
                        liveOutputRequests.incrementAndGet()
                        """{"ok":true,"output":{"paneId":"w1:p1","text":"build running\n42 tests passed","revision":2,"truncated":false,"lineLimit":80}}"""
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
    fun controlsMenuShowsSixActionsAndOpensRenameDialog() {
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
            compose.onAllNodes(androidx.compose.ui.test.hasText("Abort")).fetchSemanticsNodes().isNotEmpty()
        }
        listOf("Abort", "Retry", "Compact", "Fork", "Rename…", "Cycle thinking").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }

        compose.onNodeWithText("Rename…").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Rename session")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Rename session").assertIsDisplayed()
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
