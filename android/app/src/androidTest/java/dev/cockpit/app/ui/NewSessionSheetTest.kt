package dev.cockpit.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import dev.cockpit.app.data.ConnectionStore
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
 * The new-session create sheet against a local mock bridge: folder listing,
 * model catalog, selection, and create gating.
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

    private fun waitFor(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun folderListingRendersAndCreateGatesOnModel() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":["agents-mobile"]}}""",
            models = """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4","provider":"openai-codex"}]}]}}""",
            create = """{"ok":true,"workspaceId":"wN","paneId":"wN:p1"}""",
        )

        val vm = NewSessionViewModel(bridge())
        compose.setContent {
            NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {})
        }

        waitFor("folder_item_Dev")
        compose.onNodeWithTag("folder_item_Dev").assertIsDisplayed().performClick()

        waitFor("folder_item_agents-mobile")
        compose.onNodeWithText("/home/gdezan/Dev").assertIsDisplayed()
        compose.onNodeWithTag("folder_item_agents-mobile").assertIsDisplayed()

        // create is disabled until a model is picked
        compose.onNodeWithTag("create_session").assertIsNotEnabled()

        waitFor("model_item_gpt-5.4")
        compose.onNodeWithTag("model_item_gpt-5.4").performClick()
        compose.onNodeWithTag("create_session").assertIsEnabled()

        compose.onNodeWithTag("session_name").performTextInput("demo")
        compose.onNodeWithTag("create_session").performClick()

        // after create, the sheet stays up until onCreated fires; the request
        // must have been served (MockWebServer holds the record).
        waitFor("create_session")
    }

    @Test
    fun quickPickJumpsToDev() {
        stubEndpoints(
            home = """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}""",
            dev = """{"ok":true,"listing":{"path":"/home/gdezan/Dev","dirs":["agents-mobile"]}}""",
            models = """{"ok":true,"catalog":{"providers":[]}}""",
        )

        val vm = NewSessionViewModel(bridge())
        compose.setContent {
            NewSessionSheet(viewModel = vm, onDismiss = {}, onCreated = {})
        }

        waitFor("quick_pick_Dev")
        compose.onNodeWithTag("quick_pick_Dev").performClick()
        waitFor("folder_item_agents-mobile")
        compose.onNodeWithText("/home/gdezan/Dev").assertIsDisplayed()
    }
}
