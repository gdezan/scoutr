# Plan 010: Keep Sessions anchored while rows reorder

> **Executor instructions**: Follow this plan step by step. After each step, run the stated verification and inspect the recording before continuing. If a STOP condition occurs, stop and report rather than improvising. When done, update this plan's row in `design-plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/cockpit/app/ui/screens/HistoryScreen.kt android/app/src/main/java/dev/cockpit/app/state/SessionHistoryViewModel.kt android/app/src/androidTest/java/dev/cockpit/app/ui/HistoryScreenTest.kt android/app/src/test/java/dev/cockpit/app/state/SessionHistoryViewModelTest.kt`
> If Plan 009 is incomplete or Sessions sorting/list ownership changed, stop and reconcile before editing.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED
- **Depends on**: `design-plans/009-make-list-position-intentional.md`
- **Category**: feedback, friction, motion, mobile
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

Sessions is a live operational list, but it must remain readable while data changes. Pinning currently re-sorts the acted-on row immediately, and the eight-second catalog refresh can reorder rows by activity/update time. Without placement motion or key-based viewport preservation, the selected row can disappear and unrelated content replaces it under the user's finger.

## Current state

`HistoryScreen.kt:257-299` filters by tab, then sorts pinned first, active next, and newest `updatedAt` next. Rows use stable `session.path` keys but no `animateItem` modifier and the `LazyColumn` does not explicitly preserve a visible key through a reorder.

`SessionHistoryViewModel.kt:78-105` refreshes every eight seconds. `togglePin` at lines 108-124 changes local flags immediately, which recomputes the sort. In Pinned, unpinning intentionally removes the row from the current tab; in Active/Completed, pinning can send it to index 0.

Reuse the established Board pattern at `BoardScreen.kt:190-199`: `Modifier.animateItem` with `CockpitMotion.itemSpec(reduceMotion)` and `itemPlacementSpec(reduceMotion)`. It is no-bounce and collapses to zero duration under `LocalReduceMotion`.

## Intended result

- Rows keep stable `session.path` identity through every refresh and local mutation.
- Pin/unpin in Active or Completed keeps the acted-on row at the same visible vertical anchor while surrounding keyed rows move to reflect the existing sort. The action does not teleport the user's viewport to index 0.
- Unpin in Pinned or archive/unarchive in a tab that excludes the new state may remove the acted-on row. Its removal is legible; the next surviving row occupies the anchor instead of the viewport jumping arbitrarily.
- Poll-driven reorders preserve the first meaningfully visible session path and offset whenever it still exists. Onscreen row moves use the app's standard no-bounce placement motion.
- Rows inserted or removed above the viewport do not change which session the user is reading.
- Under reduced motion, positions update immediately but the key-based viewport anchor still holds.
- Existing sort order, poll cadence, swipe/actions, and tab membership do not change.

## Commands

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.HistoryScreenTest
```

Use an emulator-only deterministic fixture with at least 20 Active and 10 Completed sessions. Provide a controllable test refresh that reorders, inserts, and removes rows without waiting eight real seconds. Capture a bounded recording during pin and refresh sequences:

```bash
# Run this bounded recorder in a second terminal while driving the fixture.
timeout 30 adb -s emulator-5554 shell screenrecord --time-limit 20 /sdcard/session-reorder.mp4
# After the recorder exits:
timeout 30 adb -s emulator-5554 pull /sdcard/session-reorder.mp4 /tmp/session-reorder.mp4
```

Never run device commands without `-s emulator-5554`; bound each standalone `adb` call with `timeout 30`.

## Scope

**In scope**:
- `android/app/src/main/java/dev/cockpit/app/ui/screens/HistoryScreen.kt`
- `android/app/src/androidTest/java/dev/cockpit/app/ui/HistoryScreenTest.kt`
- `android/app/src/main/java/dev/cockpit/app/state/SessionHistoryViewModel.kt` only if a deterministic refresh seam is required
- `android/app/src/test/java/dev/cockpit/app/state/SessionHistoryViewModelTest.kt` only for that seam or mutation ordering

**Out of scope**:
- Sort order, tab definitions, polling interval, or catalog API
- Per-tab position restoration (Plan 009)
- Swipe affordances, action menus, confirmation semantics, or row visual redesign
- Board animation behavior or global motion token changes
- Keeping a row visible after an action intentionally removes it from the current tab

## Steps

### Step 1: Apply the established keyed placement motion

Read `LocalReduceMotion` in the Sessions list and apply the same `animateItem` specs used by Board to each keyed `HistoryRow`. Do not introduce a custom spring, bounce, fade duration, or animation dependency. Ensure swipe offset state remains keyed to the session rather than the row's previous index.

**Verify visually**: reorder two onscreen rows. At normal motion they move once along the vertical axis without flashing or cross-fading unrelated content. With reduced motion they update immediately.

### Step 2: Preserve the visible key through catalog refreshes

Build on Plan 009's list state/anchor snapshot. Immediately before applying or rendering a new sorted order, retain the first meaningfully visible session path and pixel offset, its old ordered index, and adjacent stable paths. After the keyed order changes, request that same path at that offset if it survives. If it is removed, use the saved neighbors/old index to retain the next surviving item, otherwise the prior item/top.

Do not reset merely because `updatedAt` changed when the sorted order did not.

**Verify**: while positioned mid-list, insert and reorder rows above the viewport. The same named session remains at the same approximate screen coordinate; nearby visible moves animate once.

### Step 3: Anchor local pin/archive actions

Before invoking pin/archive from a row, capture the acted-on path, visible offset, old ordered index, and adjacent stable paths. If the row remains in the current tab, preserve it at that offset while the list re-sorts. If the action removes it from the current tab, use the saved next/prior identities to anchor the nearest survivor and expose the changed state through the existing icon/action semantics—do not invent a toast or undo flow.

**Verify**: in Active, pin a middle row and confirm it remains under the user's finger while the order changes. In Pinned, unpin a middle row and confirm one clean removal with the next row anchored. Repeat with reduce motion.

### Step 4: Add deterministic reorder coverage

Extend tests for: pinning a visible middle row; poll reorder above the viewport; insertion/removal above the viewport; acted-on row removal; no-op refresh; and reduced motion. Assert stable visible identities and offsets with a small tolerance, not only final sort order.

**Verify**: focused tests pass repeatedly, then all Android gates pass. Inspect the recording at normal speed and frame-by-frame for teleportation, duplicate rows, bounce, or unrelated fades.

## Done criteria

- [ ] Existing pinned/active/updated sort order and eight-second polling remain unchanged.
- [ ] Pinning a surviving row preserves its visible anchor.
- [ ] Poll reorders and inserts above the viewport preserve the current session path/offset.
- [ ] Removal anchors the nearest surviving row deterministically.
- [ ] Keyed moves use existing no-bounce motion and honor reduce motion.
- [ ] Swipe state/actions stay attached to the correct session after reorder.
- [ ] Focused and full Android gates pass; only in-scope files changed.

## STOP conditions

- Plan 009 did not establish a reusable key/offset anchor for Sessions.
- A stable `session.path` is not unique in the live catalog.
- Preserving the anchor requires delaying or dropping catalog updates.
- `animateItem` breaks anchored-draggable state or associates an open action pane with the wrong session; report a minimal reproduction rather than shipping.
- A deterministic refresh fixture cannot reproduce reorder states.

## Maintenance notes

Keep data freshness and visual stability separate: continue applying every catalog refresh, but reconcile it by stable identity. Any future Sessions sort dimension must pass the same insert/reorder/remove anchor tests.