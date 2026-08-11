# 3. Herdr port + route table so the HTTP surface is actually tested

**Strength: Strong.** Do this second. It is the safety net for plan 1.

## Files

`bridge/src/server.ts` (821 lines), `bridge/src/herdr/client.ts`,
`bridge/test/server.test.ts`, `bridge/test/herdr-client.test.ts`.

## Problem

**The HTTP surface has no offline tests.** `bridge/test/server.test.ts:15-16`:

```ts
const socketPath = process.env.HERDR_SOCKET_PATH ?? defaultSocketPath();
const skip = !existsSync(socketPath);
…
describe("cockpit bridge HTTP/WS API", { skip }, () => {
```

Without a live herdr socket, the entire suite reports as skipped — green, silent, and
empty. `herdr-client.test.ts:50,104` does the same. What that leaves untested on a clean
machine or in CI: every route in `handleRoute` except the sessions ones,
`deriveAgentCards` / `deriveAgentCardsWithDetail` / `sessionWorkspaceRoots` /
`snapshotPaths` (`server.ts:662-714, 789-813`), the whole WS command path, and
`herdr/feed.ts`'s subscription rebuild, resync timer, and reconnect backoff. `cli.ts`,
`config.ts`, and `status.ts` have no tests at all.

This is not a coverage nit. Plan 1 rewrites the sandbox check, the transcript read, and the
control dispatch — all of which live in exactly these untested paths.

The cause is that `handleRoute` takes a concrete `HerdrClient` bound to a Unix socket.
`sessions-http.test.ts` proves the fix already works in miniature: it passes a hand-rolled
fake herdr and tests two routes offline. Nothing generalises that fake because there is no
declared interface for it to implement.

**Secondarily**, `handleRoute` is a ~300-line `if`-chain on `pathname`
(`server.ts:237-530`) mixing routing, validation, sandboxing, and handler bodies inline. A
new endpoint means finding the right spot in the chain; a route that shadows another fails
silently by ordering. This is shallow: the function's complexity is proportional to the
number of routes, with no abstraction earning its keep.

## Solution

Two changes, in order.

### 3a. Declare `HerdrPort`

Extract the interface that `sessions.ts`, `server.ts`, and (later) every agent adapter
actually use from `HerdrClient` — roughly: `ping`, `snapshot`, `workspaceCreate`,
`workspaceRename`, `workspaceClose`, `paneSendInput`, `paneSendKeys`, `paneSendText`,
`agentPrompt`, `agentGet`, `agentRead`.

```ts
// bridge/src/herdr/port.ts
export interface HerdrPort { /* the ~11 methods above */ }
```

`HerdrClient implements HerdrPort` with no behaviour change. Then:

```ts
// bridge/test/support/fake-herdr.ts
export function fakeHerdr(initial?: Partial<SessionSnapshot>): HerdrPort & {
  readonly sent: SentInput[];      // every paneSendInput / paneSendKeys / agentPrompt
  setSnapshot(next: SessionSnapshot): void;
  failNext(method: keyof HerdrPort, error: Error): void;
};
```

One fake, shared by every test file, replacing the ad-hoc one in `sessions-http.test.ts`.
Recording sends is what makes agent-adapter assertions possible in plan 1: *"the pi adapter
sent `/compact` + Enter; the Claude adapter sent `/compact` + Enter but never
`shift+tab`."*

Remove the `skip` gate. Keep the live-socket suite as a separate, explicitly-gated
integration file (`server.integration.test.ts`) so real-herdr coverage is not lost — but it
is no longer the *only* coverage, and its absence is now visible rather than silent.

### 3b. A route table

```ts
// bridge/src/routes/types.ts
export interface Route {
  method: "GET" | "POST";
  /** Literal path or a pattern with :params — "/api/sessions/:paneId/control". */
  path: string;
  handle(ctx: RouteContext): Promise<RouteResult>;
}

export interface RouteContext {
  params: Record<string, string>;
  query: URLSearchParams;
  body: JsonBody;
  deps: ServerDeps;
}

export type RouteResult = { status: number; body: unknown };
```

`server.ts` becomes: match a route from a table, build the context, call the handler, write
the JSON. Handlers move into `bridge/src/routes/` grouped by feature (`sessions.ts`,
`catalog.ts`, `review.ts`, `usage.ts`, `agents.ts`, `health.ts`) — mirroring the module
layout `bridge/src` already has, so the handler lives next to the feature it serves.

Handlers return values instead of writing to a `ServerResponse`, which is what makes them
directly unit-testable: no HTTP server, no port, no socket.

The error convention that currently repeats in every branch —

```ts
const status = error instanceof SessionsError ? error.status : 502;
sendJson(response, status, { ok: false, error: … });
```

— moves into the dispatcher once. Auth, body parsing, and the 404 fallback also become
dispatcher concerns rather than per-branch code.

## Benefits

**Locality.** A new endpoint is one file in `bridge/src/routes/` plus one table entry, next
to its feature module. Today it is a new `if` in an 821-line file, positioned correctly
relative to the other 20.

**Depth.** The dispatcher's interface is `Route[]` in, HTTP out. It hides matching,
parameter extraction, auth, body parsing, error-to-status mapping, and the 404 — each of
which is currently duplicated per branch.

**Tests.** This is the real payoff. `handle(ctx)` returning `{status, body}` is a pure-ish
function testable without a server. Combined with `fakeHerdr`, the whole route layer
becomes offline-testable, and the ~147-test suite starts actually running its HTTP third on
any machine.

**Leverage.** Plan 1 rewrites the sandbox, the transcript read, and the control dispatch.
With this in place, each of those rewrites is a green-to-green step instead of a leap.

## Steps

1. Write `herdr/port.ts`; declare `HerdrClient implements HerdrPort`. Change signatures in
   `sessions.ts` and `server.ts` from `HerdrClient` to `HerdrPort`.
2. Write `test/support/fake-herdr.ts`; port `sessions-http.test.ts` to it.
3. Remove the `skip` gate from `server.test.ts`; move the live-socket cases to
   `server.integration.test.ts` gated on `HERDR_SOCKET_PATH` being set explicitly (not on a
   default path existing — an explicit opt-in makes the gate visible).
4. Add the route table and dispatcher; move routes over feature by feature, running the
   suite after each. Health and snapshot first (smallest), sessions last (largest).
5. Backfill offline tests for `deriveAgentCards`, `snapshotPaths`, `sessionWorkspaceRoots`,
   and the WS command path using the fake.
6. Add tests for `config.ts` (token length rule, `XDG_CONFIG_HOME`) and `status.ts`.

## Risks

- **Route ordering.** The `if`-chain has an implicit precedence — the regex match for
  `/api/sessions/:paneId/control` sits after the literal `/api/sessions`. A table must
  preserve it. Match literals before patterns, and add a startup assertion that no two
  routes can match the same path.
- **WS is not routes.** The WebSocket command handling (`server.ts:582-613`) is a separate
  dispatch and should stay separate — extracting it into a `commands/` module with the same
  return-a-value shape is a reasonable follow-up, but do not force it into the HTTP table.
- **Don't grow the port.** `HerdrPort` should list only the methods the bridge calls today.
  A port that mirrors the whole herdr RPC surface is not a seam, it is a second copy of the
  client.
