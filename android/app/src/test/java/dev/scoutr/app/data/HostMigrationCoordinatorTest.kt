package dev.scoutr.app.data

import android.content.Context
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class HostMigrationCoordinatorTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cipher = FakeConnectionCipher()

    @Before
    fun clearStores() {
        context.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(ConnectionStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("scoutr_session_catalog", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun failed_pending_probe_stays_on_board_and_foreground_retry_promotes_it() = runTest {
        val legacy = ConnectionStore(context, cipher)
        legacy.save("https://saved.example", "legacy-token", ExposureKind.Custom)
        val registry = HostRegistryStore(context, cipher)
        val fake = FakeScoutrApi().apply {
            healthResult = Result.failure(IOException("offline"))
        }
        val coordinator = HostMigrationCoordinator(
            registry = registry,
            legacyStore = legacy,
            sessionCatalog = SharedPreferencesSessionCatalogStore(context),
            hostClients = FakeFactory(fake),
            scope = this,
        )

        assertTrue(registry.snapshot().pendingLegacyConnection)
        assertTrue(coordinator.state.value is LegacyMigrationState.Probing)
        advanceUntilIdle()
        assertTrue(coordinator.state.value is LegacyMigrationState.WaitingToRetry)
        assertTrue(registry.snapshot().pendingLegacyConnection)

        fake.healthResult = Result.success(
            HealthResponse(
                ok = true,
                hostId = "bridge-a",
                api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
            ),
        )
        coordinator.retry()
        assertTrue(coordinator.state.value is LegacyMigrationState.Probing)
        advanceUntilIdle()

        assertEquals(LegacyMigrationState.None, coordinator.state.value)
        assertEquals("bridge-a", registry.snapshot().profiles.single().hostId)
        assertTrue(!registry.snapshot().pendingLegacyConnection)
    }

    @Test
    fun rawLegacySessionMetadataKeepsMarkerUntilCatalogCanQualifyIt() = runTest {
        val path = "/sessions/legacy.jsonl"
        context.getSharedPreferences("scoutr_session_catalog", Context.MODE_PRIVATE)
            .edit().putStringSet("pinned", setOf(path)).commit()
        val legacy = ConnectionStore(context, cipher).apply {
            save("https://saved.example", "legacy-token", ExposureKind.Custom)
        }
        val registry = HostRegistryStore(context, cipher)
        val catalog = SharedPreferencesSessionCatalogStore(context)
        val fake = FakeScoutrApi().apply {
            healthResult = Result.success(
                HealthResponse(
                    ok = true,
                    hostId = "bridge-a",
                    api = ScoutrApiInfo(protocol = 2, features = REQUIRED_SCOUTR_API_FEATURES),
                ),
            )
        }
        val coordinator = HostMigrationCoordinator(
            registry = registry,
            legacyStore = legacy,
            sessionCatalog = catalog,
            hostClients = FakeFactory(fake),
            scope = this,
        )

        advanceUntilIdle()

        assertEquals(LegacyMigrationState.Pending, coordinator.state.value)
        assertEquals("bridge-a", registry.snapshot().pendingLegacyMetadataHostId)
        assertTrue(catalog.hasUnqualifiedLegacyEntries())

        val key = SessionKey("pi", path)
        coordinator.adoptPendingMetadata(listOf(key))

        assertFalse(catalog.hasUnqualifiedLegacyEntries())
        assertEquals(null, registry.snapshot().pendingLegacyMetadataHostId)
        assertEquals(LegacyMigrationState.None, coordinator.state.value)
        assertEquals(setOf(HostSessionKey("bridge-a", key)), catalog.pinnedKeys(emptyList()))
    }

    private class FakeFactory(private val api: FakeScoutrApi) : HostClientFactory {
        override fun api(hostId: String): ScoutrApi = api
        override fun terminal(hostId: String): TerminalTransport = error("unused")
        override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory = error("unused")
        override fun probe(host: String, token: String): ScoutrApi = api
    }
}
