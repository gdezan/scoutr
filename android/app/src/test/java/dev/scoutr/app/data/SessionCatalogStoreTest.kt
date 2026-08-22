package dev.scoutr.app.data

import android.content.Context
import org.junit.Assert.assertEquals
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
    fun legacyPathMigratesWhenExactlyOneCatalogKeyClaimsIt() {
        val path = "/sessions/one.jsonl"
        context.getSharedPreferences("scoutr_session_catalog", Context.MODE_PRIVATE)
            .edit().putStringSet("pinned", setOf(path)).commit()
        val key = SessionKey("pi", path)

        assertEquals(setOf(key), store.pinnedKeys(listOf(key)))
        assertEquals(setOf(key), store.pinnedKeys(emptyList()))
    }

    @Test
    fun ambiguousLegacyPathIsRetainedUntilOneBackendMatchRemains() {
        val path = "/shared/session.jsonl"
        context.getSharedPreferences("scoutr_session_catalog", Context.MODE_PRIVATE)
            .edit().putStringSet("archived", setOf(path)).commit()
        val pi = SessionKey("pi", path)
        val claude = SessionKey("claude", path)

        assertTrue(store.archivedKeys(listOf(pi, claude)).isEmpty())
        assertEquals(setOf(pi), store.archivedKeys(listOf(pi)))
    }

    @Test
    fun backendQualifiedKeysWithTheSamePathDoNotCollide() {
        val pi = SessionKey("pi", "/shared/session.jsonl")
        val claude = SessionKey("claude", "/shared/session.jsonl")

        store.setPinned(pi, true)
        store.setPinned(claude, true)

        assertEquals(setOf(pi, claude), store.pinnedKeys(emptyList()))
        store.setPinned(pi, false)
        assertEquals(setOf(claude), store.pinnedKeys(emptyList()))
    }

    @Test
    fun sessionKeyEncodingRoundTripsArbitraryValidPaths() {
        val key = SessionKey("claude/code", "/projects/a b/α?.jsonl")

        assertEquals(key, decodeSessionKey(key.encode()))
    }

    @Test
    fun hostQualifiedEncodingRoundTripsAndKeepsSpellingsDistinct() {
        val identity = HostSessionKey("host_a", SessionKey("pi", "/p/one.jsonl"))

        assertEquals(identity, decodeHostSessionKey(identity.encode()))
        assertNull("hsk1 is not a plain session key", decodeSessionKey(identity.encode()))
        assertNull(decodeHostSessionKey("junk"))
    }

    @Test
    fun sk1EntriesMigrateToTheCurrentHostNamespaceOnRead() {
        val key = SessionKey("pi", "/sessions/hostless.jsonl")
        // Seed through a host-unaware store: pre-pairing device behaviour.
        SharedPreferencesSessionCatalogStore(context).setPinned(key, true)
        val hosted = SharedPreferencesSessionCatalogStore(context) { "host_a" }

        assertEquals(setOf(key), hosted.pinnedKeys(emptyList()))
        // The rewrite persists, so the legacy spelling is gone from storage.
        val stored = catalogPrefs()
            .getStringSet("pinned", emptySet()).orEmpty()
        assertTrue(stored.all { it.startsWith("hsk1.") })
    }

    @Test
    fun entriesOfOtherHostsAreHiddenButKeptForAPossibleRevisit() {
        val other = HostSessionKey("host_b", SessionKey("pi", "/sessions/x.jsonl"))
        catalogPrefs()
            .edit().putStringSet("archived", setOf(other.encode())).commit()
        val mine = SharedPreferencesSessionCatalogStore(context) { "host_a" }

        assertTrue(mine.archivedKeys(emptyList()).isEmpty())

        // Re-pairing with the original bridge surfaces the entry again.
        val original = SharedPreferencesSessionCatalogStore(context) { "host_b" }
        assertEquals(setOf(other.session), original.archivedKeys(emptyList()))
    }

    @Test
    fun mutationsAreWrittenHostQualifiedWhenTheHostIsKnown() {
        val store = SharedPreferencesSessionCatalogStore(context) { "host_a" }
        val key = SessionKey("pi", "/sessions/live.jsonl")

        store.setPinned(key, true)

        assertEquals(setOf(key), store.pinnedKeys(emptyList()))
        val stored = catalogPrefs()
            .getStringSet("pinned", emptySet()).orEmpty()
        assertTrue(stored.single().startsWith("hsk1."))
    }


    private companion object {
        const val PREFS = "scoutr_session_catalog"
    }
}
