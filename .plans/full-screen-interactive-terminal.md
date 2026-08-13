# Full-screen Interactive Terminal Implementation Plan

## Current situation

Scoutr currently offers agent chat plus a read-only **Live Output** escape hatch:

- `android/app/src/main/java/dev/scoutr/app/MainActivity.kt` routes `chat/{paneId}/live` to `LiveOutputScreen`.
- `android/app/src/main/java/dev/scoutr/app/ui/screens/ChatScreen.kt` exposes “Live output…” in the session menu.
- `LiveOutputViewModel`, `LiveOutputPanel.kt`, `ScoutrApi.liveOutput`, and `BridgeClient.liveOutput` poll every 900 ms while that screen is visible.
- `bridge/src/routes/agents.ts` serves `GET /api/agents/:paneId/read` through `bridge/src/live-output.ts`, which performs a bounded `agent.read` and returns plain text.
- This path is not interactive and is not a terminal emulator.

The upstream foundations exist, but Scoutr does not expose them:

- Herdr 0.8.0 / protocol 19 snapshots already carry workspace, tab, pane, `terminal_id`, names, cwd, titles, focused IDs, and topology events in `bridge/src/herdr/types.ts`.
- `bridge/reference/herdr-schema.json` supports `workspace.create/rename/close`, `tab.create/rename/close`, and `pane.rename/close`; Scoutr’s `HerdrPort` lacks the tab and direct pane operations.
- Terminal streaming is outside that schema. The installed CLI exposes `herdr terminal session control|observe`, with NDJSON output and control records documented by Herdr.
- `HerdrClient` supports one-shot JSONL-RPC and one-way `events.subscribe`; it is not the seam for a bidirectional terminal child process.
- `createScoutrServer` owns one `/ws` server that permits query-token auth and treats every message as JSON. Terminal traffic needs a separately routed WebSocket with binary messages and header-only auth.
- Android is a single `:app` Gradle project using Compose, manual DI in `ScoutrApp.AppContainer`, and OkHttp. It has no `AndroidView` integration today.

Named references:

- Research and selected libraries: `docs/research/full-screen-interactive-terminal.md`
- Architecture decisions: `docs/adr/0001-project-herdr-terminals-through-a-dedicated-websocket.md` and `docs/adr/0002-vendor-the-termux-terminal-core.md`
- Herdr schema: `bridge/reference/herdr-schema.json`
- Design rules: `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- Verification rules: `AGENTS.md`, `docs/dev-workflow.md`, and `scripts/verify.sh`

## Objective and why

Replace Live Output with a production-quality, full-screen Android terminal that projects one existing Herdr pane at a time. The user must be able to select and manage any Herdr pane, interact with shells and common TUIs, and recover from disconnects without spawning a second shell or reproducing Herdr’s split layout on the phone.

Done is observable when:

1. Live Output and its bridge read endpoint no longer exist.
2. Terminal remains visible as an app-level destination when Herdr is unsupported, with a precise capability/upgrade explanation and no `pane.read` fallback.
3. A supported pane can be opened, observed, controlled, resized, taken over after confirmation, disconnected, resumed, switched, renamed, and closed from Android.
4. Herdr remains the sole process, PTY, terminal-state, workspace, tab, and pane owner.
5. The managed Pixel fixture passes the interaction matrix and production gates, followed by one physical Pixel integration walk without phone instrumentation.

## Scope

### Included

- Delete all Live Output Android and bridge code, tests, route, DTOs, and wording as the first implementation commit.
- Add a Herdr terminal capability probe and bidirectional child-process adapter with its own fake.
- Add a dedicated `/ws/terminal` route for the active terminal stream.
- Use typed authenticated HTTP for hierarchy snapshots and create/rename/close operations.
- Consume the existing Herdr feed only while Terminal is visible, with lifecycle/error hardening and snapshot reconciliation.
- Vendor a pinned Termux `terminal-emulator` + `terminal-view` subset adapted to remote bytes, preserving Apache-2.0 provenance.
- Build a full-screen Terminal route with one pane canvas, modal hierarchy drawer, search, hierarchy management, ownership states, extra keys, local scrollback, font sizing, clipboard safeguards, safe links, and BEL haptics.
- Add functional, lifecycle, reconnect, flow-control, renderer, and performance evidence.

### Non-goals

- Rendering multiple panes or Herdr splits on Android.
- Moving panes, editing split topology, split creation, or synchronizing Herdr’s desktop focus with the mobile selection.
- Creating Scoutr-owned shells or PTYs.
- Mosh, predictive echo, state-diff transport, or UDP exposure.
- Sixel, Kitty graphics, iTerm inline images, terminal-controlled HTML, or other graphics protocols.
- User themes, customizable key rows, scrollback search/export, or terminal accessibility acceptance work in v1.
- Multiple simultaneous mobile clients or multi-writer arbitration. The contract is one user and one active mobile terminal client.
- WebView/xterm.js spike, fallback renderer, compatibility layer, or migration path from Live Output.

### Ownership boundaries

- **Herdr** owns processes, PTYs, terminal state, current-screen replay, writable-controller arbitration, hierarchy, and persistence.
- **Bridge** owns capability detection, terminal child lifecycle, mobile reconnect grace, authenticated transport, backpressure, and hierarchy validation.
- **Android** owns the emulator, active-visit scrollback, viewport measurement, input UX, mobile selection, and per-connection preferences.
- Existing Board polling remains unchanged. New long-lived sockets are scoped to the Terminal route and must not become a global Android feed.

## Resolved decisions

### Product and navigation

- Terminal is a dedicated full-screen route with Android status/navigation bars visible and Scoutr bottom navigation hidden.
- Every Herdr-managed pane is eligible, including panes with no recognized agent.
- Add a Terminal icon to the shared primary-screen top bar beside Search and Settings.
- Chat alone gets **Open in terminal**, replacing **Live output…**, and opens that pane directly.
- Initial selection order: last valid pane for this saved connection, Herdr’s focused pane, then the selector.
- Keep exactly one pane stream active. Switching releases the old stream; returning starts a fresh Herdr generation and new Android scrollback visit.
- Mobile selection never changes Herdr desktop focus.

### Hierarchy UX

- A button-only modal side drawer overlays the terminal and never resizes its grid. Edge-swipe opening is disabled.
- The drawer is a searchable, collapsible workspace → tab → pane hierarchy. Pane rows show resolved name and cwd, not agent or ownership metadata.
- Pane name precedence: user-assigned Herdr label, live OSC title, foreground cwd/cwd, pane ID.
- Create offers primary **New tab** in the selected/current workspace and secondary **New workspace**.
- New-tab cwd precedence: selected pane foreground cwd/cwd, another pane cwd in the workspace, then Herdr’s home default.
- New workspace asks for directory plus optional name, reuses Scoutr’s directory picker, defaults the name from the folder, and relies on Herdr to create the initial root pane.
- Rename and close work on any listed pane, tab, or workspace.
- Pane close names the pane in confirmation. Tab/workspace close names the target and shows the exact pane termination count. A stale count refreshes and requires confirmation again.
- Post-close selection is deterministic over the pre-close catalog order and fresh snapshot. Closing the active pane selects next then previous in its old tab, then old workspace, then globally. Closing the active tab skips the removed tab and selects next then previous tab in its old workspace, then globally. Closing the active workspace skips the removed workspace and selects next then previous workspace. Closing an unrelated target preserves the active pane if it still exists. Otherwise show the empty selector. Show a brief **Pane closed** message when the active pane disappears.

### Ownership and lifecycle

- An unowned pane opens writable automatically. A pane owned elsewhere opens as a phone-sized observer with **Take control**.
- Takeover always requires a fresh confirmation naming the pane and warning that the current controller will be disconnected and running TUIs may reflow. Never remember the choice.
- Explicit route exit or pane switch releases immediately.
- Socket loss, child failure, or app backgrounding starts a 30-second bridge-side grace. Freeze the screen and queue no input.
- During grace, the bridge keeps the Herdr controller only to preserve ownership and discards output without retaining terminal content. Same-pane resume closes the old generation and attempts a fresh control/observer socket so Herdr replays current rendered state; Step 2 must prove this can preserve the agreed semantics.
- Bearer-token identity is the reconnect owner identity. A second connection with the same token replaces the previous mobile socket; there are no device IDs or per-view leases. Its first valid `hello` decides disposition: same pane attempts fresh-generation resume; a different pane releases any active/grace-held child before opening the new pane, preserving the one-stream contract.
- After grace, returning automatically acquires control only if free; otherwise it observes. It never steals ownership without confirmation.
- If writable control is lost, keep the pane visible, reset into observer mode, and show **Take control**.

### Terminal behavior

- V1 supports ANSI/VT text, Unicode including emoji/CJK/combining/wide cells, truecolor, alternate screen, bracketed paste, common mouse modes, Gboard composition, and hardware keyboards.
- The measured phone grid is authoritative only while Scoutr controls the pane. Initial control, rotation, IME, insets, and font changes debounce to the latest `cols`/`rows`.
- Observers cannot resize; a viewport change restarts observation as a fresh reset generation with the latest grid.
- Android scrollback is local, bounded, selectable, copyable, and touch-scrollable for the active visit. Target roughly 10,000 rows, reducing only if measured Pixel memory requires it.
- Two fixed compact extra-key pages:
  - Page 1: Esc, Ctrl, Alt, Tab, and arrows.
  - Page 2: Home, End, PgUp, PgDn, Insert, Delete, and common shell symbols.
- Ctrl, Alt, and Shift are one-shot; long press locks them with a clear selected state. Extra-key visibility persists per connection.
- A writable terminal receives focus for hardware input on open, while the soft keyboard remains hidden until user action.
- Pinch changes one font size shared across panes for the saved connection.
- Ordinary single-line paste is immediate. Multiple lines or any control character require confirmation. Paste enters through the widget so bracketed-paste mode is honored.
- OSC title may update the compact top bar without animation, focus theft, or announcements.
- BEL gives a subtle haptic only while Terminal is visible, coalesced to at most once per second; no sound or notification.
- HTTP(S) links require an explicit tap and confirmation. Block OSC 52 clipboard access and unknown integrations.
- Screenshots, screen recording, and recent-app previews remain allowed.

### Transport and security

- `GET /api/snapshot` is the hierarchy source. Fetch on route entry, topology-feed reconnect, drawer open, and post-action reconciliation.
- One typed hierarchy command endpoint performs create/rename/close and returns a fresh authoritative snapshot.
- The existing `/ws` feed is consumed with bearer-header auth only while Terminal is visible. Topology events are invalidations; Android refreshes the snapshot rather than maintaining a second partial catalog.
- `/ws/terminal` carries terminal traffic only. Binary WebSocket messages are raw terminal bytes in both directions; JSON text messages carry hello intent, ready/generation, resize, ownership, release, closed, and error state. Takeover is a replacement socket whose `hello.intent` is `takeover`, never an in-place message.
- `/ws/terminal` requires `Authorization: Bearer …` on upgrade and rejects query-token authentication. Do not copy the existing `/ws` query-token allowance.
- Bound every input, output, NDJSON record, and WebSocket queue. Disconnect a persistently slow client and recover through a fresh generation; never silently drop bytes inside a live generation or buffer without limit.
- Never log terminal content, input, tokens, or clipboard contents.

### Consequential rejected alternatives

- `pane.read`/Live Output is not an interactive terminal and is deleted rather than retained as fallback.
- xterm.js/WebView is neither spike nor fallback. The spike uses bridge contract tests and a tiny CLI client, then production goes directly to native Termux adaptation.
- ConnectBot Terminal remains a researched alternative but lacks the required demonstrated paste/IME/mouse behavior.
- Stock Termux `TerminalSession` is not used because it creates a local Android PTY through JNI.
- node-pty, SSH terminal, or Mosh would create a second process/terminal ownership model and are out of scope.

## Approach

### End-to-end flow

1. Terminal opens and fetches health/capability plus `/api/snapshot`.
2. `TerminalViewModel` resolves the initial pane. The native view reports its measured grid.
3. `TerminalSocketClient` opens `/ws/terminal` with a bearer header and sends a versioned `hello` naming pane and grid.
4. The bridge validates the pane against a fresh Herdr snapshot and asks `TerminalSessionBroker` to open control without takeover.
5. If control is free, launch `herdr terminal session control`; on the verified ownership-conflict result only, launch `observe`. Confirmed takeover launches control with `--takeover`.
6. The launcher parses bounded NDJSON, validates base64, and emits raw bytes plus typed lifecycle records. The bridge sends `ready` with a new generation before that generation’s first binary message.
7. Android resets/replaces emulator state on each new generation, then feeds binary messages directly into the remote Termux session. Widget-produced bytes return only while writable.
8. Controller resize sends the latest debounced grid. Observer viewport changes restart observation and reset the emulator generation.
9. Route-scoped topology feed events invalidate and refresh `/api/snapshot`; hierarchy actions use HTTP and return their own fresh snapshot.
10. Explicit exit sends release. Unexpected disconnect starts bridge grace. Resume ends the retained child and opens a new generation for current-screen replay; if reacquisition loses a race, fall back to observe.

### Bridge terminal process module

Create `bridge/src/terminal/process.ts` around `node:child_process.spawn`, independent of `HerdrPort`:

```ts
type TerminalMode = "control" | "observe";

type TerminalRecord =
  | { type: "bytes"; bytes: Buffer }
  | { type: "closed"; reason?: string }
  | { type: "error"; code: string; message: string };

interface TerminalProcess {
  readonly mode: TerminalMode;
  sendInput(bytes: Buffer): boolean;
  resize(cols: number, rows: number): boolean;
  pauseOutput(): void;
  resumeOutput(): void;
  release(): Promise<void>;
  onRecord(listener: (record: TerminalRecord) => void): () => void;
}

interface TerminalLauncher {
  probe(target?: string): Promise<TerminalCapability>;
  open(options: {
    target: string;
    mode: TerminalMode;
    takeover: boolean;
    cols: number;
    rows: number;
  }): Promise<TerminalProcess>;
}
```

The production adapter alone knows the CLI command and NDJSON vocabulary. Tests use `FakeTerminalLauncher`; do not add terminal-process methods to `HerdrPort`.

### Bridge session broker

Create `bridge/src/terminal/broker.ts` as the only owner of active mobile sessions and grace timers. Hide behind its interface:

- automatic control → observer fallback;
- explicit takeover;
- one active mobile stream per bearer identity;
- generation allocation;
- same-token socket replacement and grace resume;
- discard-without-retention while detached;
- ownership loss and observer restart;
- controller release and server shutdown;
- high/low-water backpressure and slow-client timeout.

`server.ts` and the WebSocket adapter must not duplicate this state machine.

### Android transport modules

Create `net/TerminalSocketClient.kt` behind a small `TerminalTransport` interface and a fake shared by unit/instrumentation tests. It owns OkHttp callback containment, opcode classification, bearer-header auth, queue checks, and cancellation. It never exposes OkHttp callbacks directly to a ViewModel.

Create a route-scoped topology client for `/ws` rather than expanding the short-lived `BridgeClient.sendCommandJson` helper. It authenticates by header, subscribes to topology kinds, converts abrupt EOF/failure into typed state, snapshots on reconnect, and runs only while Terminal is started.

### Native remote session

Vendor the pinned Termux modules and introduce the smallest transport-neutral session interface needed by `TerminalView`. `RemoteTerminalSession` owns `TerminalEmulator`, local scrollback, current terminal modes, input callback, title/BEL/link/clipboard callbacks, and reset-by-generation. It never constructs a subprocess, file descriptor, or JNI PTY.

`TerminalViewModel` owns route state and the pure remote session, not the Android `View`. `TerminalScreen` attaches the view with `AndroidView`, preserving the emulator through recomposition and configuration changes until generation changes.

## Important contracts

### Capability gate

Capability is a state, not a Boolean:

```ts
type TerminalCapability =
  | { status: "supported"; herdrVersion: string; protocol: number }
  | { status: "unverified"; herdrVersion: string; protocol: number; reason: "no-pane" }
  | { status: "unsupported"; installedVersion?: string; required: string; reason: string };
```

Rules:

1. Resolve the exact executable used by the launcher (`HERDR_BIN` when set, otherwise PATH) and run bounded version/help checks.
2. Initial support set is Herdr 0.8.0 / protocol 19. Adding another version requires recorded contract evidence, not a guessed semver range.
3. Verify both `terminal session control` and `observe` command surfaces.
4. If a snapshot has a pane, complete a bounded observer handshake and release without taking ownership.
5. With no pane, return `unverified/no-pane`; allow empty hierarchy and creation, but complete the exact handshake before first stream readiness.
6. Missing commands, unverified version/protocol, incompatible records, or failed handshake produce `unsupported` with installed/required detail. No fallback.
7. Run the bounded probe during bridge startup or first demand and cache terminal capability for that bridge process. `supported` and `unsupported` are stable. `unverified/no-pane` is provisional: once `/api/snapshot` shows a pane, the next `/ws/terminal` upgrade reruns a bounded observer handshake against the first pane in fresh snapshot order before sending HTTP 101; atomically replace the cache with `supported` or `unsupported`. A failed probe returns an actionable non-101 response. The later `hello` may select any pane; the arbitrary probe pane only establishes capability. Health always returns the current cache entry.

### Terminal WebSocket v1

Path: `/ws/terminal`

Upgrade rules:

- exact path;
- bearer Authorization header required;
- query token rejected even when correct;
- bearer token is identity; Origin is not;
- unsupported capability rejects before a child process exists.

Client JSON text messages:

```json
{"type":"hello","version":1,"paneId":"w1:p1","cols":80,"rows":24,"intent":"auto"}
{"type":"hello","version":1,"paneId":"w1:p1","cols":100,"rows":30,"intent":"takeover"}
{"type":"resize","cols":100,"rows":30}
{"type":"release"}
```

Server JSON text messages:

```json
{"type":"ready","version":1,"generation":7,"paneId":"w1:p1","mode":"control","cols":80,"rows":24,"reset":true}
{"type":"ownership","generation":7,"mode":"observe","canTakeover":true}
{"type":"closed","generation":7,"reason":"pane_closed"}
{"type":"error","code":"slow_client","message":"Terminal connection could not keep up","retryable":true}
```

Binary messages:

- server → Android: raw bytes decoded from a Herdr `terminal.frame` record;
- Android → server: exact widget-generated input bytes.

Invariants:

- `hello` is first and accepted once; `intent` is `auto` or `takeover`, and Android may send `takeover` only after the named confirmation.
- Bound positive grid dimensions, pane ID, JSON size, and binary input size before process launch/write.
- Each WebSocket carries exactly one generation. `ready(reset=true)` precedes its first binary message. Any mode/pane/reset change ends that socket: the server sends `closed` with a stable reason when possible, detaches process listeners, and closes; Android cancels the old call, then opens a new socket with a new `hello` (`intent:"takeover"` only after confirmation). Android tags callbacks by socket instance and ignores replaced-socket callbacks. This preserves raw untagged binary frames without stale-generation ambiguity.
- Opcode determines meaning; no discriminator byte or WebSocket base64.
- Resize is latest-value/coalesced. Input is ordered and never replayed after reconnect.
- Binary input is valid only after this socket's writable `ready`. A confirmed takeover uses a replacement socket whose `hello.intent` is `takeover`; there is no in-place generation switch.
- Malformed JSON, unknown message type, oversized frame, or invalid transition closes with a stable protocol error.

### Herdr NDJSON adapter

The process spike must capture the exact installed contract; do not infer it from `herdr-schema.json`.

Documented minimum:

- stdout: NDJSON `terminal.frame` with base64 ANSI bytes and `terminal.closed`;
- stdin: NDJSON `terminal.input`, `terminal.resize`, and `terminal.release`;
- control owns input/resize; observe has neither and is launched with a fixed grid.

Parser invariants:

- handle arbitrary chunk boundaries and multiple records per chunk;
- bound line and decoded-frame sizes before allocation/decoding;
- reject invalid JSON/base64, unexpected required record shape, stderr startup failure, non-zero exit, and handshake timeout as typed errors;
- never decode terminal bytes to text in the bridge;
- make release idempotent and bounded, escalating to kill only after timeout.

### Hierarchy HTTP

Keep `GET /api/snapshot`. Add `POST /api/terminal/hierarchy` with a discriminated body:

```ts
type TerminalHierarchyCommand =
  | { operation: "create_tab"; workspaceId: string; selectedPaneId?: string }
  | { operation: "create_workspace"; cwd: string; label?: string; selectedPaneId?: string }
  | { operation: "rename_pane"; paneId: string; label: string; selectedPaneId?: string }
  | { operation: "rename_tab"; tabId: string; label: string; selectedPaneId?: string }
  | { operation: "rename_workspace"; workspaceId: string; label: string; selectedPaneId?: string }
  | { operation: "close_pane"; paneId: string; selectedPaneId?: string }
  | { operation: "close_tab"; tabId: string; selectedPaneId?: string; expectedPaneCount: number }
  | { operation: "close_workspace"; workspaceId: string; selectedPaneId?: string; expectedPaneCount: number };

type TerminalHierarchyResponse = {
  ok: true;
  selectedPaneId: string | null;
  snapshot: SessionSnapshot;
};
```

Rules:

- Validate IDs, labels, cwd, and operation-specific fields before Herdr calls.
- Snapshot immediately before destructive validation and after every mutation.
- Tab/workspace count mismatch returns 409 with current name/count; Android refreshes and asks again.
- Resolve create-tab cwd server-side using the settled precedence, and pass `focus:false`.
- Use `resolveAllowedDir` for New workspace so the directory contract matches the existing picker.
- Return created root pane when available; otherwise resolve selection from the fresh snapshot.
- `selectedPaneId` is the Android selection at tap/confirmation time, not broker state. Validate it against the pre-close snapshot; if missing or already stale, use no active selection and return the first pane in the fresh catalog. Otherwise compute post-close selection from pre-close order plus fresh snapshot: preserve it for unrelated closures; for active pane use same old tab, old workspace, then global; for active tab use next/previous surviving tab in old workspace, then global; for active workspace use next/previous surviving workspace. Within each surviving scope choose its first pane by catalog order.
- Extend `HerdrPort`, `HerdrClient`, and `FakeHerdr` only with missing tab create/rename/close and pane rename/close one-shot operations.

### Android connection state

```kotlin
sealed interface TerminalConnectionState {
    data object Idle : TerminalConnectionState
    data object Connecting : TerminalConnectionState
    data class Ready(val generation: Long, val writable: Boolean) : TerminalConnectionState
    data class Reconnecting(val frozenGeneration: Long?) : TerminalConnectionState
    data class Unsupported(val explanation: String) : TerminalConnectionState
    data class Failed(val message: String, val retryable: Boolean) : TerminalConnectionState
    data object Closed : TerminalConnectionState
}
```

Rules:

- No input leaves Android unless state is `Ready(writable = true)`.
- Reconnecting freezes the emulator; no keystroke or paste queue exists.
- A new generation resets/replaces emulator state before bytes are fed.
- Topology/transport errors appear in chrome or screen state, never terminal bytes.
- Keep only the current user-facing error. Structured bridge logs may include pane ID, generation, transition, reason, queue measurements, and duration, but never content/input/token/clipboard data.

### Preferences

Create `TerminalPreferencesStore`, keyed by SHA-256 over `<canonical-base-url>\n<token>` so no raw credential appears in a preference key. Canonicalize with OkHttp `HttpUrl`: lowercase scheme/host, remove trailing path slash, omit the default port, reject query/fragment/user-info, and keep non-default port plus any non-root path. Equivalent URL spellings must hash together; a different token remains a different saved connection. Store only:

- last selected pane ID;
- terminal font size;
- extra-key row visibility.

Preferences are shared across panes/visits for one saved connection and reset naturally on a different connection. Do not add themes, key layouts, queue budgets, or scrollback settings.

### Android Back

Consume Back in order:

1. confirmation or selection UI;
2. hierarchy drawer;
3. soft keyboard;
4. route exit and immediate release.

Back is never terminal Escape. Escape is an explicit extra/hardware key.

## Changes

Implement as small green commits on `main`. The first commit intentionally removes Live Output before Terminal exists; the temporary regression is explicitly accepted.

| Step | Files / symbols | Change and side effects | Proof |
|---|---|---|---|
| 1. Delete Live Output | Delete `bridge/src/live-output.ts`, `bridge/test/live-output.test.ts`, Android `LiveOutputViewModel.kt`, `LiveOutputPanel.kt`, `LiveOutputViewModelTest.kt`, and `LiveOutputScreenTest.kt`. Edit `routes/agents.ts`, `routes/types.ts` route examples, route/server/integration tests, `Models.kt`, `ScoutrApi.kt`, `BridgeClient.kt`, `FakeScoutrApi.kt`, `MainActivity.kt`, `ChatScreen.kt`, and `ChatControlsTest.kt`. | Remove route, poll, DTOs, screen, wording, and fallback. Preserve unrelated `agent.read`, including review behavior. | `rg -i 'live.?output|/api/agents/.*/read' bridge android` has no feature references; bridge and Android unit gates pass. |
| 2. Prove Herdr controller | Create `bridge/src/terminal/process.ts`, capability probe/types, `bridge/test/terminal-process.test.ts`, and `bridge/test/support/fake-terminal.ts`. Wire launcher dependency from `cli.ts`; add a bounded spike command/script. | Establish exact process seam, parser, timeout, release, ownership-conflict classification, unsupported reason, phone-sized observation, and ownership-preserving grace replacement before network/UI work. | Fragmented parser/failure tests; against disposable panes, prove replay, input/resize/release, observer projection, conflict classification, then prove both grace phases: (a) simulate unexpected mobile disconnect while keeping controller stdin/process alive, discard output for 30 seconds, and verify a second non-takeover controller still conflicts; (b) close listeners/controller, immediately reopen without takeover, verify fresh current-screen replay, and record whether a contender can win. If these semantics cannot be defended, stop and revise ownership before Step 3. No child remains. |
| 3. Add terminal WebSocket | Create `bridge/src/terminal/broker.ts`, `websocket.ts`, protocol types/tests, and `bridge/scripts/terminal-client.mts`. Route upgrade separately in `server.ts`; include terminal teardown in `ScoutrServer.close`; extend health capability. | Add header-only binary transport, one-socket-per-generation resets, ownership fallback/takeover, 30-second grace, queue bounds, and structured logs. Existing `/ws` commands remain unchanged. | Contract tests cover auth, opcodes/order, old-socket callback suppression before new readiness, input, resize, observer fallback, takeover, same-token same/different-pane replacement, grace expiry, slow-client disconnect, malformed/oversized frames, pane close, and shutdown. Tiny client controls one real pane. |
| 4. Add hierarchy management | Extend `HerdrPort`, `HerdrClient`, `FakeHerdr`; create `bridge/src/routes/terminal.ts` and tests; register in `routes/index.ts`. Add Android snapshot/hierarchy DTOs and `ScoutrApi`/`BridgeClient` methods plus fake support. | Add create tab/workspace and rename/close pane/tab/workspace, stale-count protection, fresh reconciliation, and deterministic selection. | Route tests assert exact Herdr params, `focus:false`, cwd fallback, 400/404/409 mapping, counts, created-root selection, and fresh snapshots. |
| 5. Vendor/adapt Termux | Add `android/vendor/termux/terminal-emulator`, `terminal-view`, module Gradle files, `UPSTREAM.md`, Apache-2.0 license/notices, settings inclusions, and app dependencies. Add app `terminal/RemoteTerminalSession.kt`. | Pin commit `3df69d1da197dd9bd71a3bafd902dffd720576b4`; retain `com.termux` provenance; omit local PTY/JNI process paths; adapt the view seam to remote bytes. No `termux-shared` or Termux app code. | Provenance audit, Gradle dependency report, no PTY/JNI load path, x86_64 managed-device class load, and retained emulator tests for ANSI/Unicode/modes/scrollback. |
| 6. Add Android transport/state | Create terminal socket/topology clients, interfaces/fakes, protocol DTOs, `TerminalPreferencesStore`, `TerminalViewModel`, and tests. Wire through `ScoutrApp.AppContainer`. | Add header-auth sockets, lifecycle containment, reset/reconnect, one active pane, topology invalidation, management reconciliation, preferences, controller resize, observer restart, and no-input-queue behavior. | Unit/Robolectric tests cover all states, stale generations, callback cancellation, background grace, release, ownership loss, pane disappearance, preference isolation, and abrupt OkHttp close without escaped exceptions. |
| 7. Build Terminal UX | Add `TerminalScreen.kt` and focused terminal UI files. Add route/navigation in `MainActivity.kt`; extend `AppTopBar.kt`/`TabScaffold.kt`; replace Chat action. Add drawer/search, create/rename/close flows, takeover dialog, top bar, empty/unsupported states, `AndroidView`, extra keys, paste/link confirmation, and BEL haptic. | Terminal becomes app-level/contextual; bottom nav hides; drawer overlays without resize; all settled interaction rules apply. | Compose/Robolectric/instrumentation tests assert entry, selection, drawer, management confirmations, exceptional states, Back order, grid stability, input bytes, paste policy, title/BEL, and no edge swipe. Inspect screenshots directly. |
| 8. Harden and set budgets | Add deterministic terminal traces/fixtures, benchmark scripts/tests, measured queue constants, scrollback evidence, lifecycle soak tests, and final docs. | Convert provisional finite bounds into measured production limits; validate TUIs and failure paths. | Acceptance and performance gates below pass; simplify, independent review, `scripts/verify.sh`, and final physical Pixel walk pass. |

Suggested commit subjects:

1. `Remove live output`
2. `Add Herdr terminal controller`
3. `Add terminal WebSocket`
4. `Add terminal hierarchy API`
5. `Vendor Termux terminal core`
6. `Add Android terminal transport`
7. `Add full-screen terminal`
8. `Harden terminal performance and recovery`

Keep vendored-source/provenance review separate from transport and UI changes.

## Failure handling

### Capability/startup

- Missing executable, unsupported version/protocol, missing command, handshake timeout, or unexpected NDJSON maps to `Unsupported` with installed/required detail and remediation. Terminal entry stays visible.
- No panes is an empty state, not unsupported. Allow creation and validate before first readiness.
- Every capability child is bounded. A hung child is killed; bridge startup cannot hang.

### Authentication/protocol

- Unauthorized upgrades return 401 and create no child.
- Query tokens on `/ws/terminal` return 401.
- Invalid hello/version/pane/grid or oversized JSON/binary closes with a stable protocol error and structured log.
- Binary input while observing/reconnecting is rejected and never buffered.

### Process/ownership

- Only the verified ownership-conflict result falls back to observe; other startup errors remain errors.
- Confirmed takeover resets Android to the new generation.
- Controller closure while pane exists attempts observer fallback; pane disappearance emits `closed/pane_closed`.
- Release is idempotent. Server shutdown releases children and cancels grace timers.
- Grace retains no terminal bytes. Resume closes the old WebSocket/process listeners, ends the retained controller, and opens a new socket/generation only after teardown. The Step 2 experiment must prove that fresh replay/reacquisition is defensible; otherwise this contract is an escalation, not an implementation assumption. Observe if reacquisition is no longer free.

### Backpressure

- Bound NDJSON line, decoded frame, client input, process stdin, bridge WebSocket outbound, and Android OkHttp outbound queues.
- Pause child stdout at measured high-water and resume below low-water. On persistent pressure, close `slow_client` and recover by fresh generation.
- Never drop arbitrary bytes within a generation, append replay to stale emulator state, or retain unbounded buffers.

### Topology/management

- Treat feed events as invalidations. Failed refresh preserves last catalog with current error and retries while visible.
- Management failure leaves selection unchanged and stays out of terminal bytes.
- Active-pane disappearance stops input immediately, refreshes, selects fallback, and shows **Pane closed**.
- Count mismatch returns 409 and requires reconfirmation.

### Android lifecycle

- Background/socket failure closes Android without explicit release, allowing bridge grace. Route exit/pane switch sends release.
- No retry job, feed collection, WebSocket callback, or view reference survives route exit/`onCleared`.
- OkHttp EOF/failure becomes typed state; no exception escapes a collector.
- Reconnect uses bounded exponential backoff with jitter while visible and cancels on stop.

### Renderer/integrations

- Preserve terminal bytes; bridge and Android transport do not normalize UTF-8.
- Reject OSC 52 and unknown integrations in remote-session callbacks.
- Allow-list HTTP(S) links and open only after confirmation.
- Risky paste shows bounded preview/line count without logging clipboard content.

## Validation

### Fast gates

Bridge:

```bash
cd bridge && npm run typecheck && npm test
```

Android, one Gradle process at a time:

```bash
cd android && timeout 300 env ANDROID_HOME="$HOME/Android/sdk" ./gradlew testDebugUnitTest
cd android && timeout 300 env ANDROID_HOME="$HOME/Android/sdk" ./gradlew pixel2api36DebugAndroidTest
cd android && timeout 300 env ANDROID_HOME="$HOME/Android/sdk" ./gradlew assembleDebug
```

Use focused classes while iterating; run broad gates once after material work stabilizes.

### Bridge contract matrix

- Verified and unsupported Herdr version/command/handshake paths.
- Observe/control replay, fragmented/multiple records, input, resize, release, exit, stderr, timeout, invalid JSON/base64, and bounds.
- Header auth success; missing/wrong/query-token failure.
- Hello first/once, version, pane/grid validation, opcode classification, generation ordering, stale suppression.
- Auto-control, observe fallback, takeover, ownership loss, same-token replacement/grace resume, grace expiry, explicit release, switch, and pane close.
- Backpressure pause/resume and slow-client disconnect without unbounded retained bytes.
- Hierarchy create/rename/close, cwd fallback, stale-count 409, deterministic selection, and fresh responses.
- `createScoutrServer.close()` leaves no terminal child, WebSocket, timer, or listener.

### Android functional matrix

On managed Pixel 2 API 36 x86_64:

- App-level entry from every primary tab; Chat contextual entry opens the correct pane.
- Last-valid → Herdr-focused → selector resolution.
- Empty, loading, unsupported, connecting, writable, observing, reconnecting, offline, failed, and pane-closed states.
- Drawer overlay keeps columns stable; button opens; edge swipe does not.
- Search/collapse at small, typical, and large hierarchy sizes.
- New tab/workspace; rename/close all levels; exact counts and 409 reconfirmation.
- Gboard composition/deletion/autocorrect, keyboard show/hide, and hardware text.
- Modifiers and all fixed extra keys/pages.
- ASCII, emoji, CJK, combining/wide cells, truecolor, cursor, alternate screen, bracketed paste, and mouse modes.
- vim/neovim, htop, pi, and one tmux-like TUI.
- Selection/copy/touch scroll over bounded local history.
- Safe paste immediate; risky paste gated; cancellation sends zero bytes.
- Rotation, IME, insets, and pinch produce latest-only controller resize; observer changes reset generation.
- OSC title, coalesced foreground BEL haptic, safe-link confirmation, and blocked OSC 52.
- Background under/over 30 seconds, network loss, bridge/Herdr restart, remote takeover, and remote pane close.
- Back ordering and explicit release.
- Screenshot/recording/recent preview remain enabled.

### Performance gates

Measure direct Herdr attach and bridge/native baselines before fixing queue and memory constants.

Production acceptance on the managed Pixel fixture:

- p95 input echo/render latency adds no more than **50 ms** over direct Herdr attach under the same network conditions.
- Sustained output causes no ANRs, unbounded Node/Android queue growth, stale-generation corruption, or byte drops inside a live generation.
- Ordinary shell/TUI use stays within the device smooth-rendering budget; flood may disconnect but must not freeze the app.
- Memory reaches a stable plateau at the selected scrollback cap. Start near 10,000 rows and reduce only from measured Pixel evidence.
- Reconnect cleanly resets to current screen within the measured retry/replay budget and never replays input.
- High/low water marks and slow-client timeout are finite constants backed by recorded benchmark evidence.

### Final acceptance

```bash
scripts/verify.sh
```

Then:

1. install the debug APK on the managed/running emulator and capture screenshots for writable, observing, reconnecting, unsupported, drawer, and risky-paste states;
2. inspect those screenshots using the repository vision workflow;
3. perform one final physical Pixel integration walk for connect, input, IME, resize, reconnect, takeover, and release—without instrumentation on that phone;
4. run the repository-mandated fresh code review and resolve/dismiss every concrete finding.

## Local discretion

The implementer may choose:

- internal filenames below the named feature directories;
- bounded retry/backoff values before performance hardening;
- exact HTTP response DTO field grouping while preserving the command semantics and fresh snapshot;
- how drawer collapse/search state is represented;
- exact fixed key-row symbol set and compact spacing consistent with the two settled pages;
- how upstream Termux tests are organized after vendoring;
- benchmark artifact format and fixture location.

Local choices must not alter ownership, auth, generation/reset, one-stream, no-input-queue, hierarchy, renderer, security, or acceptance contracts.

## Escalation triggers

Stop and report evidence before deviating if:

- Herdr 0.8.0 controller records or ownership behavior differ from documented behavior.
- Fresh current-screen replay cannot be obtained after replacing a grace-held controller without stealing ownership.
- The Termux adaptation requires GPL-only app/shared code, a local PTY, native JNI at runtime, or broad rewrites outside the two Apache modules.
- Raw binary WebSocket transport cannot pass the required proxy/Tailscale path with Authorization-header auth.
- Supporting one active mobile client still requires unresolved multi-writer arbitration.
- The managed x86_64 device cannot load/render the vendored subset without native translation.
- Meeting latency/memory/flow-control gates requires changing the product semantics, dropping terminal bytes, or reducing the settled compatibility matrix.
- A required Android lifecycle fix would reintroduce a global always-on WebSocket.

A deviation report must include observed evidence, attempted paths, affected contract, recommended decision, and needed user input.

## Review handoff

An independent reviewer must verify:

- Live Output is fully deleted with no fallback or stale wording.
- Terminal process behavior is behind its own launcher fake, not mirrored onto `HerdrPort`.
- `/ws/terminal` is separately routed, header-only, binary-safe, bounded, generation-safe, and included in teardown.
- Broker ownership/grace has one source of truth and never queues input or terminal content.
- Hierarchy commands call the exact Herdr operations, preserve desktop focus, validate destructive counts, and return fresh snapshots.
- Vendored files match the pinned commit except documented adaptations; license headers/notices remain; PTY/JNI paths are absent.
- Android callbacks cannot escape lifecycle cancellation, and new generations reset before bytes.
- UI follows the design system, one-pane topology, drawer/grid, Back, paste, title, BEL, and exceptional-state contracts.
- Performance claims are supported by measured artifacts rather than constants chosen in advance.
- All required commands and device evidence pass serially.

## Completion checklist

- [ ] Live Output code, endpoint, tests, DTOs, and wording removed first.
- [ ] Exact Herdr controller, observer projection, ownership conflict, and grace replacement contracts captured; capability gate implemented.
- [ ] Terminal launcher fake, broker, WebSocket protocol, auth, bounds, grace, and teardown covered.
- [ ] Hierarchy snapshot and all settled management operations covered.
- [ ] Termux subset pinned, licensed, adapted, and free of runtime PTY/JNI.
- [ ] Android transport, topology feed, preferences, generation state, and lifecycle covered.
- [ ] Full-screen one-pane UX and all settled interactions covered.
- [ ] Functional and failure matrices pass on managed Pixel.
- [ ] Baselines and production budgets recorded; performance gates pass.
- [ ] `scripts/verify.sh`, screenshot inspection, physical Pixel walk, simplify, and independent review complete.

## References

- `docs/research/full-screen-interactive-terminal.md`
- `docs/adr/0001-project-herdr-terminals-through-a-dedicated-websocket.md`
- `docs/adr/0002-vendor-the-termux-terminal-core.md`
- `bridge/reference/herdr-schema.json`
- `bridge/src/herdr/client.ts`
- `bridge/src/herdr/port.ts`
- `bridge/src/herdr/feed.ts`
- `bridge/src/server.ts`
- `bridge/src/routes/health.ts`
- `bridge/src/routes/agents.ts`
- `bridge/test/support/fake-herdr.ts`
- `android/app/src/main/java/dev/scoutr/app/MainActivity.kt`
- `android/app/src/main/java/dev/scoutr/app/ScoutrApp.kt`
- `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt`
- `android/app/src/main/java/dev/scoutr/app/net/ScoutrApi.kt`
- `android/app/src/main/java/dev/scoutr/app/data/ConnectionStore.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/components/AppTopBar.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/nav/TabScaffold.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/SessionPickers.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/motion/Haptics.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- `docs/dev-workflow.md`
- `scripts/verify.sh`
- [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
- [Termux app source and licensing](https://github.com/termux/termux-app)
- [Compose `AndroidView`](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)
- [RFC 6455](https://datatracker.ietf.org/doc/html/rfc6455)
