package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The chat keeps the agent status fresh from /api/agents so a session that
 * becomes blocked (ask_user_question) flips the composer to "answer" mode
 * without reopening the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatStatusTest {

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

    private fun stubAgents(status: String) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when {
                    path == "/api/agents" ->
                        """{"ok":true,"agents":[{"paneId":"w1:p1","workspaceId":"w1","tabId":"w1:t1","agent":"pi","status":"$status","cwd":"/home/gdezan/Dev/agents-mobile","sessionPath":"/home/gdezan/.pi/agent/sessions/s/s.jsonl"}]}"""
                    path == "/api/sessions" ->
                        """{"ok":true,"entries":[],"since":null,"lastEntryId":null,"preview":"","exists":false,"mtimeMs":0}"""
                    else -> """{"ok":false,"error":"unexpected $path"}"""
                }
                return MockResponse().setHeader("content-type", "application/json").setBody(body)
            }
        }
    }

    private fun bridge(): BridgeClient {
        val store = ConnectionStore(org.robolectric.RuntimeEnvironment.getApplication())
        store.save(server.url("/").toString().trimEnd('/'), "t", null, null)
        return BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
    }

    /** Idle the main looper until the VM's init refresh has landed its state. */
    private fun ChatViewModel.awaitRefreshSettled() {
        runBlocking {
            repeat(200) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (!ui.value.loading) return@runBlocking
                kotlinx.coroutines.delay(25)
            }
        }
    }

    @Test
    fun statusTracksBlockedFromTheBoard() {
        stubAgents("blocked")
        val vm = ChatViewModel(bridge(), "w1:p1", null, "working")

        // the nav-arg status applies until the first board poll lands
        assertFalse(vm.waitingForAnswer)

        vm.awaitRefreshSettled()
        assertTrue(vm.waitingForAnswer)
        assertEquals("blocked", vm.ui.value.agentStatus)
    }

    @Test
    fun statusKeepsWorkingWhenTheBoardSaysWorking() {
        stubAgents("working")
        val vm = ChatViewModel(bridge(), "w1:p1", null, "working")

        vm.awaitRefreshSettled()
        assertFalse(vm.waitingForAnswer)
        assertEquals("working", vm.ui.value.agentStatus)
    }
}
