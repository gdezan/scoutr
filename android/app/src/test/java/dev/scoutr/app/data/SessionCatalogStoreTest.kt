package dev.scoutr.app.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SessionCatalogStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var store: SharedPreferencesSessionCatalogStore

    private fun catalogPrefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        catalogPrefs().edit().clear().commit()
        store = SharedPreferencesSessionCatalogStore(context)
    }

    @Test
    fun legacyPathDoesNotMigrateOnReadButDoesMigrateDuringFirstHostAdoption() {
        val path = "/sessions/one.jsonl"
        catalogPrefs().edit().putStringSet("pinned", setOf(path)).commit()
        val key = SessionKey("pi", path)
        val hosted = HostSessionKey("host_a", key)

        assertTrue(store.pinnedKeys(listOf(hosted)).isEmpty())
        assertEquals(setOf(path), catalogPrefs().getStringSet("pinned", emptySet()))
        assertTrue(store.hasUnqualifiedLegacyEntries())
        store.adoptLegacyEntries("host_a", listOf(key))

        assertEquals(setOf(hosted), store.pinnedKeys(emptyList()))
        assertTrue(catalogPrefs().getStringSet("pinned", emptySet()).orEmpty()
            .single().startsWith("hsk1."))
        assertFalse(store.hasUnqualifiedLegacyEntries())
    }

    @Test
    fun ambiguousLegacyPathWaitsForAUniqueFirstHostCatalogOwner() {
        val path = "/shared/session.jsonl"
        catalogPrefs().edit().putStringSet("archived", setOf(path)).commit()
        val pi = SessionKey("pi", path)
        val claude = SessionKey("claude", path)

        store.adoptLegacyEntries("host_a", listOf(pi, claude))
        assertTrue(store.archivedKeys(emptyList()).isEmpty())
        assertEquals(setOf(path), catalogPrefs().getStringSet("archived", emptySet()))

        // The marker is idempotent for the same first host; it does not permit
        // a later host to claim the old singleton entry.
        store.adoptLegacyEntries("host_a", listOf(pi))
        assertEquals(
            setOf(HostSessionKey("host_a", pi)),
            store.archivedKeys(emptyList()),
        )
    }

    @Test
    fun oldSk1EntriesMigrateOnlyWhenFirstHostAdoptionIsExplicit() {
        val key = SessionKey("pi", "/sessions/legacy.jsonl")
        catalogPrefs().edit().putStringSet("pinned", setOf(key.encode())).commit()
        assertTrue(store.pinnedKeys(emptyList()).isEmpty())

        store.adoptLegacyEntries("first-host")

        assertEquals(setOf(HostSessionKey("first-host", key)), store.pinnedKeys(emptyList()))
    }

    @Test
    fun sameSessionOnTwoHostsHasIndependentRetainedMetadata() {
        val session = SessionKey("pi", "/shared/session.jsonl")
        val a = HostSessionKey("host_a", session)
        val b = HostSessionKey("host_b", session)

        store.setPinned(a, true)
        store.setPinned(b, true)
        assertEquals(setOf(a, b), store.pinnedKeys(emptyList()))

        store.setPinned(a, false)
        assertEquals(setOf(b), store.pinnedKeys(emptyList()))
    }

    @Test
    fun readsOnlyReturnTheRequestedHostCatalogEntries() {
        val session = SessionKey("pi", "/same.jsonl")
        val a = HostSessionKey("host_a", session)
        val b = HostSessionKey("host_b", session)
        store.setArchived(a, true)
        store.setArchived(b, true)

        assertEquals(setOf(a), store.archivedKeys(listOf(a)))
        assertEquals(setOf(b), store.archivedKeys(listOf(b)))
    }

    @Test
    fun retainedMetadataSurvivesForgetAndSameIdRepair() {
        val key = HostSessionKey("host_a", SessionKey("pi", "/kept.jsonl"))
        store.setPinned(key, true)
        store.setArchived(key, true)

        // Cleanup intentionally has no SessionCatalogStore.clearHost call:
        // retained flags are host-owned durable metadata.
        val repaired = SharedPreferencesSessionCatalogStore(context)
        assertEquals(setOf(key), repaired.pinnedKeys(emptyList()))
        assertEquals(setOf(key), repaired.archivedKeys(emptyList()))
    }

    @Test
    fun copyingRetainedMetadataNeedsExplicitConfirmationAndKeepsTheSource() {
        val session = SessionKey("pi", "/copy.jsonl")
        val source = HostSessionKey("host_a", session)
        val target = HostSessionKey("host_b", session)
        store.setPinned(source, true)

        var rejected = false
        try {
            store.copyRetainedMetadata("host_a", "host_b", confirmed = false)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)

        store.copyRetainedMetadata("host_a", "host_b", confirmed = true)
        assertEquals(setOf(source, target), store.pinnedKeys(emptyList()))
    }

    @Test
    fun sessionAndHostCodecsRoundTripAndRejectStaleShapes() {
        val key = SessionKey("claude/code", "/projects/a b/α?.jsonl")
        val identity = HostSessionKey("host.a", key)
        val profile = HostProfileKey("host.a", 42)
        val pane = HostPaneKey(profile, "pane.1")

        assertEquals(key, decodeSessionKey(key.encode()))
        assertEquals(identity, decodeHostSessionKey(identity.encode()))
        assertNull("hsk1 is not a plain session key", decodeSessionKey(identity.encode()))
        assertEquals(profile, decodeHostProfileKey(profile.encode()))
        assertEquals(pane, decodeHostPaneKey(pane.encode()))
        assertFalse(decodeHostSessionKey("junk") != null)
        assertNull(decodeHostProfileKey("hpk1.invalid.0"))
        assertNull(decodeHostPaneKey("hpn1.invalid.1."))
    }

    private companion object {
        const val PREFS = "scoutr_session_catalog"
    }
}
