# Plan 007: Make Chat scrolling predictable under taps, appends, and drags

> **Executor instructions**: Follow this plan step by step. After each step, run the stated verification and inspect any screenshot or recording before continuing. If a STOP condition occurs, stop and report rather than improvising. When done, update this plan's row in `design-plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e67682..HEAD -- android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt android/app/src/androidTest/java/dev/cockpit/app/ui/ChatListTest.kt`
> If the scroll implementation or tests changed materially, reproduce the live behavior and reconcile this plan before editing.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: friction, feedback, mobile, a11y
- **Planned at**: commit `0e67682`, 2026-08-12

## Why this matters

A user who has scrolled up in a busy Chat depends on “Scroll to end” to return to the live turn once, cleanly. Today, tapping it near an incoming transcript update can make the list jump between positions, and dragging immediately afterward can fight the programmatic scroll. That makes the primary intervention surface feel unstable precisely while an agent is producing output.

## Current state

Reach it by opening any Chat with enough messages to scroll, dragging upward, then tapping the down-arrow button near the composer. The issue is timing-sensitive: it is most likely when a new entry or working-state row arrives during the tap.

`ChatScreen.kt:611-623, 702-705, 735-751` has three independent paths into the same helper: open-at-bottom, follow-new-content, and the button's unrestricted `scope.launch`. The helper performs up to ten `scrollToItem(lastIndex)` → delay → `scrollBy(Float.MAX_VALUE)` cycles and catches every `Exception`, including coroutine cancellation. `scrollToItem(lastIndex)` first places the tail at the viewport top, so retries can visibly yank the list before pushing it back to the true bottom.

`ChatListTest.kt` verifies one settled button tap, one settled append, and tall-last-message convergence. It does not cover a rapid second tap, an append during a button scroll, or a user drag interrupting programmatic motion.

Reuse the existing `LazyListState`, `scroll_to_end_fab` semantics, `LocalReduceMotion`, and motion vocabulary. Do not add bounce, overscroll flourish, or a looping animation.

## Intended result

- Chat opens at the true bottom and follows appends only while the user is pinned there.
- One component owns programmatic scroll-to-end work. A newer request supersedes the previous request rather than racing it.
- Cancellation ends the superseded scroll immediately; it is never swallowed and retried.
- Tapping the button causes one monotonic move toward the true bottom. The tail is not repeatedly pinned to the top of the viewport.
- Rapid repeat taps are idempotent. While a return-to-end operation is settling, the button cannot start another competing operation; it may hide or be temporarily disabled without adding a spinner.
- A finger drag always wins. If the user drags after tapping, automatic movement stops and normal follow behavior is derived from the resulting position.
- Agent status changes alone do not reposition the transcript unless they add/remove a visible tail row while the user is already following.
- With reduced motion, behavior is immediate but follows the same ownership and cancellation rules.

## Commands

All device work targets the emulator explicitly; never use a physical phone. Bound every Gradle/device command:

```bash
cd android
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew testDebugUnitTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew pixel2api36DebugAndroidTest --rerun-tasks
ANDROID_HOME=$HOME/Android/sdk timeout 300 ./gradlew assembleDebug
timeout 30 adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
timeout 30 adb -s emulator-5554 shell am force-stop dev.cockpit.app
timeout 30 adb -s emulator-5554 shell am start -n dev.cockpit.app/.MainActivity
```

Run the focused instrumentation class with:

```bash
ANDROID_SERIAL=emulator-5554 ANDROID_HOME=$HOME/Android/sdk timeout 180 ./gradlew connectedDebugAndroidTest --rerun-tasks \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.ChatListTest
```

For visual verification, use a deterministic `ChatListTest` fixture containing at least 30 short entries and one tall final entry. Record the emulator during each gesture sequence with a bounded screen recording, then pull it for inspection:

```bash
# Run this bounded recorder in a second terminal while driving the gestures.
timeout 30 adb -s emulator-5554 shell screenrecord --time-limit 20 /sdcard/chat-scroll.mp4
# After the recorder exits:
timeout 30 adb -s emulator-5554 pull /sdcard/chat-scroll.mp4 /tmp/chat-scroll.mp4
```

Start the second-terminal recorder before each gesture sequence. A timeout at the explicit limit is acceptable only if the output file exists and is playable.

## Scope

**In scope**:
- `android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt`
- `android/app/src/androidTest/java/dev/cockpit/app/ui/ChatListTest.kt`

**Out of scope**:
- Chat transcript parsing, polling cadence, or WebSocket behavior
- Current Live Output polling/scrolling and the planned Interactive Terminal lifecycle
- Composer keyboard behavior: Enter must continue to insert a newline and never send
- Working-indicator visuals or motion
- Reintroducing raw output into Chat

## Steps

### Step 1: Establish one scroll-to-end owner

Replace the three independent, retrying jobs with one cancellable ownership path scoped to `ChatList`. A new open/follow/button request must cancel and replace the prior programmatic request. Rethrow `CancellationException`; do not use a broad catch that converts cancellation into another retry.

Keep the current true-bottom guarantee for a lazily measured tall final item, but converge using current layout/frame information without repeatedly placing the last item at the viewport top. Do not rely on ten fixed 16ms sleeps or `Float.MAX_VALUE` as the user-visible motion model.

**Verify**: existing open-at-bottom and tall-tail tests pass. Add a test-visible invariant or injectable seam that proves at most one programmatic request owns the list and cancellation terminates the old request.

### Step 2: Make follow state respect user intent

Separate “the user is pinned to the end” from transient `canScrollForward` values produced during a programmatic move. A user drag away from the end disables following; reaching the true end re-enables it. Appends while following settle at the new true bottom. Appends while scrolled up do not move the viewport. Status-only recomposition must not trigger a gratuitous jump.

**Verify**: tests cover append-at-bottom, append-while-scrolled-up, and status change without transcript growth. All settle at the expected item without an extra scroll request.

### Step 3: Make the button idempotent and interruptible

Route the button through the same owner. Prevent rapid taps from spawning additional work. Preserve its accessible name and 48dp target. During settling, hide or disable it using the existing button treatment—no spinner and no new color. User-input mutation must cancel programmatic movement and leave the list where the gesture placed it.

**Verify**: add tests for two rapid taps, button tap concurrent with append, and drag interruption. They must finish without cancellation leakage, oscillation, or a stale visible button after reaching the true bottom.

### Step 4: Inspect motion on the emulator

Record these deterministic sequences at normal motion and with `LocalReduceMotion` enabled: (1) scroll up → one tap; (2) scroll up → rapid double tap; (3) scroll up → tap while appending; (4) scroll up → tap then immediately drag. Inspect the recording frame by frame.

**Verify visually**: every automatic move is monotonic toward the tail; no frame shows the tail jumping to the viewport top and back; double tap looks like one action; the immediate drag takes control; the button remains absent once the true bottom settles.

## Done criteria

- [ ] Exactly one programmatic scroll-to-end operation can own the list at a time.
- [ ] Coroutine cancellation is rethrown and user input wins.
- [ ] Open, follow, append, tall-tail, rapid-tap, concurrent-append, and drag-interruption tests pass.
- [ ] Normal- and reduced-motion recordings show no oscillation or repeated top-pin jump.
- [ ] Appends do not move a user who is reading older messages.
- [ ] The button retains its accessible name and touch target.
- [ ] Full Android gates pass; only in-scope files changed.

## STOP conditions

- The live Chat no longer uses the three scroll paths described above.
- True-bottom behavior cannot be retained without changing transcript row structure or polling.
- Compose's scroll mutation API cannot expose cancellation/user-input ownership reliably; report the failed approach and evidence instead of adding more timed retries.
- The issue cannot be exercised with a deterministic fixture or inspected in a recording.

## Maintenance notes

Keep scroll ownership local to `ChatList`; future tail rows (questions, pending messages, working state) must use the same path. Tests that only call `waitForIdle()` can hide intermediate oscillation, so retain the adversarial concurrency tests and a manual recording gate.