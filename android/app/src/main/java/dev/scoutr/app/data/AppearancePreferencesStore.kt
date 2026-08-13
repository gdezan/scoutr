package dev.scoutr.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Device-global presentation preferences owned by Settings: the defaults a new
 * Chat visit starts from, and the single haptics switch every semantic haptic
 * is gated on.
 *
 * Deliberately not per-connection — these are how the user likes the app to
 * feel, so forgetting a pairing leaves them alone. Chat reads the defaults as
 * a seed only; the header toggles stay a local override for that visit.
 *
 * Holds no observable state, so call sites construct their own instance off
 * the ambient context rather than being handed one: every read goes to the
 * process-wide SharedPreferences cache, and [dev.scoutr.app.ui.motion.rememberHaptic]
 * has no seam to inject through. Add a Flow here and that stops being true —
 * it would have to move to `AppContainer` so every reader shares one instance,
 * the way `TerminalPreferencesStore` does for its revision tick.
 */
class AppearancePreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Seeds a new Chat visit's "show thinking" toggle. */
    var showThinkingDefault: Boolean
        get() = prefs.getBoolean(KEY_SHOW_THINKING, DEFAULT_SHOW_THINKING)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_THINKING, value).apply()

    /** Seeds a new Chat visit's "expand tool details" toggle. */
    var expandToolsDefault: Boolean
        get() = prefs.getBoolean(KEY_EXPAND_TOOLS, DEFAULT_EXPAND_TOOLS)
        set(value) = prefs.edit().putBoolean(KEY_EXPAND_TOOLS, value).apply()

    /** Off silences every [dev.scoutr.app.ui.motion.HapticEvent], BEL and NeedsYou included. */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, DEFAULT_HAPTICS)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    companion object {
        const val FILE = "scoutr_appearance"

        /** Today's hard-coded Chat values, now durable. */
        const val DEFAULT_SHOW_THINKING = true
        const val DEFAULT_EXPAND_TOOLS = false
        const val DEFAULT_HAPTICS = true

        private const val KEY_SHOW_THINKING = "showThinkingDefault"
        private const val KEY_EXPAND_TOOLS = "expandToolsDefault"
        private const val KEY_HAPTICS = "hapticsEnabled"
    }
}
