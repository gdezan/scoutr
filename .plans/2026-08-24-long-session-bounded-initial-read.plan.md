# Long-session Bounded Initial Read Blueprint

## Current situation

After the reverse-pagination blueprint lands, new Android clients will request only the newest page of a long session and fetch older pages on demand. That fixes the wire-size problem, but the bridge can still pay the cost of reading/parsing the entire transcript before slicing that first page because `bridge/src/routes/sessions.ts -> readTranscriptMemoized()` calls `backend.readTranscript(target)` without read options.

The repository already contains the primitives needed for a safer bounded initial read:

- `bridge/src/transcript.ts -> TranscriptReadOpts.tail` keeps only recent entries and `readTranscriptText(..., { tail })` reads a bounded 64 KiB end window instead of the whole file;
- all registered `AgentBackend` implementations accept `readTranscript(path, opts?)`;
- Pi and Claude already forward those opts into their parser;
- `BoardDetailCache` uses the same bounded-tail mechanism for activity and combines it with separate metadata/state reads when exact model/thinking state is needed;
- `bridge/test/transcript.test.ts` already proves the tail read is actually bounded by the file window and safely drops a partial first line.

What the current tail primitive does **not** guarantee is exactly N entries. A very large record or dense tail can produce fewer entries than requested because the 64 KiB file window is the hard I/O bound. It also may not include header metadata from the start of the file. Those are known constraints and should remain explicit rather than hidden behind a fake exact-page promise.

Repository rules are in `AGENTS.md`. Verification follows `skills/scoutr-verification/SKILL.md`, and the performance study must be updated in the same change when a performance slice is implemented or reclassified.

## Objective and why

Make the **first paginated Chat read** bounded on the bridge as well as on the network, so opening a multi-megabyte session does not require reading/parsing that multi-megabyte file merely to send the newest page.

Definition of done:

- the new `limit=N` initial-page mode from the reverse-pagination blueprint no longer calls the full transcript reader before first paint;
- the bridge reads only bounded transcript windows plus the minimum metadata/state reads needed to preserve the existing session response contract;
- a large file can produce a useful newest page without I/O proportional to the whole transcript;
- ordinary forward `since`, reverse `before`, and legacy full-snapshot behavior remain unchanged in this slice;
- response fields that are required for Chat correctness (`model`, `thinkingLevel`, active questions, revision, last-entry identity) remain truthful under the bounded initial path;
- measurements distinguish host-side read/parse reduction from network improvement already delivered by pagination.

## Scope

Included:

- add a bounded initial-page read path inside the bridge for `GET /api/sessions?...&limit=N` with no `since`/`before`;
- reuse `AgentBackend.readTranscript(..., { tail })` and existing metadata/state seams instead of introducing a new transcript format;
- preserve the response contract established by the reverse-pagination blueprint;
- add focused bridge tests that prove the full reader is not used and the bounded path stays correct for large fixtures, partial-window boundaries, model/thinking metadata, Pi questions, and Claude sidecar questions;
- update `docs/performance-study.md` with observed bridge-side evidence.

Not included:

- optimizing reverse-history pages to bounded disk ranges;
- persistent per-session byte indexes;
- changing the opaque `before` cursor representation;
- Android behavior changes beyond any compatibility adjustment required by truthful bounded-tail semantics;
- gzip/brotli, WebSockets, local transcript caching, or `/api/agents` status work.

## Resolved decisions

1. **Optimize only the initial paginated read in this slice.** Reverse-history paging may continue to slice the full memo. That keeps the follow-up small and lets measurements show whether a byte/index design is actually necessary.
2. **Treat `limit` as a maximum.** The bridge may return fewer than `N` entries when the bounded tail window contains fewer complete normalized entries. Do not silently grow the window until exactly N messages are found; that would make the cost unbounded again.
3. **Keep the existing 64 KiB `TAIL_WINDOW_BYTES` as the first implementation bound.** Do not tune the window from intuition. Change it only if runtime/fixture evidence shows the page is routinely too small for useful first paint.
4. **Do not reuse bounded tail metadata blindly.** Tail reads can miss session header metadata and historical model/thinking changes. Use existing exact/bounded metadata seams to preserve response fields that Chat consumes.
5. **Question correctness is stronger than I/O purity.** Pi questions live in transcript events and may require more history than the recent tail to know whether an ask was answered. Claude's currently open ask can live outside the transcript in a sidecar. The bounded path must preserve current actionable-question behavior even if that requires a separate authoritative question read/cache. Do not infer question state from only the newest display entries.
6. **Prefer an internal derived-session cache over a new Android contract.** Any extra bridge cache for questions/metadata is keyed by the transcript revision plus backend question state stamp and remains invisible to the client.
7. **No unverified performance claims.** Structural proof that only bounded file windows were read is enough to describe bounded I/O; speed, memory, radio, or battery gains require measurement.

## Approach

For the initial paginated mode only:

```text
GET /api/sessions?...&limit=50
  -> inspect file revision
  -> read recent transcript via backend.readTranscript(path, { tail: 50 })
     (bounded tail window)
  -> obtain response metadata/state through existing bounded/exact seams
  -> obtain authoritative question state through a revision-aware derived cache/read
  -> build the same SessionReadResult contract as pagination blueprint
  -> send <= recent tail entries immediately
```

The key design boundary is to separate **display entries** from **authoritative session state**. The display page may be derived from a bounded tail, while model/thinking/question metadata may come from their own caches/read modes. `BoardDetailCache` is the closest precedent: it already combines a bounded display tail with exact/incremental state and out-of-transcript question stamps.

## Contracts and interfaces

### Initial-page mode

The HTTP contract from the reverse-pagination blueprint does not change:

```text
GET /api/sessions?...&limit=N
```

still returns chronological newest-page entries plus `beforeCursor`/`hasMoreBefore`.

The only semantic clarification is:

- `entries.size <= limit`;
- fewer than `limit` does **not** imply there is no older history;
- `hasMoreBefore` is authoritative and must be computed independently of the display-page count.

### Internal bridge split

Introduce an internal result seam around the initial read, conceptually:

```text
InitialSessionRead {
  recent: Transcript
  model: string | null
  thinkingLevel: string | null
  questions: QuestionEntry[]
  info: SessionFileInfo
  hasMoreBefore: boolean
  beforeCursor: string | null
}
```

Exact names and helper boundaries are local discretion. The important contract is that recent display entries and authoritative metadata/question state can come from different reads while sharing one validated file revision.

### Revision safety

All derived state used in one response must be tied to a known transcript revision. If the file changes while the bounded read is being assembled, follow the same bounded retry philosophy as `readTranscriptMemoized`: retry a small finite number of times, then return a coherent best effort without poisoning the memo as current. Do not loop until the live file stops growing.

## Changes

- [ ] **1. Extract the session metadata/question work from the full-transcript memo so bounded and full paths can share it.**
  - Anchor: `bridge/src/routes/sessions.ts` -> `readTranscriptMemoized`, `readSession`; `bridge/src/board-detail.ts` -> `BoardDetailCache` state/question-stamp precedent; `bridge/src/agents/types.ts` -> `readTranscript`, `readTranscriptState`, `questionStateStamp`
  - Work: create an internal revision-aware session-derived-state helper/cache for fields the route currently derives from the full transcript but the bounded display path still needs. Reuse `backend.readTranscriptState` for exact/incremental model/thinking observations where appropriate. Include `questionStateStamp` in the cache key/validation so Claude sidecar asks are not pinned by an unchanged transcript stat. For Pi/other transcript-owned questions, preserve authoritative extraction; if this requires a full question parse on the first bounded request, cache it by revision so the display path is still independently bounded and subsequent polls do not repeat the work.
  - Constraint: do not change `AgentBackend` unless an existing seam is provably insufficient. Prefer composing current `readTranscript`, `readTranscriptState`, `extractQuestions`, and `questionStateStamp` first.
  - Proof: focused bridge tests showing model/thinking and question state remain correct when the display tail omits the relevant older records, including a Claude question-stamp change with unchanged transcript mtime/size where the test fixture can model it.

- [ ] **2. Route `limit`-only initial pages through the bounded tail reader.**
  - Anchor: `bridge/src/routes/sessions.ts` -> paginated initial branch created by the reverse-pagination blueprint; `bridge/src/transcript.ts` -> `TranscriptReadOpts.tail`, `TAIL_WINDOW_BYTES`; `bridge/src/agents/pi/index.ts -> piReadTranscript`; `bridge/src/agents/claude/index.ts -> claudeReadTranscript`
  - Work: replace the full-memo call in the initial bounded branch with `backend.readTranscript(target, { tail: limit })`, combine it with the derived state from Change 1, and compute page cursors/history truth without assuming `recent.entries.length == limit`. Keep legacy full snapshot, forward `since`, and reverse `before` on their existing code paths.
  - Precedent: `BoardDetailCache.detailFor()` already performs `backend.readTranscript(path, { tail: TAIL_ENTRIES })` and separately fills metadata/state.
  - Proof: add a large session fixture where the file is well above `HEAD_WINDOW_BYTES + TAIL_WINDOW_BYTES`; assert the initial paginated response contains only tail entries, carries truthful `hasMoreBefore`, and preserves response metadata. Instrument the test seam or injected backend so it can assert the initial branch called `readTranscript(..., { tail: N })` rather than an unbounded read.

- [ ] **3. Harden bounded initial reads around live growth and partial windows.**
  - Anchor: `bridge/src/transcript.ts` -> `readWindows`, `dropPartialFirstLine`, `inspectSessionFile`; `bridge/src/routes/sessions.ts` -> existing three-attempt stable-read loop
  - Work: ensure the initial bounded path takes a file stat before/after the composite read and retries only on revision movement, with the same finite cap as the existing route. Never memoize a composite result against a revision it did not actually read. Preserve the existing tolerance for a partial first JSONL line in the tail window and for a continuously growing live transcript.
  - Proof: bridge tests for a tail window that begins mid-record, a file that grows during the first attempt, and a continuously growing/best-effort case if the current test seams can deterministically model it. At minimum, the tests must prove no malformed/partial entry is exposed and no stale revision is marked current.

- [ ] **4. Measure and record the bridge-side result without conflating it with pagination.**
  - Anchor: `docs/performance-study.md` -> long-session Chat item; existing `PerformanceCounters`/bridge metrics if useful for response bytes and request duration
  - Work: update the study to distinguish two shipped layers: reverse network pagination reduced the initial payload; this slice bounded the bridge's initial file read/display parse. Record test/fixture evidence for bytes read or read mode, and any runtime timing only if actually measured. Keep reverse-history disk optimization explicitly deferred unless the new evidence shows it remains material.
  - Proof: cold-read the performance section and verify every performance claim has either structural code/test evidence or a labeled runtime measurement.

## Failure handling

- Bounded tail contains fewer complete entries than requested: return those entries and a truthful `hasMoreBefore`; do not expand the disk window automatically.
- Bounded tail contains no complete display entries but the session exists: return an empty recent page with metadata/question state and `hasMoreBefore` according to the authoritative history calculation. Android remains usable and can retry/load history through the normal contract.
- Metadata/state auxiliary read fails: use the same best-effort/fallback semantics already established by the route/board-detail precedent where correctness allows it; do not invent a model or thinking level. If a field cannot be made truthful, leave it null rather than copying stale data across a newer revision.
- Question-state read fails: preserve current route failure semantics rather than hiding an actionable ask. If the route cannot provide authoritative question state, surface the session read failure instead of returning a page that falsely unlocks the composer.
- File grows during composite read: retry finitely. A continuously growing transcript gets a coherent best-effort response and is not cached as a stable revision.
- Unknown backend or path outside its store: current 403/security boundary stays untouched.

## Validation

Follow the normal Scoutr phase boundary:

1. `cd bridge && npm run typecheck`;
2. focused `bridge/test/transcript.test.ts`, `bridge/test/session-read.test.ts`, and backend/question tests touched by the derived-state seam;
3. no Android test is required for a bridge-internal optimization unless the response semantics change; if a compatibility adjustment is needed, run the narrow affected JVM/API tests;
4. run `skills/scoutr-review/SKILL.md`, resolve/dismiss findings, and re-run invalidated cheap checks;
5. final acceptance should include one large-session open against the deployed bridge with existing counters/metrics showing the response remains paginated and the bridge initial read no longer scales with the full file; use emulator integration only if needed to prove the user-visible open path still works end to end;
6. update the performance study with the exact evidence gathered.

## Local discretion

Helper/cache names, cache cap, and whether the derived state sits in `routes/sessions.ts` or a small adjacent module are local choices. Reuse existing bounded cache styles unless there is evidence they do not fit. The implementer may add test-only seams/counters to prove read mode, but they should not become production API unless useful beyond verification.

## Escalation triggers

Return to `gd` if:

- authoritative Pi question state cannot be preserved without repeatedly parsing the full transcript on every live revision and that cost is material enough to require a new incremental question index;
- the 64 KiB tail window routinely yields too little usable first paint and changing its size would materially change memory/I/O policy;
- computing truthful `hasMoreBefore` requires a persistent byte/index structure rather than cheap file/order information already available after the pagination slice;
- the bounded initial path forces a change to the Android pagination contract;
- measurements show reverse-history disk paging, not initial read, is now the dominant long-session cost. That would justify a separate byte-range/index blueprint rather than silently expanding this one.

## Completion checklist

- [ ] `limit`-only initial reads use `readTranscript(..., { tail: limit })` and do not require an unbounded display transcript read.
- [ ] Model/thinking/question fields remain authoritative under bounded display reads.
- [ ] Claude sidecar question changes are not hidden by transcript-only caching.
- [ ] Live file growth is finite-retry and revision-safe; partial tail records never escape parsing.
- [ ] Legacy full, forward `since`, and reverse `before` semantics are unchanged.
- [ ] Large-fixture tests prove bounded read mode and truthful history metadata.
- [ ] Review/targeted checks pass and the performance study records only verified results.

## References

- `AGENTS.md`
- `skills/scoutr-verification/SKILL.md`
- `skills/scoutr-review/SKILL.md`
- `docs/performance-study.md`
- `bridge/src/routes/sessions.ts`
- `bridge/src/transcript.ts`
- `bridge/src/agents/types.ts`
- `bridge/src/agents/pi/index.ts`
- `bridge/src/agents/claude/index.ts`
- `bridge/src/board-detail.ts`
- `bridge/test/transcript.test.ts`
- `bridge/test/session-read.test.ts`
