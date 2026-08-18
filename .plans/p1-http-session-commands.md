# HTTP Session Commands Blueprint

## Current situation

Scoutr currently uses `/ws` for two unrelated concerns:

- long-lived Herdr topology/event streaming;
- one-shot command request/response operations (`steer`, `answer_ask`, `dismiss_ask`, `slash_command`, `send_text`).

Android implements one-shot commands in `BridgeClient.sendCommandJson`: open socket, send one frame, ignore feed frames, wait for one ack, close. The bridge parses/validates/executes those command messages through `handleCommand` in `bridge/src/commands.ts` from `server.ts`'s `/ws` message handler.

The command semantics themselves are already centralized and well tested independently of the socket. Session control actions (`abort`, `compact`, `close`, model/thinking changes, etc.) already use ordinary HTTP routes under `bridge/src/routes/sessions.ts`, providing the precedent for authenticated one-shot session mutations.

The P0 WebSocket hardening plan keeps the current path safe until this migration lands. The API protocol blueprint provides a place to advertise `commands.http.v1` without guessing endpoint support.

## Objective and why

Move one-shot agent/session commands to authenticated HTTP POST endpoints and reserve WebSockets for actual streaming surfaces.

Done means current Android command callers use HTTP with ordinary status/error/cancellation behavior; command validation and backend semantics have one implementation; `/ws` remains the topology feed; and old installed APKs can continue using the legacy WS command vocabulary during the rollout window.

## Scope

Included:

- HTTP endpoints for steer, slash command, ask answer/dismiss, and raw single-line `send_text` where still used;
- extraction/reuse of command validation/execution so HTTP and compatibility WS paths do not fork semantics;
- typed Android `ScoutrApi` methods backed by normal `BridgeClient.call` requests;
- feature/protocol capability advertisement;
- deprecation of one-shot Android WS command helpers;
- compatibility tests for legacy WS callers during rollout.

Non-goals:

- no change to `/ws/terminal`;
- no replacement of topology streaming;
- no change to backend-specific ask/TUI grammar;
- no session-identity redesign beyond consuming `SessionKey`/descriptor if Session Model v3 has already landed;
- no generic RPC framework.

Compatibility: retain the existing WS command handler on the bridge for the installed-APK rollout window because bridge and APK deployments are not atomic. New Android code must never choose it when `commands.http.v1` is available. Removal of the compatibility handler is a later protocol-breaking cleanup, not a hidden part of this change.

## Global constraints

- Route auth/body/error mapping stays in `bridge/src/routes/dispatcher.ts` and feature routes.
- Agent question answers remain intent (`questionId`, `selectedLabels`, `text`); adapters still own questionnaire keystrokes.
- The same validation limits currently enforced in `bridge/src/commands.ts` remain load-bearing.
- Android ViewModels continue calling `ScoutrApi`, not constructing HTTP requests directly.
- Do not create another long-lived connection or background retry queue for mutations.
- Final integration/emulator acceptance is after review-clean/code-freeze only.

## Resolved decisions

### Endpoint shape

Use resource/action HTTP routes under sessions rather than a generic `/api/command` RPC endpoint:

```text
POST /api/sessions/:paneId/steer
POST /api/sessions/:paneId/slash-command
POST /api/sessions/:paneId/send-text
POST /api/sessions/:paneId/asks/:callId/answer
POST /api/sessions/:paneId/asks/dismiss
```

For a plain blocked prompt with no `callId`, use the answer endpoint on the session itself or a documented sentinel route chosen consistently; do not encode an empty id as an ambiguous path segment. If Session Model v3 has landed, the public route may use canonical session identity and resolve the live pane internally; the behavioral contract remains the same.

Request bodies are typed JSON. Successful mutation responses use ordinary `{ ok: true, ... }` route bodies; invalid input maps to 400, missing/stale live pane to 404/409 as appropriate, auth to 401, and backend failures preserve deliberate `BridgeError` status where one exists.

### Shared command semantics

Refactor `bridge/src/commands.ts` so validation/execution functions are callable from both HTTP routes and the legacy WS adapter. The socket-specific `CommandMessage`/`CommandResult` wrapper may remain for compatibility, but it must become a thin adapter around shared functions.

Example shared operations:

```ts
steerSession(deps, paneId, text)
answerSessionAsk(deps, request)
dismissSessionAsk(deps, paneId)
runSlashCommand(deps, paneId, text)
sendSessionText(deps, paneId, text)
```

Do not duplicate input limits or ask validation in route modules.

### Android migration

`ScoutrApi.steer`, `runSlashCommand`, `answerAsk`, and `dismissAsk` remain typed methods but become ordinary HTTP implementation calls. Remove generic `sendCommand`/`sendCommandJson` from the production API if no non-test caller remains. The topology feed retains its own `TopologyFeedClient`.

### Rollout capability

Advertise `commands.http.v1` in health `api.features`. The new app requires it for these operations. Legacy bridge compatibility is governed by the P0 protocol plan; do not silently fall back to WS from new code because that masks deployment mismatches.

## Approach

First separate command semantics from WebSocket framing. Then add thin route handlers that parse bodies/path params and call those operations. Migrate Android's typed API implementation to HTTP. Finally reduce `/ws` to feed subscription plus the legacy command compatibility switch, clearly marked and tested as such.

## Contracts and interfaces

### HTTP bodies

Steer:

```json
{ "text": "multi-line prompt allowed" }
```

Slash command:

```json
{ "text": "/compact" }
```

Send text:

```json
{ "text": "single line" }
```

Ask answer:

```json
{
  "answers": [
    { "questionId": "toolu_1#0", "text": "", "selectedLabels": ["Yes"] }
  ],
  "text": ""
}
```

Plain-prompt answer uses the same normalized answer request without an authored ask group, routed unambiguously.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | existing `handleCommand` semantics | shared command operation functions |
| 2 | shared operations | authenticated HTTP routes |
| 3 | HTTP routes | `BridgeClient` typed mutation methods |
| 4 | HTTP client | ViewModels/receiver using HTTP without transport awareness |
| 5 | new app path | legacy WS compatibility adapter only |

## Changes

- [x] **1 — Extract transport-independent command operations**
  - Anchor: `bridge/src/commands.ts` → `handleCommand`, `validateSlashCommand`, `answerDeps`
  - Move each command's validation and execution into named functions with deliberate bridge error mapping.
  - Keep `handleCommand` as a legacy WS adapter that parses its message shape and calls the shared operations.
  - Preserve all current text/length/control-character/ask bounds exactly unless a separate bug is proven.
  - Proof: existing command unit tests plus new direct-operation tests pass without a socket.

- [x] **2 — Add feature-scoped HTTP command routes**
  - Anchor: `bridge/src/routes/sessions.ts` and/or a new `bridge/src/routes/session-commands.ts` registered in `routes/index.ts`
  - Implement the route set with dispatcher-owned auth/body limits and route-level JSON parsing.
  - Map invalid body/path data to actionable 400 responses rather than generic WS error frames.
  - Stale/missing pane should return a stable 404/409 taxonomy compatible with other session control routes.
  - Add `commands.http.v1` to the health API feature list from the protocol blueprint.
  - Proof: offline bridge HTTP tests exercise every route, unauthorized requests, invalid bodies, backend error propagation, and a happy path via fake Herdr.

- [x] **3 — Migrate Android typed command methods to HTTP**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/net/ScoutrApi.kt` → `steer`, `runSlashCommand`, `answerAsk`, `dismissAsk`, generic command helpers
  - Anchor: `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt` → same implementations
  - Implement each through the existing `call` helper with JSON request bodies.
  - Preserve cancellation and `BridgeException(status, reason)` behavior.
  - Remove production generic WS command methods once no caller needs them; tests should not keep dead API alive.
  - Proof: MockWebServer tests assert method/path/body/auth and status error mapping for each operation.

- [x] **4 — Verify all command callers remain transport-agnostic**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt` → send/ask/slash reconciliation paths
  - Anchor: `android/app/src/main/java/dev/scoutr/app/service/NotificationReplyReceiver.kt` → inline reply steering
  - No caller should know whether command delivery is HTTP; keep current optimistic/reconciliation behavior unless a transport-specific workaround becomes unnecessary.
  - Remove any timeout/socket-specific UI copy made obsolete by the migration.
  - Proof: existing Chat and notification-reply unit tests pass unchanged or with only transport fixture updates.

- [x] **5 — Reduce `/ws` to streaming plus explicit legacy compatibility**
  - Anchor: `bridge/src/server.ts` → `wss.on("connection")` message handling
  - Keep `subscribe` as the live topology-feed command.
  - Keep legacy mutation frames only behind a clearly named compatibility path/comment tied to the current supported old APK/protocol; do not add new mutation verbs there.
  - Update `BridgeClientWsTest`: production one-shot mutation coverage moves to HTTP tests; any remaining WS test covers topology/subscription or explicitly legacy compatibility.
  - Proof: new Android command traffic performs no one-shot WebSocket upgrades; topology feed still reconnects/filters normally.

## Failure handling

- HTTP request cancellation cancels OkHttp call through existing `call` machinery.
- Non-2xx route response becomes `BridgeException` with bridge-provided reason.
- Ask delivery remains all-or-throw; no partial-success response is introduced.
- A pane that closes between lookup and action returns the backend/Herdr failure as a stable HTTP error; Android reconciliation must not fabricate success.
- New app + bridge missing `commands.http.v1`: compatibility handshake blocks/guides update rather than silently falling back.
- Old app + new bridge: legacy WS mutations remain functional during the rollout window.

## Validation

1. Focused bridge shared-operation tests.
2. Focused bridge route/server tests.
3. Focused Android HTTP transport tests using MockWebServer.
4. Existing Chat/ask/notification-reply unit tests.
5. `make bridge-test`.
6. `make android-test`.
7. Independent review with `skills/scoutr-review/SKILL.md`.
8. Final runtime acceptance via `skills/scoutr-verification/SKILL.md`: after code freeze, exercise steer, slash command, one structured ask, dismiss/plain prompt as available, and notification reply; confirm topology/terminal sockets remain intact.

## Local discretion

- Whether routes live beside `routes/sessions.ts` or in `routes/session-commands.ts`; choose the smaller coherent module.
- Exact successful response DTO names, provided callers do not depend on meaningless WS-style ack strings.
- Exact stale-pane choice between 404 and 409, provided it matches existing route taxonomy and is tested/documented.

## Escalation triggers

- A mutation genuinely needs server-to-client streaming before completion.
- Tailscale/proxy behavior prevents required HTTP request bodies or Authorization headers in the supported deployment.
- Removing generic `sendCommand` reveals a non-session use case not represented in this route set.
- HTTP and legacy WS paths begin needing different validation/semantics; stop rather than fork behavior.
- The implementation attempts automatic HTTP→WS fallback in new Android code.

## Review handoff

Trace each operation from Android typed method → HTTP route → shared command operation → Herdr/backend side effect. Verify there is exactly one validation implementation, no new app mutation uses `/ws`, and legacy WS support is isolated/documented rather than becoming a second evolving API.

Rerun both cheap suites after review fixes, then perform final runtime acceptance once.

## Completion checklist

- [x] Shared transport-independent command operations exist.
- [x] HTTP routes cover steer, slash, ask answer/dismiss, and required send-text/plain-prompt behavior.
- [x] New Android command methods use HTTP only.
- [x] Generic one-shot command WebSocket client is removed from production Android.
- [x] Topology and terminal WebSockets are unchanged in purpose.
- [x] Legacy installed APK WS command compatibility remains isolated for rollout.
- [x] `commands.http.v1` is advertised/required.
- [x] Bridge and Android cheap suites pass.
- [x] Independent review is clean.
- [x] Runtime acceptance passes once, last.

## References

- `AGENTS.md`
- `bridge/src/commands.ts`
- `bridge/src/server.ts`
- `bridge/src/routes/sessions.ts`
- `bridge/src/routes/dispatcher.ts`
- `bridge/src/routes/index.ts`
- `android/app/src/main/java/dev/scoutr/app/net/ScoutrApi.kt`
- `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt`
- `android/app/src/main/java/dev/scoutr/app/net/TopologyFeedClient.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/service/NotificationReplyReceiver.kt`
- `android/app/src/test/java/dev/scoutr/app/net/BridgeClientWsTest.kt`
- `.plans/p0-one-shot-command-ws-hardening.md`
- `.plans/p0-api-protocol-compatibility.md`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
