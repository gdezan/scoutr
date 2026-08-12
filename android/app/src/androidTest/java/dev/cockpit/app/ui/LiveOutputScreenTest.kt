package dev.cockpit.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import dev.cockpit.app.state.LiveOutputViewModel
import dev.cockpit.app.ui.screens.LiveOutputScreen
import dev.cockpit.app.ui.theme.CockpitTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The dedicated live output viewer: it renders the pane tail, carries the
 * drawer's old empty/error markers, and polls only while it is on screen.
 */
class LiveOutputScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private val reads = AtomicInteger()

    /** null body -> the bridge reports the read failed. */
    @Volatile private var outputText: String? = "build running\n42 tests passed"
    @Volatile private var truncated = false

    @Before
    fun setUp() {
        server = MockWebServer()
        reads.set(0)
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when (path) {
                    "/api/agents/w1:p1/read" -> {
                        reads.incrementAndGet()
                        val text = outputText
                        if (text == null) """{"ok":false,"error":"bridge offline"}"""
                        else """{"ok":true,"output":{"paneId":"w1:p1","text":"${text.replace("\n", "\\n")}","revision":2,"truncated":$truncated,"lineLimit":80}}"""
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

    private fun viewModel(): LiveOutputViewModel {
        val store = ConnectionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        val bridge = BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
        return LiveOutputViewModel(bridge, "w1:p1")
    }

    @Test
    fun screenPollsOnItsOwnAndRendersTheTail() {
        truncated = true
        val vm = viewModel()
        compose.setContent { CockpitTheme { LiveOutputScreen(viewModel = vm, onBack = {}) } }

        compose.onNodeWithTag("live_output_screen").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.text.contains("42 tests passed") }
        compose.onAllNodesWithText("42 tests passed", substring = true)[0].assertIsDisplayed()
        compose.onNodeWithText("EARLIER OUTPUT TRIMMED").assertIsDisplayed()
        assertTrue("the screen owns the poll", reads.get() > 0)
    }

    @Test
    fun emptyOutputShowsNoRecentOutput() {
        // Only terminal chrome came back — nothing meaningful to render.
        outputText = "Elapsed 2m 14s\n│ opencode-go/gpt-5.4 │"
        val vm = viewModel()
        compose.setContent { CockpitTheme { LiveOutputScreen(viewModel = vm, onBack = {}) } }

        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.text.isNotEmpty() }
        compose.onNodeWithText("No recent output").assertIsDisplayed()
    }

    @Test
    fun readFailureShowsStaleMarkerAndReason() {
        outputText = null
        val vm = viewModel()
        compose.setContent { CockpitTheme { LiveOutputScreen(viewModel = vm, onBack = {}) } }

        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.error != null }
        compose.onNodeWithText("STALE · RECONNECTING").assertIsDisplayed()
        compose.onNodeWithText("Output unavailable", substring = true).assertIsDisplayed()
        compose.onNodeWithText("bridge offline", substring = true).assertIsDisplayed()
    }

    @Test
    fun backArrowNavigatesBack() {
        val backs = AtomicInteger()
        val vm = viewModel()
        compose.setContent { CockpitTheme { LiveOutputScreen(viewModel = vm, onBack = { backs.incrementAndGet() }) } }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("live_output_back")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("live_output_back").performClick()
        compose.runOnIdle { assertEquals(1, backs.get()) }
    }
}
