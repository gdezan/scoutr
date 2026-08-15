package dev.scoutr.app.data

import android.content.Context
import org.junit.Assert.assertEquals
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
        assertEquals(11f, prefs.markdownCodeFontSizeSp)
        assertEquals(11f, prefs.reviewFontSizeSp)
        assertEquals(9.5f, prefs.toolOutputFontSizeSp, 0.01f)
    }

    @Test
    fun reduceMotionDefaultsOffAndPersists() {
        assertFalse(store().reduceMotionEnabled)

        store().reduceMotionEnabled = true

        assertTrue(store().reduceMotionEnabled)
    }

    @Test
    fun values_round_trip_through_a_second_reader() {
        store().showThinkingDefault = false
        store().expandToolsDefault = true
        store().hapticsEnabled = false
        store().markdownCodeFontSizeSp = 13f
        store().reviewFontSizeSp = 14f
        store().toolOutputFontSizeSp = 12.5f

        val reread = store()
        assertFalse(reread.showThinkingDefault)
        assertTrue(reread.expandToolsDefault)
        assertFalse(reread.hapticsEnabled)
        assertEquals(13f, reread.markdownCodeFontSizeSp)
        assertEquals(14f, reread.reviewFontSizeSp)
        assertEquals(12.5f, reread.toolOutputFontSizeSp, 0.01f)
    }

    @Test
    fun code_sizes_are_clamped_to_the_readable_range() {
        val prefs = store()
        prefs.markdownCodeFontSizeSp = 2f
        prefs.reviewFontSizeSp = 30f
        prefs.toolOutputFontSizeSp = 30f

        assertEquals(8f, prefs.markdownCodeFontSizeSp)
        assertEquals(18f, prefs.reviewFontSizeSp)
        assertEquals(18f, prefs.toolOutputFontSizeSp)
    }

    @Test
    fun keys_are_independent() {
        store().hapticsEnabled = false
        val prefs = store()
        assertTrue("haptics must not disturb the chat seeds", prefs.showThinkingDefault)
        assertFalse(prefs.expandToolsDefault)
    }
}
