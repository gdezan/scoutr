# Plan 008: Visible test gating, the four unpinned routes, and a one-command verify

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- bridge/test/herdr-client.test.ts bridge/test/server.test.ts bridge/test/ws-commands.test.ts scripts/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none (coordinate with 001/004 if they landed — they extend the same test files)
- **Category**: tests + dx
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

The bridge suite silently shrinks depending on the machine: ~19 tests in
`herdr-client.test.ts` self-skip whenever the default herdr socket is absent,
so "npm test is green" means different things on different machines — and the
repo's second-highest-churn bridge module is the one whose coverage
evaporates. The repo already fixed this pattern once
(`server.integration.test.ts` requires an explicit env opt-in and says so)
but left the older file on the old convention. Four HTTP routes
(`/api/models`, `/api/dirs`, `/api/usage`, `/api/agents/kinds`) are never
exercised through the real dispatcher offline — and `/api/agents/kinds` is
exactly what the Android agent picker gates on. Finally, verification is four
hand-run commands with no aggregate script, which is how the stale-`dist`
deployment incident happened (see `docs/production-goal-checklist.md`,
"Deployment gate").

## Current state

- `bridge/test/herdr-client.test.ts:23-30` — the silent skip:

```ts
function liveSocketPath(): string | null {
  const path = process.env.HERDR_SOCKET_PATH ?? defaultSocketPath();
  return existsSync(path) ? path : null;
}

const socketPath = liveSocketPath();
const client = socketPath ? new HerdrClient({ socketPath }) : null;
const skip = socketPath === null;
```

- `bridge/test/server.integration.test.ts:12-18` — the convention to copy; it
  documents itself as running only when `HERDR_SOCKET_PATH` is set
  **explicitly**, so its absence is visible.
- `bridge/test/server.test.ts` — the offline HTTP suite (fake herdr/feed from
  `bridge/test/support/`); it covers health, snapshot, agents, agents/:id/read,
  sessions, session-catalog (+delete/resume), repo, attachments — but not
  models/dirs/usage/kinds.
- `bridge/src/routes/models.ts` — `GET /api/models`, `?agent=` defaults to
  `"pi"`; already maps unknown agents to 404 in-route:

```ts
async function models(ctx: RouteContext): Promise<RouteResult> {
  const agent = ctx.query.get("agent") ?? "pi";
  let backend;
  try {
    backend = backendFor(agent);
  } catch {
    return { status: 404, body: { ok: false, error: `unknown agent: ${agent}` } };
  }
  // ...
}
```

  Check `bridge/src/routes/commands.ts` for the same `?agent=` handling —
  audit evidence says it may throw through to a 502 instead; pin whichever
  behavior is correct (a 4xx, never a 502, for a bad `?agent=`).
- `bridge/src/routes/agents.ts:113-127` — `/api/agents/kinds` payload shape
  the Android picker consumes (`android/.../ui/screens/NewSessionSheet.kt`
  gates the agent choice on it):

```ts
      kinds: knownBackends().map((backend) => ({
        id: backend.id,
        displayName: backend.displayName,
        capabilities: [...backend.capabilities],
        hasModelCatalog: backend.hasModelCatalog,
        hasSlashCommands: backend.hasSlashCommands,
      })),
```

- `bridge/test/ws-commands.test.ts:139-155` — a `describe("fake herdr
  recording")` block whose two tests assert only on the test double
  (`fakeHerdr()` in, assertions on it out; no production code in the path).
- `scripts/` today: `build-apk.sh`, `deploy-bridge.sh`, `install-app.sh`,
  `pair.sh`, `release.sh` — no verify aggregate. The four gates (AGENTS.md):

```bash
cd bridge && npm run typecheck && npm test
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug
```

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Typecheck | `cd bridge && npm run typecheck` | exit 0 |
| Bridge tests | `cd bridge && npm test` | all pass; skip notices visible |
| One file | `cd bridge && node --import tsx --test --test-timeout=120000 test/server.test.ts` | all pass |

## Scope

**In scope**:
- `bridge/test/herdr-client.test.ts` (gating only — do not touch test bodies)
- `bridge/test/server.test.ts` (add route cases)
- `bridge/test/ws-commands.test.ts` (remove the double-only describe block)
- `scripts/verify.sh` (create)
- `AGENTS.md` (one line pointing at verify.sh — only if plan 006 already
  landed; otherwise leave AGENTS.md alone and note it)

**Out of scope**:
- CI/GitHub Actions — there is no `.github/` and adding CI is a bigger
  operator decision (runner env, SDK provisioning); deferred deliberately.
- The live-socket test bodies and `server.integration.test.ts`.
- Any `bridge/src/` change EXCEPT: if the `?agent=` handling in
  `routes/commands.ts` returns 502 for unknown agents, mirror the 404 mapping
  from `routes/models.ts` (small, in-spirit fix; anything larger → STOP).

## Git workflow

- Work directly on `main`. Conventional commits, e.g.
  `test(bridge): explicit live-suite opt-in; pin the unpinned routes`.

## Steps

### Step 1: Make the live-suite skip explicit and visible

In `bridge/test/herdr-client.test.ts`, change `liveSocketPath()` to use ONLY
the explicit env var, and announce the skip:

```ts
function liveSocketPath(): string | null {
  // Opt-in like server.integration.test.ts: no silent default-path probing —
  // an absent env var must be visible, not a quietly smaller suite.
  const path = process.env.HERDR_SOCKET_PATH;
  if (!path) return null;
  return existsSync(path) ? path : null;
}
// after computing `skip`:
if (skip) console.error("herdr-client live suite skipped: set HERDR_SOCKET_PATH to run it");
```

Keep the offline tests in the file (the scratch-socket timeout test, plus any
added by plan 001) running unconditionally.

**Verify**: `cd bridge && npm test` → green, and with `HERDR_SOCKET_PATH`
unset the skip notice appears in the output.

### Step 2: Pin the four routes offline

In `bridge/test/server.test.ts`, following its existing request-helper
pattern, add:

1. `GET /api/models` → 200, `ok: true`, `catalog` present (pi default).
2. `GET /api/models?agent=nonsense` → 404 with `unknown agent: nonsense`
   (NOT 502).
3. `GET /api/commands?agent=nonsense` → a 4xx (read
   `bridge/src/routes/commands.ts` first; if it currently 502s, apply the
   small in-spirit fix noted in Scope, then pin 404).
4. `GET /api/agents/kinds` → 200; assert the exact field set per kind:
   `id`, `displayName`, `capabilities` (array), `hasModelCatalog`,
   `hasSlashCommands`; assert both `pi` and `claude` appear. This is the
   Android picker contract — assert field NAMES, not just truthiness.
5. `GET /api/dirs` (read `bridge/src/routes/dirs.ts` for its params — likely
   `?path=`) → 200 against a mkdtemp directory; a permission-denied or
   missing path → its documented error status, not 502.
6. `GET /api/usage` → 200 shape (the fake `usage` dep in the harness may need
   a stub method — check what `ServerDeps["usage"]` requires and extend the
   existing fake minimally).

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/server.test.ts` → all pass including 6 new cases.

### Step 3: Delete the double-only tests

Remove `describe("fake herdr recording")` from
`bridge/test/ws-commands.test.ts:139-155`. (If plan 004 already rewrote this
file, the block may have moved — search for the describe name.)

**Verify**: `cd bridge && npm test` → green; total count drops by exactly 2
relative to before this step.

### Step 4: `scripts/verify.sh`

Create (mode 755), matching the style of the existing scripts in `scripts/`
(they use `set -euo pipefail` — check `scripts/deploy-bridge.sh` and match):

```bash
#!/usr/bin/env bash
# One-command verification: the four gates from AGENTS.md, fail-fast.
# Usage: scripts/verify.sh [--no-emulator]   (skips the GMD suite)
set -euo pipefail
cd "$(dirname "$0")/.."

(cd bridge && npm run typecheck && npm test)
(cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}" ./gradlew testDebugUnitTest --rerun-tasks)
if [[ "${1:-}" != "--no-emulator" ]]; then
  (cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}" ./gradlew pixel2api36DebugAndroidTest)
fi
(cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}" ./gradlew assembleDebug)
echo "verify: all gates green"
```

**Verify**: `bash -n scripts/verify.sh` → exit 0; then
`scripts/verify.sh --no-emulator` → completes with "all gates green"
(the emulator gate is environment-dependent; run the full script only if a
`cockpit` AVD exists).

## Test plan

Steps 1–3 ARE the tests. Additionally: run `npm test` twice, once with
`HERDR_SOCKET_PATH` unset and once set to a nonexistent path, and confirm
both are green with the skip notice (never a hang — the suite has a global
`--test-timeout=120000`).

## Done criteria

- [ ] `grep -n "defaultSocketPath()" bridge/test/herdr-client.test.ts` → no match in the gating function
- [ ] `cd bridge && npm test` green with a visible skip notice when no socket env is set
- [ ] server.test.ts covers models (incl. unknown agent), commands (unknown agent), dirs, usage, agents/kinds — grep each path in the file
- [ ] `grep -n "fake herdr recording" bridge/test/ws-commands.test.ts` → no match
- [ ] `scripts/verify.sh` exists, is executable, and `--no-emulator` run exits 0
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- Making the live gate explicit reveals the offline remainder of
  `herdr-client.test.ts` depends on module-level state from the gated setup
  (e.g. `client` being non-null) — restructure minimally or report.
- The `usage` fake requires more than a trivial stub (network calls, auth
  file reads) to satisfy `/api/usage` — report rather than building a fixture
  ecosystem.
- `routes/commands.ts`'s unknown-agent handling can't be fixed in ≤5 lines —
  report; it may interact with plan 004's validation work.

## Maintenance notes

- CI remains the natural follow-up: `scripts/verify.sh --no-emulator` is
  written to be the CI entry point when the operator provisions a runner.
- Plans 001 and 004 add tests to two of the same files; whichever lands last
  reconciles trivially (different test names).
- Reviewer: the `/api/agents/kinds` field assertions are a wire contract with
  `NewSessionSheet.kt` — any future field rename must update both sides.
