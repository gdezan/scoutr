package dev.scoutr.app.state

import android.content.Context
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.SessionSnapshotStore
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostWorkCoordinator
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Per-host [FakeScoutrApi]s behind one [HostClientFactory]. */
class FakeHostClients : HostClientFactory {
    val apis = mutableMapOf<String, FakeScoutrApi>()

    fun apiFor(hostId: String): FakeScoutrApi =
        apis.getOrPut(hostId) { FakeScoutrApi() }

    override fun api(hostId: String): ScoutrApi = apiFor(hostId)

    override fun terminal(hostId: String): TerminalTransport =
        error("terminal transport is not used by BoardViewModel")

    override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory =
        error("topology feeds are not used by BoardViewModel")

    override fun probe(host: String, token: String): ScoutrApi =
        error("pairing probes are not used by BoardViewModel")
}

/**
 * Shared multi-host harness for Board VM tests (JVM Robolectric and
 * instrumented): a real registry store over cleared prefs, per-host fake APIs
 * with admitted bindings, and the shared status/filter stores the VM reads.
 */
class BoardHarness(
    private val appContext: Context,
    private val clock: () -> Long = { 1_000L },
    /** Overridable so instrumented tests can back hosts with a real transport. */
    val clients: HostClientFactory = FakeHostClients(),
) {
    val work = HostWorkCoordinator()
    val bindings = mutableMapOf<String, HostConnectionBinding>()
    val hostStatus = HostStatusRepository(
        clients = clients,
        bindingFor = { bindings[it] },
        work = work,
        clock = clock,
    )
    val hostFilter = HostFilterStore()
    val snapshots = SessionSnapshotStore(appContext.filesDir)

    init {
        // Must run BEFORE the registry reads its snapshot, otherwise a prior
        // test's profiles leak into this harness through the cached
        // SharedPreferences singleton.
        appContext.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    val registry: HostRegistryStore = HostRegistryStore(
        appContext,
        FakeConnectionCipher(),
        clock = { 100L },
    )

    /** Typed view for the default in-memory fakes. */
    val fakes: FakeHostClients
        get() = clients as FakeHostClients

    fun apiFor(hostId: String): FakeScoutrApi =
        (if (clients is FakeHostClients) clients.apiFor(hostId) else clients.api(hostId)) as FakeScoutrApi

    fun addHost(
        hostId: String,
        alias: String = hostId,
        baseUrl: String = "https://$hostId.example",
        token: String = "token-$hostId",
    ) {
        registry.addOrRefresh(hostId, baseUrl, token, ExposureKind.Custom, 10)
        registry.rename(hostId, alias)
        val binding = HostConnectionBinding(
            hostId = hostId,
            connectionRevision = registry.snapshot().profiles.first { it.hostId == hostId }.connectionRevision,
            baseUrl = baseUrl,
            token = token,
            exposure = ExposureKind.Custom,
        )
        bindings[hostId] = binding
        work.activate(binding)
    }

    fun forgetHost(hostId: String) {
        val binding = bindings.remove(hostId)
        registry.forget(hostId)
        if (binding != null) runBlocking { work.retire(binding) }
    }

    fun viewModel(
        pollInterval: Duration = 60.seconds,
        migrationState: kotlinx.coroutines.flow.StateFlow<dev.scoutr.app.data.LegacyMigrationState>? = null,
        adoptLegacyMetadata: (Collection<dev.scoutr.app.data.SessionKey>) -> Unit = {},
    ): BoardViewModel = BoardViewModel(
        hostClients = clients,
        registry = registry,
        currentBinding = { bindings[it] },
        work = work,
        hostStatus = hostStatus,
        hostFilter = hostFilter,
        migrationState = migrationState,
        adoptLegacyMetadata = adoptLegacyMetadata,
        pollInterval = pollInterval,
    )

    val connections: dev.scoutr.app.net.HostConnectionCoordinator by lazy {
        dev.scoutr.app.net.HostConnectionCoordinator(
            registry,
            healthProbe = {
                dev.scoutr.app.data.HealthResponse(
                    ok = true,
                    api = dev.scoutr.app.data.ScoutrApiInfo(
                        protocol = 2,
                        features = dev.scoutr.app.data.REQUIRED_SCOUTR_API_FEATURES,
                    ),
                    herdr = dev.scoutr.app.data.HerdrInfo(connected = true),
                )
            },
            work = work,
        )
    }

    val lifecycle: dev.scoutr.app.net.HostLifecycleCoordinator by lazy {
        dev.scoutr.app.net.HostLifecycleCoordinator(
            registry = registry,
            hostClients = clients,
            connections = connections,
            cleanupLocal = {},
            copyRetainedMetadata = null,
        )
    }

    fun hostsViewModel(clock: () -> Long = this.clock): HostsViewModel =
        HostsViewModel(
            registry = registry,
            lifecycle = lifecycle,
            hostStatus = hostStatus,
            currentBinding = { bindings[it] },
            work = work,
            clock = clock,
        )

    /** Sessions workers over the same per-host wiring as [viewModel]. */
    fun historyViewModel(
        catalogStore: dev.scoutr.app.data.SessionCatalogStore =
            dev.scoutr.app.data.SharedPreferencesSessionCatalogStore(appContext),
        pollInterval: Duration = 60.seconds,
        searchDebounceMs: Long = 300L,
        adoptLegacyMetadata: (Collection<dev.scoutr.app.data.SessionKey>) -> Unit = {},
    ): SessionHistoryViewModel = SessionHistoryViewModel(
        hostClients = clients,
        registry = registry,
        currentBinding = { bindings[it] },
        work = work,
        hostStatus = hostStatus,
        snapshots = snapshots,
        catalogStore = catalogStore,
        hostFilter = hostFilter,
        adoptLegacyMetadata = adoptLegacyMetadata,
        pollInterval = pollInterval,
        searchDebounceMs = searchDebounceMs,
    )

}
