# Done Change / Ship-Readiness Summary Blueprint

## Current situation

A Done Board card currently tells the user that the agent settled, plus title/model/latest transcript activity. The user must then swipe/tap Review to learn what actually changed.

Scoutr already has the required deterministic repository evidence:

- `bridge/src/review.ts` exposes read-only `reviewOverview` (branch, upstream, ahead/behind, porcelain status) and `reviewDiff` (per-file additions/deletions);
- `bridge/src/routes/review.ts` authorizes active/historical agent workspaces and exposes the full Review UI APIs;
- `BoardScreen` already has a Review action for every agent card;
- Board polling is one `/api/agents` request every 3 seconds with bounded transcript enrichment.

What Scoutr **does not** currently have is trustworthy test-pass/reviewer-warning state attached to an agent completion. Generated artifacts are not proof that tests passed, and Herdr `done` means the agent settled, not that the repository is safe to ship.

That distinction is load-bearing: this feature should surface evidence and risk signals, not label work “safe” without evidence.

## Objective and why

When an agent reaches Done, make its Board card immediately answer “what changed, and is there anything obviously risky about the repo state?” using deterministic git evidence, with Review one tap away for detail.

Done means a completed agent card can show branch, changed-file count, additions/deletions, clean/dirty state, and ahead/behind information without opening Review; the UI never claims tests passed or code is safe to ship unless a future explicit evidence source exists.

## Scope

Included:

- bridge-side lightweight repo summary for Done agents;
- bounded/memoized computation compatible with 3-second Board polling;
- Android DTO/rendering for Done cards;
- risk wording derived only from known git facts;
- direct Review affordance from the summary;
- tests for repo/non-repo/clean/dirty/truncated/error states.

Non-goals:

- no LLM-generated code review summary;
- no running tests from the Board poll;
- no reading CI/GitHub status;
- no “safe to ship” boolean;
- no stage/commit/revert actions;
- no full diff hunks inside Board;
- no repository scan for Working/Idle/Needs-you cards.

## Global constraints

- Board remains a glanceable supervision surface governed by `ui/theme/DESIGN.md`.
- Repo inspection remains read-only and uses the existing review safety invariants: realpath containment, fixed git subcommands, caps, timeout, no shell.
- Only Done agents are enriched with repo summaries by this feature.
- Board polling must not trigger an unbounded catalog scan or multiple redundant git subprocess sets for agents sharing the same repo.
- Summary failures never fail `/api/agents`; they degrade to no/limited repo summary.
- Final runtime/emulator verification is after review-clean/code-freeze.

## Resolved decisions

### Evidence contract

Add an optional deterministic summary:

```ts
interface DoneRepoSummary {
  repoRoot: string;
  branch: string | null;
  upstream: string | null;
  ahead: number;
  behind: number;
  changedFiles: number;
  additions: number;
  deletions: number;
  dirty: boolean;
  statusTruncated: boolean;
  diffTruncated: boolean;
}
```

If the cwd is not inside a git repository, `doneSummary = null` rather than an error card.

`dirty` means porcelain status contains entries. `changedFiles/additions/deletions` come from the working-tree diff against `HEAD` and therefore are a change summary, not a quality judgment. Untracked files that do not appear in normal diff stat must still be reflected in `dirty` and changed-file messaging where possible; do not silently call a repo “0 files changed” when status contains untracked files.

### User-facing language

Prefer evidence-first copy such as:

```text
12 files · +248 −91
main · uncommitted
2 ahead · 0 behind
```

or for a clean repository:

```text
Working tree clean
main · 1 ahead
```

Do **not** render “Ready to ship”, “Safe”, “Tests passed”, “Reviewed”, or warning counts that Scoutr cannot verify.

The feature can still be described internally as ship-readiness because it reduces the work required to judge readiness; the UI labels the facts, not the conclusion.

### Computation/caching

Create a dedicated Board repo-summary cache/helper rather than calling the HTTP Review routes from inside `/api/agents`. Reuse pure functions from `review.ts` with the Done agent's repo root as an explicitly authorized extra root.

Cache by canonical repo root with a short TTL and share in-flight computation. Multiple Done agents in the same repo should cost one summary computation per TTL window. A target around 5–10 seconds is acceptable; exact TTL is local discretion and must be tested with a fake clock if practical.

Do not key solely on transcript mtime: repository state can change independently of the agent transcript.

### Review transition

The existing Board Review action remains the detailed path. The Done summary itself may make the card's Review action more prominent, but tapping the main card continues opening Chat unless a separate product decision changes navigation globally.

## Approach

Add a bridge `BoardRepoSummaryCache` beside `BoardDetailCache`. During `/api/agents` enrichment, collect Done cards with valid cwd values, resolve/dedupe repo roots, compute cached `reviewOverview` + `reviewDiff(HEAD, working)` summaries, and attach them best-effort. Android renders two compact evidence rows on Done cards and keeps the full Review screen as the drill-down.

## Contracts and interfaces

### Bridge helper

A module such as `bridge/src/board-repo-summary.ts` should expose:

```ts
export interface DoneRepoSummary { ... }

export class BoardRepoSummaryCache {
  summaryFor(cwd: string): Promise<DoneRepoSummary | null>
  prune?(liveRoots: ReadonlySet<string>): void
}
```

The helper may call `gitRepoRoot` then `reviewOverview`/`reviewDiff` using the resolved root as the extra allowed root. It must not import route-layer `reviewRoots`, which performs catalog-wide authorization work unnecessary for a cwd already sourced from a live agent.

### Changed-file count rule

Use the union of paths from status and diff stat for the visible `changedFiles` count so untracked/rename/status-only entries are not omitted. Additions/deletions sum only the diff stat values Scoutr actually has.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | live Done cwd + review primitives | cached `DoneRepoSummary?` |
| 2 | summary cache | `/api/agents` done-summary field |
| 3 | Android DTO | Done card evidence rows |
| 4 | summary state | Review drill-down and accessibility semantics |

## Changes

- [ ] **1 — Build a bounded shared Done repo-summary helper**
  - Anchor: `bridge/src/review.ts` → `gitRepoRoot`, `reviewOverview`, `reviewDiff`, review safety constants
  - Create `BoardRepoSummaryCache` with canonical-root deduplication, short TTL, and shared in-flight work.
  - Run overview and working-tree diff concurrently after repo root resolution when safe to do so.
  - Derive the changed-file union, additions/deletions totals, dirty flag, truncation flags, branch/upstream/ahead/behind.
  - Non-repo cwd returns null; review/git error is best-effort null or a deliberately bounded unavailable marker, not a route failure.
  - Proof: unit tests cover clean repo, tracked modifications, untracked files, ahead/behind fixture/mocks, non-repo, timeout/error, cache reuse, and same-repo dedupe.

- [ ] **2 — Attach summaries only to Done agents in the Board response**
  - Anchor: `bridge/src/routes/agents.ts` → `deriveAgentCardsWithDetail`, `agents`
  - Wire one cache instance through server route deps similarly to `BoardDetailCache` rather than constructing it per request.
  - Enrich only `done`/`completed` status cards with cwd; no git work for Working/Blocked/Idle.
  - Use `Promise.all`/deduped cache access so one slow repo does not serialize unrelated Done cards.
  - Summary failure for one card must not remove other cards or turn `/api/agents` into 500.
  - Proof: route tests assert zero repo-summary calls for non-Done agents and stable best-effort response when one Done repo fails.

- [ ] **3 — Mirror and render deterministic Done evidence on Android**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/Models.kt` → Board/canonical session DTO
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt` → `AgentCardRow`
  - Add optional `doneSummary` DTO.
  - Done card: render compact change stats (`N files · +A −D`) and branch/repo-state metadata beneath latest activity. Use normal status gray for Done; additions/deletions follow the Review design color rules only if those established colors are already available in shared tokens, otherwise keep the Board row neutral rather than introducing new color semantics.
  - Dirty wording must be explicit (`uncommitted`/`working tree changed`); clean wording must say `Working tree clean`, not `ready`.
  - Truncated summaries show a quiet `+ more`/`summary truncated` indication rather than false exactness.
  - Proof: Compose tests cover dirty, clean, ahead/behind, truncated, and null summary cards at narrow width/font scaling.

- [ ] **4 — Make Review the obvious next action without changing its read-only contract**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt` → swipe Review action / Done summary semantics
  - Preserve existing `onReviewAgent` flow into `ReviewViewModel.selectRepo`/Review tab.
  - Give the summary an accessibility action/description that makes the drill-down discoverable; do not make inline stats individually interactive.
  - Review remains read-only; no commit/stage/revert buttons are added.
  - Proof: UI test activates Review from a Done card and verifies the selected repo path is the agent cwd/current repo as existing flow expects.

## Failure handling

- cwd is not a git repository: no Done summary, card still renders normally.
- Git command times out/fails: omit/unavailable summary without failing Board.
- Status/diff exceeds caps: mark truncation and never present totals as complete.
- Agent reaches Done while files continue changing from another process: TTL cache converges within its bounded window; do not tie correctness to transcript mtime.
- Multiple Done agents share a repo: one in-flight/cache entry is reused.
- Agent becomes Working again: stop requesting/rendering `doneSummary` on the next Board response.
- No upstream: omit ahead/behind line rather than showing invented zeros as tracking evidence.

## Validation

1. Focused Board repo-summary helper tests using temp git repos/fakes.
2. `/api/agents` route tests for status gating and best-effort behavior.
3. Android DTO and Board Compose tests.
4. `make bridge-test`.
5. `make android-test`.
6. Independent review via `skills/scoutr-review/SKILL.md`, specifically checking that no user-facing claim exceeds the available evidence and Board polling cost stays bounded.
7. Final runtime acceptance via `skills/scoutr-verification/SKILL.md`: after review-clean/code-freeze, finish a real agent with tracked + untracked changes, verify Board summary, open Review, then make repo clean and verify the summary converges. Run this once as the terminal verification step.

## Local discretion

- Cache TTL within a short bounded range appropriate for the 3-second Board cadence.
- Exact neutral copy/line breaks for change stats under the existing design tokens.
- Whether branch/ahead-behind lives on one or two metadata lines based on width tests.
- Whether an unavailable git summary is omitted or shown as a quiet `Repo summary unavailable`; prefer omission unless user action can fix it.

## Escalation triggers

- Product asks for an actual “safe to ship” verdict without a defined evidence model for tests/review/CI.
- Summary implementation starts running tests, parsing arbitrary logs, or invoking an LLM from Board polling.
- Reusing review primitives requires weakening existing path authorization or shell/input safety.
- Board latency becomes materially dominated by git subprocesses despite cache/dedupe.
- Accurate per-agent changes require knowing the agent's start commit/baseline rather than current working tree vs `HEAD`; that is a different provenance feature and needs a separate contract.

## Review handoff

Reviewer must verify every displayed statement maps to a concrete git fact, untracked files cannot produce a misleading “0 files changed/clean” summary, truncation is visible, and only Done agents incur repo-summary work. Inspect cache/in-flight behavior so the 3-second Board poll cannot spawn redundant subprocess storms.

Rerun both cheap suites after review fixes, then perform the one final real-agent/runtime acceptance pass.

## Completion checklist

- [ ] Done agents receive a bounded deterministic repo summary.
- [ ] Working/Blocked/Idle agents cause no repo-summary work.
- [ ] Summary includes branch, dirty/clean, changed-file union, +/- totals, and tracking state when known.
- [ ] Untracked files are represented honestly.
- [ ] Truncation/error states never become false exactness.
- [ ] Board copy does not claim safety/tests/review without evidence.
- [ ] Review remains the detailed read-only drill-down.
- [ ] Cache/dedupe prevents per-poll subprocess multiplication.
- [ ] Bridge and Android cheap suites pass.
- [ ] Independent review is clean.
- [ ] Final runtime acceptance passes once, last.

## References

- `AGENTS.md`
- `bridge/src/review.ts`
- `bridge/src/routes/review.ts`
- `bridge/src/routes/agents.ts`
- `bridge/src/board-detail.ts`
- `android/app/src/main/java/dev/scoutr/app/data/Models.kt`
- `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ReviewViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/ReviewScreen.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- `docs/decisions.md`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
