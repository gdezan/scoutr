package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.HerdrInfo
import dev.scoutr.app.data.PairingPayloadParser
import dev.scoutr.app.data.REQUIRED_SCOUTR_API_FEATURES
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.UpdateHostDisposition
import dev.scoutr.app.data.ScoutrApiInfo
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostConnectionCoordinator
import dev.scoutr.app.net.HostLifecycleCoordinator
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectViewModelTest {

    private lateinit var fake: FakeScoutrApi
    private lateinit var store: ConnectionStore
    private lateinit var viewModel: ConnectViewModel

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        store = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher()).also { it.clear() }
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(HostRegistryStore.FILE, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        viewModel = ConnectViewModel(fake, store)
    }

    @Test
    fun incompatibleProtocolIsNamedAndNotPersisted() {
        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                api = ScoutrApiInfo(protocol = 3),
                herdr = HerdrInfo(connected = true),
            ),
        )

        viewModel.connect("https://bridge.test", "secret")
        shadowOf(Looper.getMainLooper()).idle()

        val failed = viewModel.state.value as Loadable.Failed
        assertEquals(FailureKind.Server, failed.kind)
        assertTrue(failed.reason.contains("bridge protocol 3"))
        assertTrue(failed.reason.contains("app supports protocol 2"))
        assertNull(store.saved)
    }

    @Test
    fun missingProtocolRequiresBridgeUpdateAndIsNotPersisted() {
        fake.healthResult = Result.success(
            HealthResponse(ok = true, herdr = HerdrInfo(connected = true)),
        )

        viewModel.connect("https://bridge.test", "secret")
        shadowOf(Looper.getMainLooper()).idle()

        val failed = viewModel.state.value as Loadable.Failed
        assertTrue(failed.reason.contains("does not advertise"))
        assertTrue(failed.reason.contains("update or deploy the bridge", ignoreCase = true))
        assertNull(store.saved)
    }

    @Test
    fun supportedProtocolPersistsTheSuccessfulPairing() {
        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                herdr = HerdrInfo(connected = true, version = "0.8.0", protocol = 19),
            ),
        )

        viewModel.connect("https://bridge.test", "secret")
        shadowOf(Looper.getMainLooper()).idle()

        val ready = viewModel.state.value as Loadable.Ready
        assertEquals(ConnectedInfo("0.8.0", 19), ready.value)
        assertEquals("https://bridge.test", store.saved?.host)
        assertEquals("secret", store.saved?.token)
        assertEquals(
            "a hand-typed address carries no provider metadata",
            ExposureKind.Custom,
            store.saved?.exposure,
        )
    }

    @Test
    fun pairingFromAV1QrPersistsTailscale() {
        healthy()
        val payload = PairingPayloadParser.parse(
            """{"v":1,"host":"https://artemis.tail7dc568.ts.net","token":"scoutr_secret"}""",
        )!!

        viewModel.connect(payload.host, payload.token, payload.exposure)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.state.value is Loadable.Ready)
        assertEquals("https://artemis.tail7dc568.ts.net", store.saved?.host)
        assertEquals("scoutr_secret", store.saved?.token)
        assertEquals(ExposureKind.Tailscale, store.saved?.exposure)
    }

    @Test
    fun pairingFromAV2QrPersistsItsExposureKind() {
        healthy()
        val payload = PairingPayloadParser.parse(
            """{"v":2,"host":"https://scoutr.example.com","token":"scoutr_secret","exposure":{"kind":"cloudflare"}}""",
        )!!

        viewModel.connect(payload.host, payload.token, payload.exposure)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.state.value is Loadable.Ready)
        assertEquals("https://scoutr.example.com", store.saved?.host)
        assertEquals("scoutr_secret", store.saved?.token)
        assertEquals(ExposureKind.Cloudflare, store.saved?.exposure)
    }

    @Test
    fun aFailedProbeSavesNothingEvenWithQrExposure() {
        fake.healthResult = Result.failure(IOException("unreachable"))

        viewModel.connect("https://scoutr.example.com", "scoutr_secret", ExposureKind.Cloudflare)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.state.value is Loadable.Failed)
        assertNull(store.saved)
    }

    /** Reachable bridge on a supported protocol, whatever fronts it. */
    private fun healthy() {
        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                herdr = HerdrInfo(connected = true, version = "0.8.0", protocol = 19),
            ),
        )
    }

    @Test
    fun pairingPersistsTheBridgesHostIdentityFromTheHealthHandshake() {
        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                hostId = "host_live1",
                api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                herdr = HerdrInfo(connected = true, version = "0.8.0", protocol = 19),
            ),
        )

        viewModel.connect("https://bridge.test", "secret")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("host_live1", store.saved?.hostId)
        assertEquals("secret", store.saved?.token)
    }

    @Test
    fun changedIdentityWaitsForAnExplicitAddAsNewChoice() {
        val registry = hostRegistryWith("old-host")
        fake.healthResult = healthyHealth("new-host")
        val candidate = registryViewModel(registry)

        candidate.refresh("old-host", "https://new.example", "new-token")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(candidate.outcome.value is PairingOutcome.IdentityChanged)
        assertEquals(listOf("old-host"), registry.snapshot().profiles.map { it.hostId })

        candidate.confirmAddAsNew()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(candidate.outcome.value is PairingOutcome.Added)
        assertEquals(setOf("old-host", "new-host"), registry.snapshot().profiles.map { it.hostId }.toSet())
    }

    @Test
    fun changedIdentityCanExplicitlyReplaceTheOldProfile() {
        val registry = hostRegistryWith("old-host")
        fake.healthResult = healthyHealth("new-host")
        val candidate = registryViewModel(registry)

        candidate.refresh("old-host", "https://new.example", "new-token")
        shadowOf(Looper.getMainLooper()).idle()
        val changed = candidate.outcome.value as PairingOutcome.IdentityChanged
        assertTrue(changed.replacesUpdateHost)
        candidate.confirmIdentityReplacement(UpdateHostDisposition.TrustReplacementSigningKey)
        shadowOf(Looper.getMainLooper()).idle()

        val state = registry.snapshot()
        assertEquals(listOf("new-host"), state.profiles.map { it.hostId })
        assertEquals("new-host", state.defaultHostId)
        assertEquals("new-host", state.updateHostId)
    }

    @Test
    fun changedUpdateIdentityCanChooseAnotherPairedUpdateHost() {
        val registry = hostRegistryWith("old-host")
        registry.addOrRefresh("fallback-host", "https://fallback.example", "fallback-token")
        fake.healthResult = healthyHealth("new-host")
        val candidate = registryViewModel(registry)

        candidate.refresh("old-host", "https://new.example", "new-token")
        shadowOf(Looper.getMainLooper()).idle()
        val changed = candidate.outcome.value as PairingOutcome.IdentityChanged
        assertEquals(listOf("fallback-host"), changed.alternativeUpdateHosts.map { it.hostId })

        candidate.confirmIdentityReplacement(UpdateHostDisposition.UseExisting("fallback-host"))
        shadowOf(Looper.getMainLooper()).idle()

        val state = registry.snapshot()
        assertEquals(setOf("fallback-host", "new-host"), state.profiles.map { it.hostId }.toSet())
        assertEquals("fallback-host", state.updateHostId)
    }

    @Test
    fun changedIdentityCanRefreshAnAlreadyPairedProfileWithoutRemovingTheSource() {
        val registry = hostRegistryWith("old-host")
        registry.addOrRefresh("new-host", "https://existing.example", "existing-token")
        fake.healthResult = healthyHealth("new-host")
        val candidate = registryViewModel(registry)

        candidate.refresh("old-host", "https://new.example", "new-token")
        shadowOf(Looper.getMainLooper()).idle()

        val changed = candidate.outcome.value as PairingOutcome.IdentityChanged
        assertTrue(changed.reportedHostAlreadyPaired)
        candidate.confirmRefreshExisting()
        shadowOf(Looper.getMainLooper()).idle()

        val state = registry.snapshot()
        assertEquals(setOf("old-host", "new-host"), state.profiles.map { it.hostId }.toSet())
        assertEquals("https://new.example", state.profiles.single { it.hostId == "new-host" }.baseUrl)
        assertEquals("old-host", state.updateHostId)
    }

    private fun hostRegistryWith(hostId: String): HostRegistryStore {
        val registry = HostRegistryStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher())
        registry.addOrRefresh(hostId, "https://old.example", "old-token")
        return registry
    }

    private fun registryViewModel(registry: HostRegistryStore): ConnectViewModel {
        val factory = TestHostClientFactory(fake)
        val lifecycle = HostLifecycleCoordinator(
            registry = registry,
            hostClients = factory,
            connections = HostConnectionCoordinator(
                registry = registry,
                healthProbe = { healthyHealth(it.hostId).getOrThrow() },
            ),
        )
        return ConnectViewModel(factory, registry, lifecycle)
    }

    private fun healthyHealth(hostId: String): Result<HealthResponse> = Result.success(
        HealthResponse(
            ok = true,
            hostId = hostId,
            api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
            herdr = HerdrInfo(connected = true, version = "0.8.0", protocol = 19),
        ),
    )

    private class TestHostClientFactory(private val api: ScoutrApi) : HostClientFactory {
        override fun api(hostId: String): ScoutrApi = api
        override fun terminal(hostId: String): TerminalTransport = error("unused")
        override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory = error("unused")
        override fun probe(host: String, token: String): ScoutrApi = api
    }
}
