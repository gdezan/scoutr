# Plan 004: Harden the WS/control surface and test real question answering

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- bridge/src/commands.ts bridge/src/sessions.ts bridge/src/routes/sessions.ts bridge/src/questions.ts bridge/src/agents/registry.ts bridge/test/ws-commands.test.ts bridge/test/questions.test.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (touches the answer-delivery protocol; the emulator answer flow is the end-to-end net)
- **Depends on**: 003 (merge overlap in `routes/sessions.ts`; land 003 first)
- **Category**: security + tests + bug
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

Answering a blocked agent's question is Cockpit's core interaction, and the
code that actually runs in production has zero test coverage: the WS test
suite constructs deps with `feed: {} as never`, so `backendForPane` always
returns `null` and every `answer_question` test exercises only the
unknown-agent fallback — never `piAnswerQuestion` or `claudeAnswerQuestion`.
Meanwhile the WS surface violates its own stated invariant on 2 of 5 verbs
(`send_text` and `steer` skip the control-character/length validation that
`answer_question`, `slash_command`, and `set_model` all apply before text
enters a PTY), the HTTP control route casts `body.action as never` and never
enforces the per-backend `capabilities` set it advertises to the app, the
session-read route flattens deliberate 403/404 conditions to 500 (so the app
retries a permanently-broken path every 2.5s), question answers are matched
to questions **by array position** while the `questionIndex` field that would
match them correctly is parsed and then thrown away, and there are two
divergent `backendForPane` implementations that a third agent backend would
both need to learn about.

## Current state

NOTE on regexes in this plan: control-character classes are written in the
source as JavaScript unicode escapes (backslash-u ranges). To avoid any
copy/paste ambiguity, this plan never asks you to retype them — always reuse
the existing regexes/constants by importing or copying them **from the source
locations named below**.

- `bridge/src/commands.ts` — WS command dispatch.
  - Unvalidated verbs (`:35-39`, `:84-89`):

```ts
    case "steer": {
      const { target, text } = command;
      if (!target || !text) throw new Error("steer requires target and text");
      return { type: "steered", target, result: await deps.herdr.agentPrompt(target, text) };
    }
    // ...
    case "send_text": {
      const { paneId, text } = command;
      if (!paneId || !text) throw new Error("send_text requires paneId and text");
      await deps.herdr.paneSendText(paneId, text);
      return { type: "sent", paneId };
    }
```

  - The validation style the codebase already uses for pane-bound text, to be
    reused (do not invent new regexes):
    - `sanitizeAnswerText` + `MAX_ANSWER_LENGTH` in
      `bridge/src/questions.ts:157-167` — flattens newlines to spaces, strips
      the C0/DEL control range, trims, caps at 4000.
    - `PROMPT_FORBIDDEN_CHAR` + `MAX_PROMPT_LENGTH` in
      `bridge/src/sessions.ts` (used by `validateCreateSessionParams` around
      `:96-101`) — rejects NUL/DEL in multi-line prompt text.
    - The claude `set_model` guard at
      `bridge/src/agents/claude/index.ts:183-190` — length cap + C0/DEL
      rejection, with the comment "the text goes into a PTY, so anything that
      could alter submission is rejected outright".
  - The generic fallback (`:62-74`) duplicates pi's keys→text→trailingKeys
    protocol inline; the pi backend already owns that logic
    (`bridge/src/agents/pi/index.ts:102-128`).
  - `backendForPane` (`:110-121`) — sync, reads `deps.feed.snapshot`, returns
    null on miss:

```ts
function backendForPane(deps: ServerDeps, paneId: string) {
  const snapshot = deps.feed.snapshot as SessionSnapshot | null;
  const pane = snapshot?.panes.find((candidate) => candidate.pane_id === paneId);
  if (pane) {
    return backendForAgentSessionInfo(pane.agent_session) ?? getBackendOrNull(pane.agent ?? "");
  }
  const agent = snapshot?.agents.find((candidate) => candidate.pane_id === paneId);
  if (agent) {
    return backendForAgentSessionInfo(agent.agent_session) ?? getBackendOrNull(agent.agent);
  }
  return null;
}
```

- `bridge/src/sessions.ts` — a second `backendForPane` (`:270-286`): async,
  calls `herdr.snapshot()`, throws `SessionsError(..., 404)` on miss; same
  three-step resolution chain. `controlSession` (`:245-267`) delegates to
  `backend.control` and relies on the backend switch `default` to reject
  unknown verbs; it never consults `backend.capabilities` (each backend
  exports a capability set, e.g. `CLAUDE_CAPABILITIES` at
  `bridge/src/agents/claude/index.ts:199`, advertised to the app by
  `/api/agents/kinds` in `bridge/src/routes/agents.ts:113-127`).

- `bridge/src/routes/sessions.ts`:
  - `controlRoute` (`:109-119`): `action: body.action as never` — no runtime
    check.
  - `readSessionRoute` (`:37-47`): catches everything → 500, including the
    deliberate `"session path is outside a registered session store"` throw
    from `readSession` (`:55`) which is a 403-class condition. The dispatcher
    already maps `BridgeError.status` centrally
    (`bridge/src/routes/dispatcher.ts:147-153`); `SessionsError`
    (in `bridge/src/sessions.ts`) extends `BridgeError` with a status — use it.

- `bridge/src/questions.ts` — positional answer matching (`:79-90`):

```ts
  for (const { entryId, timestamp, call } of calls) {
    const raw = parseQuestions(call.arguments);
    const answers = answersByCallId.get(call.id) ?? [];
    raw.forEach((question, index) => {
      const match = index < answers.length ? answers[index] : undefined;
```

  while `questionIndex` is parsed at `:142` and never read afterwards:

```ts
      questionIndex: typeof a.questionIndex === "number" ? a.questionIndex : undefined,
```

- `bridge/src/agents/registry.ts` — where the shared pane-resolution helper
  belongs; already exports `backendForAgentSessionInfo`, `getBackendOrNull`,
  `backendFor`, `backendForSessionPath`, `knownBackends`.

- `bridge/test/ws-commands.test.ts` — the deps that force the fallback (`:7-18`):

```ts
function makeDeps(): { herdr: ReturnType<typeof fakeHerdr>; deps: ServerDeps } {
  const herdr = fakeHerdr();
  return {
    herdr,
    deps: { herdr, feed: {} as never, usage: {} as never, config: { token: "x".repeat(16), port: 1 } },
  };
}
```

  With `feed: {}`, `deps.feed.snapshot` is `undefined`, so every
  `answer_question` test lands in the fallback. `fakeHerdr()`
  (`bridge/test/support/fake-herdr.ts`) records sent keys/text — read it for
  the exact recording API before writing assertions. There is also
  `bridge/test/support/fake-feed.ts`; read it — if it can serve a snapshot
  with panes carrying `agent`/`agent_session`, use it instead of a hand-rolled
  object.

- Backend answer implementations to cover (verified correct as written):
  `piAnswerQuestion` (`bridge/src/agents/pi/index.ts:102-128` — keys, then
  optional text + trailingKeys, else plain text+Enter) and
  `claudeAnswerQuestion` (`bridge/src/agents/claude/index.ts:126-131` —
  newline-flattened single line + Enter).

- The app's WS client sends these commands from
  `android/.../state/ChatViewModel.kt` via `BridgeClient.sendCommand`; no
  Android change is needed — the app already sanitizes composer text on its
  side.

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Typecheck | `cd bridge && npm run typecheck` | exit 0 |
| All tests | `cd bridge && npm test` | all pass |
| Focused   | `cd bridge && node --import tsx --test --test-timeout=120000 test/ws-commands.test.ts test/questions.test.ts test/sessions-http.test.ts` | all pass |

## Scope

**In scope**:
- `bridge/src/commands.ts`
- `bridge/src/sessions.ts` (only `controlSession` + removing its private `backendForPane`)
- `bridge/src/routes/sessions.ts` (only `controlRoute` + `readSessionRoute` error mapping)
- `bridge/src/questions.ts` (answer matching only)
- `bridge/src/agents/registry.ts` (add the shared resolver)
- `bridge/test/ws-commands.test.ts`, `bridge/test/questions.test.ts`, `bridge/test/sessions-http.test.ts`

**Out of scope**:
- Moving pi-specific `questions.ts` into `agents/pi/` — worthwhile debt
  (audit DEBT-03) but a mechanical move that would bloat this diff; deferred.
- The `AgentBackend` interface shape (`bridge/src/agents/types.ts`) — no
  signature changes.
- Android code — the app already behaves correctly.
- `readSession`'s read/caching internals — plan 003 owns those lines.

## Git workflow

- Work directly on `main`. Conventional commits, e.g.
  `fix(bridge): validate every pane-writing WS verb; test real backend answers`.

## Steps

### Step 1: One shared pane→backend resolver

In `bridge/src/agents/registry.ts` add:

```ts
import type { SessionSnapshot } from "../herdr/types.js";

/** The registered backend that owns a live pane, from a snapshot. */
export function resolveBackendForPane(
  snapshot: SessionSnapshot | null | undefined,
  paneId: string,
): AgentBackend | null {
  const pane = snapshot?.panes.find((p) => p.pane_id === paneId);
  if (pane) return backendForAgentSessionInfo(pane.agent_session) ?? getBackendOrNull(pane.agent ?? "");
  const agent = snapshot?.agents.find((a) => a.pane_id === paneId);
  if (agent) return backendForAgentSessionInfo(agent.agent_session) ?? getBackendOrNull(agent.agent);
  return null;
}
```

Rewrite `commands.ts`'s `backendForPane` to call
`resolveBackendForPane(deps.feed.snapshot as SessionSnapshot | null, paneId)`
and `sessions.ts`'s to call it with `await herdr.snapshot()`, keeping its
throw-404-on-null policy. Each caller keeps its own snapshot source and null
policy — only the three-step chain is shared.

**Verify**: `cd bridge && npm run typecheck && npm test` → green.

### Step 2: Validate `send_text` and `steer`

In `commands.ts`, with the constants imported from their owning modules:
- `send_text`: apply `sanitizeAnswerText` semantics but **reject instead of
  silently altering** — throw when the text is not a string, is empty, exceeds
  `MAX_ANSWER_LENGTH` (import from `../questions.js` — path relative to
  `commands.ts` is `./questions.js`), or when
  `sanitizeAnswerText(text) !== text` (i.e. the input contained newlines or
  control characters). Error message:
  `"send_text requires plain single-line text (max 4000 chars)"`.
- `steer`: newlines are legal (multi-line prompts). Reject when not a string,
  empty, longer than `MAX_PROMPT_LENGTH`, or matching `PROMPT_FORBIDDEN_CHAR`
  — export those two from `bridge/src/sessions.ts` if they are not already
  exported, and import them here. Error message:
  `"steer text must be plain text without control characters"`.

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/ws-commands.test.ts` → existing cases green (they use valid text).

### Step 3: Replace the inline generic fallback

The `else` branch in `answer_question` (`commands.ts:62-74`) re-implements
pi's protocol. Replace it with the minimal generic behavior only — type then
Enter — and drop the keys-replay (an unknown agent has no known questionnaire
to navigate):

```ts
      } else {
        // Unknown agents get plain type-then-submit; questionnaire key
        // protocols belong to a registered backend.
        if (!safe) throw new Error("answer_question requires text for unknown agents");
        await deps.herdr.paneSendText(paneId, safe);
        await deps.herdr.paneSendKeys(paneId, ["Enter"]);
      }
```

Check `bridge/test/ws-commands.test.ts` for fallback tests that assert the
keys-replay behavior and update them to the new contract.

**Verify**: focused suite green.

### Step 4: Enforce actions at the route and the capability set

- `routes/sessions.ts` `controlRoute`: validate `body.action` against the
  `ControlAction` union before calling `controlSession`. Find the union's
  definition first (`grep -rn "type ControlAction" bridge/src/agents/`), then
  declare a const array that mirrors it, locked together with
  `satisfies readonly ControlAction[]`. Unknown or missing action →
  `{ status: 400, body: { ok: false, error: "unknown control action: <value>" } }`.
- `sessions.ts` `controlSession`: after resolving the backend, add
  a capability check before `backend.control(...)`:

```ts
  if (!backend.capabilities.has(action)) {
    throw new SessionsError(`${backend.id} does not support ${action}`, 400);
  }
```

  Keep the pre-backend `abort` fast path exactly as-is (its comment explains
  why it must not depend on a snapshot).

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/sessions-http.test.ts` → green (update/extend cases as needed).

### Step 5: Correct status codes from `readSessionRoute`

In `readSession` (`routes/sessions.ts:50-56`), throw
`new SessionsError("session path is outside a registered session store", 403)`
instead of a bare `Error` (import `SessionsError` from `../sessions.js`).
Then delete the local try/catch in `readSessionRoute` and let the dispatcher
map the status. Genuine unexpected read errors will then surface as 502 via
the dispatcher instead of 500 — if an existing test pins 500, keep a minimal
catch that rethrows `BridgeError` subclasses and wraps anything else in
`new BridgeError(message, 500)`.

**Verify**: focused suite green; add the 403 case (Test plan).

### Step 6: Match answers by `questionIndex`

In `bridge/src/questions.ts` (`:79-90` area), build the lookup per call id:

```ts
    const answers = answersByCallId.get(call.id) ?? [];
    const byIndex = new Map<number, (typeof answers)[number]>();
    for (const a of answers) {
      if (a.questionIndex !== undefined) byIndex.set(a.questionIndex, a);
    }
    raw.forEach((question, index) => {
      const match =
        byIndex.get(index) ??
        (byIndex.size === 0 && index < answers.length ? answers[index] : undefined);
```

i.e. prefer `questionIndex`; fall back to positional order **only when no
answer in the call carries an index** (preserves behavior for old
transcripts).

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/questions.test.ts` → green plus the new cases.

## Test plan

- `test/ws-commands.test.ts` — rebuild `makeDeps(agent?: "pi" | "claude")` to
  provide a feed whose snapshot has one pane owned by the requested backend
  (copy the exact pane/`AgentSessionInfo` shape from
  `bridge/test/support/fake-feed.ts` or `bridge/src/herdr/types.ts`). New cases:
  1. pi + keys + text: recorded herdr calls are `paneSendKeys(keys)`,
     `paneSendText(sanitized text)`, `paneSendKeys(trailingKeys)` in order.
  2. pi + keys only (option select): keys sent, no text, no trailing Enter.
  3. claude + multi-line text: text flattened to one line, then Enter.
  4. unknown agent + text: type + Enter (new fallback contract).
  5. `send_text` with a newline in the text → throws; valid text → sent.
  6. `steer` containing a NUL character (build it in the test with
     `String.fromCharCode(0)`) → throws; multi-line steer → accepted.
- `test/sessions-http.test.ts` — control with `action: "definitely-not-real"`
  → 400 (not 502); claude pane + `action: "fork"` (fork is absent from
  `CLAUDE_CAPABILITIES` — verify by reading
  `bridge/src/agents/claude/index.ts:199` first; if fork IS present, pick an
  action genuinely absent) → 400 naming the backend; session read for a path
  outside all stores → 403.
- `test/questions.test.ts` — answers arriving with `questionIndex` out of
  positional order attach to the right questions; a transcript whose answers
  lack `questionIndex` keeps today's positional pairing (characterize with an
  existing fixture).
- Pattern to follow: the existing tests in each file (node:test `describe`/
  `test`, `assert/strict`).

**Verification**: `cd bridge && npm test` → all pass, ≥9 new cases.

## Done criteria

- [ ] `cd bridge && npm run typecheck && npm test` exits 0
- [ ] `grep -n "resolveBackendForPane" bridge/src/commands.ts bridge/src/sessions.ts bridge/src/agents/registry.ts` — defined once, called from both
- [ ] `grep -n "as never" bridge/src/routes/sessions.ts` returns nothing
- [ ] `grep -n "capabilities.has" bridge/src/sessions.ts` returns a match
- [ ] `grep -n "byIndex" bridge/src/questions.ts` shows `questionIndex` being read
- [ ] A test exists in `ws-commands.test.ts` that asserts on `piAnswerQuestion`'s key sequence through `handleCommand` (not the fallback)
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- `fake-feed.ts` / the pane type shape doesn't match what step 1's resolver
  needs (e.g. `agent_session` missing from fake panes) and extending the fake
  would change its public API used by other suites — report first.
- The `ControlAction` union includes actions not handled by either backend's
  switch — that's a pre-existing inconsistency to report, not paper over.
- Capability enforcement (step 4) breaks an existing test that exercises an
  action outside the backend's declared set — the declared capabilities are
  wrong, which is a product decision; report it.
- Any Android emulator test (`ChatControlsTest`, `QuestionCardTest`) starts
  failing after these bridge changes — the wire contract moved; stop.

## Maintenance notes

- A third agent backend now needs: an adapter dir + `registerBackend` call,
  and nothing in `commands.ts`/`sessions.ts` — verify that stays true in review.
- The deferred move of pi-specific `questions.ts` → `agents/pi/questions.ts`
  (audit DEBT-03) builds directly on this plan; do it when the file next
  changes.
- Reviewer: check step 2's caps against what the Android composer actually
  sends (`ChatViewModel.send`) — the send_text cap matches
  `MAX_ANSWER_LENGTH`; the steer cap must comfortably exceed the app's
  longest legitimate prompt (it reuses the createSession prompt limit).
