# Long-session Reverse Pagination Blueprint

## Current situation

Chat already uses incremental forward reads after the first response, but a cold open of an existing session still requests the whole transcript. `ChatViewModel.readAndMerge()` calls `bridge.session(key, since = entries.lastOrNull()?.entryId)`; when the ViewModel has no entries yet, `since` is null. `BridgeClient.session()` maps that directly to `GET /api/sessions`, and `bridge/src/routes/sessions.ts -> readSession()` returns every normalized entry when no cursor is present. The route memoizes the parsed transcript by `(mtimeMs, size)`, which helps repeated reads on the host but does not reduce the first response sent over a weak connection.

The Android client buffers the complete HTTP body before decoding it. `ChatScreen.kt -> ChatList` uses a keyed `LazyColumn`, so rendering is lazy, but the app still has to download, hold, and deserialize the full response before the first useful transcript paint.

Forward updates are already the right shape: `since=<entryId>` returns only entries after that cursor, and `ChatViewModel` merges them through `mergeSessionEntries`. The performance study explicitly leaves long-session redesign open and names a bounded recent tail with lazy older-history backfill as the next design to compare for 10k/100k-entry sessions.

Repository rules that govern this work are in `AGENTS.md`. Verification must follow `skills/scoutr-verification/SKILL.md`: cheap targeted checks during implementation, review-clean/code-frozen state, then the smallest final emulator/integration acceptance set.

## Objective and why

Opening a long session must become useful without transferring the full transcript first. A new Chat route should receive only the newest page, render at the bottom, keep using the existing forward `since` cursor for live updates, and fetch older pages only when the user scrolls toward the top.

Definition of done:

- a first open from an empty `ChatViewModel` requests at most 50 transcript entries rather than the full history;
- old installed clients that omit pagination parameters retain the current full-snapshot behavior;
- new entries still arrive through the existing `since` path;
- scrolling near the top loads older entries in chronological order without jumping the currently visible content;
- a weak or failed older-page request leaves the already-rendered transcript usable and does not start a retry loop;
- open question cards remain actionable even when their anchor entry is outside the loaded history, while answered historical cards do not float at the end before their anchor entry is loaded.

## Scope

Included:

- extend `GET /api/sessions` with a bounded initial-page mode and reverse-history mode;
- extend the Android session API/DTO surface for those fields;
- add reverse-history state and loading to `ChatViewModel`;
- trigger older-page loads from `ChatList` near the top and preserve the scroll anchor across prepends;
- preserve current forward polling, pending-message reconciliation, question state, lifecycle cancellation, and full-snapshot recovery;
- add bridge, JVM, and focused UI/runtime coverage;
- update `docs/performance-study.md` in the same change with the implemented behavior and measured evidence available at acceptance time.

Not included:

- persistent transcript caching on the phone;
- response gzip/brotli work;
- changing the `/api/agents` status lookup or Chat refresh ordering;
- replacing HTTP polling with a WebSocket;
- optimizing reverse-history disk reads. This blueprint deliberately slices pages from the existing full transcript memo; the second blueprint changes the initial bridge read path after this contract exists.

## Resolved decisions

1. **Keep `since` as the forward/live cursor.** Do not overload it for reverse history.
2. **Add `before` as an opaque reverse-history cursor.** Android stores and sends it but never parses or compares it. The first implementation may encode an entry id internally; that representation is bridge-owned so a later bridge optimization can change it without changing Android behavior.
3. **`limit` is a maximum, not a promise of an exact count.** The new Android first read uses `limit=50`. The bridge rejects values outside `1..200` with 400.
4. **Preserve legacy behavior when `limit` and `before` are absent.** A request with neither pagination parameter and no `since` remains the current full snapshot, so a newer bridge does not force an older APK into a new response contract.
5. **`since` and `before` are mutually exclusive.** Supplying both is a 400 because the merge direction would otherwise be ambiguous.
6. **Entries stay chronological inside every response.** A reverse page is older than the currently loaded head, but its own `entries` remain oldest-to-newest so Android can prepend the list without reversing message semantics.
7. **Keep `questions` authoritative in the session response.** Do not create a second question-delta protocol in this slice. Android continues merging question state by stable id. `ChatList` changes its row policy so an answered question group is rendered only when its anchor entry is loaded; an unanswered/open group may still render without its anchor so the user cannot lose an actionable ask.
8. **History loading is a separate single-flight operation from the live refresh.** A reverse request may overlap a forward poll because they mutate opposite ends of the transcript, but there may be at most one reverse request in flight. `stopPolling()`/STOPPED must cancel both network owners.
9. **Do not auto-retry a failed reverse page merely because state changed.** Preserve the loaded transcript. A later user movement away from and back into the near-top threshold may try again.
10. **A missing reverse cursor is not recovered as a full prepend.** If `before` no longer exists because the transcript was compacted/rewritten, the bridge returns a conflict rather than inventing ordering. The normal forward refresh remains the authoritative path that can replace the list when its `since` cursor disappears.

## Approach

The end-to-end flow becomes:

```text
open Chat with no entries
  -> /api/agents status/key reconciliation (unchanged)
  -> GET /api/sessions?path=...&agentKind=...&limit=50
  -> bridge slices the newest <=50 entries from the memoized Transcript
  -> response carries beforeCursor + hasMoreBefore
  -> Android replaces entries with that recent page and opens at the bottom

live poll
  -> GET /api/sessions?...&since=<newest-loaded-entry>
  -> append only unseen new entries (unchanged)

user scrolls near top
  -> GET /api/sessions?...&before=<opaque-cursor>&limit=50
  -> bridge returns the previous <=50 entries
  -> Android prepends unseen entries
  -> ChatList restores the same visible row + pixel offset
```

The bridge remains authoritative for file order and cursor validity. Android never derives ordering from random entry ids.

## Contracts and interfaces

### HTTP query modes

| Request shape | Meaning | Response merge |
| --- | --- | --- |
| no `since`, no `before`, no `limit` | legacy full snapshot | replace |
| `limit=N`, no cursors | newest page, max `N` | replace |
| `since=X` | entries after `X` | append |
| `before=C&limit=N` | page immediately older than opaque cursor `C` | prepend |
| both `since` and `before` | invalid | 400 |

### Session response additions

`SessionReadResult` / Android `SessionReadResponse` gain:

```text
beforeCursor: string | null
hasMoreBefore: boolean
```

Rules:

- `beforeCursor` identifies the boundary immediately before the oldest loaded page; it is bridge-owned and opaque to Android.
- `hasMoreBefore=false` implies `beforeCursor=null`.
- legacy/full and forward responses may still include the fields; Android updates reverse-history state only when the response is a replacement page or a reverse page, not from an ordinary forward delta.
- `lastEntryId` keeps its current meaning: newest entry in the authoritative transcript, not the reverse cursor.

### Android API

Extend the existing method rather than introducing another transport surface:

```kotlin
suspend fun session(
    key: SessionKey,
    since: String? = null,
    before: String? = null,
    limit: Int? = null,
): SessionReadResponse
```

All calls must use named optional arguments. `BridgeClient`, `GenerationGuardedScoutrApi`, and `FakeScoutrApi` forward the same contract.

### Chat state

Add reverse-history state to `ChatUiState`:

```text
beforeCursor: String?
hasOlderEntries: Boolean
loadingOlderEntries: Boolean
```

A dedicated reverse-load owner in `ChatViewModel` enforces one in-flight `before` request. Prepending deduplicates by `entryId`; forward append continues through the existing `mergeSessionEntries` semantics.

## Changes

- [ ] **1. Add the bridge reverse-pagination contract while preserving legacy full snapshots.**
  - Anchor: `bridge/src/routes/sessions.ts` -> `readSessionRoute`, `readSession`, `SessionReadResult`, `readTranscriptMemoized`
  - Work: parse/validate `limit` and `before`; reject `since+before`; keep the no-pagination branch byte-for-byte compatible in meaning; for the bounded initial branch slice `session.entries.takeLast(limit)`; for reverse history resolve the opaque cursor against file order and slice the preceding page; return `beforeCursor`/`hasMoreBefore`; return a conflict for an unknown reverse cursor instead of silently prepending a replacement snapshot. Keep the current memo and full question extraction in this slice.
  - Precedent: the existing file-order `since` lookup in `readSession`, including its explicit rejection of lexical-id ordering.
  - Proof: extend `bridge/test/session-read.test.ts` with legacy-full, initial-tail, first/middle/final reverse page, random-id order, invalid limit, `since+before`, and stale reverse-cursor cases; run `cd bridge && npm run typecheck` and `cd bridge && node --import tsx --test --test-timeout=120000 test/session-read.test.ts`.

- [ ] **2. Carry pagination through the Android transport and DTO boundary.**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/net/ScoutrApi.kt` -> `session`; `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt` -> `session`; `android/app/src/main/java/dev/scoutr/app/net/GenerationGuardedScoutrApi.kt` -> session forwarding; `android/app/src/commonTest/kotlin/dev/scoutr/app/net/FakeScoutrApi.kt` -> fake session calls; `android/app/src/main/java/dev/scoutr/app/data/Models.kt` -> `SessionReadResponse`
  - Work: add `before`/`limit` query support and response fields without changing authentication, generation guards, cancellation, or JSON decoding behavior. Update fakes so tests can inspect requested direction/cursor/limit rather than only returning canned data.
  - Proof: focused Android compile plus the affected API/fake JVM tests; at minimum `cd android && ./gradlew :app:testDebugUnitTest` if no narrower existing test selector covers all changed common/test sources.

- [ ] **3. Make first paint bounded and add reverse-history state/merging to `ChatViewModel`.**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt` -> `ChatUiState`, `startPolling`, `stopPolling`, `refresh`, `readAndMerge`, `mergeSessionEntries`
  - Work: when the authoritative read has no loaded entries, request `limit=50`; store the returned reverse cursor/history flag; leave later forward reads on `since=<newest loaded id>`. Add `loadOlderEntries()` guarded by lifecycle activity, `hasOlderEntries`, cursor presence, and one in-flight reverse request. On success prepend only unseen ids and advance `beforeCursor`; on failure preserve entries and clear loading state without starting an automatic retry loop. Cancel the reverse owner on STOPPED. If the existing forward cursor falls out of the file and the bridge sends a replacement response, treat its pagination fields as the new reverse-history baseline.
  - Concurrency contract: a reverse request may overlap the live refresh, but both merge by stable ids; the reverse result may only prepend entries older than the head it was requested against. A stale/conflicting reverse cursor leaves current state untouched.
  - Proof: extend `android/app/src/test/java/dev/scoutr/app/state/ChatRefreshTest.kt` (or a dedicated adjacent pagination test if clearer) to cover initial `limit=50`, append-after-tail, one reverse request at a time, prepend dedupe, reverse failure preservation, STOPPED cancellation, and forward/reverse overlap without duplicates.

- [ ] **4. Load history near the top without moving the reader or misplacing question cards.**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/ChatScreen.kt` -> `ChatList`, `rows`, `questionsByCall`, `groupsByAnchorEntry`, `LazyColumn`, existing `followNew`/scroll owner
  - Work: pass `hasOlderEntries`, `loadingOlderEntries`, and `onLoadOlder` into `ChatList`. Observe `LazyListState.layoutInfo` and request history when the first visible row enters a small near-top threshold (target: index <= 6), only once per threshold entry while not already loading. Before requesting, capture the first visible stable row key and its offset; after a successful prepend, find that key in the new rows and restore the same offset so the visible transcript does not jump. Do not set `followNew=true` and do not reuse the scroll-to-end owner for prepends. Change the unanchored-question fallback so only unanswered/open groups render without a loaded anchor; answered groups wait until their anchor entry arrives in a history page.
  - Precedent: stable `ChatRow` keys and the existing single-owner scroll logic already treat user drag intent as authoritative.
  - Proof: focused Compose/JVM coverage for row/question construction where possible, plus final emulator acceptance showing (a) initial open lands at the newest content, (b) scrolling upward loads another page, (c) the same visible message remains at the same viewport offset after prepend, and (d) an open ask remains visible while an answered ask whose anchor is not loaded does not float at the tail.

- [ ] **5. Record the long-session result in the performance study.**
  - Anchor: `docs/performance-study.md` -> `P1 — make Chat refresh fast, coherent, and user-driven`, especially the deferred long-session redesign item
  - Work: replace the deferred statement with the implemented pagination contract, checks, and runtime evidence. Record actual initial response bytes and first-useful-paint evidence from a large fixture/session if measured; do not claim network, battery, or latency gains that were not measured.
  - Proof: cold-read the updated section against the implemented API and the final acceptance notes; no stale claim that first open still requires the full transcript may remain.

## Failure handling

- Invalid `limit`, `since+before`, malformed/unknown reverse cursor: bridge returns an ordinary typed 4xx error; it must not crash or return guessed history.
- Reverse request fails because the network is weak: keep the current transcript and current live polling. Clear `loadingOlderEntries`; do not schedule an immediate retry from the same state transition.
- Reverse cursor becomes stale because the file was compacted/rewritten: preserve current entries. Let the normal forward authoritative refresh decide whether its `since` cursor still exists and perform the existing replacement recovery if needed.
- New entries arrive while an older page is in flight: append them normally. The later reverse success prepends only unseen ids and must not replace the new tail.
- Screen reaches STOPPED: cancel both the live refresh owner and the reverse-history owner; no history request may be launched while inactive.
- Question state changes while the anchor entry is unloaded: keep merging the authoritative question list. Open questions remain renderable; answered historical questions wait for their anchor entry.

## Validation

During implementation, follow `skills/scoutr-verification/SKILL.md` and keep checks narrow:

1. bridge session pagination tests + bridge typecheck;
2. Android state/API tests for request direction and merge behavior;
3. focused Compose tests for question placement/scroll-trigger state if the changed logic is testable off-device;
4. run `skills/scoutr-review/SKILL.md`, resolve/dismiss findings, and re-run only invalidated cheap checks;
5. after code is review-clean and frozen, run the smallest emulator/integration acceptance that proves the bridge/Android contract and scroll-anchor behavior on a long session;
6. because this crosses the bridge/Android API boundary and is user-visible, broaden to `scripts/verify.sh` only if the verification skill selects it for the final risk or a release/merge milestone requires it.

Useful runtime evidence is request count/response bytes from the existing performance counters plus a large-session open and reverse-scroll journey. A bandwidth improvement is not claimed solely from unit tests.

## Local discretion

The implementer may choose the exact private type names, helper placement, and near-top observation mechanism (`snapshotFlow` or an equivalent lifecycle-safe Compose pattern) as long as the contracts above stay unchanged. The `before` cursor representation is deliberately bridge-private. The first-page constant may live beside Chat refresh constants or the API caller, but remains 50 unless evidence during implementation shows the value violates an existing payload/UX constraint.

## Escalation triggers

Return to `gd` rather than silently changing the plan if:

- preserving open question behavior requires a new question API or non-authoritative local question state;
- stable-key prepend cannot preserve the viewport without changing the current Chat scroll/follow contract;
- a backend cannot provide ordered entry ids suitable for the bridge-owned reverse cursor;
- compatibility requires removing the legacy no-pagination full-snapshot behavior;
- the bridge must add byte-offset/index persistence to meet the first slice. That belongs to the bounded-read follow-up, not this contract slice.

## Completion checklist

- [ ] Bridge supports legacy full, bounded initial, forward `since`, and reverse `before` modes with deterministic validation.
- [ ] Android API/DTO/fakes carry `before`, `limit`, `beforeCursor`, and `hasMoreBefore`.
- [ ] First Chat read requests max 50 entries; forward polling remains `since`-based.
- [ ] Reverse loads are single-flight, lifecycle-cancelled, deduped, and failure-preserving.
- [ ] Prepending older rows preserves the visible anchor and does not re-enable follow-to-bottom.
- [ ] Open/unanchored questions remain actionable; answered/unanchored historical questions do not float at the tail.
- [ ] Targeted checks pass, review is resolved, final runtime acceptance is recorded, and `docs/performance-study.md` matches the shipped behavior.

## References

- `AGENTS.md`
- `skills/scoutr-verification/SKILL.md`
- `skills/scoutr-review/SKILL.md`
- `docs/performance-study.md`
- `bridge/src/routes/sessions.ts`
- `bridge/test/session-read.test.ts`
- `bridge/src/transcript.ts`
- `bridge/src/agents/types.ts`
- `android/app/src/main/java/dev/scoutr/app/net/ScoutrApi.kt`
- `android/app/src/main/java/dev/scoutr/app/net/BridgeClient.kt`
- `android/app/src/main/java/dev/scoutr/app/data/Models.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/ChatScreen.kt`
