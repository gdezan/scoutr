package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.HerdrInfo
import dev.scoutr.app.data.PairingPayloadParser
import dev.scoutr.app.data.REQUIRED_SCOUTR_API_FEATURES
import dev.scoutr.app.data.ScoutrApiInfo
import dev.scoutr.app.net.FakeScoutrApi
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
}
