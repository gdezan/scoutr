package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.LauncherSettings
import dev.cockpit.app.data.LauncherSettingsStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewSessionViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var settingsStore: RecordingLauncherSettingsStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settingsStore = RecordingLauncherSettingsStore()
        stubEndpoints()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loadsFoldersModelsAndPersistedExecutionDefaults() {
        settingsStore.settings = LauncherSettings(
            defaultModelKey = "openai-codex/gpt-5.4",
            favoriteModelKeys = setOf("anthropic/claude-sonnet-4-6"),
            thinkingByModel = mapOf("openai-codex/gpt-5.4" to "high"),
        )

        val viewModel = NewSessionViewModel(bridge(), settingsStore)
        viewModel.waitForLoaded()

        val ui = viewModel.ui.value
        assertEquals(listOf("Dev", "Downloads"), ui.dirs)
        assertEquals("/home/gdezan", ui.path)
        assertEquals("openai-codex/gpt-5.4", ui.selectedModelKey)
        assertEquals("high", ui.selectedThinkingLevel)
        assertEquals("openai-codex/gpt-5.4", ui.modelMatches.first().key)
        assertTrue(ui.favoriteModelKeys.contains("anthropic/claude-sonnet-4-6"))
    }

    @Test
    fun pickerPreferencesTemplatesAndPresetsPersist() {
        val viewModel = NewSessionViewModel(bridge(), settingsStore)
        viewModel.waitForLoaded()

        viewModel.selectModel("openai-codex/gpt-5.4")
        viewModel.toggleFavorite("openai-codex/gpt-5.4")
        viewModel.setDefaultModel("openai-codex/gpt-5.4")
        viewModel.setThinkingLevel("high")
        viewModel.setName("review")
        viewModel.applyTaskTemplate("review_changes")
        viewModel.savePreset("Review preset")

        val ui = viewModel.ui.value
        assertTrue("openai-codex/gpt-5.4" in settingsStore.settings.favoriteModelKeys)
        assertEquals("openai-codex/gpt-5.4", settingsStore.settings.defaultModelKey)
        assertEquals("high", settingsStore.settings.thinkingByModel["openai-codex/gpt-5.4"])
        assertTrue(ui.initialPrompt.startsWith("Review the current changes"))
        assertEquals("Review preset", settingsStore.settings.presets.single().title)
        assertEquals(ui.initialPrompt, settingsStore.settings.presets.single().initialPrompt)
    }

    @Test
    fun createSendsOneAtomicLaunchRequestAndRemembersRecents() {
        val viewModel = NewSessionViewModel(bridge(), settingsStore)
        viewModel.waitForLoaded()
        viewModel.selectModel("openai-codex/gpt-5.4")
        viewModel.setThinkingLevel("high")
        viewModel.setName("demo")
        viewModel.setInitialPrompt("  preserve my spacing  ")

        viewModel.create()
        viewModel.waitForCreated()

        val request = takeRequestsUntilCreate()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("/home/gdezan", body.getValue("cwd").jsonPrimitive.content)
        assertEquals("openai-codex/gpt-5.4", body.getValue("model").jsonPrimitive.content)
        assertEquals("demo", body.getValue("name").jsonPrimitive.content)
        assertEquals("high", body.getValue("thinkingLevel").jsonPrimitive.content)
        assertEquals("  preserve my spacing  ", body.getValue("initialPrompt").jsonPrimitive.content)
        assertEquals("wN:p1", viewModel.ui.value.created?.paneId)
        assertEquals("openai-codex/gpt-5.4", settingsStore.settings.recentModelKeys.first())
        assertEquals("/home/gdezan", settingsStore.settings.recentFolders.first())
    }

    @Test
    fun latestFolderRequestWinsWhenResponsesArriveOutOfOrder() {
        val viewModel = NewSessionViewModel(bridge(), settingsStore)
        viewModel.waitForLoaded()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val requestedPath = request.requestUrl?.queryParameter("path").orEmpty()
                if (requestedPath.endsWith("first")) Thread.sleep(150)
                val path = when {
                    requestedPath.endsWith("second") -> "/home/gdezan/second"
                    else -> "/home/gdezan/first"
                }
                return MockResponse().setHeader("content-type", "application/json")
                    .setBody("""{"ok":true,"listing":{"path":"$path","dirs":[]}}""")
            }
        }

        runBlocking {
            val first = async { viewModel.loadDirs("/home/gdezan/first") }
            delay(20)
            val second = async { viewModel.loadDirs("/home/gdezan/second") }
            first.await()
            second.await()
        }

        assertEquals("/home/gdezan/second", viewModel.ui.value.path)
    }

    @Test
    fun stalePresetDoesNotLaunchAnUnavailableModel() {
        settingsStore.settings = LauncherSettings(
            presets = listOf(
                dev.cockpit.app.data.SessionLauncherPreset(
                    id = "stale",
                    title = "Stale",
                    cwd = "/home/gdezan/Dev",
                    modelKey = "removed/model",
                    initialPrompt = "Task",
                ),
            ),
        )
        val viewModel = NewSessionViewModel(bridge(), settingsStore)
        viewModel.waitForLoaded()

        viewModel.applyPreset("stale")

        assertTrue(viewModel.ui.value.launcherError.orEmpty().contains("no longer available"))
        assertTrue(viewModel.ui.value.selectedModelKey != "removed/model")
    }

    @Test
    fun selectingCataloglessBackendSkipsModelRequirementAndSendsAgent() {
        val viewModel = NewSessionViewModel(bridge(), settingsStore)
        viewModel.waitForLoaded()

        // Pi loads a model catalog and requires a model.
        viewModel.selectModel("openai-codex/gpt-5.4")
        assertTrue(viewModel.ui.value.canCreate)

        // Claude has no catalog: no model needed, and creation carries the agent.
        viewModel.selectAgent("claude")
        runBlocking {
            repeat(100) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (!viewModel.ui.value.loadingModels) return@runBlocking
                delay(25)
            }
        }
        assertTrue(viewModel.ui.value.agentKinds.size == 2)
        assertTrue(viewModel.ui.value.selectedAgent == "claude")
        assertFalse(viewModel.ui.value.selectedAgentHasModelCatalog)
        assertNull(viewModel.ui.value.selectedModelKey)
        assertTrue(viewModel.ui.value.canCreate)

        viewModel.create()
        viewModel.waitForCreated()
        val create = takeRequestsUntilCreate()
        val body = Json.parseToJsonElement(create.body.readUtf8()).jsonObject
        assertEquals("claude", body["agent"]?.jsonPrimitive?.content)
        assertEquals("", body["model"]?.jsonPrimitive?.content)
    }

    private fun stubEndpoints() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when (path) {
                    "/api/dirs" -> """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}"""
                    "/api/agents/kinds" -> """{"ok":true,"kinds":[{"id":"pi","displayName":"Pi","capabilities":["abort","retry","compact","fork","rename","close","set_model","set_thinking"],"hasModelCatalog":true,"hasSlashCommands":true},{"id":"claude","displayName":"Claude Code","capabilities":["abort","compact","close","set_model"],"hasModelCatalog":false,"hasSlashCommands":false}]}"""
                    "/api/models" ->
                        if (request.requestUrl?.queryParameter("agent") == "claude") {
                            """{"ok":true,"catalog":{"providers":[]}}"""
                        } else {
                            """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4","reasoning":true,"thinkingLevels":["low","high"],"contextWindow":200000}]},{"name":"anthropic","models":[{"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6","reasoning":true,"thinkingLevels":["high"],"contextWindow":null}]}]}}"""
                        }
                    "/api/sessions" -> """{"ok":true,"workspaceId":"wN","paneId":"wN:p1"}"""
                    else -> """{"ok":false,"error":"unexpected path $path"}"""
                }
                return MockResponse().setHeader("content-type", "application/json").setBody(body)
            }
        }
    }

    private fun bridge(): BridgeClient {
        val store = ConnectionStore(RuntimeEnvironment.getApplication())
        store.save(server.url("/").toString().trimEnd('/'), "test-token", null, null)
        return BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
    }

    private fun NewSessionViewModel.waitForLoaded() = runBlocking {
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (!ui.value.loadingDirs && !ui.value.loadingModels && ui.value.providers.isNotEmpty()) return@runBlocking
            delay(25)
        }
        assertTrue("Launcher did not finish loading", false)
    }

    private fun NewSessionViewModel.waitForCreated() = runBlocking {
        repeat(100) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (ui.value.created != null) return@runBlocking
            delay(25)
        }
        assertNotNull("Session was not created", ui.value.created)
    }

    private fun takeRequestsUntilCreate(): RecordedRequest {
        repeat(10) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)
            if (request?.path == "/api/sessions") return request
        }
        error("No create-session request was recorded")
    }

    private class RecordingLauncherSettingsStore(
        var settings: LauncherSettings = LauncherSettings(),
    ) : LauncherSettingsStore {
        override fun loadLauncherSettings(): LauncherSettings = settings
        override fun saveLauncherSettings(settings: LauncherSettings) {
            this.settings = settings
        }
    }
}
