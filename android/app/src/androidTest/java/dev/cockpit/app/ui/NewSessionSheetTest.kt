package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.SharedPreferencesLauncherSettingsStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.NewSessionViewModel
import dev.cockpit.app.ui.screens.NewSessionSheet
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
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

    private fun stubEndpoints(home: String, dev: String, models: String, create: String? = null) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when {
                    path == "/api/dirs" && request.path?.contains("%2FDev") == true -> dev
                    path == "/api/dirs" -> home
                    path == "/api/models" -> models
                    path == "/api/sessions" -> create ?: """{"ok":false,"error":"not stubbed"}"""
                    else -> """{"ok":false,"error":"unexpected path $path"}"""
                }
                return MockResponse().setHeader("content-type", "application/json").setBody(body)
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
        compose.onNodeWithTag("open_model_picker").performScrollTo().performClick()
        waitFor("model_item_gpt-5.4")
        compose.onNodeWithTag("model_item_gpt-5.4").performClick()
        compose.onNodeWithTag("new_session_content").performScrollToNode(hasTestTag("thinking_high"))
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
}
