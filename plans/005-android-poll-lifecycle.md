# Plan 005: Scope Android polling to visible screens; stop swallowing cancellation; validate pushed deep links

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- android/app/src/main/java/dev/cockpit/app/state android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt android/app/src/main/java/dev/cockpit/app/ui/screens/BoardScreen.kt android/app/src/main/java/dev/cockpit/app/ui/screens/HistoryScreen.kt android/app/src/main/java/dev/cockpit/app/service/CockpitMonitorService.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (polling drives every screen; the emulator suite is the net)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

Three ViewModels start `while (isActive)` poll loops in `init`/connect and
never stop them: with a chat open, a backgrounded app issues ~50 requests a
minute indefinitely (battery + data on the phone, plus load the bridge pays
per poll). The repo already has the correct pattern —
`LiveOutputViewModel.startPolling()/stopPolling()` driven by
`LifecycleStartEffect` — and `ChatScreen.kt` even imports
`LifecycleStartEffect` without using it. Separately, every ViewModel wraps
suspending work in `catch (e: Exception)`, which also catches Kotlin's
`CancellationException`: navigating away or cancelling a debounced search
runs the error branch, marking pending chat bubbles FAILED and flashing error
banners purely because a coroutine was cancelled. Finally, the monitor
service builds notification intents from the raw ntfy `click` string without
the `parseCockpitUri` validation the Activity entry path applies — the same
payload is validated on one route and trusted on the other.

This does NOT change the polling cadence or the poll-vs-WebSocket
architecture — both are documented decisions (`docs/decisions.md`). It only
changes *when* polls run.

## Current state

- `android/app/src/main/java/dev/cockpit/app/state/ChatViewModel.kt:303-311` —
  poll starts in `init`, runs for the ViewModel's life:

```kotlin
    init {
        viewModelScope.launch { refresh() }
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2500)
                refresh()
            }
        }
    }
```

- `android/.../state/BoardViewModel.kt:82-93` — same shape at 3s
  (`startLive()`), plus `startPush()` below it; both are (re)started from the
  connect/init path.
- `android/.../state/SessionHistoryViewModel.kt:78-86` — same shape at 8s
  (`startPolling()`).
- The exemplar to copy — `android/.../state/LiveOutputViewModel.kt` exposes
  idempotent `startPolling()`/`stopPolling()`, and
  `android/.../ui/screens/LiveOutputPanel.kt:64-67` drives it:

```kotlin
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
```

- `android/.../ui/screens/ChatScreen.kt:120` imports
  `androidx.lifecycle.compose.LifecycleStartEffect` but never calls it.
- Exception handling: `grep -rn "CancellationException" android/app/src/main/java/dev/cockpit/app/state/`
  returns **nothing** today; `catch (e: Exception)` sites that follow a
  suspending call and then write error state exist in `ChatViewModel.kt`
  (`refresh` at `:416`, plus `deliver`, `answerQuestion`, `control`),
  `BoardViewModel.kt`, `SessionHistoryViewModel.kt`,
  `CommandPaletteViewModel.kt`, `ReviewViewModel.kt`, `UsageViewModel.kt`,
  `NewSessionViewModel.kt`. Example (`ChatViewModel.kt:416-418`):

```kotlin
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.message ?: "session read failed") }
        }
```

- `android/.../service/CockpitMonitorService.kt:110-123` — raw `click` from
  the ntfy payload becomes the intent URI; contrast `MainActivity`, which
  routes incoming URIs through `parseCockpitUri`:

```kotlin
        val deepLink = message.click
            ?: message.paneId?.let { cockpitChatUri(it, statusFor(message)) }
            ?: return
        val paneId = message.paneId ?: parseCockpitUri(deepLink)?.paneId
        // ...
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(deepLink)
```

- The validator that must gate it —
  `android/.../service/DeepLink.kt:12-20` (`parseCockpitUri`) returns a
  `CockpitDeepLink(paneId, status)` only for `cockpit://chat/<paneId>` URIs;
  `cockpitChatUri(paneId, status)` rebuilds a canonical URI.
- `AppContainer.showAgentNotification` (`android/.../CockpitApp.kt`) has the
  same raw `message.click?.let { data = android.net.Uri.parse(it) }` pattern —
  fix it the same way.
- Test conventions (AGENTS.md): `BridgeClient` is final — unit tests use
  MockWebServer; never call `MockWebServer.url()` on the main thread;
  Robolectric shares SharedPreferences (save/clear explicitly);
  emulator tests run ONLY on the emulator (`emulator-5554` / `cockpit` AVD),
  never the physical phone.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL |
| Emulator tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` | BUILD SUCCESSFUL (~2 min) |
| Build | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` | BUILD SUCCESSFUL |

## Scope

**In scope**:
- `android/.../state/ChatViewModel.kt`, `BoardViewModel.kt`, `SessionHistoryViewModel.kt`
- `android/.../state/` — a new small helper file for the cancellation-safe catch (see step 3)
- Other ViewModels in `state/` ONLY for the mechanical catch-block change
- `android/.../ui/screens/ChatScreen.kt`, `BoardScreen.kt`, `HistoryScreen.kt` — wiring `LifecycleStartEffect` only
- `android/.../service/CockpitMonitorService.kt`, `android/.../CockpitApp.kt` — deep-link validation only
- `android/app/src/test/` and `android/app/src/androidTest/` — extend

**Out of scope**:
- `LiveOutputViewModel`/`LiveOutputPanel` — already correct.
- Poll intervals, the ntfy polling loop cadence, or any WS transport change.
- `NotificationReplyReceiver` and service DI — plan 007 owns those.
- `NewSessionViewModel`'s non-polling logic; only its catch blocks if touched.

## Git workflow

- Work directly on `main`. Conventional commits, e.g.
  `fix(android): lifecycle-scope the polls; never treat cancellation as failure`.

## Steps

### Step 1: Add idempotent start/stop to the three polling ViewModels

Mirror `LiveOutputViewModel`'s shape. For `ChatViewModel`: keep the one-shot
`viewModelScope.launch { refresh() }` in `init` (first paint), move the loop
into `startPolling()`, add `stopPolling()` that cancels `pollJob` and nulls
it. `startPolling()` must be a no-op when already polling. Same for
`BoardViewModel.startLive` (rename or wrap as `startPolling`/`stopPolling`;
`startPush`'s ntfy job should pause with the same lifecycle — include it in
the same start/stop pair) and `SessionHistoryViewModel.startPolling` (add the
missing `stopPolling`).

Preserve the behavior that connecting/config changes restart the poll (the
existing `pollJob?.cancel()` at the top of the start functions).

**Verify**: `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` → green. Some ViewModel tests construct these classes expecting `init`-started polling — check `BoardViewModelTest.kt` (it clears the ConnectionStore before construction precisely so polling does NOT start); update tests to call `startPolling()` explicitly where they relied on `init`.

### Step 2: Drive them from the screens

In `ChatScreen.kt`, `BoardScreen.kt`, `HistoryScreen.kt`, add (near the top of
the screen composable, matching `LiveOutputPanel.kt:64-67`):

```kotlin
    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
```

`ChatScreen.kt` already has the import at `:120`; add it to the other two.

**Verify**: `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` → green (BoardScreenTest, ChatListTest, HistoryScreenTest cover these screens; if a test asserts data appears without a lifecycle owner reaching STARTED, the Compose test rule does reach STARTED — expect green, and STOP if a suite disagrees).

### Step 3: A cancellation-safe catch helper

Add `android/app/src/main/java/dev/cockpit/app/state/CancellationSafe.kt`:

```kotlin
package dev.cockpit.app.state

import kotlinx.coroutines.CancellationException

/**
 * Runs [block]; rethrows coroutine cancellation (so scopes unwind properly)
 * and hands any real failure to [onError]. Prevents navigation-away from
 * being rendered as a network error.
 */
suspend inline fun runCatchingCancellable(onError: (Exception) -> Unit, block: () -> Unit) {
    try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        onError(e)
    }
}
```

(If inline+suspend causes compiler friction with non-local returns in
callers, use the simpler pattern instead: add
`catch (c: CancellationException) { throw c }` as the first catch clause at
each site. Either form is acceptable; consistency matters more than which.)

Apply to every `catch (e: Exception)` (and `catch (_: Exception)`) that
follows suspending work inside `viewModelScope` in `state/` — find them with
`grep -n "catch (.*Exception)" android/app/src/main/java/dev/cockpit/app/state/*.kt`.
Do NOT change catches around non-suspending parsing code.

**Verify**: `testDebugUnitTest --rerun-tasks` → green.

### Step 4: Validate pushed deep links

In `CockpitMonitorService.showEventNotification` and
`AppContainer.showAgentNotification`:
- Run the incoming `message.click` through `parseCockpitUri`; when it parses,
  rebuild the URI with `cockpitChatUri(parsed.paneId, parsed.status)` and use
  that; when `click` is present but invalid, fall back to
  `message.paneId?.let { cockpitChatUri(it, statusFor(message)) }`, else skip
  the deep link (notification may still show without a content intent, or be
  dropped — match the existing `?: return` behavior in the service).
- Constrain the `paneId` used for the inline reply action to the parsed/
  validated value (never the raw payload field when a validated one exists).

**Verify**: `testDebugUnitTest --rerun-tasks` → green, plus the new unit test below.

## Test plan

- New unit test `android/app/src/test/java/dev/cockpit/app/state/PollLifecycleTest.kt`
  (Robolectric, model after `BoardViewModelTest.kt` incl. its ConnectionStore
  handling): `startPolling()` twice creates one job (no duplicate polling —
  observable via MockWebServer request count within a bounded window);
  `stopPolling()` then advancing time issues no further requests.
- New unit test for cancellation: launch `refresh()` (or `deliver`) against a
  MockWebServer with a delayed response, cancel the scope, assert the UI
  state error field stays null / message not marked FAILED. Model after
  `ChatPendingMessageTest.kt`.
- New unit test `DeepLinkValidationTest`: a `NtfyMessage` whose `click` is
  `https://evil.example/x` produces either no content intent or a
  `cockpitChatUri`-rebuilt one from `paneId`; a valid
  `cockpit://chat/p1?status=blocked` click passes through with `paneId=p1`.
  (`parseCockpitUri` uses `android.net.Uri` → Robolectric required; model
  after `MonitoringStoreTest.kt`.)
- Existing emulator suites are the regression net for the screen wiring.

**Verification**: both gradle test tasks green; `assembleDebug` green.

## Done criteria

- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` exits 0
- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` exits 0
- [ ] `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` exits 0
- [ ] `grep -rn "LifecycleStartEffect" android/app/src/main/java/dev/cockpit/app/ui/screens/ChatScreen.kt` shows a call (not just the import); same for BoardScreen.kt and HistoryScreen.kt
- [ ] `grep -rn "CancellationException" android/app/src/main/java/dev/cockpit/app/state/ | wc -l` ≥ 1 (helper or per-site rethrows present)
- [ ] `grep -n "Uri.parse(deepLink)" android/app/src/main/java/dev/cockpit/app/service/CockpitMonitorService.kt` — the parsed value now comes from a validated/rebuilt URI (read the surrounding code to confirm)
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- An emulator test fails because a screen legitimately needs data while
  STOPPED (e.g. a test drives navigation without lifecycle) — report which
  test; do not weaken the lifecycle wiring to pass it.
- `BoardViewModel`'s connect flow turns out to depend on polling running
  while the Connect screen (not Board) is visible — check `ConnectScreen.kt`
  usage before step 2 and report if so.
- The monitor service's notification flow has no unit-test seam at all and
  testing it requires the plan-007 DI refactor — write the DeepLink test
  against `parseCockpitUri`/`cockpitChatUri` + a pure helper function you
  extract, and note the deferral.
- Emulator absent: if `adb devices` shows no `emulator-5554`, boot the
  `cockpit` AVD (`$ANDROID_HOME/emulator/emulator -avd cockpit &`, wait for
  boot) — never run instrumentation on a physical phone (AGENTS.md hard rule).

## Maintenance notes

- Plan 007 refactors service/receiver construction; it touches
  `CockpitMonitorService` too — land this plan first (007 lists it as a
  dependency).
- The pending architecture plan `docs/architecture/06` (Poller/Loadable)
  would absorb `startPolling`/`stopPolling` into a shared `Poller`; this
  plan's shape is deliberately compatible with it.
- Reviewer: check that `stopPolling` does not cancel in-flight one-shot
  actions (send/answer) — only the poll loop job.
