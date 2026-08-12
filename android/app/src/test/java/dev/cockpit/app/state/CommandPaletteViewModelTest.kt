package dev.cockpit.app.state

import dev.cockpit.app.data.AgentCard
import dev.cockpit.app.data.AgentsResponse
import dev.cockpit.app.data.CatalogAction
import dev.cockpit.app.data.SessionAction
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.SessionCatalogItem
import dev.cockpit.app.data.SessionCatalogResponse
import dev.cockpit.app.net.FakeCockpitApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandPaletteViewModelTest {

    private lateinit var fake: FakeCockpitApi

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = listOf(
                    AgentCard(
                        paneId = "pane1",
                        workspaceId = "ws1",
                        tabId = "t1",
                        agent = "pi",
                        status = "blocked",
                        cwd = "/repo/a",
                        title = "Fix billing bug",
                        sessionPath = "/root/sessions/abc.jsonl",
                        statusSinceMs = (System.currentTimeMillis() - 90_000).toDouble(),
                    ),
                    AgentCard(
                        paneId = "pane2",
                        workspaceId = "ws2",
                        tabId = "t2",
                        agent = "pi",
                        status = "working",
                        cwd = "/repo/b",
                        title = "Docs refresh",
                        sessionPath = "/root/sessions/def.jsonl",
                        statusSinceMs = System.currentTimeMillis().toDouble(),
                    ),
                ),
            ),
        )
        fake.sessionCatalogResult = Result.success(
            SessionCatalogResponse(
                ok = true,
                truncated = false,
                sessions = listOf(
                    SessionCatalogItem(
                        id = "abc",
                        path = "/root/sessions/abc.jsonl",
                        title = "Fix billing bug",
                        cwd = "/repo/a",
                        model = "openai-codex/gpt-5.4",
                        updatedAt = System.currentTimeMillis().toDouble(),
                        preview = "",
                        active = true,
                        paneId = "pane1",
                        workspaceId = "ws1",
                        status = "blocked",
                    ),
                    SessionCatalogItem(
                        id = "ghi",
                        path = "/root/sessions/ghi.jsonl",
                        title = "Old migration",
                        cwd = "/repo/c",
                        model = "anthropic/claude-sonnet-4-6",
                        updatedAt = (System.currentTimeMillis() - 3_600_000).toDouble(),
                        preview = "",
                        active = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun openWithoutQueryLoadsRunningAgents() = runBlocking {
        val viewModel = CommandPaletteViewModel(fake, savedConnection())
        viewModel.open()
        viewModel.waitForSettled()

        val ui = viewModel.ui.value
        assertTrue(ui.open)
        assertEquals(2, ui.results.size)
        assertEquals(PaletteResultKind.Agent, ui.results[0].kind)
        assertEquals("Fix billing bug", ui.results[0].title)
    }

    @Test
    fun queryMergesAgentAndSessionResults() = runBlocking {
        val viewModel = CommandPaletteViewModel(fake, savedConnection())
        viewModel.open()
        viewModel.waitForSettled()
        viewModel.setQuery("billing")
        viewModel.waitForSettled()

        val titles = viewModel.ui.value.results.map { it.title }
        assertTrue("expected the billing agent and session, saw $titles", titles.contains("Fix billing bug"))
        assertTrue(titles.contains("Old migration") || titles.contains("Fix billing bug"))
        assertEquals(2, titles.size)
    }

    @Test
    fun resumePostsToCatalogResume() = runBlocking {
        val viewModel = CommandPaletteViewModel(fake, savedConnection())
        viewModel.resume("/root/sessions/ghi.jsonl")
        viewModel.waitForIdle()
        assertNull(viewModel.ui.value.busyPath)
        assertNull(viewModel.ui.value.error)
        val resume = fake.calls.last { it.name == "sessionCatalogAction" }
        assertEquals(CatalogAction.Resume, resume.args["action"])
        assertEquals("/root/sessions/ghi.jsonl", resume.args["path"])
    }

    @Test
    fun controlPostsToPaneControl() = runBlocking {
        val viewModel = CommandPaletteViewModel(fake, savedConnection())
        viewModel.open()
        viewModel.waitForSettled()
        viewModel.control("pane1", SessionAction.Abort)
        viewModel.waitForIdle()

        assertNull(viewModel.ui.value.busyPaneId)
        assertNull(viewModel.ui.value.error)
        val control = fake.calls.last { it.name == "controlSession" }
        assertEquals("pane1", control.args["paneId"])
        assertEquals(SessionAction.Abort, control.args["action"])
    }

    @Test
    fun openWithoutConnectionShowsError() = runBlocking {
        val connection = ConnectionStore(RuntimeEnvironment.getApplication()).apply { clear() }
        val viewModel = CommandPaletteViewModel(fake, connection)
        viewModel.open()
        assertTrue(viewModel.ui.value.open)
        assertTrue(viewModel.ui.value.results.isEmpty())
        assertNotNull(viewModel.ui.value.error)
    }

    private fun savedConnection(): ConnectionStore =
        ConnectionStore(RuntimeEnvironment.getApplication()).apply {
            save("http://test-bridge", "test-token", null, null)
        }

    private fun CommandPaletteViewModel.waitForSettled() = runBlocking {
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (!ui.value.loading && (ui.value.results.isNotEmpty() || ui.value.error != null)) return@runBlocking
            delay(25)
        }
        assertTrue("Palette did not settle", !ui.value.loading)
    }

    private fun CommandPaletteViewModel.waitForIdle() = runBlocking {
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (ui.value.busyPath == null && ui.value.busyPaneId == null && ui.value.error == null) return@runBlocking
            delay(25)
        }
    }
}
