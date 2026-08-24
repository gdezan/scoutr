package dev.scoutr.app.state

import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ProbedHost
import dev.scoutr.app.data.SessionCatalogStore
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import dev.scoutr.app.net.PerformanceCounters
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Test-only factories for fixtures that intentionally exercise legacy preference migration. */
@Suppress("FunctionName")
fun TerminalViewModel(
    api: ScoutrApi,
    transport: TerminalTransport,
    feedFactory: TopologyFeed.Factory,
    connectionStore: ConnectionStore,
    preferencesStore: TerminalPreferencesStore,
    initialPaneId: String? = null,
    injectedIo: CoroutineDispatcher? = null,
    performanceCounters: PerformanceCounters? = null,
): TerminalViewModel {
    val hostId = connectionStore.saved?.hostId ?: "legacy-test-host"
    return TerminalViewModel(
        api = api,
        transport = transport,
        feedFactory = feedFactory,
        hostPreferences = preferencesStore.forHost(hostId),
        preferencesStore = preferencesStore,
        initialPaneId = initialPaneId,
        injectedIo = injectedIo,
        performanceCounters = performanceCounters,
        hostAvailable = { connectionStore.saved != null },
    )
}

@Suppress("FunctionName")
fun CommandPaletteViewModel(
    bridge: ScoutrApi,
    connectionStore: ConnectionStore,
): CommandPaletteViewModel = CommandPaletteViewModel(
    bridge = bridge,
    profile = null,
    connectionAvailable = { connectionStore.saved != null },
)

@Suppress("FunctionName")
fun ReviewViewModel(
    bridge: ScoutrApi,
    @Suppress("UNUSED_PARAMETER") connectionStore: ConnectionStore,
    store: ReviewStore,
): ReviewViewModel = ReviewViewModel(bridge, store)

@Suppress("FunctionName")
fun ConnectViewModel(
    bridge: ScoutrApi,
    connectionStore: ConnectionStore,
): ConnectViewModel = ConnectViewModel(
    probe = { _, _ -> bridge },
    pairedHostIds = { connectionStore.saved?.hostId?.let(::setOf).orEmpty() },
    commit = { host: ProbedHost, token: String ->
        connectionStore.save(host.baseUrl, token, host.exposure, host.hostId)
    },
    replaceIdentity = { _, _, _, _ -> error("Identity replacement requires the host registry") },
    updateHostContext = { false to emptyList<PairingHostOption>() },
    refreshExisting = { _, _ -> error("Credential refresh requires the host registry") },
    requireStableIdentity = false,
)
