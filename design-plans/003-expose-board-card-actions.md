# Plan 003: Expose Board card actions without requiring a hidden swipe

> **Executor instructions**: Execute and visually verify each step. Stop rather than inventing a different interaction. Update `design-plans/README.md` when done.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/cockpit/app/ui/screens/BoardScreen.kt android/app/src/androidTest/java/dev/cockpit/app/ui/BoardScreenTest.kt`

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: navigation, a11y, friction
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

Review, Close, and Copy path are useful Board actions, but the rendered card has no visible cue that swiping reveals them. Users who do not already know the gesture—and switch-access users—can miss the capabilities entirely. Swipe should remain an expert shortcut, not the only entry point.

## Current state

On phone and tablet Board screenshots, cards show status and content but no menu. `BoardScreen.kt:277-285` builds actions solely for a concealed swipe layer:

```kotlin
val actions = buildList {
    add(BoardAction("review", "Review", Icons.Outlined.Code, ..., onReview))
    add(BoardAction("close", "Close", Icons.Outlined.Close, ..., onClose))
    add(BoardAction("copy", "Copy", Icons.Outlined.ContentCopy, ..., copyPath))
}
```

Lines 303-337 put those buttons behind an `anchoredDraggable` foreground. Existing Close confirmation is already correct at lines 108-119 and must be preserved.

## Intended result

Each card has one subtle, visible overflow icon with content description “Agent actions for <title>”. Tapping it opens a compact Material 3 menu with Review, Copy path, and Close. The same callbacks power swipe and menu, so behavior cannot diverge. Close still opens the established confirmation; Review and Copy remain immediate. The overflow does not compete with status color or become blue.

At 1.3× font scale and phone width, title/status remain readable and the 48dp menu target does not overlap them. Swipe reveal remains functional.

## Commands

This plan is self-contained; target only the emulator and bound all commands:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.BoardScreenTest
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
```

Use `BoardScreenTest`'s existing `BoardViewModel initialState` seam to render a working card without a bridge. Add a test-only `captureToImage()` PNG helper and save three fixtures: normal card, open overflow menu, and Close confirmation. Pull them from the printed `targetContext.getExternalFilesDir(null)` path, normally:

```bash
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/board-card.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/board-menu.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/board-close-confirm.png /tmp/
```

Inspect all PNGs directly. Use the exact printed path if it differs.
## Scope

**In scope**:
- `android/app/src/main/java/dev/cockpit/app/ui/screens/BoardScreen.kt`
- `android/app/src/androidTest/java/dev/cockpit/app/ui/BoardScreenTest.kt`

**Out of scope**:
- Board polling/status grouping
- Card information density or visual redesign
- Sessions-row gestures
- Close confirmation copy/semantics

## Steps

### Step 1: Add a visible overflow entry point

Reuse the existing `actions` list as the single source of labels/icons/callbacks. Add a trailing `IconButton`/Material 3 dropdown menu to the foreground card content. Use `onSurfaceVariant`, not `primary`, and a minimum 48dp touch target. Give each menu item a stable test tag.

**Verify visually**: phone Board screenshot shows a quiet overflow icon on every card without clipping title, status, or preview. Tap it: all three actions are visible and named. Repeat at tablet geometry and 1.3× font scale.

### Step 2: Preserve action safety and gesture parity

Ensure both overflow and swipe use the same callbacks. Choosing Close dismisses the menu and opens “Close agent?”; choosing Review navigates; Copy gives existing haptic/toast. Tapping outside or Back dismisses only the menu.

**Verify visually**: capture the open menu and Close confirmation. Exercise swipe afterward to prove it still reveals and settles correctly.

### Step 3: Cover discovery path in instrumentation

Add tests that open the overflow without swiping, assert all labels, and invoke each callback. Retain existing swipe tests. Assert the overflow has an accessible name and is reachable while the swipe layer is closed.

**Verify**: single class then all Android gates pass.

## Done criteria

- [ ] Every populated Board card exposes a visible overflow action.
- [ ] Menu contains Review, Copy path, Close; callbacks match swipe actions.
- [ ] Close remains confirmation-gated.
- [ ] Swipe remains available.
- [ ] Phone, tablet, and large-font screenshots show no collision.
- [ ] Android gates pass; only in-scope files changed.

## STOP conditions

- Adding the icon requires removing status or primary content at phone width.
- Swipe and menu cannot share the same action definitions.
- Close confirmation would be bypassed.
- Live visual verification is unavailable.

## Maintenance notes

Future Board actions must be added to one shared action model so menu and swipe stay in sync. Review accessibility both with touch exploration and non-gesture input.