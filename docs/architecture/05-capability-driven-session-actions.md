# 5. Capability-driven session actions

**Strength: Worth exploring.** Only becomes urgent once plan 1 ships a second agent.

## Files

`android/…/ui/screens/ChatScreen.kt:413-418, :349-350`, `android/…/state/ChatViewModel.kt:359, :374-375`,
`android/…/state/BoardViewModel.kt:153`, `android/…/state/SessionHistoryViewModel.kt:142,:157,:172,:191`,
`android/…/ui/screens/CommandPalette.kt:154`, `android/…/net/BridgeClient.kt:261`,
`bridge/src/sessions.ts:32-41`.

## Problem

The control vocabulary is bare strings on both sides of the wire, with no shared
declaration:

```kotlin
// ChatScreen.kt:413-418 — menu labels and action keys, hand-paired
"abort", "retry", "compact", "fork", "rename", "close"
```

```kotlin
// BridgeClient.kt:261
suspend fun controlSession(paneId: String, action: String, text: String? = null): ControlResponse
```

`"close"` is spelled out in three unrelated files. `"set_model"` and `"set_thinking"` are
branched on inside `ChatViewModel.kt:374-375`. Catalog actions `"resume"` / `"fork"` /
`"rename"` are typed literals in two more. A typo is a runtime 400; a rename on the bridge
side is a silent break with no compile error anywhere.

That is tolerable with one agent. With two it is a correctness problem, because **the verb
sets differ**. Claude Code has no `shift+tab` thinking cycler, so `set_thinking` has no
meaning for it — but `ChatScreen` renders the control unconditionally, the app sends the
verb, and `sessions.ts:328` throws `"unknown control action"` into a toast. The user sees a
control that looks available and fails. Worse are verbs that *appear* to work: sending
`/compact` into an agent that has no such slash command types visible garbage into the
user's pane.

The board has the same gap in the other direction: `AgentCard.agent` (`Models.kt:41`) is
displayed as a title fallback (`BoardScreen.kt:241-242`) and never compared to anything, so
the UI cannot vary by backend even where it should.

## Solution

Make the action vocabulary a declared type, and make availability a property of the session
rather than of the screen.

### A shared enum, mirrored on both sides

```kotlin
// data/SessionAction.kt
enum class SessionAction(val wire: String) {
    Abort("abort"), Retry("retry"), Compact("compact"), Fork("fork"),
    Rename("rename"), Close("close"), SetModel("set_model"), SetThinking("set_thinking");

    companion object { fun fromWire(value: String): SessionAction? = entries.find { it.wire == value } }
}
```

`controlSession(paneId: String, action: SessionAction, text: String?)`. Every literal in the
five files above becomes an enum reference. This mirrors the existing `AgentStatus`
(`Models.kt:230-238`), which already does exactly this for herdr's status vocabulary — so
the pattern is established in the codebase and just needs applying to actions.

### Capabilities travel with the session

Plan 1 adds `capabilities: string[]` to `AgentCard` and `SessionReadResult`, sourced from
`AgentBackend.capabilities`. The app decodes it into `Set<SessionAction>` (dropping unknown
verbs rather than throwing, as `AgentStatus.fromWire` does) and the chat overflow menu
renders from that set instead of a hardcoded list:

```kotlin
// ChatScreen.kt
val actions = uiState.capabilities   // Set<SessionAction>, from the bridge
DropdownMenu {
    actions.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { … }) }
}
```

A control the agent cannot perform is not rendered — the strongest possible fix, and one
that costs less code than the current hand-written menu.

`SetThinking` additionally needs `thinkingLevels` on the session read (already available per
model in `ModelInfo`); the sheet at `ChatScreen.kt:349-350` should hide the thinking row
when the set is empty rather than showing an inert control.

### Bridge side

`ControlAction` (`sessions.ts:32-41`) stays the union it already is, but the dispatch moves
into `AgentBackend.control` (plan 1) and each adapter declares the subset it implements.
`controlSession` rejects a verb outside the backend's `capabilities` with a 400 *before*
touching the pane — so a stale app can never type a bogus slash command into a live session.
That check is the load-bearing part; the UI hiding is the polish.

## Benefits

**Locality.** The verb set is declared in two files (one per side) instead of scattered
across seven. Renaming a verb is a compile error, not a runtime toast.

**Depth.** `capabilities` on the wire means the app asks *"what can this session do?"*
instead of encoding the answer for one agent. The bridge holds the knowledge; the UI holds
none.

**Leverage.** A third agent with a different verb set needs zero Android changes.

**Tests.** Menu-rendering tests become table-driven over capability sets — one test asserts
`SetThinking` is absent for a Claude-shaped card. Today that scenario is unrepresentable
because the menu takes no input.

## Steps

1. Add `SessionAction` and convert the five call sites (this half is worth doing on its own,
   before plan 1 — it is a pure mechanical win).
2. Do the same for the catalog actions (`resume` / `fork` / `rename`) as `CatalogAction`.
3. After plan 1: decode `capabilities` in `ChatUiState` and `BoardState`.
4. Render the overflow menu from the set; hide the thinking row on an empty level set.
5. Enforce capabilities server-side in `controlSession` before any pane input.
6. Table-driven UI tests over capability sets.

## Risks

- **Two agents will not have disjoint labels.** `Compact` means different things to different
  agents; the enum names the *intent*, and the adapter decides the keystrokes. Do not let
  agent-specific verbs leak into the shared enum — an agent-unique action belongs in a later
  `extraActions: [{id, label}]` list, not in `SessionAction`.
- **Ordering.** The current menu order is deliberate. A `Set` does not preserve it; use the
  enum's declaration order for rendering and treat the wire list as membership only.
