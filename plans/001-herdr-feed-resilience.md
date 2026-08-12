# Plan 001: Make the bridge survive herdr restarts and client disconnects

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- bridge/src/herdr/client.ts bridge/src/herdr/feed.ts bridge/src/server.ts bridge/test/herdr-client.test.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

The bridge is a long-running daemon whose whole purpose is relaying herdr's
live events (agent blocked/done) to the phone as push notifications and board
status. Today, **one herdr restart can permanently kill that event stream**:
the subscription promise in `herdrSubscribe` never settles when the connect
fails, which wedges the feed's rebuild guard forever. Separately, a request
that loses its socket resolves to `undefined` instead of rejecting, an
unhandled rejection in the HTTP handler or an unlistened `error` event on a
WebSocket can crash the whole daemon, per-pane subscriptions are rebuilt from
a stale snapshot (so a freshly created agent's blocked event misses its push),
and the status tracker leaks one map entry per pane forever. These are all in
the same three files and share one test harness, so they land as one plan.

## Current state

Files:

- `bridge/src/herdr/client.ts` — one-shot JSONL RPC (`herdrRequest`) and the
  long-lived subscription (`herdrSubscribe`) over the herdr Unix socket.
- `bridge/src/herdr/feed.ts` — `HerdrEventFeed`: owns the subscription,
  rebuilds it on topology events, refreshes a snapshot every 30s.
- `bridge/src/server.ts` — HTTP + WS server; consumes feed events to stamp
  `StatusTracker` and prune `BoardDetailCache`.
- `bridge/src/status.ts` — `StatusTracker` with a `prune(paneIds)` method that
  **no production code calls** (verify: `grep -rn "tracker.prune" bridge/src`
  returns nothing today).
- `bridge/test/herdr-client.test.ts` — has a live-socket suite (skipped
  offline) plus one offline timeout test using a scratch Unix socket server;
  use that offline test as the structural pattern for new tests.

### Defect 1 — `herdrSubscribe` never rejects (`client.ts:144-221`)

The returned promise resolves only in `sock.once("data", ...)`; `error` and
`close` only fire callbacks. If the socket path does not exist (herdr
restarting), no `data` ever arrives and the promise stays pending forever:

```ts
    sock.on("error", (error) => {
      callbacks.onError?.(new HerdrError(`herdr socket error: ${error.message}`));
    });
    sock.on("close", () => {
      if (!closed) closed = true;
      callbacks.onClose?.();
    });

    // Give the ack a moment to arrive; resolve the handle so the caller can
    // start using it immediately after subscribing.
    const ackTimeout = setTimeout(() => {
      if (!started) {
        callbacks.onError?.(new HerdrError("no subscription ack from herdr"));
      }
    }, 3000);
    ackTimeout.unref?.();

    sock.once("data", () => {
      clearTimeout(ackTimeout);
      resolve(handle);
    });
```

Downstream in `feed.ts:117-123`, a forever-pending subscribe wedges the guard,
because `.finally` never runs:

```ts
  private buildSubscription(): Promise<void> {
    if (this.rebuilding) return this.rebuilding;
    this.rebuilding = this.doBuildSubscription().finally(() => {
      this.rebuilding = null;
    });
    return this.rebuilding;
  }
```

And the reconnect path (`feed.ts:159-167`) is exactly the caller that gets
wedged:

```ts
      onClose: () => {
        // Socket died: reconnect after a short backoff unless stopped.
        if (this.stopped) return;
        setTimeout(() => {
          void this.buildSubscription().catch((error) => {
            this.emitAll({ kind: "feed_error", data: { message: error.message } });
          });
        }, 1000);
      },
```

### Defect 2 — `herdrRequest` resolves `undefined` on empty close (`client.ts:79-95`)

`finish` is bound to both `end` and `close`. When the connection closes with
an empty buffer, `lines[0] ?? "{}"` parses `{}`, there is no `.error`, and the
promise **resolves with `undefined`**:

```ts
    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      sock.destroy();
      try {
        const lines = splitLines(buffer).filter((line) => line.trim().length > 0);
        const envelope = JSON.parse(lines[0] ?? "{}") as RpcEnvelope;
        if (envelope.error) {
          reject(new HerdrError(envelope.error.message ?? "herdr request failed", envelope.error.code));
          return;
        }
        resolve(envelope.result);
      } catch {
        reject(new HerdrError(`invalid JSON response from herdr socket: ${buffer.slice(0, 300)}`));
      }
    };
```

Callers then blow up on property access (`snapshot()` does
`result.snapshot`), surfacing herdr restarts as
`TypeError: Cannot read properties of undefined` mapped to a 502.

### Defect 3 — subscriptions rebuilt from a stale snapshot (`feed.ts:125-136`)

`doBuildSubscription` derives pane subscriptions from `this.lastSnapshot`,
which only `refreshSnapshot` updates (on `start()` and a 30s interval). The
rebuild fires 300ms after a `pane.created` event — reliably before the
snapshot contains the new pane — so the new agent's
`pane.agent_status_changed` events are not subscribed for up to 30s:

```ts
  private async doBuildSubscription(): Promise<void> {
    this.handle?.close();
    if (this.stopped) return;

    const paneIds = (this.lastSnapshot?.panes ?? []).map((pane) => pane.pane_id);
```

### Defect 4 — the daemon can crash on client disconnects (`server.ts:90-105, 130-174`)

The `createServer` callback is `async` and nothing catches a throw from
`sendJson` (e.g. client aborted mid-response) — an unhandled rejection, which
terminates the process on Node ≥ 15. The WS side registers `message` and
`close` handlers but **no `ws.on("error")`**, and `new WebSocketServer(...)`
has no `error` listener either; an `error` event on an EventEmitter with no
listener throws.

```ts
  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    const result = await dispatchRoute( /* ... */ );
    sendJson(response, result.status, result.body);
  });
```

### Defect 5 — pane-close event kinds mismatched; tracker never pruned (`server.ts:69-76`)

herdr emits event kinds in both snake_case and dot-form across versions (the
feed itself normalizes with `kind.replace(/_/g, ".")` at `feed.ts:150`; and
`notify.ts:26` checks both spellings for status events). The server's
pane-close branch mixes conventions — it matches `pane_closed` (snake) and
`pane.exited` (dot) but not `pane.closed` or `pane_exited` — and never calls
`tracker.prune`:

```ts
    } else if (message.kind === "pane_closed" || message.kind === "pane.exited") {
      const data = message.data;
      const paneId = typeof data.pane_id === "string" ? data.pane_id : "";
      if (paneId) {
        tracker.note(paneId, "closed");
        boardDetail.prune(new Set(snapshotPaths(feed.snapshot)));
      }
    }
```

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Typecheck | `cd bridge && npm run typecheck` | exit 0 |
| Tests     | `cd bridge && npm test` | all pass (~172 today; suite is bounded at 120s/test) |
| One file  | `cd bridge && node --import tsx --test --test-timeout=120000 test/herdr-client.test.ts` | all pass |

## Scope

**In scope** (the only files you should modify):
- `bridge/src/herdr/client.ts`
- `bridge/src/herdr/feed.ts`
- `bridge/src/server.ts`
- `bridge/test/herdr-client.test.ts` (extend)
- `bridge/test/status.test.ts` (extend if needed)

**Out of scope** (do NOT touch, even though they look related):
- `bridge/src/notify.ts` — its map pruning/fetch timeout is plan 009.
- `bridge/test/herdr-client.test.ts` live-suite **gating** (the
  `liveSocketPath()` fallback) — that is plan 008; only add offline tests here.
- The 3s/30s polling architecture — documented decision, do not redesign.
- `bridge/src/routes/` — untouched by these fixes.

## Git workflow

- Work directly on `main` (repo convention, AGENTS.md).
- Commit style: conventional, e.g. `fix(bridge): reject failed herdr subscriptions so the feed can reconnect`.
- End commit messages with the repo's standard co-author line if other commits have one (check `git log -3`).

## Steps

### Step 1: Reject on failure in `herdrSubscribe`

In `bridge/src/herdr/client.ts`, add a `settled` flag to the subscribe
promise. Reject (and stop) when:
- the socket emits `error` **before** the first `data` (keep calling
  `callbacks.onError` for post-resolve errors),
- the socket emits `close` before the first `data`,
- the 3s ack timeout fires before the first `data` (replace the
  onError-only branch with a reject; keep `unref`).

After the first `data` resolves the handle, behavior stays exactly as today.

**Verify**: `cd bridge && npm run typecheck` → exit 0.

### Step 2: Reject `herdrRequest` on empty close

In `finish()` (`client.ts:79-95`), before parsing: if
`splitLines(buffer).filter(...)` is empty, `reject(new HerdrError("herdr closed the connection without responding"))`
instead of parsing `"{}"`.

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/herdr-client.test.ts` → pass.

### Step 3: Make the feed retry a failed rebuild with backoff

In `bridge/src/herdr/feed.ts`, wrap the `await this.client.subscribe(...)` in
`doBuildSubscription` in try/catch. On failure: emit a
`{ kind: "feed_error", data: { message } }`, and schedule a retry via
`setTimeout(() => void this.buildSubscription(), RETRY_MS)` guarded by
`this.stopped` (1s initial is fine; cap growth at ~15s if you add backoff —
a fixed 1s matching the existing onClose backoff is acceptable). The key
invariant: **`buildSubscription`'s `.finally` must always run**, which step 1
guarantees once subscribe can reject.

Store the retry timeout handle on the instance and clear it in `stop()`.

**Verify**: `cd bridge && npm test` → all pass.

### Step 4: Refresh the snapshot at the top of `doBuildSubscription`

Before computing `paneIds`, add:

```ts
try {
  await this.refreshSnapshot(true);
} catch {
  // a snapshot failure must not block resubscribing to global events
}
```

then re-check `if (this.stopped) return;` after the await.

**Verify**: `cd bridge && npm test` → all pass.

### Step 5: Crash-proof the HTTP and WS handlers

In `bridge/src/server.ts`:
- Wrap the `createServer` async callback body in try/catch; in the catch,
  attempt a 500 JSON response only if `!response.writableEnded`, and swallow
  any secondary write failure.
- Add `wss.on("error", () => {})`-style listeners that log via
  `console.error` (match the existing minimal logging at `server.ts:177-183`).
- Inside `wss.on("connection", ...)`, add `ws.on("error", ...)` that logs and
  runs the same cleanup as `close` (set `closed = true`,
  `feed.removeMessage(handleFeed)`).
- Guard `ws.send` in `handleFeed` with `ws.readyState === WebSocket.OPEN`.

**Verify**: `cd bridge && npm test` → all pass (the offline HTTP suite in
`test/server.test.ts` exercises this path).

### Step 6: Normalize pane-close kinds and prune the tracker

In the feed listener at `server.ts:62-77`, normalize once —
`const kind = message.kind.replace(/_/g, ".")` — then match
`kind === "pane.agent.status.changed"` … careful: naive `replace` turns
`pane_agent_status_changed` into `pane.agent.status.changed` while the
dot-form event is `pane.agent_status_changed`. **Do not blind-replace.**
Instead, match against explicit sets:

```ts
const STATUS_KINDS = new Set(["pane_agent_status_changed", "pane.agent_status_changed"]);
const CLOSE_KINDS = new Set(["pane_closed", "pane.closed", "pane_exited", "pane.exited"]);
```

In the close branch, additionally prune the tracker with the live pane ids:

```ts
tracker.prune(new Set((feed.snapshot?.panes ?? []).map((p) => p.pane_id)));
```

(`feed.snapshot` may be typed loosely here; follow the existing cast pattern
`feed.snapshot as SessionSnapshot | null` used in `routes/review.ts:66`.)

**Verify**: `cd bridge && npm test` → all pass.

## Test plan

New tests, using the scratch-Unix-socket pattern from the existing offline
timeout test in `bridge/test/herdr-client.test.ts` (it creates a socket server
in `mkdtemp` and controls its behavior):

1. `herdrSubscribe` rejects when the socket path does not exist.
2. `herdrSubscribe` rejects when the server accepts then closes before any data.
3. `herdrRequest` rejects with "closed the connection without responding" when
   the server accepts and closes with no bytes.
4. Feed-level: a `HerdrEventFeed` pointed at a dead socket path emits
   `feed_error` and `start()`/rebuild does not hang the test (bound with the
   node test `timeout` option like the existing offline test).
5. StatusTracker prune on close: extend `bridge/test/status.test.ts` or add a
   server-level assertion if the harness in `test/server.test.ts` (fake feed)
   makes it cheap — inject a `pane_exited` event and assert `since()` for the
   closed pane is gone.

**Verification**: `cd bridge && npm test` → all pass, ≥5 new tests.

## Done criteria

- [ ] `cd bridge && npm run typecheck` exits 0
- [ ] `cd bridge && npm test` exits 0 with the new tests present
- [ ] `grep -n "sock.once(\"data\"" bridge/src/herdr/client.ts` — the subscribe resolve path coexists with reject paths for error/close/ack-timeout (read the surrounding code to confirm)
- [ ] `grep -rn "tracker.prune" bridge/src/server.ts` returns exactly one match
- [ ] `grep -n "ws.on(\"error\"" bridge/src/server.ts` returns at least one match
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- The excerpts above don't match the live code (drift).
- Making `subscribe` reject causes existing live-socket tests to fail **when
  a real herdr is running** — that suite encodes real herdr behavior; report
  the failure rather than weakening the assertion.
- Fixing the rebuild loop appears to require changing the
  `HerdrPort` interface in `bridge/src/herdr/port.ts` — that seam is shared
  with the fake (`bridge/test/support/fake-herdr.ts`) and widening it needs a
  human decision.
- You find the process actually depends on the resolve-undefined behavior of
  `herdrRequest` somewhere (a caller that treats `undefined` as a sentinel).

## Maintenance notes

- Plan 008 changes how the live-socket suite in `herdr-client.test.ts` is
  gated; if it landed first, put new offline tests outside the gated block.
- A future WS-feed transport for the app (currently rejected by decision
  docs) would build directly on the now-settling subscribe promise.
- Reviewer: scrutinize step 6's kind sets — do NOT accept a `replace(/_/g, ".")`
  normalization; it corrupts `pane_agent_status_changed`.
