package dev.cockpit.app.data

/**
 * The session-control verb set, mirrored with the bridge's `ControlAction`
 * (bridge/src/agents/types.ts) and enforced there per backend capability.
 * A control the backend cannot perform is rejected server-side before any
 * pane input; the app renders the overflow menu from the session's
 * capability set so such controls are not offered in the first place.
 */
enum class SessionAction(val wire: String, val label: String) {
    Abort("abort", "Abort response"),
    Retry("retry", "Retry last message"),
    Compact("compact", "Compact context"),
    Fork("fork", "Fork session"),
    Rename("rename", "Rename session…"),
    Close("close", "Close session…"),
    SetModel("set_model", "Set model"),
    SetThinking("set_thinking", "Set thinking");

    companion object {
        /** Unknown verbs are dropped, never thrown — same rule as [AgentStatus.fromWire]. */
        fun fromWire(value: String): SessionAction? = entries.find { it.wire == value }
    }
}

/**
 * The chat overflow menu surface. Null means the backend is unknown yet
 * (show the full pi surface until the first agents poll names it); non-null
 * entries are the decoded capability set with unknown verbs dropped.
 */
fun List<String>?.toSessionActions(): Set<SessionAction>? =
    this?.mapNotNull(SessionAction::fromWire)?.toSet()
