# Scoutr performance and energy-efficiency study

_Date: 2026-08-14_
_Scope: static code study, external research, and incrementally verified implementation slices. Runtime results are labeled by target; no representative-device speed, memory, radio, or battery claim is made from the emulator run below._

> **Superseded (2026-08-19):** every finding below about background attention — the `dataSync` monitor (P0), foreground ntfy ownership (P1), and ntfy as the attention transport — is superseded by ADR 0007. Push is now a contentless FCM ping the app resolves over the tailnet; the ntfy subsystem, `ScoutrMonitorService`, and the monitoring opt-in are deleted. The sections are kept as the record of why that path was abandoned. Findings outside background attention still stand.

> **Maintenance rule:** update this document in the same change whenever a performance-study slice is implemented, verified, deferred, or superseded. Keep each item's status, evidence date, exact checks/results, remaining acceptance work, and the recommended next slice current. Distinguish observed facts, external facts, hypotheses, and measured results; never mark runtime work complete from compilation alone.

## Executive summary

Scoutr already has a sound high-level split:

- **Interactive terminal:** a dedicated, route-scoped WebSocket with bounded queues and slow-client handling.
- **Foreground app state:** pooled HTTP snapshots and incremental transcript reads, mostly scoped to the visible screen.
- **Background attention:** system-delivered push rather than a permanent bridge WebSocket (self-hosted ntfy at the time of this study; FCM since ADR 0007).
- **Bridge state:** one shared long-lived herdr feed, with bounded file reads and several effective caches.

A blanket “replace polling with WebSockets” migration is not recommended. WebSockets improve change latency when events are frequent, but a complete mobile policy also includes heartbeats, reconnects, network changes, catch-up, queue limits, and lifecycle ownership. For Scoutr, the right comparison is an optimized adaptive HTTP policy versus one foreground-only invalidation stream, measured against the same freshness and energy targets.

The most important findings are more specific:

1. **The opt-in monitor service is not viable as an indefinite `dataSync` foreground service on the app’s current API target.** Android 15+ gives this service type only six background hours per 24 hours. Scoutr targets API 36 and does not implement `Service.onTimeout`. This is both a correctness and energy-design issue.
2. **Usage polling is now lifecycle-gated, but the ten-minute STOPPED-traffic acceptance check is still pending.** `UsageScreen` starts its 10-second loop only while STARTED and stops it when leaving; the retained ViewModel no longer owns an ambient poll loop.
3. **Release optimization, cross-layer counters, and benchmark infrastructure are implemented; emulator benchmark acceptance passed.** Release enables R8/resource shrinking, the profileable minified benchmark target includes cold-start, Board, and deep-link Chat smoke journeys, and Android/bridge counters are wired for request and WebSocket lifecycle evidence. Results are emulator-only and not a representative-device performance claim.
4. **Chat refresh is single-flight with a user-driven pull path; long sessions now open on a bounded tail, on the wire and on the bridge.** Poll, post-action, and pull-to-refresh triggers share one coordinator that joins an in-flight read instead of duplicating it; a successful pull resets the next poll deadline; Chat scroll-to-end is owned by one cancellable job. First Chat open reads the newest 50 entries and backfills older history when the user scrolls up. Still queued: fetching the full enriched agent board to locate one pane, the empty-incremental transcript copy, and first-paint ordering.
5. **The shared OkHttp client sends pings every 20 seconds on all WebSockets (and eligible HTTP/2 connections).** This may be justified for an active terminal, but it should be an explicit measured keepalive policy rather than a global default.
6. **The bridge usage endpoint fetches four providers serially.** Four 10-second provider timeouts can exceed Android’s 30-second read timeout.
7. **Terminal output batching is implemented, and it measured as unnecessary on this transport.** The Android path no longer launches a coroutine per WebSocket frame; one consumer drains each burst into a single ≤64 KiB emulator append. But Herdr and the bridge already coalesce terminal output into periodic frames, so the measured WS-message-to-batch ratio was ~1.00 and appends/repaints did not fall. The change stands on correctness — an off-UI-thread `refreshEmulator` call is fixed, byte accounting is exact, pending output is bounded — not on a measured speedup.
8. **Many earlier suspected hotspots are already handled well.** HTTP calls propagate coroutine cancellation; one OkHttp client is reused; bridge transcript and board reads are memoized; terminal backpressure is bounded end to end; Compose lazy lists generally have stable keys; and the bridge owns one shared herdr feed.

## Method and evidence standard

- **Observed**: established by the current repository implementation.
- **External fact**: documented by an official platform, protocol, or library source linked alongside the claim.
- **Hypothesis**: plausible but requires the listed measurement before changing architecture.
- Third-party “WebSockets are always faster” articles, uncontrolled battery anecdotes, and debug-build microbenchmarks were excluded.

## Product and implementation map

Scoutr is a self-hosted native Android console for supervising herdr panes and pi/Claude agents on a Linux host. The Node/TypeScript bridge is the only owner of the herdr Unix socket. The Android app communicates with its token-authenticated private HTTP/WebSocket API through the host's configured exposure (Tailscale in the measured setup; a Cloudflare Tunnel or a custom reverse proxy are equally supported).

| Feature | Android implementation | Bridge implementation | Runtime behavior |
|---|---|---|---|
| Pairing and health | `ConnectScreen`, `ConnectViewModel`, `ConnectionStore` | `/api/health`, `pairing.ts`, `config.ts` | Demand-driven HTTP |
| Agent board and badge | `BoardScreen`, activity-scoped `BoardViewModel` | `/api/agents`, `routes/agents.ts`, `BoardDetailCache`, `StatusTracker` | 3 s HTTP polling while Board is STARTED; pull refresh |
| Chat, questions, steering | `ChatScreen`, `ChatViewModel` | `/api/sessions?limit=` first page, `/api/sessions?since=` live tail, `/api/sessions?before=` older history, `/api/sessions/:paneId/...` command routes, `commands.ts`, agent adapters | 2.5 s HTTP incremental polling while Chat is STARTED; first open is a 50-entry newest page with on-demand reverse backfill; pull-to-refresh for an immediate user refresh; user actions on demand |
| New sessions | `NewSessionSheet`, `NewSessionViewModel` | `POST /api/sessions`, `sessions.ts` | Demand-driven |
| Session history | `HistoryScreen`, `SessionHistoryViewModel`, local pin/archive store | `/api/session-catalog`, `session-catalog.ts` | 8 s polling while History is STARTED; mutations on demand |
| Provider usage | `UsageScreen`, `UsageViewModel` | `/api/usage`, `usage/providers.ts` | 10 s polling only while Usage is STARTED; bridge provider cache TTL 60 s |
| Read-only git review | `ReviewScreen`, `ReviewViewModel` | `/api/repo*`, `review.ts` | Demand-driven, bounded per-file reads and VM caching |
| Full-screen terminal | `TerminalScreen`, `TerminalViewModel`, `RemoteTerminalSession`, vendored Termux core | `/ws/terminal`, `terminal/broker.ts`, `terminal/websocket.ts`, `terminal/process.ts` | Dedicated WebSocket only while route is STARTED |
| Terminal hierarchy | `HierarchyDrawer`, `TopologyFeedClient` | `/api/snapshot`, `/api/terminal/hierarchy`, filtered `/ws` feed | Route-scoped filtered event invalidation plus HTTP reconciliation |
| Notifications and reply | `ScoutrMessagingService`, `NotificationPresenter`, deep links, reply/mute receivers | `FcmPublisher` in `push/publisher.ts` | System-delivered FCM ping; the app fetches identity from `/api/agents` on wake (ADR 0007) |
| Command palette | `CommandPaletteViewModel` | Existing agent/catalog/control routes | Demand-driven with debounce |
| Settings | `SettingsScreen`, SharedPreferences stores | None for local preferences | Local reads/writes |

## What is already efficient

### Lifecycle and request ownership

- `BoardScreen`, `ChatScreen`, `HistoryScreen`, and `TerminalScreen` use `LifecycleStartEffect` to start and stop their producers.
- `Poller` awaits each tick and delays afterward, so periodic requests do not overlap.
- Board and Usage explicit refreshes are serialized with a `Mutex`.
- `BridgeClient` registers coroutine cancellation with `Call.cancel()`.
- Command palette, review, session creation, catalogs, file mentions, and settings are demand-driven rather than ambient.

### Shared network stack

`ScoutrApp.AppContainer` creates one OkHttp client and shares it among `BridgeClient`, `NtfyClient`, terminal transport, and topology feed. OkHttp recommends sharing a client because each client otherwise owns connection and thread pools; reuse reduces latency and memory and permits connection pooling. [OkHttpClient](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/) [Connections](https://square.github.io/okhttp/features/connections/)

### Bridge feed and file work

- One `HerdrEventFeed` owns the long-lived upstream subscription and fans events out to status tracking, push publishing, and filtered WebSocket clients.
- It rebuilds pane subscriptions after topology changes and performs a 30-second authoritative snapshot resync.
- Board detail is memoized by `(path, mtime, size)` with a 128-entry cap.
- Chat transcript parsing is memoized by `(path, mtime, size)` with an 8-entry cap; the first paginated page skips it, reading a bounded tail plus revision-keyed derived state instead.
- Catalog metadata is memoized and catalog scans are capped.
- Transcript tail/metadata modes read bounded head/tail windows rather than the whole file; the exact metadata and question scans read the whole file as text but parse only the lines that can carry what they look for.
- Review commands, output, body sizes, and file reads are bounded.

### Terminal transport

The terminal is the clearest case where WebSocket is the right transport. It is interactive, ordered, high-rate, and bidirectional.

Current safeguards include:

- Android outbound queue cap: 256 KiB.
- Bridge input queue cap: 256 KiB.
- Bridge pauses terminal output above 512 KiB and resumes below 128 KiB.
- Persistently slow clients are disconnected after 10 seconds.
- Child-process stdin is bounded and drain-aware.
- Terminal resize and snapshot work are debounced.
- The socket and route-scoped topology feed stop when the terminal screen stops.

This architecture should be retained. Further batching or rendering changes are measurement-gated.

## Verified opportunities

### P0 — replace the indefinite `dataSync` monitor design *(superseded by ADR 0007)*

**Observed before this slice:** `ScoutrMonitorService` declared `foregroundServiceType="dataSync"`, returned `START_STICKY`, and polled ntfy every 30 seconds indefinitely. The app targets API 36 and had no `onTimeout` implementation.

**Implemented in the first slice:** the service now returns `START_NOT_STICKY`, handles both Android foreground-service timeout callbacks by cancelling the poll, removing its notification, clearing the monitoring opt-in, and stopping itself. Settings and the foreground notification communicate the six-hour quota, and the activity no longer silently restarts a saved monitoring session after process recreation.

**External fact:** Android 15 limits all of an app’s `dataSync` foreground services to six background hours in a 24-hour period. At timeout, the service must stop within seconds or Android raises a fatal exception. [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)

**Recommendation:** do not force-fit generic agent monitoring into another foreground-service type. Choose a background-delivery architecture explicitly:

1. Prefer the official ntfy Android client as the persistent self-hosted subscriber and have Scoutr consume its documented [`io.heckel.ntfy.MESSAGE_RECEIVED` broadcast](https://docs.ntfy.sh/subscribe/phone/#react-to-incoming-messages), if that preserves the no-cloud product constraint. Validate message extras, Android package visibility/export behavior, duplicate-notification ownership, installation dependency, and Scoutr’s deep-link/reply requirements before adopting it.
2. Otherwise treat Scoutr monitoring as a visibly time-bounded user session, implement `onTimeout`, and communicate the limit. This is the current Scoutr choice.
3. Use WorkManager only for delayed reconciliation, not real-time monitoring: periodic work has a 15-minute minimum and is inexact. [Periodic WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work)

The current implementation is not described as durable all-day monitoring on API 35+.

**Resolution (ADR 0007):** none of the three options was taken. Option 1 was rejected for its install dependency and unvalidated broadcast contract; option 2 was the state this study described and never delivered background attention at all. Scoutr moved to FCM, where the *system* does the waking, and deleted the foreground service outright.

### P0 — create a production-like performance build
**Status (2026-08-14): implementation complete; emulator Macrobenchmark acceptance passed; broader runtime acceptance remains pending.**

The app now has a minified, resource-shrunk `benchmark` variant (`isProfileable = true`) and release enables R8 plus resource shrinking. The `android/benchmark` module contains Macrobenchmark smoke coverage for cold startup, Board tab navigation, and deep-link Chat opening. Process-local counters cover Android HTTP requests and both Android WebSocket lifecycle surfaces, plus bridge HTTP/WebSocket surfaces; they record request counts, statuses, response bytes, durations, active requests, and opened/closed/active sockets. The Usage producer is lifecycle-gated so leaving the retained Usage destination stops its 10-second polling loop. Bridge typecheck/full tests, Android `testDebugUnitTest`, targeted Usage/counter tests, release R8 packaging, benchmark APK compilation, and benchmark APK signature verification passed.
**Previously observed:** `android/app/build.gradle.kts` disabled R8 for release and did not enable resource shrinking. There was no Macrobenchmark/Baseline Profile module.

**External fact:** Android recommends enabling `isMinifyEnabled = true` and `isShrinkResources = true` for release. R8 removes unused code/resources and performs runtime optimizations. Android reports that Baseline Profiles commonly improve covered code paths by about 30% from first launch, but app-specific gains must be benchmarked. [R8 optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)

**Runtime evidence (2026-08-14):** `cd android && ANDROID_SERIAL=emulator-5554 ./gradlew :benchmark:connectedBenchmarkAndroidTest` passed all three tests (`coldStartup`, `boardOpening`, `chatOpening`) with five iterations each on the API 36 x86_64 emulator. The output contained non-empty metrics: cold-start median time to initial display was 416.4 ms; Board median frame count was 15 with frame-duration CPU P50 22.9 ms; Chat median frame count was 10 with frame-duration CPU P50 20.9 ms. These are emulator observations for functional and regression evidence, not representative-device speed, thermal, radio, or energy results. The ignored raw JSON and Perfetto traces are under `android/benchmark/build/outputs/connected_android_test_additional_output/`.

**Remaining verification:** exercise R8-sensitive serialization, manifest receivers, notification reply, and vendored terminal startup; demonstrate zero `/api/usage` calls for ten minutes after Usage is STOPPED; and rerun the managed-device instrumentation suite after its environment timeout is resolved. The attempted `pixel2api36DebugAndroidTest` run timed out after 10m46 before executing any tests (the report recorded zero tests), so it is not evidence about app behavior. No bridge load, heap, radio, battery, or energy result is claimed yet. Do not call these checks complete from compilation alone.

**Recommendation:** after those runtime checks, generate an app-specific Baseline Profile for startup, opening a busy chat, transcript scrolling, terminal entry, and notification deep-link entry. Because Scoutr is side-loaded rather than Play-distributed, verify that the profile is installed on the actual distribution path rather than only packaged in the APK.

### P0 — fix hidden Usage polling

**Observed before this slice:** `UsageViewModel` started polling every 10 seconds in `init` and stopped only in `onCleared`. `UsageScreen` used `collectAsState()` rather than lifecycle-aware collection. The ViewModel remains on the navigation back stack after leaving the tab.
**Implemented (2026-08-14):** `UsageScreen` now uses `LifecycleStartEffect` to start/stop the retained ViewModel's poller and `collectAsStateWithLifecycle()` for UI collection. `UsageViewModel` loads immediately on start, polls every 10 seconds without overlapping requests, preserves cached data on refresh failure, and handles cancellation without publishing a false error. Unit coverage verifies immediate loading and that stopping the loop prevents another scheduled request.

**Acceptance:** targeted unit tests passed. Runtime acceptance remains: demonstrate zero `/api/usage` calls for ten minutes after the Usage screen is STOPPED.
**Recommendation for the next slice:** make Chat refresh single-flight; coalesce polling, pull-to-refresh, resume, and post-action reconciliation. Keep the remaining acceptance check above explicit until runtime evidence is recorded.

### P1 — make Chat refresh fast, coherent, and user-driven

Chat is the highest-value foreground experience: it combines status freshness, transcript reading, questions that need action, composer mutations, and scroll continuity. Optimize the complete refresh path and its user-visible latency, not just the number of HTTP bytes. The product review also identifies pull-to-refresh; treat that gesture as a first-class Chat refresh trigger, not as a second polling loop. (The shared `PullToRefreshBox` implementation is visible on Board and Usage in this checkout; verify the reviewed Chat build's wiring before changing its contract.)

**Observed:** each Chat refresh:

1. calls `/api/agents`, enriching every live agent, to obtain status/path/kind for one pane;
2. calls `/api/sessions?since=<lastId>`;
3. builds a set of every existing transcript entry ID and concatenates a new list even when `response.entries` is empty;
4. receives `questions` derived from the complete cached transcript rather than only changed questions;
5. performs status/path sync, model configuration, command catalog refresh, and transcript refresh sequentially, so optional catalog work can delay the first useful transcript paint;
6. serializes only the poller's own ticks. Send, control, question-answer, resume, and pull-to-refresh paths can all become additional refresh triggers and need one shared ownership policy.

`MutableStateFlow` already uses strong-equality conflation for `ChatUiState`, so structurally equal values should not be treated as an unverified hotspot or wrapped in another `distinctUntilChanged` layer. Count actual emissions before changing this part.

**Recommendation, in priority order:**

1. **Create one serialized, single-flight Chat refresh coordinator.** Poll ticks, initial/resume refresh, pull-to-refresh, send completion, control completion, and question-answer reconciliation should all call the same coordinator. Allow at most one authoritative Chat read per pane at a time; concurrent callers join it or wait for its result rather than launch duplicate `/api/agents` and session calls. Commit a coherent immutable state and reject results for an obsolete pane/session generation. Keep mutation progress (`sending`, answer submission) separate from read progress so a refresh cannot cancel or mask a user action.
2. **Make pull-to-refresh an explicit immediate refresh.** Its indicator must represent only the user-requested refresh and must clear on success, failure, or lifecycle cancellation. If a poll is in flight, the gesture joins that request; repeated pulls are no-ops. A successful pull resets the next poll deadline. Preserve already-rendered transcript content on failure, and do not force-scroll a reader who has moved away from the bottom. Use the official Material 3 pull-to-refresh contract and test both the gesture and its accessibility refresh action. [Compose pull-to-refresh](https://developer.android.com/develop/ui/compose/components/pull-to-refresh)
3. **Shorten first useful paint.** When the session path is already known, fetch pane status and the transcript concurrently or use one snapshot route, then publish the transcript before loading slash commands and model catalogs. For a fresh session whose path is not known, resolve the pane once, then read the transcript. Keep catalogs lazy and cached because they are composer/configuration affordances, not prerequisites for reading the conversation. Collect UI state with lifecycle-aware collection as well as stopping the producer at `STARTED`; the latter protects network work, while the former avoids unnecessary off-screen collection. [Lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines)
4. **Remove steady-state allocation and parsing work.** Make `mergeSessionEntries` return the existing list immediately for an incremental response with no entries, while still applying any response metadata or question state that changed. On the bridge, cache derived questions and other session metadata with the existing `(mtimeMs, size)` transcript memo so an unchanged file does not re-walk the full transcript every 2.5 seconds. Keep the full question list authoritative until a revisioned/delta protocol is justified; a question answer can change state even when a client-side list merge would otherwise look empty.
5. **Replace the full-board status lookup.** Add a pane-scoped lightweight status/session-reference endpoint returning only the fields Chat needs (`status`, `statusSinceMs`, path, kind, cwd, title, and capabilities), or consume a foreground feed event as an invalidation and fetch that pane's authoritative state. Do not make Chat pay the `/api/agents` cost of enriching every live agent. Existing herdr status events can reduce perceived latency, but HTTP reconciliation remains the correctness path after reconnects and missed events.
6. **Make transcript/question updates revision-safe.** Keep stable entry and question IDs, use the existing cursor for append-only reads, and fall back to a full snapshot when the cursor disappears or the session is rotated/compacted. If a future response adds revisions, ETags, or question deltas, require gap detection and full-snapshot recovery; do not change JSON shape before response-size, parse-time, and allocation traces show that the simpler fixes are insufficient.
7. **Bound the first Chat transcript page and backfill older history on demand.** First open now requests `GET /api/sessions?limit=50` (newest file-order page). Reverse pages use `before` + `limit`; unknown `before` is 409. Android prepends older entries, keeps live `since` polls on the newest loaded id, and only shows unanchored questions when they are still open. Runtime first-paint, scroll-anchor, and 10k/100k heap traces are still pending.

**Implemented (2026-08-15):** Chat refresh is now single-flight. All triggers (the 2.5 s poll tick, send/control/slash-command completion, question-answer reconciliation, and pull) route through one `ChatViewModel` coordinator keyed by `RefreshSource`; a trigger while a read is in flight joins that read (recording the join) instead of launching duplicate `/api/agents` and session calls, and a refresh is refused once the pane is STOPPED, with `stopPolling()` cancelling the in-flight read. Pull-to-refresh ships under the official Material 3 contract: `PullToRefreshBox` wraps the transcript area below the header with a `chat_refresh_root` tag plus the accessibility refresh action; the indicator represents only the user request and clears on success, failure, or cancellation; a pull while a poll is in flight joins it; a successful pull resets the next poll deadline via `Poller.resetNextDeadline()`; empty/loading/failed states keep the gesture usable through scrollable fillers, and a failed pull preserves already-rendered entries. The Chat scroll-to-end paths (open-at-bottom, append-follow, button) now share one cancellable owner whose convergence uses current layout info rather than fixed sleeps, and follow state is drag-intent-driven, so a reader scrolled up is never force-scrolled (UX plan 007 was folded into this slice). Minimal counters in `PerformanceCounters` record starts, joins, per-source triggers, pull attempts/completions/failures, and in-flight cancellations through the snapshot/reset API.

**Implemented (2026-08-24):** long Chat sessions no longer require a full transcript on first open. `GET /api/sessions` accepts optional `before` and `limit` (1–200). A request with no pagination parameters remains the legacy full snapshot. First Chat read uses `limit=50`; live polls keep `since=<newestLoadedId>`; older history uses `before=<beforeCursor>&limit=50`. Reverse pages are file-order slices, `beforeCursor` is the oldest returned id when older entries exist, and an unknown reverse cursor is 409. Android prepends with id-dedup, may overlap the live refresh, and drops answered questions until their anchor entry is loaded. Unit coverage is `session-read.test.ts`, `ChatPaginationTest`, and `ChatTranscriptRowsTest`. Runtime first-paint bytes, scroll-anchor, and 10k/100k heap traces were not measured in this change.

**Implemented (2026-08-24, bridge side):** the first paginated read no longer parses the whole transcript either. Network pagination (above) bounded what the response carries; this bounds what the host reads to build it. A `limit`-only request (no `since`, no `before`) now serves its display entries from `readTranscript(path, { tail: limit + 1 })` — the one extra entry is what proves older history exists — and takes the fields that describe the whole session from their own scans: exact model/thinking through `readTranscriptState`, and question cards through a new `AgentBackend.readQuestions`. Both are memoized per file revision in `session-derived-state.ts`, and the question cache is also keyed by `questionStateStamp`, so Claude's sidecar ask still opens and clears without a transcript write. Forward `since`, reverse `before`, and the legacy full snapshot are untouched and still read through the full-transcript memo.

Question state could not be derived from the displayed tail: an ask escaped in the terminal is never written back as answered, and Chat keeps its composer locked on it wherever it sits in the file. It also could not afford a full parse. Both scans (`readQuestions`, and the exact metadata read) instead match candidate lines by byte offset and hand only those to the JSONL parser — `linesMentioning` in `transcript.ts`. Reverse-history pages still slice the full memo; bounding those to disk ranges remains deferred until a measurement shows it is the dominant cost.

**Measured (2026-08-24, dev machine, not a device claim):** synthetic pi transcripts, warm page cache, Node 26, median of 7. Each iteration stamps a new mtime first, so every read is a cache miss — the cost of opening a session that has changed, which is every open while an agent is working. "Before" is `f162767`, the commit preceding both long-session slices; "after" is `526fbfd`. Time is what the bridge spends producing the response body, split into the read and the JSON serialization the old path could not avoid.

| Transcript | Before: read + serialize | Before: body / entries / retained heap | After: read + serialize | After: body / entries / retained heap |
| --- | --- | --- | --- | --- |
| 3.8 MiB, 8k entries (~300 B each) | 41.1 + 9.0 ms | 3.73 MB / 8000 / 12.4 MiB | 8.9 + 0.1 ms | 23.9 KiB / 50 / ~0 |
| 14.4 MiB, 30k entries (~300 B each) | 151.8 + 30.0 ms | 14.0 MB / 30000 / 46.4 MiB | 29.4 + 0.1 ms | 23.8 KiB / 50 / ~0 |
| 13.9 MiB, 8k entries (~1.5 KB each) | 55.9 + 26.5 ms | 14.0 MB / 8000 / 42.6 MiB | 26.4 + 0.2 ms | 62.1 KiB / 35 / ~0 |

Retained heap is measured after a forced GC, so it is what the caches hold, not parse peak; the post-change figures are inside measurement noise. The re-read of an unchanged file is memoized on both sides and costs about 1 ms either way. The third row shows `limit` behaving as the documented maximum: 50 fat entries do not fit the 64 KiB tail window, so the page is 35 and `hasMoreBefore` carries the rest.

Residual cost after the change is the two whole-file text scans the bounded read still needs — the exact metadata scan and the ask-record scan — visible as the gap between the 3.8 MiB and 14.4 MiB rows. The record-line filter is what keeps them cheap: before it, the exact metadata scan alone cost 50.2 ms on the 14 MiB fixture, which would have made the composite slower than the full read it replaced. Structural proof that the display read is bounded is in `session-read.test.ts` ("serves the initial page from the bounded tail window" asserts the only display read is `{ tail: 6 }`), with truthful history, model/thinking, and question coverage beside it.

**Chat acceptance targets to ratify:** zero duplicate in-flight refreshes per pane; zero refresh traffic after Chat reaches `STOPPED`; one network operation for a poll-plus-pull race; p95 working-status/question visibility under two seconds on the LAN; first useful transcript paint measured independently from command/catalog loading; and no transcript jump when new entries arrive while the reader is scrolled up. Unit coverage (`PollerTest`, `ChatRefreshTest`, `PollLifecycleTest`, `ChatPendingMessageTest`) verifies single-flight collapse, poll-plus-pull joining, deadline reset, failure preservation, per-source recording, and STOPPED gating.

**Runtime evidence (2026-08-15):** the full `ChatListTest` instrumentation suite (34 tests, including the five plan-007 acceptance tests for rapid taps, tap-concurrent-with-append, drag interruption, and no-viewport-move while scrolled up) passes on `emulator-5554`. On the interactive emulator against the real bridge, a pull gesture on a live Chat transcript showed the Material 3 pull indicator mid-gesture (confirmed via screenshot) with the transcript intact and no errors, and the accessibility refresh action is present on the transcript node; the pull's refresh burst landed exactly at the gesture moment and the next poll landed a full interval after it. For the STOPPED check, with Chat on screen the bridge saw `/api/agents` + `/api/sessions` pairs every 2.5 s; after navigating away (Chat STOPPED) there were **zero `/api/sessions` calls for 22+ seconds** while the Board's own 3 s `/api/agents` cadence continued, so no refresh traffic survives Chat reaching STOPPED. The p95 working-status/question visibility measurement remains documented acceptance with no dedicated surface yet.

Do not change JSON format until response size and parse/allocation traces show it matters after this redundant work is removed.

### P1 — parallelize usage providers

**Observed:** `UsageService.all()` awaits Codex, Claude, DeepSeek, and xAI sequentially. Individual providers use timeouts around 10 seconds, while Android’s shared client has a 30-second read timeout.

**Recommendation:** fetch independent providers concurrently with `Promise.all`, while preserving the existing per-provider error snapshots and cache. This reduces worst-case response latency. Preventing provider work after an Android timeout is a separate concern: propagate request disconnect/abort into provider fetch signals, or share one in-flight cached request.

### P1 — make the 20-second ping policy transport-specific

**Observed:** one shared OkHttp client is configured with `pingInterval(20 seconds)`. OkHttp applies this to WebSockets and eligible HTTP/2 connections. Terminal and topology sockets therefore emit keepalive traffic while active.

**External fact:** OkHttp’s default ping interval is zero; when enabled, a missed pong within one interval fails the connection. [OkHttp ping interval](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/ping-interval.html)

**Recommendation:** keep the shared connection/thread pools, but derive transport-specific clients with `newBuilder()` if policies differ. Determine the longest reliable ping interval—or whether no application ping is needed—from Tailscale/proxy/NAT experiments. Reset `TopologyFeedClient`’s reconnect attempt after a successful open; it currently remains at the maximum backoff after prior failures.

### P1 — make foreground polling adaptive and conditional

Current fixed intervals are Board 3 seconds, Chat 2.5 seconds, History 8 seconds, and Usage 10 seconds. They stop with screen lifecycle except Usage.

After instrumentation, compare:

- fast cadence while an agent is working or immediately after user action;
- slower cadence when state is stable;
- exponential, jittered backoff on failures;
- immediate refresh on resume, pull-to-refresh, push wake-up, or filtered foreground feed invalidation;
- endpoint revisions/ETags and `304 Not Modified` for unchanged board/history/usage bodies.

HTTP validators reduce payload and parsing, though not request wakeups. [RFC 9110 conditional requests](https://www.rfc-editor.org/rfc/rfc9110.html#name-conditional-requests)

### P1 — rationalize foreground ntfy ownership *(superseded by ADR 0007)*

**Observed:** ntfy `poll=1` is a finite cached-message fetch, not a long-held stream. Board and the monitor service can both poll the same topic with separate cursors. Board seeds its cursor from the latest message; the service persists its cursor.

**Recommendation:** define one owner for local notifications. When the monitor service is enabled, the Board should not run a second notification producer. If in-app status freshness is needed, refresh board state rather than post a duplicate local notification.

For an app-owned foreground subscription, ntfy recommends the NDJSON stream for most non-JavaScript clients and also supports WebSocket. That can remove 30-second finite polls while visible, but it does not solve Android background execution. [ntfy subscription API](https://docs.ntfy.sh/subscribe/api/)

**Resolution (ADR 0007):** the duplicate-owner problem is gone by construction. `NotificationPresenter` is the single owner of every notification, and the app posts nothing at all while foregrounded.

### P2 — terminal render batching: implemented, and the hypothesis did not hold

**Original hypothesis:** every incoming terminal frame launches work on the terminal dispatcher and can trigger a full view refresh. Under ANSI-heavy output, dispatch/emulator/draw cost may dominate before network backpressure engages.

**Implemented (2026-08-15):** `TerminalOutputPump` replaced the coroutine-per-WebSocket-frame path. Transport callbacks now enqueue and return; one long-lived consumer on `scoutr-terminal-io` drains everything already available into one contiguous batch, capped at 64 KiB, and each batch costs exactly one `TerminalEmulator.append` and one screen update. Batching emerges from draining — there is no timer and no artificial delay, so a lone keystroke echo is delivered on its own. A generation is a handle rather than a flag: only the handle installed by the last `resetGeneration` can enqueue, and installing one carries the emulator reset as its prologue, so the reset cannot be reordered behind the bytes it must precede. Pending output is bounded (4 MiB / 65536 chunks); exceeding it, or an append that throws, retires the generation explicitly and reconnects rather than dropping bytes silently, with repeated delivery failures inside a 60 s window settling into a non-retryable failure instead of a reconnect loop.

**Why 64 KiB:** upstream Termux moved its terminal receive buffer from 4 KiB to 64 KiB in February 2026 because the smaller buffer caused serious lag in large-output workloads such as terminal multiplexers. That is the evidence for the starting value — it is not a Scoutr measurement, and on the workloads below the cap was never reached.

**Measured (2026-08-15, `emulator-5554`, real bridge on port 8737, full path Herdr → bridge child adapter → `/ws/terminal` → pump → Termux emulator → TerminalView).** Counters via `adb logcat -s ScoutrTerminal`, cumulative per app process:

| Workload (cumulative) | WS msgs | WS bytes | Batches | Batch bytes | Max batch | Max pending | Appends | Screen updates | Overflows | Delivery failures |
|---|---|---|---|---|---|---|---|---|---|---|
| Interactive + 20k lines + `top` + ANSI fixture | 148 | 268,310 | 148 | 268,310 | 51,907 | 51,907 | 148 | 150 | 0 | 0 |
| \+ `seq 1 400000` | 161 | 328,927 | 161 | 328,927 | 51,911 | 51,911 | 161 | 164 | 0 | 0 |
| \+ 400 small writes at ~100/s | 447 | 496,387 | 446 | 496,387 | 51,911 | 51,911 | 446 | 450 | 0 | 0 |

**What the numbers say.** Batching did **not** reduce append or repaint count on any measured workload: the WS-message-to-batch ratio was 1.000, 1.000, and 1.004. Only one coalescing event occurred in the entire session. The reason is upstream of Android — Herdr and the bridge already deliver terminal output as periodic frames rather than raw pty writes, so the Android frame rate is low and each frame is already large (mean ~1.1 KB, max ~52 KB). `seq 1 400000` added just 13 WS messages. The premise that per-frame dispatch cost dominates is therefore **not supported** on this transport, and the 64 KiB cap was never reached (largest batch 51,911 bytes).

What the counters do confirm is semantic correctness under the new path: `batch bytes == WS bytes` exactly in every dump (no byte lost or duplicated between socket and emulator), `appends == batches`, `screen updates == batches + one per generation`, and zero overflows or delivery failures across ~500 KB and four generations.

**What the change is still worth,** stated without unmeasured claims: it removes one coroutine launch per WebSocket frame (447 launches avoided in the session above — a structural reduction, not a measured time saving); it fixes a real defect where `TerminalView.refreshEmulator()` — which mutates scroll position, wakes scrollbars, and invalidates — was called directly from `scoutr-terminal-io` instead of the UI thread, now a coalesced `view.post`; and it makes pending output bounded with explicit failure instead of unbounded queueing. No battery, latency, CPU, or memory improvement was measured, and none is claimed.

**Runtime acceptance (same session):** typed input echoed promptly with no perceptible batching delay; 20,000 generated lines rendered with the tail exact and contiguous and the prompt returning; `top -d 0.2` redrew without corruption, keeping reverse-video, bold, and column alignment, and `q` reached the shell mid-redraw; a 200-iteration fixture of bold/underline/reverse/256-colour sequences rendered with no attribute bleed; leaving and re-entering the route produced a fresh generation whose replay reproduced the screen exactly with no stale bytes. Note that Herdr's frame model means the phone receives a compacted view of a fast producer's output, so "no missing lines" is verified for the delivered stream, not for every byte the pane ever wrote.

**Remaining hotspot / next step:** none identified on the Android side by these counters. Bridge → Android WebSocket micro-batching stays deferred, and this data argues against it: the frame count is already low. If terminal rendering is revisited, measure draw/invalidate cost in `TerminalView` rather than dispatch, and do it on a physical device — these numbers are emulator numbers.

**Not measured:** dispatcher queue delay, emulator append duration, draw duration, frame misses, and bridge queue bytes. A workload that genuinely saturates the socket (many frames per second reaching Android) was not reachable through Herdr's frame cadence, so the pump's coalescing path remains exercised only by unit tests.

### P2 — improve cache eviction only if churn appears

Transcript, board-detail, and catalog memos evict the oldest inserted entry rather than least recently used. This is bounded and simple. Change it to LRU only if counters show hot entries being evicted under realistic multi-session use.

## Polling versus WebSockets for Scoutr

| Dimension | Lifecycle-gated HTTP reconciliation | Foreground WebSocket/event stream |
|---|---|---|
| Change latency | Interval + request RTT; adaptive fast polling can meet human-scale status needs | Event delivery is usually immediate after server processing |
| Idle traffic | Fixed polls still send headers and wake CPU/radio; validators cut payload | Quiet if no pings/events, but heartbeats and reconnects are traffic |
| Correctness | Every successful read is authoritative; missed intervals are harmless | Needs revision/cursor, gap detection, reconnect backoff, and snapshot catch-up |
| Lifecycle | Simple cancellation and restart | Must close when ineligible and reconcile after reconnect/network change |
| Bridge resources | No per-idle-client subscription queue | File descriptor and listener/queue state per client |
| Best Scoutr fit | snapshots, lists, history, usage, mutations | terminal; possibly one foreground invalidation stream |

Android warns that repeated periodic requests can wake the radio and recommends batching or server-initiated delivery for sparse updates. It does not establish that WebSocket is universally more energy-efficient: heartbeat interval, event rate, network path, and reconnect frequency determine the result. [Minimize regular updates](https://developer.android.com/develop/connectivity/minimize-effect-regular-updates) [Network access optimization](https://developer.android.com/develop/connectivity/network-ops/network-access-optimization)

### Recommended transport direction

1. Retain `/ws/terminal` exactly as the dedicated high-rate transport.
2. Retain HTTP as the authoritative snapshot and command/result surface.
3. Fix lifecycle, hidden work, redundant payload/list work, and conditional/adaptive polling first.
4. Then benchmark one foreground-only multiplexed invalidation stream for board/chat/history. Events should carry a monotonically increasing revision; reconnect performs HTTP catch-up/snapshot reconciliation.
5. Do not keep that bridge socket alive in the background. Attention events arrive as FCM pings (ADR 0007).

## Measurement plan

### Workloads and candidate SLOs

Use fixed event timelines and fixture sizes:

- Idle Board for 30 minutes with no changes.
- Working agent with status updates and five transcript entries/second.
- One `NeedsYou` transition after 15 minutes idle.
- Board → Chat → Terminal → Home → lock, five minutes each.
- Chat open with a known path, Chat open while a fresh session is booting, pull-to-refresh during an in-flight poll, pull-to-refresh during send/question submission, and reading while new entries arrive above the bottom.
- Terminal typing plus 1/10/50 MiB plain and ANSI-heavy output bursts.
- History/transcript fixtures at 1k, 10k, and 100k records.
- Wi-Fi loss, network switch, bridge restart, 200 ms RTT, Doze, and app standby.

Candidate SLOs to ratify:

- terminal p95 input-to-emulator under 100 ms on LAN;
- working status p95 under 1 second while foreground;
- stable status p95 under 10 seconds;
- no screen-owned traffic after STOP;
- no unbounded queue or RSS growth.

### Android evidence

- Macrobenchmark a non-debuggable, minified target for startup, list scroll, chat append/scroll, terminal entry, and sustained terminal output.
- Use `FrameTimingMetric`, startup metrics, trace sections, allocation/GC data, and `PowerMetric` on supported controlled hardware.
- Add an OkHttp `EventListener` for endpoint count, bytes, DNS/connect/TLS, connection reuse, cancellations, and failures.
- Record active pollers/sockets, WS pings/reconnects/queue size, response parse time, and equal UI-state emissions.
- For Chat, record time to first transcript paint, time to status/question visibility, refresh-coordinator joins, pull-to-refresh attempts/completions, duplicate in-flight calls, entries appended, question extraction time, and scroll-anchor changes.
- Use Compose compiler stability reports and Layout Inspector only after frame traces identify an expensive route.
- Use StrictMode in a diagnostic build to identify main-thread disk/network work and preference fsync stalls.

Emulator results are useful for functional regression, not decision-grade radio, energy, thermal, or startup comparisons. The repository currently prohibits instrumentation on the physical Pixel; designate separate approved benchmark hardware or label results non-representative.

### Bridge evidence

Record route count/status/bytes/duration, JSON serialization time, transcript bytes scanned/rows parsed/cache hit rate, herdr subscribers, WebSocket clients/queue bytes, terminal bytes/chunks, event-loop delay, CPU, RSS/heap, and GC.

Load-test optimized polling and foreground event-stream variants with identical event timelines and 1/10/100 clients. Include slow clients and reconnect storms. Pass on bounded memory/queues and latency at target concurrency, not peak requests/second alone.

### Energy experiment

Compare complete policies over both Wi-Fi and cellular/Tailscale:

- fixed current polling;
- adaptive polling + backoff + validators;
- foreground event stream with no application ping;
- event stream with the minimum infrastructure-required ping;
- push-triggered background attention followed by an HTTP refresh.

Report request/event counts, bytes, connection reuse, p50/p95/p99 freshness, reconnects, heartbeats, catch-up requests, CPU, radio/system energy rails, thermal state, and bridge resources. Randomize repeated run order and report distributions—not one battery-percentage observation.

## Recommended sequence

1. **Complete:** decide the API 35+ monitoring architecture and add timeout-safe behavior.
2. **Implementation complete / emulator benchmark accepted / broader runtime acceptance pending:** add a minified benchmark target and basic cross-layer counters; record the emulator benchmark evidence and finish R8-sensitive runtime journeys on approved acceptance targets.
3. **Complete implementation / pending runtime acceptance:** lifecycle-gate Usage polling; verify no `/api/usage` traffic after STOPPED.
4. **Complete (2026-08-15):** make Chat refresh single-flight; coalesce polling, pull-to-refresh, resume, and post-action reconciliation. Runtime acceptance passed: `ChatListTest` (34 tests) green on the emulator, pull gesture evidence, and zero `/api/sessions` traffic after Chat reaches STOPPED.
5. **Complete (2026-08-15):** terminal output batching, bounded output queue, and terminal throughput counters. Emulator acceptance passed across interactive, bulk, TUI, ANSI-fragmentation, and reconnect scenarios; the counters show no reduction in emulator/repaint work, which is recorded as a negative result rather than a win. Physical-device acceptance remains outstanding.
6. Shorten Chat first paint, remove the empty incremental-list copy, and replace the full-board status lookup.
7. Parallelize usage providers.
8. Separate and measure WebSocket ping/reconnect policy.
9. Add adaptive cadence and validators/revisions to large unchanged HTTP resources.
10. Benchmark an optional foreground invalidation stream against that optimized baseline.
11. Add the app Baseline Profile after critical journeys and performance targets stabilize.

After every implementation or verification step, update this sequence and the relevant item's status/evidence before committing.
## Changes not recommended now

- Replacing every HTTP poll with WebSockets.
- Keeping a private bridge WebSocket alive in the background.
- Moving terminal bytes into general Chat/Compose state.
- Adding binary serialization or WebSocket compression before payload/CPU evidence.
- Migrating small SharedPreferences stores solely for benchmark optics.
- Marking Compose models `@Stable`/`@Immutable` without satisfying the contract.
- Optimizing recomposition count without frame-time evidence.
- Tuning intervals or ping periods by intuition alone.

## Primary sources

- [Android foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Android 16 job quota changes](https://developer.android.com/about/versions/16/behavior-changes-all#job-scheduler-quota-optimizations)
- [Android network update optimization](https://developer.android.com/develop/connectivity/minimize-effect-regular-updates)
- [Android network-access optimization](https://developer.android.com/develop/connectivity/network-ops/network-access-optimization)
- [WorkManager persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)
- [Android R8 optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
- [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Compose performance](https://developer.android.com/develop/ui/compose/performance)
- [Compose pull to refresh](https://developer.android.com/develop/ui/compose/components/pull-to-refresh)
- [Lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines)
- [Kotlin `StateFlow`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)
- [OkHttp clients and connections](https://square.github.io/okhttp/features/connections/)
- [OkHttp WebSocket and queue behavior](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-web-socket/index.html)
- [ntfy subscription API](https://docs.ntfy.sh/subscribe/api/)
- [ntfy Android broadcast integration](https://docs.ntfy.sh/subscribe/phone/#react-to-incoming-messages)
- [Node.js stream backpressure](https://nodejs.org/api/stream.html#writablewritechunk-encoding-callback)
- [RFC 6455 WebSocket protocol](https://www.rfc-editor.org/rfc/rfc6455.html)
- [RFC 9110 HTTP semantics](https://www.rfc-editor.org/rfc/rfc9110.html)

## Bottom line

Scoutr’s terminal WebSocket, shared bridge feed, caches, bounded reads, and lifecycle-scoped producers are strong foundations. The monitoring timeout, lifecycle-gated Usage implementation, R8 configuration, cross-layer counters, emulator Macrobenchmark, and the Chat refresh slice (single-flight coordinator, pull-to-refresh, scroll ownership, counters) are recorded above with their runtime acceptance evidence (Chat: instrumentation green, pull gesture, zero `/api/sessions` traffic after STOPPED). After that, use the counters and approved runtime targets to compare adaptive conditional HTTP against one foreground invalidation stream. Keep whichever meets explicit freshness targets with the lowest measured energy and acceptable bridge cost.
