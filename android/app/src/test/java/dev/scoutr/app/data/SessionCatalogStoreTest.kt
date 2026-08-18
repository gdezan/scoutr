package dev.scoutr.app.data

import android.content.Context
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        context.getSharedPreferences("scoutr_session_catalog", Context.MODE_PRIVATE).edit().clear().commit()
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
}
