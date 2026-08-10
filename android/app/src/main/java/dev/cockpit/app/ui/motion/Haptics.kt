package dev.cockpit.app.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Semantic haptics: interactions map to meaning, not to raw motor calls, so
 * the whole app speaks one tactile vocabulary and a future settings toggle
 * can gate every haptic in one place.
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
 * has no haptics.
 */
@Composable
fun rememberHaptic(): (HapticEvent) -> Unit {
    val feedback: HapticFeedback = LocalHapticFeedback.current
    return { event -> feedback.performHapticFeedback(event.toFeedbackType()) }
}
