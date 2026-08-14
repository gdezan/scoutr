package dev.scoutr.app.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.scoutr.app.data.AgentKindInfo
import dev.scoutr.app.data.LauncherSettings
import dev.scoutr.app.data.LauncherSettingsStore
import dev.scoutr.app.data.ModelInfo
import dev.scoutr.app.data.ModelProvider
import dev.scoutr.app.data.SessionLauncherPreset
import dev.scoutr.app.net.ScoutrApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** Quick-pick roots for the folder picker. */
fun quickPicks(home: String): List<String> = listOf(home, "$home/Dev").filter(String::isNotBlank)


/** Human label for a breadcrumb path. */
fun crumbLabel(crumb: String, home: String): String = if (crumb == home) "~" else crumb.substringAfterLast('/')



data class NewSessionUiState(
    val loadingDirs: Boolean = true,
    val loadingModels: Boolean = true,
    val home: String = "",
    val path: String = "",
    val dirs: List<String> = emptyList(),
    /** Registered agent backends for the selector (GET /api/agents/kinds). */
    val agentKinds: List<AgentKindInfo> = emptyList(),
    val selectedAgent: String = "pi",
    val providers: List<ModelProvider> = emptyList(),
    val modelMatches: List<ModelPickerMatch> = emptyList(),
    val modelFilters: ModelPickerFilters = ModelPickerFilters(),
    val selectedModelKey: String? = null,
    val selectedThinkingLevel: String? = null,
    val favoriteModelKeys: Set<String> = emptySet(),
    val recentModelKeys: List<String> = emptyList(),
    val defaultModelKey: String? = null,
    val recentFolders: List<String> = emptyList(),
    val presets: List<SessionLauncherPreset> = emptyList(),
    val name: String = "",
    val initialPrompt: String = "",
    val creating: Boolean = false,
    val folderError: String? = null,
    val modelError: String? = null,
    val launcherError: String? = null,
    val created: CreatedSessionResult? = null,
) {
    val folderChoices: List<String>
        get() = (quickPicks(home) + recentFolders).distinct().take(8)

    val selectedModel: ModelPickerMatch?
        get() = providers.asSequence().flatMap { provider ->
            provider.models.asSequence().map { model ->
                val providerName = model.provider.ifBlank { provider.name }
                ModelPickerMatch(
                    key = modelPickerKey(providerName, model.id),
                    provider = providerName,
                    model = model,
                    favorite = modelPickerKey(providerName, model.id) in favoriteModelKeys,
                    recent = modelPickerKey(providerName, model.id) in recentModelKeys,
                    default = modelPickerKey(providerName, model.id) == defaultModelKey,
                )
            }
        }.firstOrNull { it.key == selectedModelKey }


    val canCreate: Boolean
        get() = path.isNotBlank() &&
            !loadingDirs && !loadingModels && !creating && folderError == null && modelError == null &&
            (selectedModel != null || !selectedAgentHasModelCatalog)

    /** Derived: catalog-less backends (e.g. claude) never need a model pick. */
    val selectedAgentHasModelCatalog: Boolean
        get() = agentKinds.firstOrNull { it.id == selectedAgent }?.hasModelCatalog != false
}

data class CreatedSessionResult(val paneId: String)

/**
 * Owns the fast session launcher: host folders, fuzzy model search, on-device
 * preferences, presets, and atomic create plus first-prompt delivery.
 */
class NewSessionViewModel(
    private val bridge: ScoutrApi,
    private val settingsStore: LauncherSettingsStore,
) : ViewModel() {
    private var settings = settingsStore.loadLauncherSettings()
    private var folderRequestId = 0
    private val _ui = MutableStateFlow(
        NewSessionUiState(
            favoriteModelKeys = settings.favoriteModelKeys,
            recentModelKeys = settings.recentModelKeys,
            defaultModelKey = settings.defaultModelKey,
            recentFolders = settings.recentFolders,
            presets = settings.presets,
            selectedModelKey = settings.defaultModelKey ?: settings.recentModelKeys.firstOrNull(),
        ),
    )
    val ui: StateFlow<NewSessionUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { loadDirs() }
        viewModelScope.launch { loadAgentKinds() }
        viewModelScope.launch { loadModels() }
    }

    suspend fun loadAgentKinds() {
        try {
            val response = bridge.agentKinds()
            val kinds = response.kinds
            _ui.update {
                val current = it.selectedAgent
                val stillKnown = kinds.any { kind -> kind.id == current }
                it.copy(
                    agentKinds = kinds,
                    selectedAgent = if (stillKnown || kinds.isEmpty()) current else (kinds.firstOrNull()?.id ?: "pi"),
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // The selector is a convenience; without it the launcher still works for pi.
        }
    }

    fun selectAgent(agent: String) {
        if (agent == ui.value.selectedAgent) return
        _ui.update { it.copy(selectedAgent = agent, selectedModelKey = null, selectedThinkingLevel = null, modelError = null) }
        viewModelScope.launch { loadModels() }
    }

    suspend fun loadDirs(path: String? = null) {
        val requestId = ++folderRequestId
        _ui.update { it.copy(loadingDirs = true, folderError = null) }
        try {
            val listing = bridge.dirs(path).listing
            if (requestId != folderRequestId) return
            if (listing != null) {
                _ui.update {
                    it.copy(
                        loadingDirs = false,
                        path = listing.path,
                        dirs = listing.dirs,
                        home = if (path == null) listing.path else it.home,
                    )
                }
            } else {
                _ui.update { it.copy(loadingDirs = false, folderError = "Folder listing failed. Check the bridge and retry.") }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (error: Exception) {
            if (requestId != folderRequestId) return
            _ui.update {
                it.copy(loadingDirs = false, folderError = error.message ?: "Folder listing failed. Check the bridge and retry.")
            }
        }
    }

    suspend fun loadModels() {
        _ui.update { it.copy(loadingModels = true, modelError = null) }
        try {
            val response = bridge.models(_ui.value.selectedAgent)
            val providers = response.catalog?.providers
            if (providers != null) {
                _ui.update { current ->
                    val availableKeys = providers.flatMap { provider ->
                        provider.models.map { modelPickerKey(it.provider.ifBlank { provider.name }, it.id) }
                    }.toSet()
                    val selectedKey = current.selectedModelKey?.takeIf { it in availableKeys }
                        ?: current.defaultModelKey?.takeIf { it in availableKeys }
                        ?: current.recentModelKeys.firstOrNull { it in availableKeys }
                        ?: availableKeys.firstOrNull()
                    val selectedModel = selectedKey?.let { findModel(providers, it) }
                    val selectedThinking = selectedKey
                        ?.let(settings.thinkingByModel::get)
                        ?.takeIf { level -> selectedModel?.thinkingLevels?.contains(level) == true }
                    withModelMatches(
                        current.copy(
                            loadingModels = false,
                            providers = providers,
                            selectedModelKey = selectedKey,
                            selectedThinkingLevel = selectedThinking,
                        ),
                    )
                }
            } else {
                _ui.update {
                    it.copy(loadingModels = false, modelError = response.error ?: "Model catalog failed. Check the bridge and retry.")
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (error: Exception) {
            _ui.update {
                it.copy(loadingModels = false, modelError = error.message ?: "Model catalog failed. Check the bridge and retry.")
            }
        }
    }

    fun enterDir(dir: String) {
        viewModelScope.launch { loadDirs(ui.value.path.trimEnd('/') + "/" + dir) }
    }

    fun goUp() {
        val state = ui.value
        val path = state.path.trimEnd('/')
        val parent = path.substringBeforeLast('/')
        if (path != state.home && parent.isNotEmpty() && parent != path) viewModelScope.launch { loadDirs(parent) }
    }

    fun retryFolders() {
        viewModelScope.launch { loadDirs(ui.value.path.takeIf(String::isNotBlank)) }
    }

    fun retryModels() {
        viewModelScope.launch { loadModels() }
    }

    fun jumpTo(path: String) {
        viewModelScope.launch { loadDirs(path) }
    }

    fun setModelQuery(query: String) = updateModelPicker { it.copy(modelFilters = it.modelFilters.copy(query = query)) }


    fun selectModel(modelKey: String) {
        val model = findModel(ui.value.providers, modelKey) ?: return
        updateModelPicker {
            it.copy(
                selectedModelKey = modelKey,
                selectedThinkingLevel = settings.thinkingByModel[modelKey]?.takeIf(model.thinkingLevels::contains),
                launcherError = null,
            )
        }
    }

    fun toggleFavorite(modelKey: String) {
        val favorites = settings.favoriteModelKeys.toMutableSet().apply {
            if (!add(modelKey)) remove(modelKey)
        }.toSet()
        saveSettings(settings.copy(favoriteModelKeys = favorites))
        updateModelPicker { it.copy(favoriteModelKeys = favorites) }
    }

    fun setDefaultModel(modelKey: String) {
        val newDefault = modelKey.takeUnless { it == settings.defaultModelKey }
        saveSettings(settings.copy(defaultModelKey = newDefault))
        updateModelPicker { it.copy(defaultModelKey = newDefault) }
    }

    fun setThinkingLevel(level: String?) {
        val state = ui.value
        val model = state.selectedModel?.model ?: return
        if (level != null && level !in model.thinkingLevels) return
        val modelKey = state.selectedModelKey ?: return
        val thinking = settings.thinkingByModel.toMutableMap().apply {
            if (level == null) remove(modelKey) else put(modelKey, level)
        }
        saveSettings(settings.copy(thinkingByModel = thinking))
        _ui.update { it.copy(selectedThinkingLevel = level) }
    }

    fun setName(name: String) {
        _ui.update { it.copy(name = name.take(100)) }
    }

    fun setInitialPrompt(prompt: String) {
        _ui.update { it.copy(initialPrompt = prompt.take(100_000)) }
    }

    fun savePreset(title: String) {
        val state = ui.value
        if (state.selectedAgentHasModelCatalog && state.selectedModelKey == null) return
        val trimmedTitle = title.trim().take(60)
        if (trimmedTitle.isEmpty() || state.path.isBlank()) return
        val preset = SessionLauncherPreset(
            id = UUID.randomUUID().toString(),
            title = trimmedTitle,
            cwd = state.path,
            modelKey = state.selectedModelKey ?: "",
            thinkingLevel = state.selectedThinkingLevel,
            sessionName = state.name,
            initialPrompt = state.initialPrompt,
            agent = state.selectedAgent,
        )
        val presets = (listOf(preset) + settings.presets).take(12)
        saveSettings(settings.copy(presets = presets))
        _ui.update { it.copy(presets = presets) }
    }

    fun applyPreset(presetId: String) {
        val state = ui.value
        val preset = settings.presets.firstOrNull { it.id == presetId } ?: return
        val wantsModel = state.selectedAgentHasModelCatalog
        if (wantsModel) {
            val model = findModel(state.providers, preset.modelKey)
            if (model == null) {
                _ui.update { it.copy(launcherError = "This preset's model is no longer available. Choose another model and save a new preset.") }
                return
            }
            updateModelPicker {
                it.copy(
                    selectedModelKey = preset.modelKey,
                    selectedThinkingLevel = preset.thinkingLevel?.takeIf(model.thinkingLevels::contains),
                    name = preset.sessionName,
                    initialPrompt = preset.initialPrompt,
                    launcherError = null,
                )
            }
        } else {
            updateModelPicker {
                it.copy(
                    selectedModelKey = null,
                    selectedThinkingLevel = null,
                    name = preset.sessionName,
                    initialPrompt = preset.initialPrompt,
                    launcherError = null,
                )
            }
        }
        jumpTo(preset.cwd)
    }

    fun deletePreset(presetId: String) {
        val presets = settings.presets.filterNot { it.id == presetId }
        saveSettings(settings.copy(presets = presets))
        _ui.update { it.copy(presets = presets) }
    }

    fun create() {
        val state = _ui.value
        if (!state.canCreate) return
        viewModelScope.launch {
            _ui.update { it.copy(creating = true, launcherError = null) }
            try {
                val response = bridge.createSession(
                    cwd = state.path,
                    model = state.selectedModelKey ?: "",
                    name = state.name.trim().ifEmpty { null },
                    initialPrompt = state.initialPrompt.takeIf { it.isNotBlank() },
                    thinkingLevel = state.selectedThinkingLevel,
                    agent = state.selectedAgent,
                )
                if (response.ok && response.paneId != null) {
                    rememberSuccessfulLaunch(state)
                    _ui.update { it.copy(creating = false, created = CreatedSessionResult(response.paneId)) }
                } else {
                    _ui.update {
                        it.copy(creating = false, launcherError = response.error ?: "Session creation failed. The bridge rolled back the workspace.")
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (error: Exception) {
                _ui.update {
                    it.copy(creating = false, launcherError = error.message ?: "Session creation failed. Check the bridge and retry.")
                }
            }
        }
    }


    fun consumeCreatedSession() {
        _ui.update { it.copy(created = null) }
    }

    private fun rememberSuccessfulLaunch(state: NewSessionUiState) {
        val modelKey = state.selectedModelKey ?: return
        val recentModels = (listOf(modelKey) + settings.recentModelKeys).distinct().take(8)
        val recentFolders = (listOf(state.path) + settings.recentFolders).distinct().take(6)
        saveSettings(settings.copy(recentModelKeys = recentModels, recentFolders = recentFolders))
        updateModelPicker { it.copy(recentModelKeys = recentModels, recentFolders = recentFolders) }
    }

    private fun updateModelPicker(transform: (NewSessionUiState) -> NewSessionUiState) {
        _ui.update { withModelMatches(transform(it)) }
    }

    private fun withModelMatches(state: NewSessionUiState): NewSessionUiState = state.copy(
        modelMatches = searchModelCatalog(
            providers = state.providers,
            filters = state.modelFilters,
            favoriteKeys = state.favoriteModelKeys,
            recentKeys = state.recentModelKeys,
            defaultKey = state.defaultModelKey,
            selectedKey = state.selectedModelKey,
        ),
    )

    private fun saveSettings(updated: LauncherSettings) {
        settings = updated
        settingsStore.saveLauncherSettings(updated)
    }

    private fun findModel(providers: List<ModelProvider>, modelKey: String): ModelInfo? = providers.asSequence()
        .flatMap { provider -> provider.models.asSequence().map { modelPickerKey(it.provider.ifBlank { provider.name }, it.id) to it } }
        .firstOrNull { it.first == modelKey }
        ?.second

    companion object {
        fun factory(
            bridge: ScoutrApi,
            settingsStore: LauncherSettingsStore,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NewSessionViewModel(bridge, settingsStore) as T
            }
        }
    }
}
