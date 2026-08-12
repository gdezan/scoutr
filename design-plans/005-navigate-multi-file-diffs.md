# Plan 005: Navigate multi-file diffs by file

> **Executor instructions**: Follow the specified incremental design; do not replace the raw diff renderer. Inspect each visual gate and update `design-plans/README.md` when done.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/cockpit/app/ui/screens/ReviewScreen.kt android/app/src/androidTest/java/dev/cockpit/app/ui/ReviewScreenTest.kt android/app/src/test/java/dev/cockpit/app/ui/screens/`

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: navigation, direction, mobile
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

Review reports a file count but renders all files as one horizontally and vertically scrolling text stream. On a phone, finding the next file requires scanning diff headers amid long lines, so reviewing anything beyond a tiny change becomes disorienting.

## Current state

Review → choose repository → open a commit/working tree. The audited two-file screen showed “Diff <hash> / 2 files” followed by one continuous raw diff.

`ReviewScreen.kt:462-494`:

```kotlin
Text("${diffData?.stat?.size ?: 0} files", ...)
...
Column(
    Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .horizontalScroll(rememberScrollState()),
) {
    diff.split("\n").forEach { line -> DiffLine(line) }
}
```

The response already exposes `diffData.stat` with file paths/additions/deletions; the raw diff contains `diff --git` boundaries. Preserve `DiffLine` and `DiffPalette` colors. Preserve the 64KiB truncation warning.

## Intended result

```text
← Diff 0e67682b             2 files
[ 1 / 2  docs/report.md  +4 −1   ▾ ]
──────────────────────────────────
raw lines for selected file only

[‹ Previous]                    [Next ›]
```

- Single-file diff: compact file header, no redundant previous/next controls.
- Multi-file diff: current index, path, additions/deletions, and previous/next controls. Tapping the header opens an accessible file list for direct selection.
- Each file gets independent vertical/horizontal scroll reset on selection; long code lines remain pannable and unwrapped.
- If raw parsing produces a chunk absent from stats, show it using its parsed path; never drop content that exists in the returned raw diff.
- If stats name a file whose chunk is absent because the response was truncated, list it as “Content unavailable — diff truncated” and do not fabricate an empty diff.
- If the response is truncated, keep the existing truncation note visible in a fixed footer and do not imply all files are available.
- Back first closes file selection, then returns to overview.

## Commands

This plan is self-contained; target only the emulator and bound all commands:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.ReviewScreenTest
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
```

Use `ReviewScreenTest`'s fake API seam to drive the parent Review mode selection with deterministic two-file and single-file `RepoDiffResponse` values, plus rename/binary, malformed, globally truncated, loading, failed, and empty responses. The truncated fixture must include at least one stat-only file whose raw chunk falls beyond the 64KiB response. Add a test-only `captureToImage()` helper that saves under `targetContext.getExternalFilesDir(null)` and prints each path. At minimum pull and inspect:

```bash
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/diff-file-1.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/diff-file-picker.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/diff-file-2.png /tmp/
timeout 30 adb -s emulator-5554 pull /sdcard/Android/data/dev.cockpit.app/files/diff-truncated.png /tmp/
```

Save and inspect the remaining edge-state PNGs with matching names. Use the exact printed path if it differs.
## Scope

**In scope**:
- `android/app/src/main/java/dev/cockpit/app/ui/screens/ReviewScreen.kt`
- `android/app/src/androidTest/java/dev/cockpit/app/ui/ReviewScreenTest.kt`
- A small pure parser/test file under `android/app/src/main/java/dev/cockpit/app/ui/screens/` and matching unit test, if separation makes parsing clearer

**Out of scope**:
- Bridge diff API and 64KiB limit
- Syntax highlighting, inline commenting, staging, or editing
- Review overview and repository picker
- Large-screen layout (handled by Plan 006)

## Steps

### Step 1: Parse complete file chunks without data loss

Implement a small pure function that splits on `diff --git ` boundaries, retaining preamble and every returned line. Extract display path from `+++ b/` or boundary, then join stats by exact path. Define behavior for rename, binary, deletion (`/dev/null`), malformed input, raw chunks absent from stats, and stat entries absent from a globally truncated raw response. Unit-test all cases: rejoining parsed raw chunks preserves all returned input apart from documented separators, while stat-only truncated entries produce an explicit unavailable state.

**Verify**: unit tests pass. Render a two-file fixture and confirm both files and stats appear.

### Step 2: Add the file navigator

Keep selected index as saveable UI state in `DiffMode`, clamped when data changes. Add a compact header/menu below the existing top row. Use `onSurface`, `onSurfaceVariant`, `DiffPalette.Added`, and `DiffPalette.Deleted`; do not use AI primary blue for additions. Give controls 48dp targets and names including path/index.

**Verify visually**: phone screenshot shows current path and 1/2 without obscuring code. Open the selector and screenshot both file rows. Select file 2; body and index change and scroll starts at its top.

### Step 3: Preserve renderer and edge states

Render only the selected available chunk through `DiffLine`; render the explicit unavailable state for a stat-only truncated file. Preserve horizontal pan, no-change, and the bridge's existing 64KiB truncation semantics. Keep `ReviewScreen.kt`'s parent loading/failure branches intact and add focused assertions through the parent Review screen rather than trying to construct `DiffMode` for states it cannot own. The current `ReviewScreen.kt:485-498` places `TruncatedNote` after a `fillMaxSize()` scroll body, which can leave the warning with no measurable height. Move it to a fixed footer outside a weighted (not `fillMaxSize`) diff body so it remains visible for every selected file in a truncated response. Hide navigation for no-change; disable unavailable previous/next controls.

**Verify visually**: screenshot single-file, multi-file, long-line, binary/rename fixture, truncated available file, truncated unavailable file, loading, failure, and empty states. No line wraps; every returned raw line remains available; unavailable upstream content is named rather than silently omitted.

### Step 4: Add interaction coverage

Extend `ReviewScreenTest.kt` for direct selection, next/previous boundaries, index/path semantics, scroll reset, the fixed truncation footer, explicit stat-only unavailable content, and parent-level loading/failure responses.

**Verify**: single class and full Android gates pass.

## Done criteria

- [ ] Multi-file diffs support direct file selection and previous/next navigation.
- [ ] Path, index, and stats are legible on phone.
- [ ] Long lines remain horizontally pannable.
- [ ] Rename, binary, deletion, malformed, and single-file inputs preserve every raw line returned by the bridge.
- [ ] A globally truncated response marks stat-only files as unavailable instead of showing an empty or complete-looking diff.
- [ ] A truncated diff persistently announces `diff truncated to 64 KiB` in a fixed footer with nonzero height.
- [ ] Controls have accessible names and 48dp targets.
- [ ] Full Android gates pass; only in-scope files changed.

## STOP conditions

- The bridge response lacks enough stable information to map complete file chunks without dropping data.
- Parsing requires changing the bridge contract.
- Current raw diff/truncation semantics differ materially from this plan.
- A live multi-file fixture cannot be rendered.

## Maintenance notes

Keep parsing pure and independently tested. Future inline comments or staging should build on the file model rather than coupling actions to rendered line text.