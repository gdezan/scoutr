# Plan 011: Keep slash-command keyboard selection in view without jumping

> **Executor instructions**: Follow this plan step by step. After each step, run the stated verification and inspect the recording before continuing. If a STOP condition occurs, stop and report rather than improvising. When done, update this plan's row in `design-plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/cockpit/app/ui/screens/SlashCommandMenu.kt android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt android/app/src/androidTest/java/dev/cockpit/app/ui/SlashCommandMenuTest.kt`
> If menu row height, keyboard selection, or list behavior changed materially, stop and reconcile before editing.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: `design-plans/007-stabilize-chat-scroll-to-end.md`
- **Category**: navigation, friction, keyboard, a11y
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

Slash commands are fastest when navigated from the keyboard, but the menu currently repositions every newly selected command at the top edge. Once selection passes the visible rows, each Down press makes the entire menu jump. Stable command identity and minimal keep-visible scrolling make the highlight easy to track without slowing keyboard use.

## Current state

Type `/` in the Chat composer with at least eight available commands, then press Down repeatedly.

`SlashCommandMenu.kt:53-72` runs `listState.scrollToItem(selectedIndex)` after every selection change. This pins the selected row to the viewport top even when it was already fully visible. Item keys include the mutable index—`"$index:${command.source}:${command.name}"`—so filtering/reordering changes every following identity.

`ChatScreen.kt:1049-1057, 1143-1148` owns selection and correctly clamps Up/Down. `SlashCommandMenuTest.kt` covers one Down+Enter selection and manual scrolling, but not the highlight's visible position across a long keyboard sequence.

The menu is capped at 182dp with 52dp rows. Preserve this size, row visuals, Enter behavior, and composer contract: Enter inserts newlines normally and only accepts a command while command completion is active; it must never otherwise send unexpectedly.

## Intended result

- Up/Down changes the selected command immediately.
- If the next selected row is already fully visible, the list does not move.
- Crossing the bottom edge scrolls only the minimum distance needed to reveal the full selected row; the highlight stays near the bottom rather than snapping to the top.
- Moving upward follows the same rule at the top edge.
- The first/last selections remain clamped; there is no wraparound.
- Every command row has a unique stable catalog identity, never its current filtered-list index. Prefer a canonical ID; otherwise derive `(source, name, duplicateOrdinal)` once from the unfiltered ordered catalog and carry it through filtering.
- Filtering resets selection through the existing `LaunchedEffect(value, commandsValue)` behavior and the first matching command is visible.
- Touch scrolling and tapping still work. Reduced motion uses immediate minimal movement; no bounce or flourish is added.

## Commands

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.SlashCommandMenuTest
```

Use a deterministic list of at least 12 commands and record a bounded emulator sequence while pressing Down through row 8 and Up through row 1:

```bash
# Run this bounded recorder in a second terminal while sending key events.
timeout 30 adb -s emulator-5554 shell screenrecord --time-limit 15 /sdcard/slash-navigation.mp4
# After the recorder exits:
timeout 30 adb -s emulator-5554 pull /sdcard/slash-navigation.mp4 /tmp/slash-navigation.mp4
```

All device work targets only `emulator-5554`; bound every standalone `adb` command.

## Scope

**In scope**:
- `android/app/src/main/java/dev/cockpit/app/ui/screens/SlashCommandMenu.kt`
- `android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt` only if selection visibility needs a callback/seam from the composer
- `android/app/src/androidTest/java/dev/cockpit/app/ui/SlashCommandMenuTest.kt`

**Out of scope**:
- Slash-command search/ranking, loading, retry, and source labels
- Menu height, row visual design, or placement above the composer
- Command execution semantics and argument insertion
- Composer send/newline keyboard contract
- Model picker, Command Palette, and Chat transcript scrolling

## Steps

### Step 1: Give rows stable command identity

Replace index-prefixed filtered-list keys with a collision-safe identity. First inspect the command catalog/domain model for a canonical immutable ID and use it if present. If none exists, assign a `duplicateOrdinal` within each `(source, name)` group from the unfiltered ordered catalog, store that identity with the command before filtering, and use the full tuple as the row key. Never derive the discriminator from the filtered result index. Add a uniqueness assertion/test for catalogs containing duplicate names both across and within a source.

**Verify**: filter a long list so indices shift. Surviving rows retain their identity, the selected highlight attaches to the correct command, and duplicate built-in/skill/prompt names produce no duplicate-key failure. If neither a canonical ID nor a stable unfiltered catalog order exists, STOP rather than guessing.

### Step 2: Scroll only when selection crosses an edge

Replace unconditional top-pinning with a keep-visible routine based on `LazyListState.layoutInfo.visibleItemsInfo` and viewport bounds. If selected item bounds are fully inside the viewport, do nothing. If clipped/below/above, scroll by only the missing pixel delta or use an equivalent bring-into-view primitive that does not relocate an already visible row.

Avoid fixed delays, assumptions about exactly three visible rows, or an offset hard-coded from 182dp; font scale and density must use measured bounds.

**Verify visually**: Down through initially visible rows leaves the menu fixed. The first offscreen selection advances content by approximately one row and keeps the highlight at the lower edge. Up mirrors this behavior. No selection snaps from bottom to top.

### Step 3: Add keyboard and filter-transition coverage

Extend the existing test fixture to at least 12 rows. Assert that an already-visible selection produces no first-visible-item change, edge crossing moves only enough to reveal selection, Up works symmetrically, first/last clamp, Enter accepts the highlighted command, filtering resets to and displays the first match, and touch selection remains correct.

Expose a test-only list-state seam only if semantics bounds cannot make these assertions reliable; do not leak scroll state into production ViewModels.

**Verify**: focused tests pass repeatedly, then all Android gates pass. Inspect the recording at normal speed and frame-by-frame.

## Done criteria

- [ ] Already-visible keyboard selection causes no list movement.
- [ ] Edge crossing reveals the row with minimum measured movement in both directions.
- [ ] Stable keys are unique for duplicate names and contain no current filtered-list index.
- [ ] Clamp, Enter, filtering, and touch behavior remain correct.
- [ ] The recording shows a continuously trackable highlight with no top-edge snapping.
- [ ] Full Android gates pass; only in-scope files changed.

## STOP conditions

- Commands expose neither a canonical immutable ID nor a stable unfiltered catalog order from which to assign a duplicate discriminator.
- Keeping rows visible requires changing command selection/execution semantics.
- Compose visible-item bounds are unavailable or inconsistent for the 52dp rows; report measurements before choosing another primitive.
- The live composer no longer matches the keyboard contract described above.

## Maintenance notes

This menu's policy is “minimal reveal,” unlike search-result lists that reset to top. Future variable-height command rows must retain measured-bound logic and corresponding accessibility-font-scale tests.