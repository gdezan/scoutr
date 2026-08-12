package dev.cockpit.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.SharedPreferencesLauncherSettingsStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.NewSessionViewModel
import dev.cockpit.app.ui.screens.NewSessionSheet
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
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
        devDelayMillis: Long = 0,
    ) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val isDevRequest = path == "/api/dirs" && request.path?.contains("%2FDev") == true
                val body = when {
                    isDevRequest -> dev
                    path == "/api/dirs" -> home
                    path == "/api/agents/kinds" -> """{"ok":true,"kinds":[{"id":"pi","displayName":"Pi","capabilities":["abort","retry","compact","fork","rename","close","set_model","set_thinking"],"hasModelCatalog":true,"hasSlashCommands":true}]}"""
                    path == "/api/models" -> models
                    path == "/api/sessions" -> create ?: """{"ok":false,"error":"not stubbed"}"""
                    else -> """{"ok":false,"error":"unexpected path $path"}"""
                }
                return MockResponse()
                    .setHeader("content-type", "application/json")
                    .setBody(body)
                    .apply {
                        if (isDevRequest && devDelayMillis > 0) {
                            setBodyDelay(devDelayMillis, TimeUnit.MILLISECONDS)
                        }
                    }
            }
        }
    }

    private fun bridge(): BridgeClient {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val host = server.url("/").toString().trimEnd('/')
        store.save(host, "test_token", null, null)
        return BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
    }

    private fun launcherSettingsStore(): SharedPreferencesLauncherSettingsStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("cockpit_launcher", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        return SharedPreferencesLauncherSettingsStore(context)
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.getExternalFilesDir(null) ?: error("External files directory unavailable")
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            compose.onNodeWithTag("folder_picker").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        println("SCREENSHOT[$name]=${file.absolutePath}")
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
        capture("folder-picker-populated")

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
        capture("folder-picker-empty")
    }

    @Test
    fun folderPickerLoadingStateKeepsDisabledConfirmationVisible() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":["agents-mobile"]}}""",
            models = """{"ok":true,"catalog":{"providers":[]}}""",
            devDelayMillis = 5_000,
        )
        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent { NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {}) }

        waitFor("open_folder_picker")
        compose.onNodeWithTag("open_folder_picker").performScrollTo().performClick()
        waitFor("folder_item_Dev")
        compose.onNodeWithTag("folder_item_Dev").performClick()
        compose.waitUntil(timeoutMillis = 2_000) { vm.ui.value.loadingDirs }
        compose.onNodeWithTag("use_folder").assertIsDisplayed()
        compose.onNodeWithTag("use_folder").assertIsNotEnabled()
        capture("folder-picker-loading")
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
        capture("folder-picker-error")
    }

    @Test
    fun launcherSendsPromptAndExecutionSettingsInOneCreateRequest() {
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

        compose.onNodeWithTag("initial_prompt").performTextInput("Fix the flaky sync test")
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
        assertTrue(body.contains("\"initialPrompt\":\"Fix the flaky sync test\""))
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
                    {"id":"gpt-4.1","name":"GPT-4.1","provider":"openai-codex","reasoning":true,"thinkingLevels":["low"],"contextWindow":128000}]},
                {"name":"deepseek","models":[
                    {"id":"deepseek-v4-flash","name":"DeepSeek V4 Flash","provider":"deepseek","reasoning":false,"thinkingLevels":[],"contextWindow":128000}]}
            ]}}""",
        )

        val vm = NewSessionViewModel(bridge(), launcherSettingsStore())
        compose.setContent {
            NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {})
        }

        compose.onNodeWithTag("open_model_picker").performScrollTo().performClick()
        waitFor("provider_header_openai-codex")
        compose.onNodeWithTag("provider_header_openai-codex").assertIsDisplayed()
        // The second provider's models are below the fold; scroll the list.
        compose.onNodeWithTag("model_list").performScrollToNode(hasTestTag("provider_header_deepseek"))
        compose.onNodeWithTag("provider_header_deepseek").assertIsDisplayed()

        // Every model appears exactly once, under exactly one provider group.
        // The tag is the provider-qualified key, so a model id shared by two
        // providers (e.g. deepseek-v4-flash) still resolves to a single node.
        compose.onNodeWithTag("model_item_openai-codex/gpt-5.4").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("model_item_openai-codex/gpt-5.4")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("model_item_openai-codex/gpt-4.1")).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("model_item_deepseek/deepseek-v4-flash")).assertCountEquals(1)

        // Selection still lands and closes the picker.
        compose.onNodeWithTag("model_item_deepseek/deepseek-v4-flash").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { vm.ui.value.selectedModelKey == "deepseek/deepseek-v4-flash" }
    }
}
