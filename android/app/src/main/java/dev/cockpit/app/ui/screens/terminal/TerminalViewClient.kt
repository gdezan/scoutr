package dev.cockpit.app.ui.screens.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.cockpit.app.terminal.RemoteTerminalSession

/**
 * Extra-key modifier model (slice 7 "Extra keys"): each of Ctrl/Alt/Shift
 * cycles tap Off -> One-shot -> Off and Locked -> Off, while a long press
 * locks it (Off/One-shot -> Locked, Locked -> Off).
 *
 * One-shot modifiers are consumed by the next key event — in the view client
 * ([TerminalViewClient.onKeyUp] for hardware keys, [TerminalViewClient.onCodePoint]
 * for Gboard text) and by the extra-key row after it sends a key — so a
 * single tap of Ctrl then `c`
 * into every key it processes (handleKeyCode / inputCodePoint), which is why
 * reading here covers hardware keyboards, Gboard and the extra row alike.
 */
enum class ModifierMode { OFF, ONE_SHOT, LOCKED }

enum class ModifierKey(val label: String) {
    CTRL("Ctrl"), ALT("Alt"), SHIFT("Shift");
}

class ExtraKeyModifierState {
    var ctrl: ModifierMode = ModifierMode.OFF
        private set
    var alt: ModifierMode = ModifierMode.OFF
        private set
    var shift: ModifierMode = ModifierMode.OFF
        private set

    val ctrlActive: Boolean get() = ctrl != ModifierMode.OFF
    val altActive: Boolean get() = alt != ModifierMode.OFF
    val shiftActive: Boolean get() = shift != ModifierMode.OFF

    fun mode(key: ModifierKey): ModifierMode = when (key) {
        ModifierKey.CTRL -> ctrl
        ModifierKey.ALT -> alt
        ModifierKey.SHIFT -> shift
    }

    /** Tap: Off -> One-shot, anything else -> Off. */
    fun tap(key: ModifierKey) {
        set(key, if (mode(key) == ModifierMode.OFF) ModifierMode.ONE_SHOT else ModifierMode.OFF)
    }

    /** Long press: Off/One-shot -> Locked, Locked -> Off. */
    fun longPress(key: ModifierKey) {
        set(key, if (mode(key) == ModifierMode.LOCKED) ModifierMode.OFF else ModifierMode.LOCKED)
    }

    /** Drop one-shot state after it has been applied to a key event. */
    fun consumeOneShots() {
        if (ctrl == ModifierMode.ONE_SHOT) ctrl = ModifierMode.OFF
        if (alt == ModifierMode.ONE_SHOT) alt = ModifierMode.OFF
        if (shift == ModifierMode.ONE_SHOT) shift = ModifierMode.OFF
    }

    private fun set(key: ModifierKey, mode: ModifierMode) {
        when (key) {
            ModifierKey.CTRL -> ctrl = mode
            ModifierKey.ALT -> alt = mode
            ModifierKey.SHIFT -> shift = mode
        }
    }

    fun toKeyMod(): Int {
        var mod = 0
        if (ctrlActive) mod = mod or KeyHandler.KEYMOD_CTRL
        if (altActive) mod = mod or KeyHandler.KEYMOD_ALT
        if (shiftActive) mod = mod or KeyHandler.KEYMOD_SHIFT
        return mod
    }
}

/**
 * Slice 7 view client. Policy decisions (plan "TerminalView integration"):
 *  - Back is never terminal Escape ([shouldBackButtonBeMappedToEscape] false);
 *    Compose's BackHandler owns it.
 *  - Char-based input on (Gboard composition via the visible-password input
 *    type), no Ctrl+Space workaround.
 *  - Text selection is the default long-press behavior ([onLongPress] false).
 *  - Modifier reads come from the extra-key row state.
 *  - Grid changes are reported so the ViewModel can resize the controller or
 *    restart the observer; font-size changes from pinch flow through
 *    [onFontScale].
 *  - A single tap looks for an http(s) link at the cell and reports it; plain
 *    taps keep the keyboard-toggle behavior.
 */
internal class TerminalViewClient(
    private val view: TerminalView,
    private val session: RemoteTerminalSession,
    private val modifierState: ExtraKeyModifierState,
    private val writable: () -> Boolean,
    private val onGridMeasured: (cols: Int, rows: Int) -> Unit,
    private val onLinkTap: (String) -> Unit,
    private val onPlainTap: () -> Unit,
    private val currentFontSizeSp: () -> Float,
    private val onFontScale: (Float) -> Unit,
) : com.termux.view.TerminalViewClient {

    override fun onScale(scale: Float): Float {
        val newSp = (currentFontSizeSp() * scale)
            .coerceIn(8f, 24f)
            .let { if (it.isNaN() || it.isInfinite()) currentFontSizeSp() else it }
        onFontScale(newSp)
        // Reset the view's accumulated scale factor; the new font size is
        // already applied via the preference-driven setTextSize pass.
        return 1f
    }

    override fun onSingleTapUp(event: MotionEvent) {
        if (!writable()) {
            onPlainTap()
            return
        }
        val word = linkAt(event)
        if (word != null) onLinkTap(word) else onPlainTap()
    }

    private fun linkAt(event: MotionEvent): String? {
        val emulator = session.emulator ?: return null
        val cell = view.getColumnAndRow(event, true) ?: return null
        if (cell.size < 2) return null
        val word = emulator.getScreen().getWordAtLocation(cell[0], cell[1]) ?: return null
        val trimmed = word.trimEnd(')', ']', '}', ',', ';', '.', ':', '!', '?', '\'', '"')
        return if (LINK_REGEX.matches(trimmed)) trimmed else null
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = writable()

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean {
        // Not consumed here: the vendored view reads readControlKey() AFTER
        // this callback, so clearing one-shots now would starve the event.
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isModifierKey(keyCode)) modifierState.consumeOneShots()
        return false
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = modifierState.ctrlActive

    override fun readAltKey(): Boolean = modifierState.altActive

    override fun readShiftKey(): Boolean = modifierState.shiftActive

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        modifierState.consumeOneShots()
        return false
    }

    override fun onEmulatorSet() {
        val emulator = view.mEmulator ?: return
        onGridMeasured(emulator.mColumns, emulator.mRows)
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }

    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }

    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }

    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }

    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }

    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stack trace", e) }

    private fun isModifierKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        -> true
        else -> false
    }

    private companion object {
        val LINK_REGEX = Regex("""^https?://\S+$""")
    }
}
