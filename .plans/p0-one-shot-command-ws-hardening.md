# One-shot Command WebSocket Hardening Blueprint

## Current situation

Scoutr's Android command path is a one-command-per-WebSocket transport implemented by `BridgeClient.sendCommandJson` in `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt`. It opens `/ws?token=...`, ignores feed frames, settles on the first non-feed ack/error frame, and cancels on coroutine cancellation. `android/app/src/test/java/dev/scoutr/app/net/BridgeClientWsTest.kt` is the existing real-transport seam.

Two gaps are verified in that path:

- a clean socket close before an ack does not settle the continuation, so the caller can wait indefinitely;
- the command client puts the bearer token in the WebSocket URL even though `TopologyFeedClient` already proves `/ws` works with `Authorization: Bearer ...`.

The bridge upgrade handler in `bridge/src/server.ts` currently allows both header auth and a query token for `/ws`. Do not remove query-token acceptance in this bounded safety patch: an older APK may still be talking to a newly deployed bridge. The later HTTP-command blueprint removes the one-shot command socket entirely.

Precedent: `TopologyFeedClient` owns header-authenticated `/ws` connection lifecycle and typed upgrade failure handling. Repository verification policy is in `AGENTS.md` and `skills/scoutr-verification/SKILL.md`.

## Objective and why

Make every one-shot command terminate deterministically and stop current Android builds from placing the pairing token in the command WebSocket URL.

Done means a command either returns its ack or fails within a bounded time when the socket closes, fails, or never acknowledges; the upgrade request carries the token only in the Authorization header.

## Scope

Included:

- Android one-shot `/ws` command lifecycle only;
- header authentication for that client;
- bounded ack wait;
- regression tests for close-before-ack, timeout, auth header, and existing ack/error behavior.

Non-goals:

- do not migrate commands to HTTP here;
- do not change terminal WebSockets;
- do not change topology-feed behavior;
- do not remove bridge query-token compatibility yet;
- do not add a new transport abstraction for code scheduled to disappear in the P1 HTTP-command change.

Compatibility: the bridge continues accepting existing query-token `/ws` clients. New Android builds stop sending it.

## Resolved decisions

- Keep the patch deliberately narrow: harden `BridgeClient.sendCommandJson`; no protocol redesign.
- Use `Authorization: Bearer <token>` on the WebSocket upgrade, matching `TopologyFeedClient`.
- Add an explicit command-ack timeout owned by `BridgeClient`, not OkHttp's HTTP read timeout; WebSocket message waits are not governed by the normal response-body read timeout.
- A socket that closes before a command reply is an `IOException`, even when the close code is 1000.
- Malformed frames may still be ignored while the socket remains open, but the timeout/close path must eventually settle the caller.
- If the P1 HTTP-command blueprint lands before this plan is implemented, satisfy this plan by removal of the one-shot command socket rather than recreating it.

## Approach

Wrap the existing one-command exchange in one bounded coroutine operation. Build the WebSocket request with the Authorization header and no token query parameter. Keep the existing atomic single-settlement guard. Add close handling that fails an unsettled command, and make timeout cancellation cancel the socket through the existing cancellation hook.

No bridge semantic changes are required; `/ws` already accepts Authorization headers.

## Changes

- [ ] **1 — Harden one-shot command lifecycle and authentication**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt` → `sendCommandJson`
  - Implement a named command timeout constant and bound the complete ack wait.
  - Build `/ws` without `?token=...`; send `Authorization: Bearer ${saved.token}`.
  - Add `onClosed` settlement: when no ack/error has won the atomic guard, fail with an `IOException` that names close code/reason without including the token or command body.
  - Preserve cancellation by cancelling the active socket.
  - Preserve feed-frame skipping and first-ack-wins semantics.
  - Proof: the Android JVM tests include deterministic timeout and close-before-ack cases and all previous command WS tests remain green.

- [ ] **2 — Extend the real transport regression suite**
  - Anchor: `android/app/src/test/java/dev/scoutr/app/net/BridgeClientWsTest.kt` → `BridgeClientWsTest`
  - Change the existing upgrade assertion from `/ws?token=test-token` to `/ws` and assert `Authorization: Bearer test-token`.
  - Add a server that accepts the command then cleanly closes without replying; the client must throw rather than hang.
  - Add a server that stays open and never replies; the client must fail at the command timeout and the test must not depend on the test runner's global timeout.
  - Keep coverage for ack, error, feed-before-ack, slash command, ask answer, and dismiss.
  - Proof: `make android-test` passes.

## Failure handling

- HTTP upgrade failure continues through OkHttp `onFailure`.
- Clean or abnormal close before ack becomes an `IOException`.
- No ack before the command deadline becomes a timeout failure and cancels the socket.
- Cancellation from the caller wins without later callbacks resuming the cancelled continuation.
- Do not log token, prompt text, ask answers, or command JSON.

## Validation

1. Run the focused `BridgeClientWsTest` while iterating.
2. Run `make android-test` after the change is stable.
3. Use `skills/scoutr-review/SKILL.md` for the independent review pass.
4. Use `skills/scoutr-verification/SKILL.md` to decide whether final runtime acceptance is warranted. If run, it is the final step after review-clean/code-freeze, not an inner-loop test.

Expected evidence:

- command upgrade path is exactly `/ws`;
- Authorization header carries the bearer token;
- close-before-ack and no-ack cases finish with typed failures;
- existing successful commands still return their first non-feed reply.

## Local discretion

- Exact timeout value, provided it is a named constant, short enough to prevent an apparent hang, and long enough for normal bridge command execution; prefer matching an existing request ceiling if one exists by implementation time.
- Exact exception wording, provided it is actionable and content-free.
- Whether the timeout wrapper is a small private helper or directly around `sendCommandJson`.

## Escalation triggers

- The bridge does not actually receive Authorization headers for `/ws` through the supported Tailscale deployment path.
- Fixing settlement requires changing shared topology-feed or terminal socket semantics.
- A command legitimately needs to remain open beyond the selected bounded request window.
- The HTTP-command migration has already landed; do not restore this transport merely to satisfy the plan literally.

## Review handoff

An independent reviewer must verify that every terminal path of the one-shot exchange has exactly one outcome: ack/error, failure, close-before-ack, timeout, or caller cancellation. Confirm no current command URL contains the pairing token and that bridge compatibility for older clients was not removed accidentally.

Rerun `make android-test` after review fixes.

## Completion checklist

- [ ] New command WebSocket requests authenticate by header and have no token query parameter.
- [ ] Clean close before ack fails promptly.
- [ ] No-ack socket is bounded by a command timeout.
- [ ] Cancellation cancels the socket and cannot double-resume.
- [ ] Existing ack/error/feed behavior remains covered.
- [ ] `make android-test` passes.
- [ ] Independent review is clean.
- [ ] Any selected runtime acceptance is performed once, last.

## References

- `AGENTS.md`
- `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt`
- `android/app/src/main/java/dev/scoutr/app/net/TopologyFeedClient.kt`
- `android/app/src/test/java/dev/scoutr/app/net/BridgeClientWsTest.kt`
- `bridge/src/server.ts`
- `.plans/p1-http-session-commands.md` (follow-on; once implemented it supersedes the one-shot command socket)
- `skills/scoutr-verification/SKILL.md`
- `skills/scoutr-review/SKILL.md`
