# Plan 010: Keep Sessions anchored while rows reorder

> **Executor instructions**: Treat this as an implementation plan, not a runtime
> gate. First run the drift check, implement in small steps, and run only the
> cheap checks named by each step. After the implementation is review-clean and
> code-frozen, use `skills/scoutr-verification/SKILL.md` for one final runtime
> acceptance pass. If a STOP condition occurs, stop and report rather than
> improvising. When done, update this plan's row in `design-plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/scoutr/app/ui/screens/HistoryScreen.kt android/app/src/main/java/dev/scoutr/app/state/SessionHistoryViewModel.kt android/app/src/androidTest/java/dev/scoutr/app/ui/HistoryScreenTest.kt android/app/src/test/java/dev/scoutr/app/state/SessionHistoryViewModelTest.kt`
> If Sessions sorting or list ownership changed materially, stop and reconcile this plan before editing.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: feedback, friction, motion, mobile
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

Sessions is a live operational list, but it must remain readable while data changes. Pinning currently re-sorts the acted-on row immediately, and the eight-second catalog refresh can reorder rows by activity/update time. Without placement motion or key-based viewport preservation, the selected row can disappear and unrelated content replaces it under the user's finger.

## Current state

`HistoryScreen.kt:257-299` filters by tab, then sorts pinned first, active next, and newest `updatedAt` next. Rows use stable `session.path` keys but no `animateItem` modifier and the `LazyColumn` does not explicitly preserve a visible key through a reorder.

`SessionHistoryViewModel.kt:78-105` refreshes every eight seconds. `togglePin` at lines 108-124 changes local flags immediately, which recomputes the sort. In Pinned, unpinning intentionally removes the row from the current tab; in Active/Completed, pinning can send it to index 0.

Reuse the established Board pattern at `BoardScreen.kt:190-199`: `Modifier.animateItem` with `ScoutrMotion.itemSpec(reduceMotion)` and `itemPlacementSpec(reduceMotion)`. It is no-bounce and collapses to zero duration under `LocalReduceMotion`.

## Intended result

- Rows keep stable `session.path` identity through every refresh and local mutation.
- Pin/unpin in Active or Completed keeps the acted-on row at the same visible vertical anchor while surrounding keyed rows move to reflect the existing sort. The action does not teleport the user's viewport to index 0.
- Unpin in Pinned or archive/unarchive in a tab that excludes the new state may remove the acted-on row. Its removal is legible; the next surviving row occupies the anchor instead of the viewport jumping arbitrarily.
- Poll-driven reorders preserve the first meaningfully visible session path and offset whenever it still exists. Onscreen row moves use the app's standard no-bounce placement motion.
- Rows inserted or removed above the viewport do not change which session the user is reading.
- Under reduced motion, positions update immediately but the key-based viewport anchor still holds.
- Existing sort order, poll cadence, swipe/actions, and tab membership do not change.

## Verification evidence

During implementation, run only cheap checks that do not require an emulator and
add the targeted assertions needed by each step. The in-scope UI tests and
recording belong to the review-clean/code-frozen final acceptance pass, using
the workflow in `skills/scoutr-verification/SKILL.md` and a deterministic fixture
with at least 20 Active and 10 Completed sessions. Exercise pin/unpin, poll
reorder, insertion, and removal above the viewport there. Inspect the recording
for stable identity, anchored offsets, and the established no-bounce placement motion.

## Scope

**In scope**:
- `android/app/src/main/java/dev/scoutr/app/ui/screens/HistoryScreen.kt`
- `android/app/src/androidTest/java/dev/scoutr/app/ui/HistoryScreenTest.kt`
- `android/app/src/main/java/dev/scoutr/app/state/SessionHistoryViewModel.kt` only if a deterministic refresh seam is required
- `android/app/src/test/java/dev/scoutr/app/state/SessionHistoryViewModelTest.kt` only for that seam or mutation ordering

**Out of scope**:
- Sort order, tab definitions, polling interval, or catalog API
- Per-tab position restoration beyond reorder anchoring
- Swipe affordances, action menus, confirmation semantics, or row visual redesign
- Board animation behavior or global motion token changes
- Keeping a row visible after an action intentionally removes it from the current tab

## Steps

### Step 1: Apply the established keyed placement motion

Read `LocalReduceMotion` in the Sessions list and apply the same `animateItem` specs used by Board to each keyed `HistoryRow`. Do not introduce a custom spring, bounce, fade duration, or animation dependency. Ensure swipe offset state remains keyed to the session rather than the row's previous index.

**Final acceptance**: reorder two onscreen rows. At normal motion they move once
along the vertical axis without flashing or cross-fading unrelated content. With
reduced motion they update immediately.

### Step 2: Preserve the visible key through catalog refreshes

Capture the current list-state anchor immediately before applying or rendering a
new sorted order: retain the first meaningfully visible session path and pixel
offset, its old ordered index, and adjacent stable paths. After the keyed order
changes, request that same path at that offset if it survives. If it is removed,
use the saved neighbors/old index to retain the next surviving item, otherwise
the prior item/top.

Do not reset merely because `updatedAt` changed when the sorted order did not.

**Final acceptance**: while positioned mid-list, insert and reorder rows above the
viewport. The same named session remains at the same approximate screen
coordinate; nearby visible moves animate once.

### Step 3: Anchor local pin/archive actions

Before invoking pin/archive from a row, capture the acted-on path, visible offset, old ordered index, and adjacent stable paths. If the row remains in the current tab, preserve it at that offset while the list re-sorts. If the action removes it from the current tab, use the saved next/prior identities to anchor the nearest survivor and expose the changed state through the existing icon/action semantics—do not invent a toast or undo flow.

**Final acceptance**: in Active, pin a middle row and confirm it remains under the
user's finger while the order changes. In Pinned, unpin a middle row and confirm
one clean removal with the next row anchored. Repeat with reduce motion.

### Step 4: Add deterministic reorder coverage

Extend tests for: pinning a visible middle row; poll reorder above the viewport; insertion/removal above the viewport; acted-on row removal; no-op refresh; and reduced motion. Assert stable visible identities and offsets with a small tolerance, not only final sort order.

**Verify**: add the focused assertions and defer their execution, the recording,
and runtime acceptance to the review-clean/code-frozen final acceptance pass.

## Done criteria

- [ ] Existing pinned/active/updated sort order and eight-second polling remain unchanged.
- [ ] Pinning a surviving row preserves its visible anchor.
- [ ] Poll reorders and inserts above the viewport preserve the current session path/offset.
- [ ] Removal anchors the nearest surviving row deterministically.
- [ ] Keyed moves use existing no-bounce motion and honor reduce motion.
- [ ] Swipe state/actions stay attached to the correct session after reorder.
- [ ] Final runtime acceptance passes; only in-scope files changed.

## STOP conditions

- The current Sessions list does not expose a reusable key/offset anchor.
- A stable `session.path` is not unique in the live catalog.
- Preserving the anchor requires delaying or dropping catalog updates.
- `animateItem` breaks anchored-draggable state or associates an open action pane with the wrong session; report a minimal reproduction rather than shipping.
- A deterministic refresh fixture cannot reproduce reorder states.

## Maintenance notes

Keep data freshness and visual stability separate: continue applying every catalog refresh, but reconcile it by stable identity. Any future Sessions sort dimension must pass the same insert/reorder/remove anchor tests.