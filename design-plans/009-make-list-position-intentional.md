# Plan 009: Make search and tab position changes intentional

> **Executor instructions**: Follow this plan step by step. After each step, run the stated verification and inspect screenshots before continuing. If a STOP condition occurs, stop and report rather than improvising. When done, update this plan's row in `design-plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/scoutr/app/ui/screens/SessionPickers.kt android/app/src/main/java/dev/scoutr/app/ui/screens/ConversationConfigSheet.kt android/app/src/main/java/dev/scoutr/app/ui/screens/CommandPalette.kt android/app/src/main/java/dev/scoutr/app/ui/screens/HistoryScreen.kt android/app/src/androidTest/java/dev/scoutr/app/ui/NewSessionSheetTest.kt android/app/src/androidTest/java/dev/scoutr/app/ui/ChatControlsTest.kt android/app/src/androidTest/java/dev/scoutr/app/ui/CommandPaletteTest.kt android/app/src/androidTest/java/dev/scoutr/app/ui/HistoryScreenTest.kt`
> If Plan 008 is not complete or any list ownership changed, stop and reconcile before editing.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: Plans 002, 006, and 008
- **Category**: navigation, friction, feedback
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

A list position is part of the user's context. Today, narrowing and clearing a search can retain a raw numeric index and land at an arbitrary model or command, while switching Sessions tabs can carry one tab's index into another. The interface should either begin a new result set at its top or restore the exact item the user was reading—never expose incidental index clamping.

## Current state

- `SessionPickers.kt:100-127` and `ConversationConfigSheet.kt:173-198` keep their `LazyListState` as model matches change. Searching from deep in the catalog can clamp the old index into the shorter results; clearing can then reveal a seemingly random point in the full catalog.
- `CommandPalette.kt:130-155` similarly keeps list state while `ui.results` changes with `ui.query`.
- `HistoryScreen.kt:107-128, 246-299` switches among Active, Completed, Pinned, and Archived while one list composition/state represents all tabs. If both tabs have content, a deep index from one tab can carry into another and back.

Stable item keys already exist for model keys, palette identities, and session paths. Reuse them. This plan changes position policy, not sorting, ranking, or list visuals.

## Intended result

| Interaction | Position policy |
|---|---|
| Enter or change model search | Reset that picker's result list to the first provider header/top result. |
| Clear model search | Reset to the top of the full provider catalog. |
| Enter, change, or clear command-palette search | Reset results to the first row. |
| Switch Sessions tab | Restore that tab's most recently visible session by stable `session.path`; first visit starts at top. |
| Remembered Sessions item no longer exists | Use the saved old ordered index and adjacent stable paths to prefer the next surviving item, otherwise the prior item/top. |
| Process/configuration recreation | Preserve per-tab anchors with saveable identity where supported; do not persist them to app storage. |

Position changes are immediate and quiet—no animated fly-through of dozens of rows. Focus remains in the active search field while typing. Resetting search must not change the selected model or execute a palette/session action.

## Commands

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.NewSessionSheetTest,dev.scoutr.app.ui.ChatControlsTest,dev.scoutr.app.ui.CommandPaletteTest,dev.scoutr.app.ui.HistoryScreenTest
```

All device work must target `emulator-5554` and every `adb` command must use `timeout 30`. Use deterministic fixtures with at least 30 rows so the start and restored positions are visually distinct. Add screenshot helpers to the existing classes only if semantics assertions cannot prove the visible anchor; print and use exact artifact paths.

## Scope

**In scope**:
- `android/app/src/main/java/dev/scoutr/app/ui/screens/SessionPickers.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/ConversationConfigSheet.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/CommandPalette.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/HistoryScreen.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/NewSessionSheetTest.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/ChatControlsTest.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/CommandPaletteTest.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/HistoryScreenTest.kt`

**Out of scope**:
- Search matching/ranking and provider grouping (Plan 008)
- Sessions sorting and live reorder motion (Plan 010)
- Persisting scroll positions across app launches or accounts
- Board, Review, Chat transcript, and slash-command scrolling
- Changing keyboard focus or IME behavior

## Steps

### Step 1: Reset model results by query identity

Give each model picker its own `LazyListState`. On any query text change—including Clear—cancel an obsolete position job and perform one immediate reset to index 0 after the new provider-grouped content is available. Do not share state between the New Session and Conversation Setup entry points.

**Verify**: in each picker, scroll below the first provider, type a query, and confirm the first matching provider header/result is visible. Clear the query and confirm the first provider header is visible. Search focus and selected model remain unchanged.

### Step 2: Reset command-palette results

Hoist an explicit `LazyListState` in `CommandPalette`. Key one immediate reset to `ui.query`; if loading briefly removes the list, apply the reset when the new non-empty results attach rather than throwing or retaining a stale index. Repeated result refreshes for an unchanged query must not reset a user who is browsing.

**Verify**: scroll deep, type a narrowing query, clear it, and assert the first result is displayed after each query transition. Refreshing unchanged results leaves the current anchor intact.

### Step 3: Preserve one stable anchor per Sessions tab

Maintain a saveable anchor snapshot per `HistoryView`: the first meaningfully visible session path and offset, its index in the old ordered list, and the nearest preceding/following stable paths. Before switching tabs, capture the outgoing snapshot. On entering a tab, restore its surviving path and offset; a first visit begins at top. If the path is gone, use the saved neighbors/old index to prefer the next surviving item, then the prior item/top. Handle empty/loading/error branches without discarding saved anchors.

Keep restoration logic next to `HistoryList` and its `LazyListState`; do not put UI scroll offsets in `SessionHistoryViewModel` or persistent stores.

**Verify**: with long Active and Completed fixtures, position each tab at a different named session and alternate twice. Each returns to its named row at the same approximate offset. Pinned/Archived first visits start at top. If the anchored session disappears, restoration is valid and does not crash.

### Step 4: Add adversarial state-transition tests

Cover model and palette query changes while deep-scrolled, clear-query behavior, unchanged-query refresh, first tab visits, per-tab return, empty-tab round trip, and missing-anchor fallback. Avoid assertions based only on `waitForIdle`; assert the expected first visible item/provider through semantics or an exposed test-only state seam.

**Verify**: focused classes run repeatedly without flakiness, then all Android gates pass.

## Done criteria

- [ ] Both model searches reset to the top for every query transition and keep focus/selection.
- [ ] Command Palette resets only when query identity changes, not on same-query refresh.
- [ ] Sessions preserves a stable path/offset independently for all four tabs.
- [ ] Empty/loading transitions and removed anchors produce deterministic fallbacks without crashes.
- [ ] No position state is written to a ViewModel store or disk.
- [ ] Focused and full Android gates pass; only in-scope files changed.

## STOP conditions

- Plan 008 is incomplete or leaves either model picker without a stable provider/model key structure.
- A list does not expose a stable item identity matching the Current state.
- Preserving a Sessions anchor requires changing backend sorting/query contracts.
- A Compose lifecycle race cannot be made deterministic without fixed delays; report evidence rather than adding sleeps.

## Maintenance notes

Document the policy beside each list state: search creates a new result set and starts at top; tabs are parallel places and restore by identity. Future filters should explicitly choose one of these policies instead of inheriting a raw list index.