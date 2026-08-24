package dev.scoutr.app.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostRegistryStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cipher = FakeConnectionCipher()

    @Before
    fun clearPrefs() {
        context.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun store() = HostRegistryStore(context, cipher, clock = { 100L })

    @Test
    fun two_hosts_round_trip_with_lowercase_exposure_and_encrypted_tokens() {
        val registry = store()
        val first = registry.addOrRefresh("host-a", "HTTPS://A.example/", "token-a", ExposureKind.Tailscale, 10)
        val second = registry.addOrRefresh("host-b", "https://b.example:8443/", "token-b", ExposureKind.Cloudflare, 20)

        assertEquals("https://a.example", first.baseUrl)
        assertEquals("b.example:8443", second.alias)
        assertEquals(HostCredentials("https://a.example", "token-a", ExposureKind.Tailscale), registry.credentials("host-a"))
        assertEquals(HostCredentials("https://b.example:8443", "token-b", ExposureKind.Cloudflare), registry.credentials("host-b"))
        assertEquals("tailscale", Json.encodeToString(ExposureKind.Tailscale).trim('"'))
        assertEquals("cloudflare", Json.encodeToString(ExposureKind.Cloudflare).trim('"'))
        assertEquals("custom", Json.encodeToString(ExposureKind.Custom).trim('"'))
        assertFalse("tokens must not be persisted as plaintext", registryPrefs().all.values.any { it.toString().contains("token-") })
    }

    @Test
    fun refresh_preserves_alias_and_generation_but_allocates_a_revision() {
        val registry = store()
        val original = registry.addOrRefresh("host-a", "https://a.example", "old", nowMs = 10)
        registry.rename("host-a", "my bridge")
        val refreshed = registry.addOrRefresh("host-a", "https://new.example/", "new", ExposureKind.Custom, 50)

        assertEquals("my bridge", refreshed.alias)
        assertEquals(original.profileGeneration, refreshed.profileGeneration)
        assertTrue(refreshed.connectionRevision > original.connectionRevision)
        assertEquals("new", registry.credentials("host-a")?.token)
    }

    @Test
    fun epochs_are_monotonic_across_forget_and_same_id_repair() {
        val registry = store()
        val first = registry.addOrRefresh("host-a", "https://a.example", "a", nowMs = 1)
        val second = registry.addOrRefresh("host-b", "https://b.example", "b", nowMs = 2)
        registry.forget("host-a", UpdateHostDisposition.UseExisting("host-b"))

        assertNull(registry.credentials("host-a"))
        assertTrue(registry.state.pendingCleanupHostIds.contains("host-a"))
        assertThrows<IllegalArgumentException> {
            registry.addOrRefresh("host-a", "https://a.example", "a2", nowMs = 3)
        }
        registry.completePendingCleanup("host-a")
        val repaired = registry.addOrRefresh("host-a", "https://a.example", "a2", nowMs = 4)

        assertTrue(repaired.profileGeneration > first.profileGeneration)
        assertTrue(repaired.connectionRevision > second.connectionRevision)
        assertEquals("host-b", registry.state.defaultHostId)
    }

    @Test
    fun default_falls_back_by_last_used_only_when_removed_and_updates_never_fallback_on_failure() {
        val registry = store()
        registry.addOrRefresh("host-a", "https://a.example", "a", nowMs = 1)
        registry.addOrRefresh("host-b", "https://b.example", "b", nowMs = 2)
        registry.setDefaultHost("host-a", nowMs = 30)
        // A background-style read does not alter either timestamp.
        registry.credentials("host-b")
        registry.forget("host-a", UpdateHostDisposition.UseExisting("host-b"))

        assertEquals("host-b", registry.state.defaultHostId)
        assertEquals("host-b", registry.state.updateHostId)
        assertTrue(registry.state.inAppUpdatesEnabled)
    }

    @Test
    fun update_host_removal_requires_a_disposition_and_disable_clears_it() {
        val registry = store()
        registry.addOrRefresh("host-a", "https://a.example", "a", nowMs = 1)
        registry.addOrRefresh("host-b", "https://b.example", "b", nowMs = 2)

        assertThrows<IllegalStateException> { registry.forget("host-a") }
        registry.forget("host-a", UpdateHostDisposition.Disable)

        assertNull(registry.state.updateHostId)
        assertFalse(registry.state.inAppUpdatesEnabled)
    }

    @Test
    fun pending_legacy_credentials_survive_store_restart_and_promote_atomically() {
        val registry = store()
        registry.stageLegacyConnection("https://legacy.example/", "legacy-token", ExposureKind.Cloudflare, 7)
        assertTrue(registry.state.pendingLegacyConnection)
        assertEquals("legacy-token", registry.pendingCredentials()?.token)
        assertFalse(registryPrefs().all.values.any { it.toString().contains("legacy-token") })

        val restarted = HostRegistryStore(context, cipher, clock = { 100L })
        val profile = restarted.promotePending("legacy-id", nowMs = 12)
        val after = HostRegistryStore(context, cipher, clock = { 100L })

        assertFalse(after.state.pendingLegacyConnection)
        assertEquals("legacy-id", after.state.defaultHostId)
        assertEquals(profile.profileGeneration, after.state.legacyLinkGeneration)
        assertEquals("legacy-token", after.credentials("legacy-id")?.token)
        assertEquals("https://legacy.example", profile.baseUrl)
        assertEquals("legacy.example", profile.alias)
        assertEquals("legacy-id", after.state.pendingLegacyMetadataHostId)
    }

    @Test
    fun replacement_transfers_default_and_confirmed_update_disposition_without_partial_duplicate() {
        val registry = store()
        val old = registry.addOrRefresh("old", "https://old.example", "old-token", nowMs = 1)
        registry.addOrRefresh("other", "https://other.example", "other-token", nowMs = 2)
        registry.setDefaultHost("old", nowMs = 3)

        assertThrows<IllegalArgumentException> {
            registry.replaceIdentity("old", "other", "https://other.example", "new-token")
        }
        assertEquals(old.hostId, registry.state.profiles.single { it.hostId == "old" }.hostId)

        val result = registry.replaceIdentity(
            previousHostId = "old",
            reportedHostId = "new",
            baseUrl = "https://new.example/",
            token = "new-token",
            updateHostDisposition = UpdateHostDisposition.UseExisting("other"),
            nowMs = 4,
        )
        assertEquals("new", result.replacement.hostId)
        assertEquals("new", registry.state.defaultHostId)
        assertEquals("other", registry.state.updateHostId)
        assertEquals("old.example", result.replacement.alias)
        assertEquals("new-token", registry.credentials("new")?.token)
        assertNull(registry.credentials("old"))
    }

    @Test
    fun remembered_alias_returns_after_forget_and_repair() {
        val registry = store()
        registry.addOrRefresh("host-a", "https://a.example", "a", nowMs = 1)
        registry.rename("host-a", "remember me")
        registry.forget("host-a")
        registry.completePendingCleanup("host-a")

        assertEquals("remember me", registry.addOrRefresh("host-a", "https://changed.example", "a2", nowMs = 2).alias)
    }

    @Test
    fun legacy_singleton_with_identity_imports_with_migration_link_marker() {
        val legacy = ConnectionStore.Saved(
            host = "https://legacy.example/",
            token = "legacy-token",
            exposure = ExposureKind.Tailscale,
            hostId = "legacy-id",
        )
        val profile = store().importLegacyConnection(legacy, nowMs = 9)
        val state = store().state

        assertNotNull(profile)
        assertEquals("legacy-id", state.defaultHostId)
        assertEquals("legacy-id", state.updateHostId)
        assertTrue(state.inAppUpdatesEnabled)
        assertEquals(profile!!.profileGeneration, state.legacyLinkGeneration)
        assertEquals("legacy-id", state.pendingLegacyMetadataHostId)
        assertEquals("legacy-token", store().credentials("legacy-id")?.token)
    }

    private fun registryPrefs() = context.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE)

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("wrong exception: ${error.javaClass}", error is T)
            return
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
