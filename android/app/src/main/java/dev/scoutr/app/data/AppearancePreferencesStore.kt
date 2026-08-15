package dev.scoutr.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Device-global presentation preferences owned by Settings: the defaults a new
 * Chat visit starts from, adjustable machine-text sizes, and the single haptics
 * switch every semantic haptic is gated on, and the app-level reduce-motion
 * switch.
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

    /** Font size for inline and fenced code in assistant Markdown. */
    var markdownCodeFontSizeSp: Float
        get() = prefs.getFloat(KEY_MARKDOWN_CODE_FONT_SIZE, DEFAULT_MARKDOWN_CODE_FONT_SIZE_SP)
        set(value) = prefs.edit().putFloat(KEY_MARKDOWN_CODE_FONT_SIZE, value.coerceIn(MIN_CODE_FONT_SIZE_SP, MAX_CODE_FONT_SIZE_SP)).apply()

    /** Font size for diff and file content in the Review screen. */
    var reviewFontSizeSp: Float
        get() = prefs.getFloat(KEY_REVIEW_FONT_SIZE, DEFAULT_REVIEW_FONT_SIZE_SP)
        set(value) = prefs.edit().putFloat(KEY_REVIEW_FONT_SIZE, value.coerceIn(MIN_CODE_FONT_SIZE_SP, MAX_CODE_FONT_SIZE_SP)).apply()

    /** Font size for expanded tool results and inline file-edit diffs. */
    var toolOutputFontSizeSp: Float
        get() = prefs.getFloat(KEY_TOOL_OUTPUT_FONT_SIZE, DEFAULT_TOOL_OUTPUT_FONT_SIZE_SP)
        set(value) = prefs.edit().putFloat(KEY_TOOL_OUTPUT_FONT_SIZE, value.coerceIn(MIN_CODE_FONT_SIZE_SP, MAX_CODE_FONT_SIZE_SP)).apply()

    /** Off silences every [dev.scoutr.app.ui.motion.HapticEvent], BEL and NeedsYou included. */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, DEFAULT_HAPTICS)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** When on, collapses the app's decorative motion without changing Android's system setting. */
    var reduceMotionEnabled: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, DEFAULT_REDUCE_MOTION)
        set(value) = prefs.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    companion object {
        const val FILE = "scoutr_appearance"

        /** Defaults for compact machine-text controls in Chat and Review. */
        const val DEFAULT_MARKDOWN_CODE_FONT_SIZE_SP = 11f
        const val DEFAULT_REVIEW_FONT_SIZE_SP = 11f
        const val DEFAULT_TOOL_OUTPUT_FONT_SIZE_SP = 9.5f
        const val MIN_CODE_FONT_SIZE_SP = 8f
        const val MAX_CODE_FONT_SIZE_SP = 18f

        /** Today's hard-coded Chat values, now durable. */
        const val DEFAULT_SHOW_THINKING = true
        const val DEFAULT_EXPAND_TOOLS = false
        const val DEFAULT_HAPTICS = true
        const val DEFAULT_REDUCE_MOTION = false

        private const val KEY_SHOW_THINKING = "showThinkingDefault"
        private const val KEY_EXPAND_TOOLS = "expandToolsDefault"
        private const val KEY_MARKDOWN_CODE_FONT_SIZE = "markdownCodeFontSizeSp"
        private const val KEY_REVIEW_FONT_SIZE = "reviewFontSizeSp"
        private const val KEY_TOOL_OUTPUT_FONT_SIZE = "toolOutputFontSizeSp"
        private const val KEY_HAPTICS = "hapticsEnabled"
        private const val KEY_REDUCE_MOTION = "reduceMotionEnabled"
    }
}
