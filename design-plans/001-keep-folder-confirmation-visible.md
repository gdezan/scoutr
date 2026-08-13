# Plan 001: Keep folder confirmation visible throughout browsing

> **Executor instructions**: Follow this plan step by step. After each step, run the visual verification and inspect the screenshot before continuing. If a STOP condition occurs, stop and report rather than improvising. When done, update this plan's row in `design-plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/scoutr/app/ui/screens/SessionPickers.kt android/app/src/androidTest/java/dev/scoutr/app/ui/NewSessionSheetTest.kt`
> If the live screen or excerpt below changed materially, stop and reconcile before editing.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: friction, mobile, a11y
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

A user starting an agent can browse into another folder but cannot confirm that folder on a phone because the directory list consumes all remaining height. This blocks a core setup path at the final, expected action.

## Current state

Reach it from Board → `+` → Browse. On a 1080×2340 phone, the full-screen picker shows the header, path, and directories, but “Use this folder” is below the viewport.

`SessionPickers.kt:163-198` currently places a `fillMaxSize()` state before the button:

```kotlin
else -> LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("folder_list"),
    ...
)
...
Button(
    onClick = onDismiss,
    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag("use_folder"),
) { Text("Use this folder") }
```

The empty state at lines 166-168 also uses `fillMaxSize()`. Reuse `MaterialTheme.colorScheme`, the existing `Button`, 48dp minimum target, and `navigationBarsPadding()` already applied to the dialog surface. Do not add a new visual system.

## Intended result

```text
┌ Choose a folder              × ┐
│ ↑ /current/path                │
├───────────────────────────────┤
│ folder rows                    │
│ (scroll independently)         │
│                                │
├───────────────────────────────┤
│ [ Use this folder ]            │  always visible
└───────────────────────────────┘
```

The loading, error, empty, and populated content region takes only available space (`weight(1f)`); the action remains fixed above the navigation bar. It is enabled under the existing conditions. At large font scale, its label remains fully visible and the directory area shrinks rather than displacing it. TalkBack reads the list before the final action.

## Commands

All device work targets the emulator explicitly; never use a physical phone. Bound every Gradle/device command:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
timeout 30 adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
timeout 30 adb -s emulator-5554 shell am force-stop dev.scoutr.app
timeout 30 adb -s emulator-5554 shell am start -n dev.scoutr.app/.MainActivity
```

For deterministic visual states, add a test-only screenshot helper in `NewSessionSheetTest.kt`: capture `compose.onRoot().captureToImage().asAndroidBitmap()`, compress it as PNG into `InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)`, and print the absolute path. Render populated, empty, loading, and error fixtures in separate tests, run the class on the running emulator, then pull and inspect each PNG:

```bash
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.NewSessionSheetTest
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/folder-picker-populated.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/folder-picker-empty.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/folder-picker-loading.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.scoutr.app/files/folder-picker-error.png /tmp/
```

If the package-specific external path differs, use the exact path printed by the test; do not guess.
## Scope

**In scope**:
- `android/app/src/main/java/dev/scoutr/app/ui/screens/SessionPickers.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/NewSessionSheetTest.kt`

**Out of scope**:
- Folder-loading and selection state in `NewSessionViewModel`
- New-session sheet layout outside the full-screen picker
- Review repository picker

## Steps

### Step 1: Reserve space for the confirmation action

Wrap every picker content state in a shared `Box(Modifier.weight(1f))`, or apply `weight(1f)` consistently so no loading/error/empty/list branch can claim the action's height. Keep the `Button` after this region and preserve its existing enablement and test tag. The directory list itself remains vertically scrollable.

**Verify visually**: install and open Board → `+` → Browse. At phone geometry, screenshot the picker: “Use this folder” is fully visible at the bottom while the directory list scrolls behind neither it nor the navigation bar. Repeat with an empty directory and a loading/error fixture if available.

### Step 2: Pin the layout contract in instrumentation

Extend `NewSessionSheetTest.kt` to render a populated folder picker in a phone-sized container and assert `use_folder` is displayed and clickable without scrolling. Add a large-font or constrained-height case if the test harness supports it without global leakage.

**Verify**: run the single instrumentation class, then the full Android gates above. All exit 0.

## Done criteria

- [ ] Populated, empty, loading, and error picker states leave the action visible.
- [ ] The directory list scrolls independently.
- [ ] The action has a 48dp minimum target and is not covered by system navigation.
- [ ] Phone screenshot shows the complete action without scrolling.
- [ ] Android unit, managed-device, and assemble gates pass.
- [ ] Only in-scope files and `design-plans/README.md` changed.

## STOP conditions

- Picker state no longer uses the excerpted layout.
- Fixing it requires changing folder selection semantics or backend APIs.
- The action cannot remain visible at standard phone size without removing an existing control.
- A live screenshot cannot be produced.

## Maintenance notes

Any future footer action in this dialog must stay outside the weighted content region. Reviewers should check constrained height, 1.3× font scale, navigation insets, and every load state.