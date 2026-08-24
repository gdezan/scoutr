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
 * Host-qualified terminal preferences: host-id isolation, defaults,
 * first-host migration, cleanup, and no credential-derived durable keys.
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
        val prefs = store().forHost("host-a")
        assertEquals(null, prefs.lastPaneId)
        assertEquals(TerminalPreferencesStore.ConnectionPreferences.DEFAULT_FONT_SIZE_SP, prefs.fontSizeSp)
        assertTrue(prefs.extraKeysVisible)
    }

    @Test
    fun values_round_trip_per_host_and_survive_credential_refresh() {
        val store = store()
        val a = store.forHost("host-a")
        val b = store.forHost("host-b")
        a.lastPaneId = "w1:p1"
        a.fontSizeSp = 16f
        a.extraKeysVisible = false
        assertNotEquals(a.lastPaneId, b.lastPaneId)
        assertEquals(null, b.lastPaneId)
        // URL/token changes are irrelevant: the host id is the durable key.
        assertEquals(16f, store.forHost("host-a").fontSizeSp)
        assertFalse(store.forHost("host-a").extraKeysVisible)
    }

    @Test
    fun view_preference_writes_are_visible_to_a_second_reader_and_tick_the_revision() {
        // Settings and the terminal route both ask the store for the same
        // connection; a write on either must reach the other, and the revision
        // is what tells an already-composed reader to re-read.
        val store = store()
        val settings = store.forHost("host-a")
        val terminal = store.forHost("host-a")
        val before = store.viewPreferencesRevision.value

        settings.fontSizeSp = 18f
        assertEquals(18f, terminal.fontSizeSp)
        assertEquals(before + 1, store.viewPreferencesRevision.value)

        settings.extraKeysVisible = false
        assertFalse(terminal.extraKeysVisible)
        assertEquals(before + 2, store.viewPreferencesRevision.value)

        // Independently of the cache, the values are on disk for a cold reader.
        assertEquals(18f, TerminalPreferencesStore(RuntimeEnvironment.getApplication())
            .forHost("host-a").fontSizeSp)
    }

    @Test
    fun connection_handles_are_cached_so_a_pinch_does_not_re_derive_the_key() {
        // Pinch writes on every motion event and the revision tick makes the
        // screen re-read; deriving the SHA-256 key each time put three digests
        // on the frame thread per event.
        val store = store()
        assertSame(
            store.forHost("host-a"),
            store.forHost("host-a"),
        )
        assertNotSame(
            store.forHost("host-a"),
            store.forHost("host-b"),
        )
    }

    @Test
    fun font_size_is_clamped_on_write_whoever_writes_it() {
        val prefs = store().forHost("host-a")
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
        store.forHost("host-a").lastPaneId = "w1:p1"
        assertEquals(before, store.viewPreferencesRevision.value)
    }

    @Test
    fun first_host_migration_moves_legacy_url_token_state_once() {
        val oldHost = "https://bridge.example/path"
        val oldToken = "legacy-token"
        val oldKey = TerminalPreferencesStore.legacyConnectionKey(oldHost, oldToken)
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(TerminalPreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("$oldKey.lastPaneId", "legacy-pane")
            .putFloat("$oldKey.fontSizeSp", 17f)
            .commit()

        val store = store()
        store.adoptLegacyPreferences("host-a", oldHost, oldToken)

        assertEquals("legacy-pane", store.forHost("host-a").lastPaneId)
        assertEquals(17f, store.forHost("host-a").fontSizeSp)
        assertEquals(null, store.forHost("host-b").lastPaneId)
    }

    @Test
    fun clearHost_removes_selected_pane_and_view_choices_without_touching_another_host() {
        val store = store()
        store.forHost("host-a").lastPaneId = "pane-a"
        store.forHost("host-a").fontSizeSp = 19f
        store.forHost("host-b").lastPaneId = "pane-b"

        assertTrue(store.clearHost("host-a"))
        assertEquals(null, store.forHost("host-a").lastPaneId)
        assertEquals(TerminalPreferencesStore.ConnectionPreferences.DEFAULT_FONT_SIZE_SP, store.forHost("host-a").fontSizeSp)
        assertEquals("pane-b", store.forHost("host-b").lastPaneId)
    }

    @Test
    fun retiredHostCannotRepopulateClearedTerminalState() {
        val guarded = TerminalPreferencesStore(RuntimeEnvironment.getApplication()) { _, _ -> false }
        guarded.forHost("host-a").lastPaneId = "stale-pane"

        assertEquals(null, guarded.forHost("host-a").lastPaneId)
    }

    @Test
    fun token_never_appears_in_preference_keys() {
        val app = RuntimeEnvironment.getApplication()
        val token = "super-secret-token-42"
        store().forHost("host-a").lastPaneId = "w1:p1"
        val all = app.getSharedPreferences(TerminalPreferencesStore.FILE, Context.MODE_PRIVATE).all
        assertTrue(all.isNotEmpty())
        for (key in all.keys) {
            assertFalse("raw token leaked into key: $key", key.contains(token))
        }
        assertFalse("raw token leaked into value", all.values.any { it.toString().contains(token) })
        assertTrue("host state must be keyed by host id", all.keys.any { it.startsWith("host.") })
    }
}
