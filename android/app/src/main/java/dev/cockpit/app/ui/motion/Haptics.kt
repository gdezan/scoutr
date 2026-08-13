package dev.cockpit.app.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.cockpit.app.data.AppearancePreferencesStore

/**
 * Semantic haptics: interactions map to meaning, not to raw motor calls, so
 * the whole app speaks one tactile vocabulary and the Settings switch gates
 * every haptic in one place.
 */
enum class HapticEvent {
    /** Cheap, low-stakes feedback: tab switch, palette row, filter chip. */
    Select,

    /** An action landed: message sent, question answered, session resumed. */
    Confirm,

    /** A destructive action succeeded: close, delete, abort. */
    Destructive,

    /** The action failed or was rejected. */
    Error,

    /** A condition needs attention (e.g. an agent is waiting). */
    NeedsYou,

    /** A warning surfaced (error banner, quota near limit). */
    Warning,
}

/** Maps a semantic event to the strongest platform-appropriate motor output. */
fun HapticEvent.toFeedbackType(): HapticFeedbackType = when (this) {
    HapticEvent.Select -> HapticFeedbackType.ContextClick
    HapticEvent.Confirm -> HapticFeedbackType.LongPress
    HapticEvent.Destructive -> HapticFeedbackType.LongPress
    HapticEvent.Error -> HapticFeedbackType.Reject
    HapticEvent.NeedsYou -> HapticFeedbackType.LongPress
    HapticEvent.Warning -> HapticFeedbackType.ContextClick
}

/**
 * Returns a function that fires the given semantic event on the current
 * haptic feedback provider. Always safe to call; no-ops where the platform
 * has no haptics, and no-ops entirely while the Settings haptics switch is
 * off.
 *
 * The preference is read when the event fires, not when the composable runs,
 * so flipping the switch silences screens that are already composed (an open
 * Terminal's BEL, the Board's needs-you nudge) without any invalidation.
 * This is the only motor entry point in the app — never call
 * `LocalHapticFeedback.performHapticFeedback` directly, or the switch will
 * not reach it.
 */
@Composable
fun rememberHaptic(): (HapticEvent) -> Unit {
    val feedback: HapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val appearance = remember(context) { AppearancePreferencesStore(context) }
    return remember(feedback, appearance) {
        { event ->
            if (appearance.hapticsEnabled) feedback.performHapticFeedback(event.toFeedbackType())
        }
    }
}
