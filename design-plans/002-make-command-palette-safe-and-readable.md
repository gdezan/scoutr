# Plan 002: Make command-palette targets readable and Close safe

> **Executor instructions**: Follow this plan step by step and inspect each requested screenshot. Stop on any STOP condition. Update `design-plans/README.md` when done.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/scoutr/app/ui/screens/CommandPalette.kt android/app/src/androidTest/java/dev/scoutr/app/ui/CommandPaletteTest.kt`

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: a11y, hierarchy, feedback
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

The palette is used to act quickly on live agents. Its titles currently render nearly black on the near-black dialog, while Abort and Close remain legible; this makes the action target hard to identify. Close also executes immediately even though Scoutr's established risk model confirmation-gates pane closure. Abort is deliberately recoverable and must remain direct.

## Current state

Open the magnifying-glass action from a tab with live agents. The populated palette shows readable paths and action labels but almost invisible titles.

`CommandPalette.kt:188-203` omits an explicit title color:

```kotlin
Text(
    result.title,
    style = MaterialTheme.typography.bodyLarge,
    fontWeight = FontWeight.Medium,
    ...
)
Text(result.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, ...)
```

`CommandPalette.kt:153-155,210-212` dispatches both controls directly:

```kotlin
onAbort = { viewModel.control(..., SessionAction.Abort) },
onClose = { viewModel.control(..., SessionAction.Close) },
...
TextButton(onClick = onAbort) { Text("Abort", color = MaterialTheme.colorScheme.error) }
TextButton(onClick = onClose) { Text("Close") }
```

Use `MaterialTheme.colorScheme.onSurface` for titles and the existing `ConfirmDialog` pattern from `BoardScreen.kt:108-119`: title “Close agent?”, named target, transcript-preservation explanation, confirm label “Close”. Do not alter the global theme.

## Intended result

- Every result title is off-white `onSurface`, distinct from the muted monospace subtitle.
- Tapping a row still opens it.
- Abort still dispatches immediately, without confirmation.
- Tapping Close opens a modal naming the agent/session: “Closing “<title>” stops its live pane. The transcript is preserved and can be resumed from Sessions.” Dismiss makes no API call; confirm calls Close exactly once.
- The dialog has accessible confirm/dismiss controls and Back dismisses it.

## Commands

This plan is self-contained; target only the emulator and bound all commands:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.CommandPaletteTest
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
```

Make the populated-state tests visual fixtures using a test-only helper in `CommandPaletteTest.kt`: capture `compose.onRoot().captureToImage().asAndroidBitmap()`, save PNGs under `targetContext.getExternalFilesDir(null)`, and print their paths. The existing `viewModel()`/fake bridge seam supplies agent rows; add one fixture for the populated palette, one after tapping Close, and one busy row. Pull and inspect:

```bash
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/palette-populated.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/palette-close-confirm.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/palette-busy.png /tmp/
```

Use the exact printed path if the external-files path differs.
## Scope

**In scope**:
- `android/app/src/main/java/dev/scoutr/app/ui/screens/CommandPalette.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/CommandPaletteTest.kt`

**Out of scope**:
- `CommandPaletteViewModel` control semantics
- Abort confirmation (explicitly forbidden)
- Global color tokens, search behavior, Resume behavior

## Steps

### Step 1: Restore explicit title contrast

Set the result title's color to `MaterialTheme.colorScheme.onSurface`. Preserve typography, ellipsis, subtitle color, and AI icon blue.

**Verify visually**: populate two similarly located agents, open the palette, screenshot it. Both titles must be immediately readable on `background`; paths remain subordinate. Check enabled and busy rows.

### Step 2: Confirm only Close

Add local pending-close state in `CommandPalette`, set it from the row Close callback, and render the existing `ConfirmDialog`. Keep Abort wired directly. Include the result title in confirmation copy and clear pending state before dispatch/dismiss.

**Verify visually**: tap Close and screenshot the modal; the named target and consequence are visible. Dismiss it and confirm no control call. Reopen and confirm it; exactly one Close call occurs. Tap Abort separately and confirm no modal appears.

### Step 3: Add regression coverage

Extend `CommandPaletteTest.kt` to assert title display, Close modal copy, dismiss/no-call, confirm/one-call, and direct Abort. Avoid pixel-only color assertions if the harness cannot inspect color; retain a dark-theme screenshot test or semantics assertion plus manual screenshot gate.

**Verify**: single class and all Android gates pass.

## Done criteria

- [ ] Titles are visibly legible against the dark palette.
- [ ] Close is confirmation-gated and names the target.
- [ ] Abort remains one tap and ungated.
- [ ] Busy state cannot dispatch either action.
- [ ] Back/dismiss makes no Close call.
- [ ] Full Android gates pass; no out-of-scope files changed.

## STOP conditions

- `ConfirmDialog` cannot be reused without changing its global contract.
- Product semantics no longer distinguish recoverable Abort from pane-closing Close.
- A populated dark-theme palette cannot be rendered for screenshot verification.

## Maintenance notes

Any new irreversible palette action must use the same risk-based confirmation language. Keep titles explicit rather than relying on dialog-local content-color inheritance.