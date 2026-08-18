# Shared Notification Monitoring Blueprint

## Current situation

Scoutr currently has two owners for ntfy consumption:

- `BoardViewModel.startPush()` polls the ntfy topic while the Board screen is STARTED. Its cursor is in-memory (`lastId`) and initialized from `NtfyClient.latestId()`.
- `ScoutrMonitorService` polls the same topic while opt-in background monitoring is active. Its cursor is persisted through `MonitoringStore.ntfyCursor`.

Both loops retry every 30 seconds, both invoke notification handling, and they intentionally run under different lifecycles. This duplication has produced separate cursor semantics and means notification delivery behavior is split between a ViewModel and a foreground service.

`ScoutrMonitorService.pollOnce()` already has a focused test seam and persists the service cursor. `MonitoringStore` already owns durable monitoring state. `ScoutrApp.AppContainer` already provides the shared `NtfyClient` and notification rendering callback. `SettingsScreen` exposes the opt-in state and explains the Android 15 six-hour foreground-service limit.

The product contract remains important: foreground Board usage should receive timely local notifications/events without requiring the background service, while background monitoring is explicitly opt-in and time-bounded by Android.

Precedent: Scoutr centralizes transport clients in `AppContainer` and keeps lifecycle ownership at the caller. This plan centralizes **cursor/delivery semantics**, not lifecycle itself.

## Objective and why

Create one reusable ntfy monitoring engine with one cursor model and one message-processing contract, then let Board foreground monitoring and `ScoutrMonitorService` host that engine under their respective lifecycles.

Done means there is no duplicated ntfy polling/cursor implementation in `BoardViewModel` and `ScoutrMonitorService`; messages are consumed through one tested component, cursor advancement is deterministic, overlapping foreground/background owners do not generate duplicate event notifications, and Android 15 timeout behavior remains explicit and unchanged.

## Scope

Included:

- shared Android ntfy monitor/poller component;
- one durable cursor namespace per active paired bridge/topic;
- Board and foreground-service adoption;
- deduplication semantics when Board and background service overlap briefly;
- monitoring status exposed cleanly enough for Settings/UI to show enabled vs expired/stopped state without inventing a second lifecycle owner;
- tests for cursor advancement, retry, overlap, cancellation, re-pairing/topic changes, and Android timeout state.

Non-goals:

- do not replace ntfy with FCM or a new push service;
- do not create a permanently running process/service while the app is foregrounded;
- do not work around Android 15 foreground-service limits;
- do not make background monitoring opt-out/default-on;
- do not change bridge publishing semantics unless a verified message identity deficiency blocks deterministic dedupe;
- no WorkManager scheduler unless repository evidence shows it is required for the existing product contract.

## Global constraints

- Background monitoring remains explicit opt-in and `START_NOT_STICKY`.
- Android 15 timeout still stops monitoring and clears the active opt-in; do not auto-restart behind the user's back.
- Foreground Board lifecycle still starts/stops with the Board screen rather than becoming an application-global permanent loop.
- The shared engine must not own Android `Service` or Compose/ViewModel lifecycle directly; lifecycle owners start/cancel it.
- Cursor persistence must be scoped to the current connection/topic; after `.plans/p2-host-workspace-namespace.md`, prefer `hostId` + ntfy topic rather than host URL/token.
- Notification rendering and inline reply behavior stay in existing service/app notification seams; the monitor delivers messages, it does not become a UI layer.
- Final emulator/integration/E2E verification is terminal after review-clean/code-freeze.

## Resolved decisions

### One durable cursor

Use one persisted cursor for a given bridge/topic, shared by foreground Board monitoring and the background service. Do not keep a separate Board-only cursor.

The cursor store should be connection-qualified. After the host namespace blueprint, the logical key is:

```kotlin
data class NtfySubscriptionKey(
    val hostId: String,
    val topic: String,
)
```

If that blueprint is not yet implemented, this plan must wait rather than cementing URL/token-based identity.

### Shared monitor contract

Introduce a small lifecycle-neutral component, e.g. `NtfyMonitor`, that performs one subscription loop:

```kotlin
class NtfyMonitor(
    private val client: NtfyClient,
    private val cursorStore: NtfyCursorStore,
) {
    suspend fun run(
        subscription: NtfySubscription,
        onMessage: suspend (NtfyMessage) -> Unit,
    ): Nothing
}
```

The exact signature is local discretion. Required behavior:

1. read the persisted cursor before each subscription attempt;
2. call `client.messages(..., initialSince = cursor)`;
3. for each message, atomically decide whether it is new for this subscription;
4. deliver it at most once to the process-level consumer path;
5. advance/persist the cursor in an order that does not redeliver already handled messages after a retry;
6. on transient errors, delay with the existing bounded 30-second cadence and retry;
7. propagate coroutine cancellation immediately.

Because Board and Service can overlap during app/background transitions, cursor read/write alone is insufficient if both collectors subscribe from the same old cursor simultaneously. Add an in-process subscription coordinator keyed by subscription identity so at most one active collector owns a topic in the process. Ownership may transfer when one lifecycle stops and another starts; do not run two network collectors for the same subscription.

### Delivery ownership

Use the same message-to-notification callback path regardless of host lifecycle. `ScoutrApp`/existing notification helpers remain the place that turns an ntfy message into a local notification. `ScoutrMonitorService` keeps service-specific foreground-notification responsibilities and inline-reply notification construction if that logic cannot be reused cleanly without moving Android service context concerns.

The shared engine guarantees transport-level at-most-once processing within the persisted cursor semantics. Notification code may use `message.id` as the stable notification id as it does today.

### Monitoring state

`MonitoringStore.enabled` currently means the user opted into the foreground service and is cleared on service destroy/timeout. Preserve that meaning. Add explicit last-stop/timeout status only if required to render a useful Settings state; do not overload `enabled` to mean "Board foreground polling is active".

A reasonable minimal shape is:

```kotlin
enum class MonitoringStopReason { User, AndroidTimeout, MissingConnection, ServiceStopped }
```

Persist only what is needed for user-visible status. A normal app foreground Board monitor is not background-monitoring enablement.

### Rejected alternatives

- Application-global singleton coroutine that always polls: rejected because it erases the product distinction between foreground observation and opt-in background monitoring.
- Two persisted cursors, one per owner: rejected because overlapping owners can still duplicate notifications and semantics drift again.
- Rely only on `NotificationManager.notify(message.id.hashCode())` replacement: rejected because visual replacement is not the same as avoiding duplicate processing/replies/haptics/logical delivery.
- Move all notification code into the monitor engine: rejected because Android service/channel/PendingIntent concerns are presentation/platform responsibilities, not transport polling.

## Approach

Extract ntfy cursor persistence from `MonitoringStore` into a subscription-qualified cursor seam (or extend the store cleanly), add a shared `NtfyMonitor` plus an in-process coordinator, and inject that monitor from `ScoutrApp.AppContainer` into both `BoardViewModel` and `ScoutrMonitorService`.

Board continues to start the monitor while STARTED. Background service continues to start it while the foreground service is alive. If service and Board overlap, the coordinator ensures one collector; when the current owner cancels, the waiting/current lifecycle owner may take over from the persisted cursor without replay.

## Contracts and interfaces

### Subscription identity

```kotlin
data class NtfySubscription(
    val key: NtfySubscriptionKey,
    val url: String,
    val topic: String,
)
```

The URL is transport configuration; identity uses `hostId + topic`.

### Cursor store

```kotlin
interface NtfyCursorStore {
    fun get(key: NtfySubscriptionKey): String?
    fun set(key: NtfySubscriptionKey, cursor: String)
    fun clear(key: NtfySubscriptionKey)
}
```

Writes must not expose token material.

### Monitor coordinator

The implementation must ensure only one active `NtfyClient.messages()` collection per subscription key inside the app process. It may use a `Mutex`, keyed job registry, shared flow, or another small coroutine primitive, but must have deterministic cancellation/handoff tests.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | host-qualified connection + current cursor storage | `NtfySubscriptionKey` / cursor store |
| 2 | `NtfyClient` + cursor store | shared `NtfyMonitor` + coordinator |
| 3 | monitor | Board lifecycle adoption |
| 4 | monitor | foreground-service adoption |
| 5 | service lifecycle outcomes | explicit monitoring status in Settings/store |

## Changes

- [ ] **1 — Introduce subscription-qualified cursor storage**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/MonitoringStore.kt` → `ntfyCursor`
  - Anchor: result of `.plans/p2-host-workspace-namespace.md` → saved `hostId`
  - Replace the one global cursor with a cursor keyed by current `hostId + topic`.
  - Migrate the existing single cursor only when it belongs to the currently authenticated saved subscription; otherwise leave it unassigned rather than guessing.
  - Keep `enabled` and any service stop reason separate from cursor data.
  - Proof: store tests cover two subscriptions, migration, clearing/re-pairing, and no cross-host/topic collision.

- [ ] **2 — Extract the shared ntfy monitoring engine and coordinator**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/service/ScoutrMonitorService.kt` → `pollOnce`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt` → `startPush`
  - Create a lifecycle-neutral monitor that owns subscribe/retry/cursor advancement and a keyed in-process coordinator that prevents duplicate simultaneous collectors.
  - Reuse the existing 30-second retry cadence unless current tests/repository policy establishes a different constant.
  - Cancellation must stop collection immediately and never be converted into retry.
  - Proof: focused coroutine tests cover normal delivery, persisted resume, transient error retry, cancellation, and two simultaneous owners resulting in one underlying subscription and one delivery per message.

- [ ] **3 — Make Board use the shared monitor**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt` → `startPush`, `startPolling`, `stopPolling`
  - Remove direct `NtfyClient.latestId/messages` loop and in-memory `lastId` ownership.
  - Start/cancel a monitor lease/job with Board STARTED/STOPPED lifecycle exactly where the current push job is controlled.
  - Keep Board data polling (`/api/agents` every 3 seconds) unchanged.
  - Keep notification callback injection testable and do not let ntfy failures alter Board connection state.
  - Proof: Board ViewModel tests show lifecycle starts/stops monitor ownership without duplicate polling and ntfy failure remains isolated from board state.

- [ ] **4 — Make `ScoutrMonitorService` use the shared monitor**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/service/ScoutrMonitorService.kt` → `onStartCommand`, `pollOnce`, `onTimeout`, `onDestroy`
  - Remove the service-local polling loop/cursor implementation and acquire the same subscription monitor under the service coroutine scope.
  - Preserve foreground notification, agent event notification, inline Reply, `START_NOT_STICKY`, missing-config stop, and Android timeout behavior.
  - Service shutdown cancels only its lease/ownership; if Board is concurrently STARTED, monitoring may continue under Board without replay.
  - Proof: service tests cover start, message notification, overlap/handoff, timeout, destroy, and missing connection.

- [ ] **5 — Surface monitoring health without inventing another owner**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/SettingsScreen.kt` → `MonitoringSection`
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/MonitoringStore.kt`
  - Keep the toggle tied to opt-in background service state.
  - If the service was ended by Android timeout, show that state clearly and offer the existing/manual re-enable action; do not silently restart.
  - Do not expose transient Board foreground-monitor ownership as a settings toggle state.
  - Follow `ui/theme/DESIGN.md`: status/warning semantics, no new neon treatment or decorative motion.
  - Proof: JVM/UI state tests cover enabled, disabled, and Android-timeout presentation; final visual/runtime acceptance is deferred to the final verification boundary.

## Failure handling

- ntfy unreachable: retry after the existing bounded delay; no Board disconnect state.
- persisted cursor malformed/unknown: client starts from a safe supported baseline; do not crash monitoring.
- duplicate lifecycle owners start concurrently: coordinator permits one underlying collector only.
- owner cancellation while another owner is waiting: handoff resumes from persisted cursor and does not replay already delivered messages.
- connection/topic changes: old subscription lease is cancelled and new key uses its own cursor.
- Android 15 timeout: foreground service stops, clears background enablement, records timeout state if implemented, and does not auto-restart.
- missing saved pairing/ntfy configuration: service stops and Board simply has no push monitor; no retry loop with invalid config.
- notification callback throws: decide explicitly whether cursor advances before or after callback. Prefer **deliver successfully, then advance** if callback failure is meaningful/retriable; if notification rendering is intentionally best-effort and must never replay, catch rendering errors in the callback and let the monitor advance. Do not leave this ordering implicit.

## Validation

1. Focused `NtfyMonitor`/cursor/coordinator coroutine tests.
2. `BoardViewModel` lifecycle tests.
3. `ScoutrMonitorServiceTest` and `MonitoringStoreTest`.
4. `make android-test`.
5. Independent review via `skills/scoutr-review/SKILL.md`, with special attention to cancellation, two-owner races, cursor ordering, and Android timeout semantics.
6. After code freeze, final runtime acceptance via `skills/scoutr-verification/SKILL.md`: foreground Board event, transition app to background with monitoring enabled, receive another event without duplicate, foreground again, then verify timeout/disable behavior through the narrowest reproducible runtime path available.

## Local discretion

- Exact class names/file split for monitor, cursor store, and coordinator.
- Whether one shared flow or lease-based keyed jobs implement the coordinator, provided only one network collection exists per key and lifecycle ownership remains explicit.
- Exact timeout-status copy in Settings, following the design system.
- Whether cursor store remains in `MonitoringStore` or becomes a separate SharedPreferences-backed class.

## Escalation triggers

- ntfy's `since` semantics cannot guarantee the expected resume behavior with message ids as currently used.
- The bridge can emit two semantically distinct events with the same ntfy message id, making id-based cursor/dedupe invalid.
- A single process-level coordinator cannot cover observed duplicates because notifications come from multiple Android processes; verify manifest/process configuration before redesigning.
- Product scope changes to always-on background monitoring or exact real-time guarantees; Android platform constraints require a different architecture.
- Host identity P2 has not landed and implementing this would require cementing URL/token-based subscription identity.

## Review handoff

Reviewer must trace one message through foreground Board monitoring, an app foreground→background overlap, background service ownership, and return to foreground. The message must be processed at most once, cursor state must monotonically advance for the subscription, and cancellation/timeouts must not create hidden retry jobs.

Also verify the Board's 3-second `/api/agents` polling is untouched; this plan removes duplicate **ntfy** ownership only.

## Completion checklist

- [ ] One subscription-qualified persisted ntfy cursor model exists.
- [ ] One shared monitor owns polling/retry/cursor semantics.
- [ ] One process-level coordinator prevents duplicate simultaneous collectors.
- [ ] Board no longer implements its own ntfy polling logic.
- [ ] Foreground service no longer implements its own ntfy polling logic.
- [ ] Background monitoring remains opt-in and Android-time-bounded.
- [ ] Foreground/background handoff does not duplicate message delivery.
- [ ] Connection/topic changes do not cross-contaminate cursors.
- [ ] Monitoring timeout/status is user-visible if needed without automatic restart.
- [ ] `make android-test` passes.
- [ ] Independent review is clean.
- [ ] Final runtime acceptance passes once, last.

## References

- `AGENTS.md`
- `.plans/p2-host-workspace-namespace.md`
- `android/app/src/main/java/dev/scoutr/app/ScoutrApp.kt`
- `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/state/MonitoringStore.kt`
- `android/app/src/main/java/dev/scoutr/app/service/ScoutrMonitorService.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/SettingsScreen.kt`
- `android/app/src/test/java/dev/scoutr/app/service/ScoutrMonitorServiceTest.kt`
- `android/app/src/test/java/dev/scoutr/app/state/MonitoringStoreTest.kt`
- `docs/adr/0004-time-bounded-ntfy-monitoring.md`
- `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
