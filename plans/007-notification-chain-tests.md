# Plan 007: One HTTP stack and real tests for the notification chain

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- android/app/src/main/java/dev/cockpit/app/net/NtfyClient.kt android/app/src/main/java/dev/cockpit/app/service android/app/src/main/java/dev/cockpit/app/CockpitApp.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (services/receivers can cold-start from a notification; DI must tolerate that)
- **Depends on**: 005 (both touch `CockpitMonitorService`; land 005 first)
- **Category**: tests + tech-debt
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

Alerting is the product's premise — "π needs you" reaching the phone — and
the entire delivery chain has zero tests: `NtfyClient`'s NDJSON poll loop,
the monitor service's cursor persistence, and the inline-reply receiver whose
`bridge.steer(paneId, text)` failure is swallowed by `catch (_: Exception)`.
A silent regression here means missed alerts or a reply typed into the wrong
pane, and nothing would notice. The chain is untestable today because the
service and receiver each construct their own `OkHttpClient` + clients inline
— three independent HTTP stacks whose timeouts already diverge from the app
container's. One shared container access plus injectable seams makes the
whole chain unit-testable, and the tests then pin the behavior. A rider bug:
`NtfyClient.messages` assigns `since = message.id` inside the response loop,
but the request URL was already built from the initial value and the flow
closes at the end of the body — the assignment is dead code implying an
in-flow cursor that does not exist (the real cursor lives in
`MonitoringStore.ntfyCursor`).

## Current state

- `android/.../CockpitApp.kt` — the container owns the shared stack:

```kotlin
class CockpitApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}

class AppContainer(application: Application) {
    val connectionStore = ConnectionStore(appContext)
    // ...
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    val bridge = BridgeClient(okHttp = okHttp, connectionStore = connectionStore)
    val ntfy = NtfyClient(okHttp)
```

- `android/.../service/CockpitMonitorService.kt:56-63` — second stack, built
  inline in `onStartCommand`:

```kotlin
            pollJob = scope.launch {
                val client = NtfyClient(
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
                )
                var lastId = store.ntfyCursor
                while (isActive) {
                    try {
                        client.messages(saved.ntfyUrl, saved.ntfyTopic, initialSince = lastId)
                            .collect { message ->
                                store.ntfyCursor = message.id
                                if (message.paneId != null) showEventNotification(message)
                            }
                    } catch (_: Exception) { /* retry next loop */ }
                    delay(30_000)
                }
            }
```

  Note a live bug hiding in plain sight: `lastId` is read once before the
  loop and **never updated** — after the first iteration, every poll re-uses
  the original cursor value instead of `store.ntfyCursor` (which the collect
  updates). Each 30s cycle therefore re-fetches (and re-notifies,
  modulo `notify()` id-dedup) everything since app start. Fix while you're
  here (read `store.ntfyCursor` at the top of each iteration).

- `android/.../service/NotificationReplyReceiver.kt:35-50` — third stack:

```kotlin
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = ConnectionStore(context)
                val bridge = BridgeClient(
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
                    connection,
                )
                bridge.steer(paneId, text)
            } catch (_: Exception) {
                // Reply is best-effort from the notification shade.
            } finally {
                result.finish()
            }
```

- `android/.../net/NtfyClient.kt` — the NDJSON poll (`messages(baseUrl,
  topic, initialSince)`), one-shot `latestId`, and the dead write:

```kotlin
        callbackFlow {
            var since = initialSince
            val url = "${baseUrl.trimEnd('/')}/$topic/json?poll=1" +
                (if (since != null) "&since=$since" else "")
            // ... enqueue; in onResponse:
                            if (message.event == "message") {
                                trySend(message)
                                since = message.id   // <-- dead: never read again
                            }
```

- Constraints (AGENTS.md gotchas): `BridgeClient` is `final` — the receiver
  test cannot mock it; inject a **factory/lambda seam** instead (see step 3).
  MockWebServer's `url()` must not run on the main thread. Robolectric shares
  SharedPreferences across tests — save/clear explicitly.
- Existing test to model after: `android/app/src/test/java/dev/cockpit/app/state/MonitoringStoreTest.kt`
  (Robolectric store test) and `BridgeClientUploadTest.kt` (MockWebServer
  against the real BridgeClient).
- The pending architecture plan `docs/architecture/04-android-cockpit-api-seam.md`
  will later introduce a `CockpitApi` interface for ViewModels. This plan
  must NOT preempt it with a different interface — use the container +
  factory seams only, which plan 04 can absorb.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Unit tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL |
| Emulator tests | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest` | BUILD SUCCESSFUL |
| Build | `cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug` | BUILD SUCCESSFUL |

## Scope

**In scope**:
- `android/.../CockpitApp.kt` (expose container access for non-Activity components)
- `android/.../service/CockpitMonitorService.kt`
- `android/.../service/NotificationReplyReceiver.kt`
- `android/.../net/NtfyClient.kt` (dead-write fix only)
- `android/app/src/test/java/dev/cockpit/app/net/NtfyClientTest.kt` (create)
- `android/app/src/test/java/dev/cockpit/app/service/` (create tests)

**Out of scope**:
- Any ViewModel or `BridgeClient` interface change — that is architecture
  plan 04's territory.
- Notification channel setup, deep-link validation (plan 005 landed it), and
  the 30s poll cadence.
- ntfy server behavior.

## Git workflow

- Work directly on `main`. Conventional commits, e.g.
  `test(android): pin the ntfy delivery chain; share one HTTP stack`.

## Steps

### Step 1: Container access for services and receivers

In `CockpitApp.kt`, add a companion helper:

```kotlin
    companion object {
        /** Container access for services/receivers; safe on cold start because
         *  Application.onCreate always runs before any component. */
        fun container(context: Context): AppContainer =
            (context.applicationContext as CockpitApp).container
    }
```

**Verify**: `assembleDebug` → BUILD SUCCESSFUL.

### Step 2: Monitor service uses the shared stack and a live cursor

In `CockpitMonitorService`:
- Replace the inline `NtfyClient(OkHttpClient...)` with a
  `var ntfyClientFactory: (Context) -> NtfyClient` seam — default
  `{ CockpitApp.container(it).ntfy }` — as an internal settable property or
  constructor-adjacent hook (Android services are framework-constructed, so a
  settable `internal var` + companion default is the workable seam; keep it
  minimal).
- Fix the stale cursor: inside `while (isActive)`, read
  `val lastId = store.ntfyCursor` at the top of **each** iteration and pass
  that.

**Verify**: `testDebugUnitTest --rerun-tasks` → green; `assembleDebug` green.

### Step 3: Reply receiver becomes injectable and stops losing failures silently

In `NotificationReplyReceiver`:
- Pull `bridge` from `CockpitApp.container(context)` by default, behind an
  `internal var bridgeProvider: (Context) -> BridgeClient` companion seam a
  test can override.
- Keep best-effort semantics but stop hiding the failure entirely: on
  exception, log (`android.util.Log.w`) — reply failure should at least be
  visible in logcat. Do not add UI.

**Verify**: `testDebugUnitTest --rerun-tasks` → green.

### Step 4: Remove the dead cursor write in `NtfyClient`

Delete `since = message.id` in `messages` (and the now-unneeded `var since =
initialSince` → make it a `val`). The flow's contract ("callers loop to pick
up future messages", per its own comment) is that the **caller** owns the
cursor — which step 2 made true.

**Verify**: `testDebugUnitTest --rerun-tasks` → green.

## Test plan

All Robolectric/JVM, MockWebServer for HTTP (build clients off the main
thread — follow `BridgeClientUploadTest.kt`):

- `NtfyClientTest`:
  1. NDJSON body with two `message` events + one keepalive line + one
     malformed line → exactly two emissions, in order.
  2. `initialSince` present → request path contains `since=<id>`.
  3. non-2xx response → flow closes with `IOException`.
  4. `latestId` returns the last id across mixed lines; null on empty body.
- `NotificationReplyReceiverTest`:
  5. With `bridgeProvider` overridden to a BridgeClient pointed at
     MockWebServer (real final class, fake server — the repo's standard
     workaround) and a saved connection in a fresh `ConnectionStore`:
     a reply intent results in one POST to the steer endpoint carrying the
     pane id and trimmed text (assert on the recorded request).
  6. Server returns 500 → `result.finish()` still called (no crash), warning
     logged.
- `CockpitMonitorService` cursor logic: extract the per-iteration cursor read
  into a small pure/testable function only if the service harness proves
  heavy; otherwise cover via a `MonitoringStore`-level test asserting the
  collect updates `ntfyCursor` (model after `MonitoringStoreTest.kt`) and
  rely on review for the loop read. Do not build a full foreground-service
  test harness.

**Verification**: `testDebugUnitTest --rerun-tasks` → all pass, ≥6 new tests;
`pixel2api36DebugAndroidTest` still green; `assembleDebug` green.

## Done criteria

- [ ] All three gradle tasks exit 0
- [ ] `grep -rn "OkHttpClient.Builder" android/app/src/main/java/dev/cockpit/app/service/` → no matches (both components use the container)
- [ ] `grep -n "since = message.id" android/app/src/main/java/dev/cockpit/app/net/NtfyClient.kt` → no match
- [ ] `grep -n "store.ntfyCursor" android/app/src/main/java/dev/cockpit/app/service/CockpitMonitorService.kt` shows a read inside the poll loop
- [ ] New test files exist: `NtfyClientTest.kt`, `NotificationReplyReceiverTest.kt`
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- A notification-shade cold start path exists where `applicationContext` is
  not a `CockpitApp` (e.g. a different process declared in the manifest —
  check `android:process` attributes in `AndroidManifest.xml` first; if the
  service runs in a separate process, the container approach needs a rethink;
  report).
- The receiver test cannot obtain a deterministic MockWebServer round-trip
  because `goAsync`/`result.finish()` timing is flaky after two attempts —
  report with the failing pattern rather than landing a flaky test.
- Fixing the stale `lastId` changes user-visible notification behavior in a
  way any existing test pins — report; the current re-fetch behavior may be
  masking a missing-dedup assumption.

## Maintenance notes

- Architecture plan 04 (CockpitApi seam) should absorb `bridgeProvider` /
  `ntfyClientFactory` into its interface when it lands — these seams are
  deliberately minimal so that migration is mechanical.
- Reviewer: scrutinize the cursor fix (step 2) — the interaction between
  `initialSince`, `store.ntfyCursor`, and ntfy's `since=` semantics decides
  whether a killed app re-notifies old messages; the test in
  `MonitoringStoreTest` style should pin the exact expectation.
- The bridge-side counterpart (ntfy publish timeout + throttle-map pruning)
  is plan 009.
