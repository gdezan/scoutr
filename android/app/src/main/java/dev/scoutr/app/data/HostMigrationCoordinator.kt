package dev.scoutr.app.data

import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostLifecycleCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process state for durable migration of the old singleton connection. */
sealed interface LegacyMigrationState {
    data object None : LegacyMigrationState
    data object Pending : LegacyMigrationState
    data object Probing : LegacyMigrationState
    data class WaitingToRetry(val message: String) : LegacyMigrationState
}

/** Imports and probes legacy singleton credentials without ever returning an offline user to Connect. */
class HostMigrationCoordinator(
    private val registry: HostRegistryStore,
    private val legacyStore: ConnectionStore,
    private val sessionCatalog: SessionCatalogStore,
    private val hostClients: HostClientFactory,
    private val scope: CoroutineScope,
    private val lifecycle: HostLifecycleCoordinator? = null,
    private val terminalPreferences: TerminalPreferencesStore? = null,
    private val launcherSettings: SharedPreferencesLauncherSettingsStore? = null,
    private val adoptLegacyReview: (String) -> Unit = {},
    private val adoptLegacyMutes: (HostProfileKey) -> Unit = {},
    private val clearLegacyNotifications: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<LegacyMigrationState>(LegacyMigrationState.None)
    val state: StateFlow<LegacyMigrationState> = mutableState.asStateFlow()

    init {
        initializeDurableMigration()
    }

    private fun initializeDurableMigration() {
        val registryState = registry.snapshot()
        if (registryState.profiles.isEmpty() && !registryState.pendingLegacyConnection) {
            legacyStore.saved?.let { saved ->
                registry.importLegacyConnection(saved)?.let { lifecycle?.activate(it) }
                legacyStore.clearLegacyFields()
            }
        } else if (legacyStore.saved != null) {
            // A prior process committed the registry first and crashed before cleanup.
            legacyStore.clearLegacyFields()
        }
        adoptPendingMetadata()
        val pending = registry.snapshot()
        when {
            pending.pendingLegacyConnection -> {
                mutableState.value = LegacyMigrationState.Pending
                retry()
            }
            pending.pendingLegacyMetadataHostId != null -> {
                mutableState.value = LegacyMigrationState.Pending
            }
        }
    }

    /** Retries the authenticated identity probe; pending credentials remain durable on every failure. */
    fun retry() {
        val current = registry.snapshot()
        if (!current.pendingLegacyConnection) {
            // Metadata adoption is a separate crash boundary from promotion.
            // A foreground retry must be able to finish it even if the probe
            // already promoted the durable credential in the previous process.
            if (current.pendingLegacyMetadataHostId != null) {
                val adoption = runCatching { adoptPendingMetadata() }
                adoption.onFailure { error ->
                    mutableState.value = LegacyMigrationState.WaitingToRetry(
                        error.message ?: "Could not adopt saved session metadata",
                    )
                }
                if (adoption.isSuccess) {
                    mutableState.value = if (registry.snapshot().pendingLegacyMetadataHostId == null) {
                        LegacyMigrationState.None
                    } else {
                        LegacyMigrationState.Pending
                    }
                }
            }
            return
        }
        if (mutableState.value == LegacyMigrationState.Probing) return
        mutableState.value = LegacyMigrationState.Probing
        scope.launch {
            try {
                val credentials = registry.pendingCredentials() ?: error("Pending migration credentials unavailable")
                val health = hostClients.probe(credentials.baseUrl, credentials.token).health()
                val compatibility = classifyScoutrApiCompatibility(health.api)
                val hostId = health.hostId?.trim()?.takeIf { it.isNotEmpty() }
                require(compatibility is ScoutrApiCompatibility.Compatible) { "Bridge is not multi-host compatible" }
                requireNotNull(hostId) { "Bridge did not report a stable host identity" }
                val profile = registry.promotePending(hostId)
                lifecycle?.activate(profile)
                adoptPendingMetadata()
                mutableState.value = if (registry.snapshot().pendingLegacyMetadataHostId == null) {
                    LegacyMigrationState.None
                } else {
                    LegacyMigrationState.Pending
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = LegacyMigrationState.WaitingToRetry(
                    error.message ?: "Could not identify the saved bridge",
                )
            }
        }
    }

    /** Completes deferred raw-path adoption after the first successful session catalog fetch. */
    fun adoptPendingMetadata(catalogKeys: Collection<SessionKey> = emptyList()) {
        val state = registry.snapshot()
        val hostId = state.pendingLegacyMetadataHostId ?: return
        val profile = state.profiles.firstOrNull { it.hostId == hostId }
            ?: error("Migrated host profile is unavailable")
        sessionCatalog.adoptLegacyEntries(hostId, catalogKeys)
        registry.credentials(hostId)?.let { credentials ->
            terminalPreferences?.adoptLegacyPreferences(hostId, credentials.baseUrl, credentials.token)
        }
        launcherSettings?.adoptLegacySettings(hostId)
        adoptLegacyReview(hostId)
        adoptLegacyMutes(HostProfileKey(profile.hostId, profile.profileGeneration))
        clearLegacyNotifications()
        if (!sessionCatalog.hasUnqualifiedLegacyEntries()) {
            registry.clearPendingLegacyMetadata(hostId)
            if (!registry.snapshot().pendingLegacyConnection) {
                mutableState.value = LegacyMigrationState.None
            }
        }
    }
}
