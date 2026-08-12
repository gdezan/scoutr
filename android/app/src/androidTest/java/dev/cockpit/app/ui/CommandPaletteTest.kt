package dev.cockpit.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.CommandPaletteViewModel
import dev.cockpit.app.ui.screens.CommandPalette
import dev.cockpit.app.ui.theme.CockpitTheme
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
    private val agentsBody = """
        {"ok":true,"agents":[
          {"paneId":"pane1","workspaceId":"ws1","tabId":"t1","agent":"pi","status":"blocked","cwd":"/repo/a","title":"Fix billing bug","sessionPath":"/root/sessions/abc.jsonl","statusSinceMs":${System.currentTimeMillis() - 90_000}.0},
          {"paneId":"pane2","workspaceId":"ws1","tabId":"t2","agent":"pi","status":"blocked","cwd":"/repo/b","title":"Fix auth bug","sessionPath":"/root/sessions/def.jsonl","statusSinceMs":${System.currentTimeMillis() - 90_000}.0}
        ]}
    """.trimIndent()

    private val singleAgentBody = """
        {"ok":true,"agents":[{"paneId":"pane1","workspaceId":"ws1","tabId":"t1","agent":"pi","status":"blocked","cwd":"/repo/a","title":"Fix billing bug","sessionPath":"/root/sessions/abc.jsonl","statusSinceMs":${System.currentTimeMillis() - 90_000}.0}]}
    """.trimIndent()

    private val busyAgentsBody = """
        {"ok":true,"agents":[{"paneId":"pane1","workspaceId":"ws1","tabId":"t1","agent":"pi","status":"working","cwd":"/repo/a","title":"Fix billing bug","sessionPath":"/root/sessions/abc.jsonl"}]}
    """.trimIndent()

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

    @Test
    fun closeIsConfirmedAndAbortRemainsDirect() {
        stubAgents(body = singleAgentBody)
        val vm = viewModel()
        compose.setContent {
            CockpitTheme {
                CommandPalette(vm, onOpenAgent = { _, _ -> }, onOpenSession = { _, _ -> })
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
            CockpitTheme {
                CommandPalette(vm, onOpenAgent = { _, _ -> }, onOpenSession = { _, _ -> })
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
