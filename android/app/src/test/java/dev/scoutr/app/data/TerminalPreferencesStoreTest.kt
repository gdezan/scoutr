package dev.scoutr.app.data

import android.content.Context
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Per-connection terminal preferences: SHA-256 key isolation, URL
 * canonicalization, defaults, and no raw token in preference keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalPreferencesStoreTest {

    @Before
    fun clearPrefs() {
        // Robolectric shares SharedPreferences across tests in one class.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(TerminalPreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun store(): TerminalPreferencesStore =
        TerminalPreferencesStore(RuntimeEnvironment.getApplication())

    @Test
    fun defaults_apply() {
        val prefs = store().forConnection("http://host", "token-a")
        assertEquals(null, prefs.lastPaneId)
        assertEquals(TerminalPreferencesStore.ConnectionPreferences.DEFAULT_FONT_SIZE_SP, prefs.fontSizeSp)
        assertTrue(prefs.extraKeysVisible)
    }

    @Test
    fun values_round_trip_per_connection() {
        val store = store()
        val a = store.forConnection("http://host", "token-a")
        val b = store.forConnection("http://host", "token-b")
        a.lastPaneId = "w1:p1"
        a.fontSizeSp = 16f
        a.extraKeysVisible = false
        assertNotEquals(a.lastPaneId, b.lastPaneId)
        assertEquals(null, b.lastPaneId)
        assertEquals(16f, store.forConnection("http://host", "token-a").fontSizeSp)
        assertFalse(store.forConnection("http://host", "token-a").extraKeysVisible)
    }

    @Test
    fun view_preference_writes_are_visible_to_a_second_reader_and_tick_the_revision() {
        // Settings and the terminal route both ask the store for the same
        // connection; a write on either must reach the other, and the revision
        // is what tells an already-composed reader to re-read.
        val store = store()
        val settings = store.forConnection("http://host", "t")
        val terminal = store.forConnection("http://host", "t")
        val before = store.viewPreferencesRevision.value

        settings.fontSizeSp = 18f
        assertEquals(18f, terminal.fontSizeSp)
        assertEquals(before + 1, store.viewPreferencesRevision.value)

        settings.extraKeysVisible = false
        assertFalse(terminal.extraKeysVisible)
        assertEquals(before + 2, store.viewPreferencesRevision.value)

        // Independently of the cache, the values are on disk for a cold reader.
        assertEquals(18f, TerminalPreferencesStore(RuntimeEnvironment.getApplication())
            .forConnection("http://host", "t").fontSizeSp)
    }

    @Test
    fun connection_handles_are_cached_so_a_pinch_does_not_re_derive_the_key() {
        // Pinch writes on every motion event and the revision tick makes the
        // screen re-read; deriving the SHA-256 key each time put three digests
        // on the frame thread per event.
        val store = store()
        assertSame(
            store.forConnection("http://host", "t"),
            store.forConnection("http://host", "t"),
        )
        assertNotSame(
            store.forConnection("http://host", "t"),
            store.forConnection("http://host", "other-token"),
        )
    }

    @Test
    fun font_size_is_clamped_on_write_whoever_writes_it() {
        val prefs = store().forConnection("http://host", "t")
        prefs.fontSizeSp = 100f
        assertEquals(TerminalPreferencesStore.ConnectionPreferences.MAX_FONT_SIZE_SP, prefs.fontSizeSp)
        prefs.fontSizeSp = 1f
        assertEquals(TerminalPreferencesStore.ConnectionPreferences.MIN_FONT_SIZE_SP, prefs.fontSizeSp)
    }

    @Test
    fun last_pane_writes_do_not_tick_the_view_revision() {
        // Attach writes lastPaneId often; nothing observes it, so it must not
        // churn the terminal's font/extra-keys recomposition.
        val store = store()
        val before = store.viewPreferencesRevision.value
        store.forConnection("http://host", "t").lastPaneId = "w1:p1"
        assertEquals(before, store.viewPreferencesRevision.value)
    }

    @Test
    fun canonicalize_equivalents_share_keys() {
        val store = store()
        val http = store.forConnection("http://HOST:80/path/", "t")
        val https = store.forConnection("https://host/path", "t")
        assertEquals(http.lastPaneId, https.lastPaneId)
        // Different port is a different connection.
        val port = store.forConnection("http://host:8080/path", "t")
        assertEquals(null, port.lastPaneId)
        port.lastPaneId = "w1:p1"
        assertEquals(null, store.forConnection("http://host:8080/other", "t").lastPaneId)
    }

    @Test
    fun canonicalize_rules() {
        assertEquals("http://host", TerminalPreferencesStore.canonicalize("HTTP://HOST:80/"))
        assertEquals("https://host:8443/path", TerminalPreferencesStore.canonicalize("https://host:8443/path/"))
        assertEquals("host", TerminalPreferencesStore.canonicalize("host"))
        assertEquals("http://host", TerminalPreferencesStore.canonicalize("http://host/"))
        assertEquals("http://host/a/b", TerminalPreferencesStore.canonicalize("http://host/a/b/"))
    }

    @Test
    fun token_never_appears_in_preference_keys() {
        val app = RuntimeEnvironment.getApplication()
        val token = "super-secret-token-42"
        store().forConnection("https://bridge.example", token).lastPaneId = "w1:p1"
        val all = app.getSharedPreferences(TerminalPreferencesStore.FILE, Context.MODE_PRIVATE).all
        assertTrue(all.isNotEmpty())
        for (key in all.keys) {
            assertFalse("raw token leaked into key: $key", key.contains(token))
        }
        assertFalse("raw token leaked into value", all.values.any { it.toString().contains(token) })
    }
}
