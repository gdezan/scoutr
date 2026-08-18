package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.HerdrInfo
import dev.scoutr.app.data.NtfyInfo
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectViewModelTest {

    private lateinit var fake: FakeScoutrApi
    private lateinit var store: ConnectionStore
    private lateinit var viewModel: ConnectViewModel

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        store = ConnectionStore(RuntimeEnvironment.getApplication()).also { it.clear() }
        viewModel = ConnectViewModel(fake, store)
    }

    @Test
    fun incompatibleProtocolIsNamedAndNotPersisted() {
        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                api = ScoutrApiInfo(protocol = 2),
                herdr = HerdrInfo(connected = true),
            ),
        )

        viewModel.connect("https://bridge.test", "secret")
        shadowOf(Looper.getMainLooper()).idle()

        val failed = viewModel.state.value as Loadable.Failed
        assertEquals(FailureKind.Server, failed.kind)
        assertTrue(failed.reason.contains("bridge protocol 2"))
        assertTrue(failed.reason.contains("app supports protocol 1"))
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
                api = ScoutrApiInfo(protocol = 1),
                herdr = HerdrInfo(connected = true, version = "0.8.0", protocol = 19),
                ntfy = NtfyInfo(url = "https://bridge.test/ntfy", topic = "topic"),
            ),
        )

        viewModel.connect("https://bridge.test", "secret")
        shadowOf(Looper.getMainLooper()).idle()

        val ready = viewModel.state.value as Loadable.Ready
        assertEquals(ConnectedInfo("0.8.0", 19), ready.value)
        assertEquals("https://bridge.test", store.saved?.host)
        assertEquals("secret", store.saved?.token)
        assertEquals("topic", store.saved?.ntfyTopic)
    }
}
