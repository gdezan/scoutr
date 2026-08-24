package dev.scoutr.app.service

import android.content.Context
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.FcmTokenStore
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PushRegistrationManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearStores() {
        context.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("scoutr_fcm_token", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun foregroundRetryRegistersEveryCurrentHost() = runTest {
        val registry = HostRegistryStore(context, FakeConnectionCipher())
        val hostA = registry.addOrRefresh("host-a", "https://a.example", "token-a", ExposureKind.Custom)
        val hostB = registry.addOrRefresh("host-b", "https://b.example", "token-b", ExposureKind.Custom)
        val apiA = FakeScoutrApi().apply {
            registerDeviceResult = Result.failure(IOException("offline"))
        }
        val apiB = FakeScoutrApi()
        val tokenStore = FcmTokenStore(context).apply { update("device-token") }
        val managerJob = SupervisorJob()
        val managerScope = CoroutineScope(managerJob + StandardTestDispatcher(testScheduler))
        val manager = PushRegistrationManager(
            registry = registry,
            tokens = tokenStore,
            hostClients = FakeFactory(mapOf("host-a" to apiA, "host-b" to apiB)),
            scope = managerScope,
        )
        advanceUntilIdle()

        assertEquals(1, apiA.calls.count { it.name == "registerDevice" })
        assertEquals(1, apiB.calls.count { it.name == "registerDevice" })

        apiA.registerDeviceResult = Result.success(Unit)
        manager.registerAllCurrent()
        advanceUntilIdle()

        assertEquals(2, apiA.calls.count { it.name == "registerDevice" })
        assertEquals(2, apiB.calls.count { it.name == "registerDevice" })
        assertEquals(hostA.profileGeneration, apiA.calls.last { it.name == "registerDevice" }.args["profileGeneration"])
        assertEquals(hostB.profileGeneration, apiB.calls.last { it.name == "registerDevice" }.args["profileGeneration"])
        managerJob.cancel()
    }

    private class FakeFactory(private val apis: Map<String, ScoutrApi>) : HostClientFactory {
        override fun api(hostId: String): ScoutrApi = requireNotNull(apis[hostId])
        override fun terminal(hostId: String): TerminalTransport = error("unused")
        override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory = error("unused")
        override fun probe(host: String, token: String): ScoutrApi = error("unused")
    }
}
