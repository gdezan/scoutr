package dev.scoutr.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertTrue

import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.SharedPreferencesLauncherSettingsStore
import dev.scoutr.app.net.BridgeClient
import dev.scoutr.app.state.NewSessionViewModel
import dev.scoutr.app.ui.screens.NewSessionSheet
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Fast-launch behavior against a local mock bridge: focused pickers, create gating,
 * and atomic delivery of the first task with launch settings.
 */
class NewSessionSheetTest {

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

    private fun stubEndpoints(
        home: String,
        dev: String,
        models: String,
        create: String? = null,
        devGate: CountDownLatch? = null,
    ) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val p = request.path ?: ""
                val pathOnly = if (p.contains('?')) p.substringBefore('?') else p
                val isDevRequest = pathOnly == "/api/dirs" && p.contains("%2FDev")
                val body = when {
                    isDevRequest -> dev
                    pathOnly == "/api/dirs" -> home
                    pathOnly == "/api/agents/kinds" -> """{"ok":true,"kinds":[{"id":"pi","displayName":"Pi","capabilities":["abort","retry","compact","fork","rename","close","set_model","set_thinking"],"hasModelCatalog":true,"hasSlashCommands":true}]}"""
                    pathOnly == "/api/models" -> models
                    pathOnly == "/api/sessions" -> create ?: """{"ok":false,"error":"not stubbed"}"""
                    else -> """{"ok":false,"error":"unexpected path $pathOnly"}"""
                }
                return MockResponse()
                    .setHeader("content-type", "application/json")
                    .setBody(body)
                    .apply {
                        if (isDevRequest && devGate != null) {
                            // Hold the listing open until the test has observed the
                            // loading state; the gate is released explicitly, so the
                            // observation never races a wall-clock response delay.
                            devGate.await(15, TimeUnit.SECONDS)
                        }
                    }
            }
        }
    }

    private fun bridge(readTimeoutSeconds: Long = 5): BridgeClient {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val host = server.url("/").toString().trimEnd('/')
        store.save(host, "test_token", null, null)
        return BridgeClient(
            OkHttpClient.Builder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build(),
            store,
        )
    }

    private fun launcherSettingsStore(): SharedPreferencesLauncherSettingsStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("scoutr_launcher", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        return SharedPreferencesLauncherSettingsStore(context)
    }
    private fun largeFontEnabled(): Boolean = InstrumentationRegistry.getArguments().getString("fontScale") == "1.5"


    private fun waitFor(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String, tag: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.getExternalFilesDir(null) ?: error("External files directory unavailable")
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        println("SCREENSHOT[$name]=${file.absolutePath}")
    }

    private fun longModelCatalog(): String {
        fun models(provider: String, count: Int) = (0 until count).joinToString(",") { index ->
            """{"id":"model-${index.toString().padStart(2, '0')}","name":"Model $index","provider":"$provider","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":128000}"""
        }
        return """{"ok":true,"catalog":{"providers":[{"name":"alpha","models":[${models("alpha", 20)}]},{"name":"omega","models":[${models("omega", 15)}]}]}}"""
    }
    @Test
    fun folderPickerKeepsConfirmationVisibleWhileDirectoryListScrolls() {
        val directories = (1..24).joinToString(",") { "\"folder-$it\"" }
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":[$directories]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":[]}}""",
            models = """{"ok":true,"catalog":{"providers":[]}}""",
        )
        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.3f),
            ) {
                NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {})
            }
        }

        waitFor("open_folder_picker")
        compose.onNodeWithTag("open_folder_picker").performScrollTo().performClick()
        waitFor("folder_list")
        compose.onNodeWithTag("use_folder").assertIsDisplayed().assertIsEnabled()
        capture("folder-picker-populated", "folder_picker")

        compose.onNodeWithTag("folder_list").performScrollToNode(hasTestTag("folder_item_folder-24"))
        compose.onNodeWithTag("folder_item_folder-24").assertIsDisplayed()
        compose.onNodeWithTag("use_folder").assertIsDisplayed()
    }

    @Test
    fun folderPickerEmptyStateKeepsConfirmationVisible() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":[]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":[]}}""",
            models = """{"ok":true,"catalog":{"providers":[]}}""",
        )
        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent { NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {}) }

        waitFor("open_folder_picker")
        compose.onNodeWithTag("open_folder_picker").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { !vm.ui.value.loadingDirs }
        compose.onNodeWithText("No subfolders").assertIsDisplayed()
        compose.onNodeWithTag("use_folder").assertIsDisplayed().assertIsEnabled()
        capture("folder-picker-empty", "folder_picker")
    }

    @Test
    fun folderPickerLoadingStateKeepsDisabledConfirmationVisible() {
        val devGate = CountDownLatch(1)
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":["agents-mobile"]}}""",
            models = """{"ok":true,"catalog":{"providers":[]}}""",
            devGate = devGate,
        )
        val vm = NewSessionViewModel(bridge(readTimeoutSeconds = 30), launcherSettingsStore())
        compose.setContent { NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {}) }
        try {
            waitFor("open_folder_picker")
            compose.onNodeWithTag("open_folder_picker").performScrollTo().performClick()
            waitFor("folder_item_Dev")
            compose.onNodeWithTag("folder_item_Dev").performScrollTo().performClick()
            // The response is gated, so the loading state persists until the gate
            // is released below; wait for the disabled confirmation to actually
            // compose instead of racing a wall-clock response delay.
            compose.waitUntil(timeoutMillis = 10_000) {
                val node = compose.onAllNodesWithTag("use_folder").fetchSemanticsNodes().firstOrNull()
                vm.ui.value.loadingDirs && node != null && node.config.getOrNull(SemanticsProperties.Disabled) != null
            }
            compose.onNodeWithTag("use_folder").assertIsDisplayed().assertIsNotEnabled()
            capture("folder-picker-loading", "folder_picker")
        } finally {
            devGate.countDown()
        }
    }

    @Test
    fun folderPickerErrorStateKeepsDisabledConfirmationVisible() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev"]}}""",
            dev = """{"ok":false,"error":"Folder unavailable"}""",
            models = """{"ok":true,"catalog":{"providers":[]}}""",
        )
        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent { NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {}) }

        waitFor("open_folder_picker")
        compose.onNodeWithTag("open_folder_picker").performScrollTo().performClick()
        waitFor("folder_item_Dev")
        compose.onNodeWithTag("folder_item_Dev").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.folderError != null }
        compose.onNodeWithText("Folder listing failed. Check the bridge and retry.").assertIsDisplayed()
        compose.onNodeWithTag("use_folder").assertIsDisplayed()
        compose.onNodeWithTag("use_folder").assertIsNotEnabled()
        capture("folder-picker-error", "folder_picker")
    }

    @Test
    fun launcherCreatesSessionWithExecutionSettings() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":["agents-mobile"]}}""",
            models = """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4","provider":"openai-codex","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":200000}]}]}}""",
            create = """{"ok":true,"workspaceId":"wN","paneId":"wN:p1"}""",
        )

        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        var createdPane: String? = null
        compose.setContent {
            NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = { createdPane = it })
        }

        compose.onNodeWithTag("open_folder_picker").performScrollTo().performClick()
        waitFor("folder_item_Dev")
        compose.onNodeWithTag("folder_item_Dev").performClick()
        waitFor("folder_item_agents-mobile")
        compose.onNodeWithTag("use_folder").performClick()

        compose.onNodeWithTag("create_session").assertIsEnabled()
        compose.onNodeWithTag("new_session_content").performScrollToNode(hasTestTag("open_model_picker"))
        compose.onNodeWithTag("open_model_picker").performClick()
        waitFor("model_item_openai-codex/gpt-5.4")
        compose.onNodeWithTag("model_item_openai-codex/gpt-5.4").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.ui.value.selectedModel?.model?.thinkingLevels?.contains("high") == true
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithTag("new_session_content").performScrollToNode(hasTestTag("thinking_high"))
            }.isSuccess
        }
        compose.onNodeWithTag("thinking_high").performClick()
        compose.onNodeWithTag("new_session_content").performScrollToNode(hasTestTag("session_name"))
        compose.onNodeWithTag("session_name").performTextInput("demo")
        compose.onNodeWithTag("create_session").assertIsEnabled().performClick()

        compose.waitUntil(timeoutMillis = 10_000) { createdPane == "wN:p1" }
        val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
        val createRequest = requests.single { it.path == "/api/sessions" }
        val body = createRequest.body.readUtf8()
        assertTrue(body.contains("\"cwd\":\"/home/gdezan/Dev\""))
        assertTrue(body.contains("\"model\":\"openai-codex/gpt-5.4\""))
        assertTrue(body.contains("\"thinkingLevel\":\"high\""))
    }

    @Test
    fun quickPickChangesTheSelectedFolder() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":["agents-mobile"]}}""",
            models = """{"ok":true,"catalog":{"providers":[]}}""",
        )

        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent {
            NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {})
        }

        waitFor("quick_pick_Dev")
        compose.onNodeWithTag("quick_pick_Dev").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.path == "/home/gdezan/Dev" }
        compose.onNodeWithText("/home/gdezan/Dev").assertIsDisplayed()
    }

    @Test
    fun modelPickerGroupsModelsUnderProviderHeaders() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":["agents-mobile"]}}""",
            models = """{"ok":true,"catalog":{"providers":[
                {"name":"openai-codex","models":[
                    {"id":"gpt-5.4","name":"GPT-5.4","provider":"openai-codex","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":200000},
                    {"id":"gpt-5.3","name":"GPT-5.3","provider":"openai-codex","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":128000},
                    {"id":"gpt-5.2","name":"GPT-5.2","provider":"openai-codex","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":128000},
                    {"id":"gpt-5.1","name":"GPT-5.1","provider":"openai-codex","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":128000},
                    {"id":"gpt-5","name":"GPT-5","provider":"openai-codex","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":128000}]},
                {"name":"anthropic","models":[
                    {"id":"claude-sonnet-4.6","name":"Claude Sonnet 4.6","provider":"anthropic","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":200000}]}
            ]}}""",

        )

        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent { NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {}) }

        compose.onNodeWithTag("new_session_content").performScrollToNode(hasTestTag("open_model_picker"))
        compose.onNodeWithTag("open_model_picker").performClick()
        waitFor("provider_header_openai-codex")
        compose.onNodeWithTag("filter_reasoning").assertDoesNotExist()
        compose.onNodeWithTag("filter_thinking_high").assertDoesNotExist()
        compose.onNodeWithText("Any context").assertDoesNotExist()
        compose.onNodeWithTag("model_search").performClick()
        compose.onNodeWithTag("model_search").performTextInput("openai-codex/gpt-5.3")
        compose.onNodeWithTag("model_item_openai-codex/gpt-5.3")
            .assertIsDisplayed()
            .assertContentDescriptionContains("openai-codex/gpt-5.3", substring = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { vm.ui.value.selectedModelKey == "openai-codex/gpt-5.3" }
        compose.onNodeWithTag("open_model_picker").performScrollTo().performClick()
        waitFor("provider_header_openai-codex")
        compose.onNodeWithContentDescription("Clear model search").performClick()
        waitFor("provider_header_anthropic")
        compose.onNodeWithTag("provider_header_openai-codex").assertIsDisplayed()
        compose.onNodeWithTag("provider_count_openai-codex").assertIsDisplayed()
        compose.onNodeWithTag("model_item_openai-codex/gpt-5.4").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("model_item_openai-codex/gpt-5.4")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("model_item_openai-codex/gpt-5")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("model_item_anthropic/claude-sonnet-4.6")).assertCountEquals(1)
        // The second provider's models are below the fold; scroll the list.
        compose.onNodeWithTag("model_list").performScrollToNode(hasTestTag("provider_header_anthropic"))
        compose.onNodeWithTag("provider_header_anthropic").assertIsDisplayed()
        capture(if (largeFontEnabled()) "model-picker-provider-groups-large-font" else "model-picker-provider-groups-default", "model_picker")

        // Selection still lands and closes the picker.
        compose.onNodeWithTag("model_item_anthropic/claude-sonnet-4.6").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { vm.ui.value.selectedModelKey == "anthropic/claude-sonnet-4.6" }
    }

    @Test
    fun modelSearchChangesResetToFirstProviderWithoutChangingSelection() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":[]}}""",
            models = longModelCatalog(),
        )
        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent { NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {}) }
        compose.onNodeWithTag("new_session_content").performScrollToNode(hasTestTag("open_model_picker"))
        compose.onNodeWithTag("open_model_picker").performClick()
        waitFor("model_item_alpha/model-00")
        val selected = vm.ui.value.selectedModelKey

        compose.onNodeWithTag("model_list").performScrollToNode(hasTestTag("model_item_omega/model-14"))
        compose.onNodeWithTag("model_search").performTextInput("alpha/model-03")
        waitFor("model_item_alpha/model-03")
        val filteredListTop = compose.onNodeWithTag("model_list").getUnclippedBoundsInRoot().top
        val filteredHeaderTop = compose.onNodeWithTag("provider_header_alpha").getUnclippedBoundsInRoot().top
        assertTrue("query result must restart near the list top", filteredHeaderTop - filteredListTop < 64.dp)
        compose.onNodeWithTag("model_search").assertIsFocused()
        assertTrue("search must not change selection", vm.ui.value.selectedModelKey == selected)

        compose.onNodeWithTag("model_search").performTextClearance()
        waitFor("model_item_alpha/model-00")
        val fullListTop = compose.onNodeWithTag("model_list").getUnclippedBoundsInRoot().top
        val fullHeaderTop = compose.onNodeWithTag("provider_header_alpha").getUnclippedBoundsInRoot().top
        assertTrue("clearing search must restart the full catalog near the top", fullHeaderTop - fullListTop < 64.dp)
        compose.onNodeWithTag("model_search").assertIsFocused()
        assertTrue("clearing search must not change selection", vm.ui.value.selectedModelKey == selected)
    }
}
