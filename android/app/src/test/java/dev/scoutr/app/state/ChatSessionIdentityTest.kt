package dev.scoutr.app.state

import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.SessionAction
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.SessionReadResponse
import dev.scoutr.app.data.liveSessionFixture
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatSessionIdentityTest {

    private val key = SessionKey("pi", "/sessions/canonical.jsonl")
    private val fake = FakeScoutrApi().apply {
        sessionResult = Result.success(SessionReadResponse(exists = true, path = key.path))
    }

    @Test
    fun bootstrapPaneConvergesToCanonicalKey() = runBlocking {
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = listOf(
                    liveSessionFixture(
                        paneId = "pane-bootstrap",
                        workspaceId = "workspace",
                        tabId = "tab",
                        key = key,
                        cwd = "/repo",
                    ),
                ),
            ),
        )
        val viewModel = ChatViewModel(fake, initialKey = null, bootstrapPaneId = "pane-bootstrap")

        viewModel.startPolling()
        waitUntil { viewModel.ui.value.sessionKey == key }

        assertEquals("pane-bootstrap", viewModel.ui.value.livePaneId)
        assertEquals(key.path, fake.calls.last { it.name == "session" }.args["path"])
        viewModel.stopPolling()
    }

    @Test
    fun canonicalKeyTracksANewPaneAndRejectsControlsAfterItDisappears() = runBlocking {
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = listOf(
                    liveSessionFixture(
                        paneId = "pane-new",
                        workspaceId = "workspace",
                        tabId = "tab",
                        key = key,
                    ),
                ),
            ),
        )
        val viewModel = ChatViewModel(fake, initialKey = key, bootstrapPaneId = null)
        viewModel.startPolling()
        waitUntil { viewModel.ui.value.livePaneId == "pane-new" }

        fake.agentsResult = Result.success(AgentsResponse())
        viewModel.refresh(RefreshSource.Pull)
        ShadowLooper.idleMainLooper()
        assertEquals(key, viewModel.ui.value.sessionKey)
        assertNull(viewModel.ui.value.livePaneId)

        viewModel.control(SessionAction.Abort)
        waitUntil { viewModel.ui.value.sendError?.contains("no longer live") == true }
        assertTrue(fake.calls.none { it.name == "controlSession" })
        viewModel.stopPolling()
    }

    @Test
    fun inactiveStoredSessionReadsTranscriptWithoutALivePane() = runBlocking {
        fake.agentsResult = Result.success(AgentsResponse())
        val viewModel = ChatViewModel(fake, initialKey = key, bootstrapPaneId = null)

        viewModel.startPolling()
        waitUntil { fake.calls.any { it.name == "session" } }

        assertEquals(key, viewModel.ui.value.sessionKey)
        assertNull(viewModel.ui.value.livePaneId)
        assertEquals(key.path, fake.calls.last { it.name == "session" }.args["path"])
        viewModel.stopPolling()
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(300) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            delay(10)
        }
        assertTrue("condition did not settle", condition())
    }
}
