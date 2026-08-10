package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.CommandPaletteViewModel
import dev.cockpit.app.ui.screens.CommandPalette
import dev.cockpit.app.ui.theme.CockpitTheme
import kotlinx.coroutines.delay
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

/** Command palette: search, results, and inline actions. */
class CommandPaletteTest {

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

    private val agentsBody = """
        {"ok":true,"agents":[
          {"paneId":"pane1","workspaceId":"ws1","tabId":"t1","agent":"pi","status":"blocked","cwd":"/repo/a","title":"Fix billing bug","sessionPath":"/root/sessions/abc.jsonl","statusSinceMs":${System.currentTimeMillis() - 90_000}.0}
        ]}
    """.trimIndent()

    private fun stubAgents() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/agents") ->
                    MockResponse().setResponseCode(200).setBody(agentsBody)
                request.path!!.contains("/control") ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"ok":false}""")
            }
        }
    }

    private fun viewModel(): CommandPaletteViewModel {
        val connection = ConnectionStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
        connection.save(server.url("/").toString().trimEnd('/'), "test-token", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), connection)
        return CommandPaletteViewModel(bridge, connection)
    }

    @Test
    fun openListsRunningAgentsAndOpensOnTap() {
        stubAgents()
        val vm = viewModel()
        var opened: String? = null
        compose.setContent {
            CockpitTheme {
                CommandPalette(
                    viewModel = vm,
                    onOpenAgent = { paneId, _ -> opened = paneId },
                    onOpenSession = { _, _ -> },
                )
            }
        }
        vm.open()
        compose.waitUntil(5_000) { opened != null || runBlocking { vm.ui.value.results.isNotEmpty() } }
        compose.onNodeWithText("Fix billing bug").assertIsDisplayed()
        compose.onNodeWithText("/repo/a").assertIsDisplayed()
        compose.onNodeWithText("Abort").assertIsDisplayed()
        compose.onNodeWithText("Fix billing bug").performClick()
        compose.waitUntil(2_000) { opened != null }
        assertEquals("pane1", opened)
    }

    @Test
    fun typingQueriesTheBridge() {
        stubAgents()
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                CommandPalette(
                    viewModel = vm,
                    onOpenAgent = { _, _ -> },
                    onOpenSession = { _, _ -> },
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
}
