package dev.scoutr.app.net

import android.content.Context
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.FcmTokenStore
import dev.scoutr.app.data.HealthResponse
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.ProbedHost
import dev.scoutr.app.data.REQUIRED_SCOUTR_API_FEATURES
import dev.scoutr.app.data.ScoutrApiInfo
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.service.PushRegistrationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostLifecycleCoordinatorTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cipher = FakeConnectionCipher()

    @Before
    fun clearRegistry() {
        context.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun refresh_retires_old_revision_and_forget_drains_cleanup_before_repair() = runBlocking {
        val registry = HostRegistryStore(context, cipher)
        val old = registry.addOrRefresh("host-a", "https://old.example", "old-token")
        val work = HostWorkCoordinator()
        val connections = HostConnectionCoordinator(
            registry,
            healthProbe = { binding ->
                HealthResponse(
                    ok = true,
                    hostId = binding.hostId,
                    api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                )
            },
            work = work,
        )
        val oldBinding = connections.currentBinding("host-a")!!
        connections.activate(oldBinding)
        val cleanup = mutableListOf<String>()
        val events = mutableListOf<String>()
        val fake = FakeScoutrApi().apply {
            healthResult = Result.success(
                HealthResponse(
                    ok = true,
                    hostId = "host-a",
                    api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                ),
            )
        }
        fake.onCall = { name, _ ->
            if (name == "unregisterDevice") {
                events += "unregister"
                assertTrue(registry.snapshot().profiles.any { it.hostId == "host-a" })
            }
            null
        }
        context.getSharedPreferences("scoutr_fcm_token", Context.MODE_PRIVATE).edit().clear().commit()
        val tokenStore = FcmTokenStore(context)
        val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val push = PushRegistrationManager(registry, tokenStore, FakeFactory(fake), pushScope)
        tokenStore.update("device-token")
        val lifecycle = HostLifecycleCoordinator(
            registry = registry,
            hostClients = FakeFactory(fake),
            connections = connections,
            pushRegistrations = push,
            cleanupLocal = {
                events += "cleanup"
                cleanup += it
            },
        )

        val refreshed = lifecycle.addOrRefresh(
            ProbedHost("host-a", "https://new.example", ExposureKind.Custom),
            "new-token",
        )

        assertTrue(refreshed.connectionRevision > old.connectionRevision)
        assertFalse(work.isActive(oldBinding))
        assertTrue(work.isActive(connections.currentBinding("host-a")!!))
        val currentBinding = connections.currentBinding("host-a")!!
        assertTrue(work.registerCloser(currentBinding) { events += "close" })

        lifecycle.forget("host-a")
        assertEquals(listOf("close", "unregister", "cleanup"), events)
        assertTrue(cleanup.contains("host-a"))
        assertTrue(registry.state.pendingCleanupHostIds.isEmpty())
        registry.addOrRefresh("host-a", "https://repair.example", "repair-token")
        Unit
    }

    @Test
    fun forgetDoesNotSendUnregisterWhenOldUrlReportsAnotherIdentity() = runBlocking {
        val registry = HostRegistryStore(context, cipher)
        registry.addOrRefresh("host-a", "https://a.example", "old-token")
        val connections = HostConnectionCoordinator(
            registry,
            healthProbe = { binding ->
                HealthResponse(
                    ok = true,
                    hostId = binding.hostId,
                    api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                )
            },
        )
        val fake = FakeScoutrApi().apply {
            healthResult = Result.success(
                HealthResponse(
                    ok = true,
                    hostId = "host-b",
                    api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                ),
            )
        }
        context.getSharedPreferences("scoutr_fcm_token", Context.MODE_PRIVATE).edit().clear().commit()
        val tokenStore = FcmTokenStore(context).apply { update("device-token") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = FakeFactory(fake)
        val push = PushRegistrationManager(registry, tokenStore, factory, scope)
        val lifecycle = HostLifecycleCoordinator(registry, factory, connections, push)

        lifecycle.forget("host-a")

        assertFalse(fake.calls.any { it.name == "unregisterDevice" })
    }

    @Test
    fun delayed_work_cannot_follow_a_same_host_refresh_to_the_new_revision() = runBlocking {
        val registry = HostRegistryStore(context, cipher)
        registry.addOrRefresh("host-a", "https://a.example", "old-token")
        val work = HostWorkCoordinator()
        val connections = HostConnectionCoordinator(
            registry,
            healthProbe = { binding ->
                HealthResponse(
                    ok = true,
                    hostId = binding.hostId,
                    api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                )
            },
            work = work,
        )
        val oldBinding = connections.currentBinding("host-a")!!
        connections.activate(oldBinding)
        val lifecycle = HostLifecycleCoordinator(
            registry = registry,
            hostClients = FakeFactory(FakeScoutrApi()),
            connections = connections,
        )
        lifecycle.addOrRefresh(ProbedHost("host-a", "https://new.example", ExposureKind.Custom), "new-token")

        var ran = false
        try {
            connections.withVerifiedBinding(oldBinding) {
                ran = true
            }
        } catch (_: HostBindingRetiredException) {
            // Expected: this request captured the retired revision.
        }
        assertFalse(ran)
    }

    @Test
    fun replacementDoesNotReactivateOldBindingAfterRegistryCommit() = runBlocking {
        val registry = HostRegistryStore(context, cipher)
        registry.addOrRefresh("host-a", "https://a.example", "old-token")
        val connections = HostConnectionCoordinator(
            registry,
            healthProbe = { binding ->
                HealthResponse(
                    ok = true,
                    hostId = binding.hostId,
                    api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                )
            },
        )
        connections.activate("host-a")
        val lifecycle = HostLifecycleCoordinator(
            registry = registry,
            hostClients = FakeFactory(FakeScoutrApi()),
            connections = connections,
            cleanupLocal = { error("cleanup failed") },
        )

        val failure = runCatching {
            lifecycle.replaceIdentity(
                previousHostId = "host-a",
                reportedHostId = "host-b",
                baseUrl = "https://b.example",
                token = "new-token",
                exposure = ExposureKind.Custom,
                updateHostDisposition = dev.scoutr.app.data.UpdateHostDisposition.TrustReplacementSigningKey,
            )
        }.exceptionOrNull()

        assertTrue(failure?.message == "cleanup failed")
        assertFalse(registry.snapshot().profiles.any { it.hostId == "host-a" })
        assertTrue(registry.snapshot().profiles.any { it.hostId == "host-b" })
        assertTrue(connections.isRetired("host-a"))
        assertTrue(connections.currentBinding("host-b")?.let(connections::isActive) == true)
    }

    @Test
    fun startup_cleanup_retries_a_tombstone_without_touching_retained_registry_state() {
        val registry = HostRegistryStore(context, cipher)
        registry.addOrRefresh("host-a", "https://a.example", "token")
        registry.forget("host-a")
        assertTrue(registry.state.pendingCleanupHostIds.contains("host-a"))

        val cleanup = mutableListOf<String>()
        val lifecycle = HostLifecycleCoordinator(
            registry = registry,
            hostClients = FakeFactory(FakeScoutrApi()),
            connections = HostConnectionCoordinator(
                registry = registry,
                healthProbe = { HealthResponse(ok = false) },
            ),
            cleanupLocal = cleanup::add,
        )
        lifecycle.resumePendingCleanup()

        assertTrue(cleanup == listOf("host-a"))
        assertTrue(registry.state.pendingCleanupHostIds.isEmpty())
        assertTrue(registry.addOrRefresh("host-a", "https://repaired.example", "new").alias == "a.example")
    }

    private class FakeFactory(private val fake: FakeScoutrApi) : HostClientFactory {
        override fun api(hostId: String): ScoutrApi = fake
        override fun terminal(hostId: String): TerminalTransport = error("unused")
        override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory = error("unused")
        override fun probe(host: String, token: String): ScoutrApi = fake
    }
}
