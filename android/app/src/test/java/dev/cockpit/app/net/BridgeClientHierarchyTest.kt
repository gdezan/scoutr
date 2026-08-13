package dev.cockpit.app.net

import android.content.Context
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.TerminalHierarchyCommand
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Wire contract for Slice 4 hierarchy mutations (POST /api/terminal/hierarchy):
 * exact path/auth and a body carrying exactly the fields the operation needs —
 * null optionals are omitted (kotlinx encodeDefaults=false), matching the
 * bridge's discriminated command union. 409 close conflicts surface as
 * BridgeException(409) so the caller can refresh and re-ask.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeClientHierarchyTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cockpit_connection", Context.MODE_PRIVATE).edit()
            .putString("host", server.url("/").toString().trimEnd('/'))
            .putString("token", "test-token")
            .apply()
        client = BridgeClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            ConnectionStore(app),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun closeTab_sendsExactWireBodyAndDecodesSelectionAndSnapshot() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"selectedPaneId":"p2","snapshot":{"version":"0.8.0","protocol":19}}"""),
        )

        val response = runBlocking {
            client.terminalHierarchy(TerminalHierarchyCommand.closeTab(tabId = "t1", expectedPaneCount = 2, selectedPaneId = "p2"))
        }

        assertEquals(true, response.ok)
        assertEquals("p2", response.selectedPaneId)
        assertNotNull(response.snapshot)
        assertEquals("0.8.0", response.snapshot?.get("version")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/terminal/hierarchy", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertEquals(
            """{"operation":"close_tab","tabId":"t1","expectedPaneCount":2,"selectedPaneId":"p2"}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun createWorkspace_omitsNullOptionalsAndSendsLabel() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"selectedPaneId":"p1"}"""),
        )

        runBlocking {
            client.terminalHierarchy(
                TerminalHierarchyCommand.createWorkspace(cwd = "/home/user/proj", label = "New Workspace", selectedPaneId = "p9"),
            )
        }

        val recorded = server.takeRequest()
        assertEquals(
            """{"operation":"create_workspace","cwd":"/home/user/proj","label":"New Workspace","selectedPaneId":"p9"}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun stalePaneCount_surfacesAsBridgeException409() {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":false,"error":"tab pane count changed","id":"t1","name":"Tab 1","count":3,"expectedPaneCount":2}""",
                ),
        )

        try {
            runBlocking {
                client.terminalHierarchy(TerminalHierarchyCommand.closeTab(tabId = "t1", expectedPaneCount = 2))
            }
            fail("expected BridgeException for the 409 pane-count conflict")
        } catch (expected: BridgeException) {
            assertEquals(409, expected.status)
            assertEquals("bridge 409: tab pane count changed", expected.message)
        }
    }
}
