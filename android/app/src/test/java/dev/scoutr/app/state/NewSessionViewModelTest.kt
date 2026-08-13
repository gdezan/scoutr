package dev.scoutr.app.state

import dev.scoutr.app.data.AgentKindInfo
import dev.scoutr.app.data.AgentKindsResponse
import dev.scoutr.app.data.CreatedSessionResponse
import dev.scoutr.app.data.DirListing
import dev.scoutr.app.data.DirListingResponse
import dev.scoutr.app.data.LauncherSettings
import dev.scoutr.app.data.LauncherSettingsStore
import dev.scoutr.app.data.ModelInfo
import dev.scoutr.app.data.ModelProvider
import dev.scoutr.app.data.ModelsCatalog
import dev.scoutr.app.data.ModelsCatalogResponse
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewSessionViewModelTest {
    private lateinit var fake: FakeScoutrApi
    private lateinit var settingsStore: RecordingLauncherSettingsStore

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        settingsStore = RecordingLauncherSettingsStore()
        stubEndpoints()
    }

    @Test
    fun loadsFoldersModelsAndPersistedExecutionDefaults() {
        settingsStore.settings = LauncherSettings(
            defaultModelKey = "openai-codex/gpt-5.4",
            favoriteModelKeys = setOf("anthropic/claude-sonnet-4-6"),
            thinkingByModel = mapOf("openai-codex/gpt-5.4" to "high"),
        )

        val viewModel = NewSessionViewModel(fake, settingsStore)
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
        val viewModel = NewSessionViewModel(fake, settingsStore)
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
        fake.createSessionResult = Result.success(CreatedSessionResponse(ok = true, workspaceId = "wN", paneId = "wN:p1"))
        val viewModel = NewSessionViewModel(fake, settingsStore)
        viewModel.waitForLoaded()
        viewModel.selectModel("openai-codex/gpt-5.4")
        viewModel.setThinkingLevel("high")
        viewModel.setName("demo")
        viewModel.setInitialPrompt("  preserve my spacing  ")

        viewModel.create()
        viewModel.waitForCreated()

        val create = fake.calls.last { it.name == "createSession" }.args
        assertEquals("/home/gdezan", create["cwd"])
        assertEquals("openai-codex/gpt-5.4", create["model"])
        assertEquals("demo", create["name"])
        assertEquals("high", create["thinkingLevel"])
        assertEquals("  preserve my spacing  ", create["initialPrompt"])
        assertEquals("wN:p1", viewModel.ui.value.created?.paneId)
        assertEquals("openai-codex/gpt-5.4", settingsStore.settings.recentModelKeys.first())
        assertEquals("/home/gdezan", settingsStore.settings.recentFolders.first())
    }

    @Test
    fun latestFolderRequestWinsWhenResponsesArriveOutOfOrder() {
        val modelsHandler = fake.onCall
        fake.onCall = { name, args ->
            if (name == "dirs") {
                val requestedPath = (args["path"] as String?).orEmpty()
                val path = when {
                    requestedPath.endsWith("second") -> "/home/gdezan/second"
                    else -> "/home/gdezan/first"
                }
                Result.success(DirListingResponse(ok = true, listing = DirListing(path, emptyList())))
            } else modelsHandler?.invoke(name, args)
        }
        fake.callDelays["dirs"] = 0
        val viewModel = NewSessionViewModel(fake, settingsStore)
        viewModel.waitForLoaded()
        fake.callDelays["dirs"] = 150

        runBlocking {
            val first = async { viewModel.loadDirs("/home/gdezan/first") }
            delay(20)
            fake.callDelays["dirs"] = 0
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
                dev.scoutr.app.data.SessionLauncherPreset(
                    id = "stale",
                    title = "Stale",
                    cwd = "/home/gdezan/Dev",
                    modelKey = "removed/model",
                    initialPrompt = "Task",
                ),
            ),
        )
        val viewModel = NewSessionViewModel(fake, settingsStore)
        viewModel.waitForLoaded()

        viewModel.applyPreset("stale")

        assertTrue(viewModel.ui.value.launcherError.orEmpty().contains("no longer available"))
        assertTrue(viewModel.ui.value.selectedModelKey != "removed/model")
    }

    @Test
    fun selectingCataloglessBackendSkipsModelRequirementAndSendsAgent() {
        fake.createSessionResult = Result.success(CreatedSessionResponse(ok = true, workspaceId = "wN", paneId = "wN:p1"))
        val viewModel = NewSessionViewModel(fake, settingsStore)
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
        val create = fake.calls.last { it.name == "createSession" }.args
        assertEquals("claude", create["agent"])
        assertEquals("", create["model"])
    }

    private fun stubEndpoints() {
        fake.dirsResult = Result.success(
            DirListingResponse(ok = true, listing = DirListing(path = "/home/gdezan", dirs = listOf("Dev", "Downloads"))),
        )
        fake.agentKindsResult = Result.success(
            AgentKindsResponse(
                ok = true,
                kinds = listOf(
                    AgentKindInfo(
                        id = "pi",
                        displayName = "Pi",
                        capabilities = listOf("abort", "retry", "compact", "fork", "rename", "close", "set_model", "set_thinking"),
                        hasModelCatalog = true,
                        hasSlashCommands = true,
                    ),
                    AgentKindInfo(
                        id = "claude",
                        displayName = "Claude Code",
                        capabilities = listOf("abort", "compact", "close", "set_model"),
                        hasModelCatalog = false,
                        hasSlashCommands = false,
                    ),
                ),
            ),
        )
        fake.onCall = { name, args ->
            if (name == "models") {
                val agent = args["agent"] as String?
                if (agent == "claude") {
                    Result.success(ModelsCatalogResponse(ok = true, catalog = ModelsCatalog(providers = emptyList())))
                } else {
                    Result.success(
                        ModelsCatalogResponse(
                            ok = true,
                            catalog = ModelsCatalog(
                                providers = listOf(
                                    ModelProvider(
                                        name = "openai-codex",
                                        models = listOf(
                                            ModelInfo(
                                                id = "gpt-5.4",
                                                name = "GPT-5.4",
                                                reasoning = true,
                                                thinkingLevels = listOf("low", "high"),
                                                contextWindow = 200000,
                                            ),
                                        ),
                                    ),
                                    ModelProvider(
                                        name = "anthropic",
                                        models = listOf(
                                            ModelInfo(
                                                id = "claude-sonnet-4-6",
                                                name = "Claude Sonnet 4.6",
                                                reasoning = true,
                                                thinkingLevels = listOf("high"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            } else null
        }
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

    private class RecordingLauncherSettingsStore(
        var settings: LauncherSettings = LauncherSettings(),
    ) : LauncherSettingsStore {
        override fun loadLauncherSettings(): LauncherSettings = settings
        override fun saveLauncherSettings(settings: LauncherSettings) {
            this.settings = settings
        }
    }
}