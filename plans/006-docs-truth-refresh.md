# Plan 006: Make the agent-facing docs tell the truth again

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- AGENTS.md README.md docs/architecture/README.md`
> If any in-scope file changed since this plan was written, re-verify each
> claim below against the live code before editing.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

`AGENTS.md` is the first file every coding agent reads, and it currently
sends refactorers to `bridge/src/pi/` — a directory deleted when the agent
seam shipped — while omitting the two directories (`routes/`, `agents/`) that
the last three refactors created specifically so future work flows through
them. `docs/architecture/README.md` still lists shipped plans as pending, so
an agent could re-plan finished work. The README front page understates what
shipped (claims Claude Code transcripts are not exposed — they are), carries
a 5×-stale test count used as a pass/fail signal, shows an architecture
diagram with a layer that was removed ("spawns `pi --mode rpc`"), and has two
sections numbered 7. Stale docs are worse than missing ones: each of these
actively misleads.

Every edit below states the verified ground truth to write. Verify each
against the code anyway (the drift check above) — docs plans rot fastest.

## Current state (verified 2026-08-12 at `1ece5c9`)

1. `AGENTS.md:9` lists bridge modules as
   `herdr/ ... transcript.ts ... pi/commands.ts ...` — there is no
   `bridge/src/pi/`; the real layout adds `routes/` (route table +
   dispatcher: `routes/index.ts`, `routes/dispatcher.ts`, per-feature route
   modules) and `agents/` (`registry.ts`, `types.ts`, `pi/`, `claude/`).
   Verify: `ls bridge/src/agents bridge/src/routes; ls bridge/src/pi 2>&1`.
2. `AGENTS.md:16` says "read before any refactor in `bridge/src/pi/`, …" —
   dead path; should name `bridge/src/agents/` (and its `pi/`, `claude/`
   subdirs).
3. `AGENTS.md` mentions the HerdrPort seam nowhere; the test seam
   (`bridge/src/herdr/port.ts` + `bridge/test/support/fake-herdr.ts` +
   `bridge/test/support/fake-feed.ts`) is what offline HTTP tests build on.
4. `docs/architecture/README.md` plans table marks plan 1 (agent backend
   seam) and plan 3 (route table + herdr fake) as "Strong" (i.e. pending) and
   only plan 2 as "Shipped". Git says otherwise: `688c020 feat(bridge,android):
   agent backend seam — pi and claude adapters` and `3c612bc refactor(bridge):
   route table, dispatcher-owned auth/body, and offline HTTP tests` +
   `86a5b7a refactor(bridge): declare HerdrPort seam and shared fake herdr`.
   The "Top recommendation" and "Sequencing" prose is likewise stale (it
   recommends starting work that has since shipped).
5. `README.md:8-18` diagram shows `spawns pi --mode rpc` and a
   "pi RPC sessions (app-owned chat)" box; `docs/decisions.md` ("Sessions v2
   (layer 3)") records that layer as **removed with no shims** — sessions are
   pane-native now. `README.md:20-23` bridge bullet repeats "Spawns app-owned
   `pi --mode rpc` sessions."
6. `README.md:48` says `npm test  # 36 tests (socket client, parser, usage,
   RPC, ntfy)` — the suite is ~172 tests and "RPC" no longer exists. Other
   counts live in `docs/dev-workflow.md` ("≈147") and `AGENTS.md` ("~172") —
   run the suite once and put the real current count ONLY in AGENTS.md's
   verification section; elsewhere drop counts entirely (they rot per
   commit).
7. `README.md` has two `## 7.` sections ("Configuration reference" at `:284`
   and "Security notes" at `:300`), then `## 8.` and `## 9.` — renumber
   7/8/9/10.
8. `README.md:328-329` (Known limits): "Claude Code: status + steer work via
   herdr; transcripts are not exposed (the session path guard is pi-only)."
   False since `688c020`: `bridge/src/agents/claude/transcript.ts` parses
   claude transcripts and `backendForSessionPath`
   (`bridge/src/agents/registry.ts:26-31`) makes the guard multi-backend.
   The real residual limits worth stating: claude has no fork-from-transcript
   (`claudeResumeCommand` throws for `mode === "fork"`,
   `bridge/src/agents/claude/index.ts:39-43`), no model catalog
   (`hasModelCatalog` false → the app hides the model picker), and a smaller
   control set (`CLAUDE_CAPABILITIES` at `claude/index.ts:199` — read it and
   list the actual verbs).
9. `README.md` "Configuration reference" table omits env vars the bridge
   reads; one of them (`COCKPIT_REPO_ROOTS`) appears in a user-facing 403
   error message. Full set to document (verify each with grep before
   writing): `XDG_CONFIG_HOME` (`bridge/src/config.ts:25`),
   `HERDR_SOCKET_PATH` (`bridge/src/cli.ts` / `herdr/client.ts`),
   `COCKPIT_REPO_ROOTS` (`bridge/src/review.ts:96`), `PI_CODING_AGENT_DIR`
   (`bridge/src/usage/auth.ts`, `agents/pi/models.ts`, `agents/pi/commands.ts`),
   `PI_CODING_AGENT_SESSION_DIR` (`agents/pi/index.ts`, `session-catalog.ts`),
   plus the already-documented `PI_BIN`, `PI_NODE_BIN`, `COCKPIT_PUBLIC_HOST`.

## Commands you will need

| Purpose | Command | Expected on success |
|---------|---------|---------------------|
| Ground-truth the suite count | `cd bridge && npm test 2>&1 | tail -5` | pass line with the real test total |
| Verify paths | `ls bridge/src/agents bridge/src/routes` | both exist |
| Verify claude capabilities | `sed -n '195,210p' bridge/src/agents/claude/index.ts` | the capability set |

## Scope

**In scope**:
- `AGENTS.md` (the architecture-map bullet and the extendability-plans bullet only)
- `README.md`
- `docs/architecture/README.md` (status table + recommendation prose only)

**Out of scope**:
- `docs/decisions.md`, `docs/AUDIT.md`, `docs/shipping-report.md` — historical
  records; do not rewrite history. (A one-line "superseded" header on
  AUDIT.md is allowed if trivial, but not required.)
- The seven architecture plan files 01–07 themselves.
- Any source code.

## Git workflow

- Work directly on `main`. Commit style: `docs: ...` (see `bff5a6f`,
  `5119c3f` in git log for the convention).

## Steps

### Step 1: Fix the AGENTS.md architecture map

Rewrite the bridge bullet (AGENTS.md:9) to name: `herdr/` (socket client +
feed + `port.ts` seam), `routes/` (route table + dispatcher own auth/body/
error-mapping; one module per feature), `agents/` (backend registry;
`agents/pi/`, `agents/claude/` adapters; `agents/types.ts` is the
`AgentBackend` interface), `transcript.ts`, and the remaining per-feature
modules that still exist (verify each name against `ls bridge/src`). Add one
sentence: offline HTTP tests use the fakes in `bridge/test/support/`.
Fix the path in the extendability bullet (AGENTS.md:16):
`bridge/src/pi/` → `bridge/src/agents/`. Update the test-count parenthetical
to the number you measured.

**Verify**: `grep -n "bridge/src/pi" AGENTS.md` → no matches.

### Step 2: Update the architecture index

In `docs/architecture/README.md`: mark plans 1 and 3 as **Shipped** with
their commit hashes (1 → `688c020`, 3 → `3c612bc` + `86a5b7a`), keep plan 2's
shipped marker, and rewrite the "Top recommendation" paragraph to reflect
reality: 2→3→1 shipped in order; the live recommendation is now plan 4
(CockpitApi seam), with 5–7 as documented. Do not touch the analysis prose
above the table — it is a correct historical record of *why*.

**Verify**: `grep -n "Shipped" docs/architecture/README.md` → three plan rows.

### Step 3: Fix the README

Apply items 5–9 from "Current state":
- Diagram + bridge bullet: replace the RPC-spawn layer with pane-native
  session creation (the bridge creates a herdr workspace whose pane runs the
  agent CLI — `pi --model …` or `claude …`).
- Test-count line: drop the count and the "RPC" word; say what the suite
  covers today (socket client, transcript parsing, routes offline via fake
  herdr, usage, ntfy, agents).
- Renumber the duplicate sections.
- Rewrite the Claude Code known-limits bullet per item 8 (verify the
  capability list from the source first).
- Extend the configuration table per item 9.

**Verify**: `grep -c "^## 7\." README.md` → 1; `grep -n "mode rpc" README.md`
→ no matches outside a historical-context sentence if you keep one;
`grep -n "transcripts are not exposed" README.md` → no matches.

## Test plan

Docs-only: the verification greps above are the tests. Additionally run
`cd bridge && npm run typecheck` and confirm the working tree contains no
source changes (`git status` shows only the three doc files).

## Done criteria

- [ ] `grep -rn "bridge/src/pi" AGENTS.md README.md docs/architecture/README.md` → no matches
- [ ] `docs/architecture/README.md` marks plans 1, 2, 3 shipped with commits
- [ ] README has exactly one `## 7.` and sections run 1–10 without duplicates
- [ ] README no longer claims claude transcripts are unexposed, no longer says "36 tests", and documents `COCKPIT_REPO_ROOTS`
- [ ] `git status` shows only AGENTS.md, README.md, docs/architecture/README.md modified
- [ ] `plans/README.md` status row updated

## STOP conditions

- Any claim in "Current state" fails its verification command (the codebase
  moved again) — re-derive that item from the code, and if the discrepancy is
  in *source behavior* rather than docs, report instead of documenting it.
- You find yourself wanting to edit source or `docs/decisions.md` — out of
  scope, stop.

## Maintenance notes

- Test counts rot on every commit; this plan leaves exactly one approximate
  count (AGENTS.md verification section). If it drifts again, prefer deleting
  it over updating it.
- When plan 04 (CockpitApi seam) or a third agent backend ships, the
  architecture index needs the same status-table update — cheap now that the
  convention (strength column → "Shipped + commit") exists.
