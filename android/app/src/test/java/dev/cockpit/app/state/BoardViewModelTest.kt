package dev.cockpit.app.state

import android.os.Looper
import dev.cockpit.app.data.CatalogAction
import dev.cockpit.app.data.SessionAction
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeException
import dev.cockpit.app.net.FakeCockpitApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Board swipe-bar Close: posts the control action and surfaces failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoardViewModelTest {

    private lateinit var fake: FakeCockpitApi
    private lateinit var viewModel: BoardViewModel

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
        val app = RuntimeEnvironment.getApplication()
        val connectionStore = ConnectionStore(app)
        // Unsaved at construction: the VM init never connects, so no health
        // probe and no poll loop interfere with the control POST below.
        connectionStore.clear()
        viewModel = BoardViewModel(
            bridge = fake,
            connectionStore = connectionStore,
            initialState = BoardUiState(connected = true),
        )
        connectionStore.save("http://test-bridge", "test-token")
    }

    private fun controls(): List<Map<String, Any?>> =
        fake.calls.filter { it.name == "controlSession" }.map { it.args }

    @Test
    fun closeAgentPostsControlActionAndStaysQuiet() {
        runBlocking { viewModel.closeAgent("p1") }
        // The response resumption is posted to the (paused) main looper;
        // idle it so the coroutine actually processes the response.
        shadowOf(Looper.getMainLooper()).idle()
        val control = controls().single()
        assertEquals("p1", control["paneId"])
        assertEquals(SessionAction.Close, control["action"])
        assertTrue("no error on success", viewModel.ui.value.error == null)
    }

    @Test
    fun closeAgentSurfacesBridgeError() {
        fake.controlResult = Result.failure(BridgeException(500, "pane not found"))
        runBlocking { viewModel.closeAgent("p1") }
        // The 500 resumption is posted to the (paused) main looper; idle it so
        // closeAgent's catch + reportError actually run.
        shadowOf(Looper.getMainLooper()).idle()
        waitUntil { viewModel.ui.value.error?.contains("pane not found") == true }
        assertTrue(
            "error surfaced, was: ${viewModel.ui.value.error}",
            viewModel.ui.value.error?.contains("pane not found") == true,
        )
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
