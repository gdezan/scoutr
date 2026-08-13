package dev.scoutr.app.data

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Device-global chat seeds + haptics switch: defaults, round-trip, independence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearancePreferencesStoreTest {

    @Before
    fun clearPrefs() {
        // Robolectric shares SharedPreferences across tests in one class.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(AppearancePreferencesStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun store() = AppearancePreferencesStore(RuntimeEnvironment.getApplication())

    @Test
    fun defaults_match_todays_chat_literals() {
        val prefs = store()
        assertTrue(prefs.showThinkingDefault)
        assertFalse(prefs.expandToolsDefault)
        assertTrue(prefs.hapticsEnabled)
    }

    @Test
    fun values_round_trip_through_a_second_reader() {
        store().showThinkingDefault = false
        store().expandToolsDefault = true
        store().hapticsEnabled = false

        val reread = store()
        assertFalse(reread.showThinkingDefault)
        assertTrue(reread.expandToolsDefault)
        assertFalse(reread.hapticsEnabled)
    }

    @Test
    fun keys_are_independent() {
        store().hapticsEnabled = false
        val prefs = store()
        assertTrue("haptics must not disturb the chat seeds", prefs.showThinkingDefault)
        assertFalse(prefs.expandToolsDefault)
    }
}
