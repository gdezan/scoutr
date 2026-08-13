package dev.cockpit.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertContentDescriptionContains
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.Loadable
import dev.cockpit.app.state.ChatViewModel
import dev.cockpit.app.ui.screens.ChatScreen
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
class ChatControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private val controlBodies = CopyOnWriteArrayList<String>()
    @Volatile private var agentStatus = "working"
    @Volatile private var agentCardJson: String? = null
    @Volatile private var modelCatalogJson: String? = null
    /** Serve entries with a thinking block + tool call so the header toggles have an observable effect. */
    @Volatile private var richEntries = false

    @Before
    fun setUp() {
        server = MockWebServer()
        controlBodies.clear()
        agentStatus = "working"
        agentCardJson = null
        modelCatalogJson = null
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when {
                    path == "/api/sessions" -> {
                        val entries = if (richEntries)
                            """[{"entryId":"e1","role":"assistant","content":[{"type":"thinking","thinking":"hidden reasoning"},{"type":"toolCall","name":"bash","arguments":{"command":"ls"}},{"type":"text","text":"done"}]}]"""
                        else
                            """[]"""
                        """{"ok":true,"entries":$entries,"since":null,"lastEntryId":null,"preview":"","exists":true,"mtimeMs":0,"model":"openai-codex/gpt-5.4","thinkingLevel":"high"}"""
                    }
                    path == "/api/models" ->
                        // Catalog-less backends get an empty catalog; the app
                        // must hide the model search instead of looping.
                        if (request.requestUrl?.queryParameter("agent") == "claude")
                            """{"ok":true,"catalog":{"providers":[]}}"""
                        else
                            modelCatalogJson
                                ?: """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":200000},{"id":"gpt-5.3","name":"GPT-5.3","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":128000},{"id":"gpt-5.2","name":"GPT-5.2","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":128000},{"id":"gpt-5.1","name":"GPT-5.1","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":128000},{"id":"gpt-5","name":"GPT-5","provider":"openai-codex","reasoning":true,"thinkingLevels":["off","low","high"],"contextWindow":128000}]},{"name":"anthropic","models":[{"id":"claude-sonnet-4.6","name":"Claude Sonnet 4.6","provider":"anthropic","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":200000}]}]}}"""
                    path == "/api/agents" ->
                        agentCardJson
                            ?: """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","status":"$agentStatus","sessionPath":"/tmp/session.jsonl"}]}"""
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
    private fun largeFontEnabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("fontScale") == "1.5"

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            ?: error("External files directory unavailable")
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            compose.onNodeWithTag("conversation_config_sheet").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        println("SCREENSHOT[$name]=${file.absolutePath}")
    }

    private fun longModelCatalog(): String {
        fun models(provider: String, prefix: String, count: Int) = (0 until count).joinToString(",") { index ->
            """{"id":"$prefix-${index.toString().padStart(2, '0')}","name":"$prefix $index","provider":"$provider","reasoning":true,"contextWindow":128000}"""
        }
        return """{"ok":true,"catalog":{"providers":[{"name":"alpha","models":[${models("alpha", "model", 20)}]},{"name":"omega","models":[${models("omega", "model", 15)}]}]}}"""
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
    fun overflowMenuRendersFromCapabilitySet() {
        // Table-driven over capability sets served through the real bridge:
        // the overflow menu shows exactly the verbs the backend advertises,
        // unknown verbs are dropped, and an empty set leaves an empty menu.
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("chat_controls")).fetchSemanticsNodes().isNotEmpty()
        }
        val allMenuLabels = listOf(
            "Abort response", "Retry last message", "Compact context",
            "Fork session", "Rename session…", "Close session…",
        )
        val rows = listOf(
            listOf("abort", "retry", "compact", "fork", "rename", "close") to allMenuLabels,
            listOf("abort", "compact", "close", "set_model") to
                listOf("Abort response", "Compact context", "Close session…"),
            listOf("abort", "no_such_verb") to listOf("Abort response"),
            emptyList<String>() to emptyList(),
        )
        rows.forEach { (capabilities, present) ->
            agentCardJson =
                """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"t1","agent":"pi","agentKind":"pi","displayName":"Pi","capabilities":${capabilities.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},"status":"working","sessionPath":"/tmp/pi.jsonl"}]}"""
            // The VM polls every 2.5s; the next poll picks the new card up.
            compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.capabilities == capabilities }

            compose.onNodeWithTag("chat_controls").performClick()
            if (present.isNotEmpty()) {
                compose.waitUntil(timeoutMillis = 5_000) {
                    compose.onAllNodesWithText(present.first()).fetchSemanticsNodes().isNotEmpty()
                }
            }
            present.forEach { label -> compose.onNodeWithText(label).assertIsDisplayed() }
            allMenuLabels.filter { it !in present }.forEach { label ->
                compose.onNodeWithText(label).assertDoesNotExist()
            }
            // Dismiss the open menu before the next row.
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
        }
    }

    @Test
    fun headerTogglesIndependentlyControlThinkingAndToolCalls() {
        richEntries = true
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent {
            ChatScreen(viewModel = vm, onBack = {})
        }

        // Thinking blocks are visible by default.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("thinking_block")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("thinking_block").assertIsDisplayed()
        // Tool calls start collapsed (one-line chip).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("\u25B8 bash", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Hiding thinking must not touch the tool-call collapse state.
        compose.onNodeWithTag("toggle_thinking").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("thinking_block")).fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("\u25B8 bash", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Expanding tool calls must not bring thinking back.
        compose.onNodeWithTag("toggle_tools").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("\u25BE bash", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("thinking_block")).fetchSemanticsNodes().isEmpty()
        }

        // Showing thinking again must leave the tool calls expanded.
        compose.onNodeWithTag("toggle_thinking").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("thinking_block")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("\u25BE bash", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Collapsing tool calls must leave thinking visible — every
        // combination is reachable, so a handler that couples the two
        // toggles could not pass.
        compose.onNodeWithTag("toggle_tools").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("\u25B8 bash", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("thinking_block")).fetchSemanticsNodes().isNotEmpty()
        }
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
    fun configurationSheetShowsAndSelectsExactThinkingAndModel() {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")

        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        compose.waitUntil(timeoutMillis = 10_000) {
            (vm.ui.value.configuration as? Loadable.Ready)?.value?.isNotEmpty() == true && vm.ui.value.model != null
        }

        compose.onNodeWithTag("chat_thinking_config").assertIsDisplayed().performClick()
        compose.onNodeWithTag("conversation_config_sheet").assertIsDisplayed()
        compose.onNodeWithTag("conversation_model_search").performTextInput("openai-codex/gpt-5.3")
        compose.onNodeWithTag("thinking_level_high").assertIsDisplayed()
        compose.onNodeWithTag("thinking_level_low").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { controlBodies.any { "set_thinking" in it && "low" in it } }

        compose.onNodeWithTag("conversation_model_openai-codex/gpt-5.3").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { controlBodies.any { "set_model" in it && "openai-codex/gpt-5.3" in it } }
    }

    @Test
    fun configurationSheetGroupsProvidersAndScrollsWithoutSheetDrag() {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")
        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.model != null }
        compose.onNodeWithTag("chat_model_config").performClick()
        compose.onNodeWithTag("conversation_config_sheet").assertIsDisplayed()
        compose.onNodeWithTag("provider_header_openai-codex").assertIsDisplayed()
        compose.onNodeWithTag("provider_count_openai-codex").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search models").assertIsDisplayed()
        compose.onNodeWithTag("conversation_model_openai-codex/gpt-5.4")
            .assertIsDisplayed()
            .assertContentDescriptionContains("openai-codex/gpt-5.4", substring = true)
        compose.onNodeWithTag("conversation_model_openai-codex/gpt-5.4")
            .assertContentDescriptionContains("Current model", substring = true)
        compose.onNodeWithContentDescription("Current model").assertIsDisplayed()
        capture(if (largeFontEnabled()) "conversation-picker-large-font" else "conversation-picker-default")
        repeat(3) {
            compose.onNodeWithTag("conversation_model_list").performTouchInput { swipeUp() }
        }
        compose.onNodeWithTag("conversation_model_anthropic/claude-sonnet-4.6")
            .assertIsDisplayed()
            .assertContentDescriptionContains("anthropic/claude-sonnet-4.6", substring = true)
        compose.onNodeWithTag("provider_header_anthropic").assertIsDisplayed()
        repeat(3) {
            compose.onNodeWithTag("conversation_model_list").performTouchInput { swipeDown() }
        }
        compose.onNodeWithTag("conversation_model_openai-codex/gpt-5.4").assertIsDisplayed()
        compose.onNodeWithTag("conversation_model_list").performTouchInput { swipeDown() }
        compose.onNodeWithTag("conversation_model_openai-codex/gpt-5.4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close conversation setup").performClick()
    }

    @Test
    fun configurationSearchChangesResetToFirstProviderWithoutChangingSelection() {
        modelCatalogJson = longModelCatalog()
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        val vm = ChatViewModel(bridge, "w1:p1", null, "working")
        compose.setContent { ChatScreen(viewModel = vm, onBack = {}) }
        compose.waitUntil(10_000) { (vm.ui.value.configuration as? Loadable.Ready)?.value?.isNotEmpty() == true }

        compose.onNodeWithTag("chat_model_config").performClick()
        compose.onNodeWithTag("conversation_model_list")
            .performScrollToNode(hasTestTag("conversation_model_omega/model-14"))
        compose.onNodeWithTag("conversation_model_search").performTextInput("alpha/model-03")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversation_model_alpha/model-03").fetchSemanticsNodes().isNotEmpty()
        }
        val filteredListTop = compose.onNodeWithTag("conversation_model_list").getUnclippedBoundsInRoot().top
        val filteredHeaderTop = compose.onNodeWithTag("provider_header_alpha").getUnclippedBoundsInRoot().top
        assertTrue("query result must restart near the list top", filteredHeaderTop - filteredListTop < 64.dp)
        compose.onNodeWithTag("conversation_model_search").assertIsFocused()
        assertEquals("openai-codex/gpt-5.4", vm.ui.value.model)

        compose.onNodeWithTag("conversation_model_list")
            .performScrollToNode(hasTestTag("conversation_model_alpha/model-03"))
        compose.onNodeWithTag("conversation_model_search").performTextClearance()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("conversation_model_alpha/model-00").fetchSemanticsNodes().isNotEmpty()
        }
        val fullHeaderTop = compose.onNodeWithTag("provider_header_alpha").getUnclippedBoundsInRoot().top
        val fullListTop = compose.onNodeWithTag("conversation_model_list").getUnclippedBoundsInRoot().top
        assertTrue("clearing search must restart the full catalog near the top", fullHeaderTop - fullListTop < 64.dp)
        compose.onNodeWithTag("conversation_model_search").assertIsFocused()
        assertEquals("openai-codex/gpt-5.4", vm.ui.value.model)
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

}
