# Plan 003: Stop re-reading whole transcript stores on every poll

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 1ece5c9..HEAD -- bridge/src/transcript.ts bridge/src/session-catalog.ts bridge/src/routes/sessions.ts bridge/src/routes/review.ts bridge/src/review.ts bridge/src/board-detail.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (the `since`-cursor semantics are load-bearing; see STOP conditions)
- **Depends on**: none (merge-overlaps with plan 004 in `routes/sessions.ts`; land this first)
- **Category**: perf
- **Planned at**: commit `1ece5c9`, 2026-08-12

## Why this matters

The bridge runs on the same machine as the agents it supervises, and its three
hottest read paths all do unbounded, uncached filesystem work per poll:

1. **Chat**: the app polls `GET /api/sessions?path=…&since=…` every 2.5s while
   a chat is open; the route reads and JSON-parses the *entire* transcript
   file each time and applies the `since` cursor only after the full parse. A
   multi-MB session is parsed ~24×/minute exactly when the user is engaged.
2. **Catalog**: `listSessionCatalog` stats and window-reads up to 500 files
   (128 KiB head + 64 KiB tail each, worst case ≈ 96 MB parsed per call). The
   history screen re-runs it every 8s; the command palette runs it per
   debounced keystroke with a comment calling it "cheap".
3. **Review**: opening the Review screen fires three routes, each calling
   `reviewRoots()`, each of which does a **full catalog scan** plus one
   sequential `git rev-parse --show-toplevel` subprocess per session cwd.

The repo already contains the correct pattern — `BoardDetailCache` memoizes by
`(path, mtimeMs, size)` — the fix is applying it to the other read paths.
Two correctness bugs ride along because they live in the same lines: `readSlice`
ignores `bytesRead` (short reads yield NUL-padded garbage), and one bad file
(dangling symlink, EPERM dir) aborts the whole catalog scan, 500-ing the
Sessions tab.

## Current state

- `bridge/src/transcript.ts` — the one JSONL reader.
  - `readTranscriptText` (`:117-127`): `metadataOnly` reads head+tail windows;
    `tail` reads a tail window; the default reads **the whole file**:

```ts
export async function readTranscriptText(path: string, opts: TranscriptReadOpts = {}): Promise<string> {
  if (opts.metadataOnly) return readWindows(path, { includeHead: true });
  if (opts.tail !== undefined) return readWindows(path, { includeHead: false });
  const handle = await open(path, "r");
  try {
    const info = await handle.stat();
    return readSlice(handle, 0, Number(info.size));
  } finally {
    await handle.close();
  }
}
```

  - `readSlice` (`:154-163`) ignores the read result:

```ts
async function readSlice(handle, start, length): Promise<string> {
  if (length <= 0) return "";
  const buffer = Buffer.alloc(length);
  await handle.read(buffer, 0, length, start);
  return buffer.toString("utf8");
}
```

  - `inspectSessionFile` (`:103-111`) returns `{path, exists, size, mtimeMs}` —
    the natural memo key.

- `bridge/src/routes/sessions.ts` — `readSession` (`:50-92`): full read, then
  cursor slice **after** parse:

```ts
  const session = await backend.readTranscript(target);
  let entries = session.entries;
  let cursor: string | null = since;
  if (since) {
    // Compare by file position, not lexically: pi ids are random hex, so
    // lexical order re-sends loaded entries and the app appends duplicate
    // LazyColumn keys (Compose crashes on those).
    const cursorIndex = session.entries.findIndex((entry) => entry.entryId === since);
    if (cursorIndex === -1) {
      cursor = null;   // full snapshot; app replaces its list
    } else {
      entries = session.entries.slice(cursorIndex + 1);
    }
  }
```

  The cursor semantics (`docs/decisions.md`, "Chat scroll crash — duplicate
  LazyColumn keys") are: unknown cursor ⇒ respond with `since: null` and the
  full entry list, and the app replaces its list. **These semantics must not
  change.**

- `bridge/src/board-detail.ts:25-47` — the exemplar memo to copy:

```ts
export class BoardDetailCache {
  private readonly memo = new Map<string, { mtimeMs: number; size: number; detail: BoardDetail }>();
  async detailFor(path: string): Promise<BoardDetail | null> {
    const info = await inspectSessionFile(path);
    if (!info.exists) return null;
    const cached = this.memo.get(path);
    if (cached && cached.mtimeMs === info.mtimeMs && cached.size === info.size) {
      return cached.detail;
    }
    // ... read, derive, memo.set, evict beyond MEMO_CAP ...
  }
}
```

- `bridge/src/session-catalog.ts`:
  - `listSessionCatalog` (`:139-155`): `findSessionFiles` walks the roots,
    then per scanned file `readCatalogFile(file)` does
    `backend.readTranscript(path, { metadataOnly: true })`. `MAX_SCANNED_FILES = 500`.
    No memo anywhere in the module.
  - Unguarded filesystem calls: `await stat(path)` at `:143` (active refs),
    `readdir(path, ...)` at `:212` (nested dir), and `addSessionFile`
    (`:222-227`) — `realpath` + `stat` with no try/catch:

```ts
async function addSessionFile(root: string, path: string, files: SessionFile[]): Promise<void> {
  const canonical = await realpath(path);
  if (!isInside(root, canonical)) return;
  const info = await stat(canonical);
  if (info.isFile()) files.push({ path: canonical, mtimeMs: info.mtimeMs });
}
```

  One throw propagates to `routes/catalog.ts` → 500 for the whole Sessions
  tab, and also degrades the review allow-list (which consumes the catalog).
  The module's stated policy for roots is "a missing store is an empty store"
  (`:191`) — extend that policy to entries.

- `bridge/src/routes/review.ts` — `reviewRoots` (`:51-66`) runs a full catalog
  scan per request, and `sessionWorkspaceRoots` awaits `add(cwd)` sequentially
  (each `add` may spawn `git rev-parse --show-toplevel` via
  `gitRepoRoot`, `bridge/src/review.ts:181-190`). All three review routes
  (`/api/repo`, `/api/repo/diff`, `/api/repo/artifacts`) call `reviewRoots`
  independently, and the Android Review screen hits all three on open.

- Callers that poll: `android/.../state/ChatViewModel.kt:305-310` (2.5s),
  `SessionHistoryViewModel.kt:78-85` (8s, `limit=200`),
  `CommandPaletteViewModel.kt` (per keystroke, debounced).

- Existing tests that pin behavior: `bridge/test/session-read.test.ts`,
  `bridge/test/sessions-http.test.ts`, `bridge/test/session-catalog.test.ts`,
  `bridge/test/board-detail.test.ts`, `bridge/test/review.test.ts`,
  `bridge/test/transcript.test.ts`.

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Typecheck | `cd bridge && npm run typecheck` | exit 0 |
| All tests | `cd bridge && npm test` | all pass |
| Focused   | `cd bridge && node --import tsx --test --test-timeout=120000 test/session-read.test.ts test/session-catalog.test.ts test/transcript.test.ts` | all pass |

## Scope

**In scope**:
- `bridge/src/transcript.ts`
- `bridge/src/session-catalog.ts`
- `bridge/src/routes/sessions.ts` (the `readSession` internals only)
- `bridge/src/routes/review.ts`
- `bridge/src/review.ts` (memoizing `gitRepoRoot` callers — see step 5)
- `bridge/test/` — extend the suites named above

**Out of scope**:
- The `since` response contract with the app (shape and null-cursor semantics
  are pinned; change how it's computed, never what it returns).
- `bridge/src/agents/*/transcript.ts` parsers — parsing stays per-backend.
- `bridge/src/live-output.ts` — already bounded and tuned.
- `routes/sessions.ts` `controlRoute`/`createSessionRoute` — plan 004 owns those.
- Android polling cadence — plan 005.

## Git workflow

- Work directly on `main`. Conventional commits, e.g.
  `perf(bridge): memoize transcript and catalog reads by (mtime,size)`.

## Steps

### Step 1: Fix `readSlice` short reads

Loop until `length` bytes are read or EOF, and slice the buffer to the total
actually read:

```ts
async function readSlice(handle, start, length): Promise<string> {
  if (length <= 0) return "";
  const buffer = Buffer.alloc(length);
  let total = 0;
  while (total < length) {
    const { bytesRead } = await handle.read(buffer, total, length - total, start + total);
    if (bytesRead === 0) break;
    total += bytesRead;
  }
  return buffer.subarray(0, total).toString("utf8");
}
```

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/transcript.test.ts` → pass.

### Step 2: Memoize the parsed transcript behind `readSession`

Add a module-level cache in `bridge/src/routes/sessions.ts` (or a small new
`bridge/src/transcript-cache.ts` if you prefer symmetry with
`board-detail.ts`), shaped exactly like `BoardDetailCache`:

- Key: canonical `target` path. Value: `{ mtimeMs, size, session: Transcript }`.
- On request: `inspectSessionFile(target)` (already called at `:57`); if the
  memo entry matches `(mtimeMs, size)`, reuse the parsed `Transcript`;
  otherwise `backend.readTranscript(target)` and store.
- Cap the memo (`MEMO_CAP`-style FIFO eviction like `board-detail.ts:42-45`;
  8 entries is plenty — one per open chat).
- The cursor slicing below the read stays byte-for-byte identical.

This turns the 2.5s poll steady state (file unchanged) into one `stat`.
Do NOT attempt incremental append-parsing in this plan — the memo removes
~96% of the cost with none of the cursor risk.

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/session-read.test.ts test/sessions-http.test.ts` → all pass unchanged.

### Step 3: Memoize `readCatalogFile` per file

In `bridge/src/session-catalog.ts`, add the same `(path → {mtimeMs, size,
parsed})` memo around `readCatalogFile` — the `SessionFile` entries already
carry `mtimeMs`; also capture `size` (extend `findSessionFiles`'s `stat` calls
to record it). Cap at ~600 entries (just above `MAX_SCANNED_FILES`), FIFO
eviction. An unchanged store then costs 500 `stat`s and zero reads per call.

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/session-catalog.test.ts` → all pass.

### Step 4: Make the catalog scan tolerate bad entries

Wrap in `try { … } catch { /* skip */ }` (matching the "missing store is an
empty store" comment style at `:190-192`):
- the `await stat(path)` for active refs (`:143`) — treat failure as "not on
  disk yet", i.e. `continue`;
- the nested `readdir(path, …)` in `scanRoot` (`:212`) — skip the directory;
- the body of `addSessionFile` (`:222-227`) — skip the file.

**Verify**: new test (see Test plan) passes; suite green.

### Step 5: Cache review roots and parallelize the git resolution

In `bridge/src/routes/review.ts`:
- Memoize `gitRepoRoot` results per canonical cwd in a module-level
  `Map<string, string | null>` (a cwd's repo root does not move; cap ~500).
- In `sessionWorkspaceRoots`, collect the cwds first, then resolve with
  `Promise.all` instead of sequential `await add(...)` in loops.
- Cache the final `reviewRoots` result for a short TTL (2s) keyed by nothing
  (single global): within one Review-screen open, the three routes then share
  one scan. **The TTL must stay ≤ a few seconds** — the root set is a security
  allow-list; a long TTL would keep a closed workspace reviewable.

**Verify**: `cd bridge && node --import tsx --test --test-timeout=120000 test/review.test.ts` → all pass.

## Test plan

New tests (model after the existing style in each file):

- `test/transcript.test.ts`: a `readSlice`-level or `readTranscriptText`-level
  case proving no NUL padding — e.g. write a file, read it, assert the decoded
  text length equals the file length (this guards the loop in step 1).
- `test/session-read.test.ts`: (a) two consecutive `readSession` calls with an
  unchanged file return identical results (memo hit — assert equality, and if
  cheap, count `open` calls via a spy or by asserting on timing-free
  observable behavior only); (b) appending a line to the file invalidates the
  memo — the new entry appears; (c) unknown `since` still returns
  `since: null` + full list (already covered — keep it green).
- `test/session-catalog.test.ts`: (a) a dangling symlink inside a root does
  not fail the listing and other files still return; (b) an unreadable
  subdirectory (chmod 0o000; skip this case when running as root) is skipped;
  (c) memo: unchanged store returns identical sessions on a second call.
- `test/review.test.ts`: two `reviewRoots`-driven route calls within the TTL
  hit the catalog once — if observing call counts is awkward, assert instead
  that a repo removed from disk between two immediate calls is still allowed
  within the TTL window and rejected after it (bound the sleep; or make the
  TTL injectable).

**Verification**: `cd bridge && npm test` → all pass, with the new cases present.

## Done criteria

- [ ] `cd bridge && npm run typecheck && npm test` exits 0
- [ ] `grep -n "bytesRead" bridge/src/transcript.ts` shows the read loop using it
- [ ] A memo keyed by mtime+size exists on the chat read path (`grep -n "mtimeMs" bridge/src/routes/sessions.ts` or the new cache module)
- [ ] `grep -n "Promise.all" bridge/src/routes/review.ts` shows the parallel resolution
- [ ] `grep -c "catch" bridge/src/session-catalog.ts` increased (scan-tolerance guards present)
- [ ] No files outside the in-scope list modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- Any existing test in `session-read.test.ts`, `sessions-http.test.ts`, or
  the Android-facing cursor behavior changes shape — the duplicate-LazyColumn
  crash (docs/decisions.md) is the regression this contract prevents. If a
  memo change alters any `since`-related assertion, stop.
- The `Transcript` type turns out to be mutated by callers after
  `readSession` returns (memo would alias shared state). Check: search for
  mutation of `session.entries` in `routes/sessions.ts` — slicing creates new
  arrays, which is fine; if you find in-place mutation anywhere, stop.
- Review TTL caching would need to exceed a few seconds to be effective —
  that trades away allow-list freshness; report instead.

## Maintenance notes

- Plan 004 also edits `routes/sessions.ts` (controlRoute + readSessionRoute
  error mapping). Land 003 first; the diffs touch different functions.
- If a future change adds transcript compaction/rotation, the `(mtimeMs,
  size)` key handles it (both change), but the FIFO caps assume a handful of
  concurrent readers — revisit if multi-host lands.
- A true incremental byte-offset parser (parse only appended bytes) remains
  available as a follow-up if profiling still shows parse cost after the
  memo; it was deliberately excluded to protect the cursor semantics.
- Direction note: every additional agent backend multiplies catalog scan
  breadth; this plan is the prerequisite for adding a `codex` backend without
  making the history screen the bridge's top CPU consumer.
