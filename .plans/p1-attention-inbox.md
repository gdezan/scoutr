# Attention Inbox Blueprint

## Current situation

The Board is already attention-first: `/api/agents` returns live cards grouped into Needs you / Working / Done / Idle, with bounded transcript-derived title, model, and latest activity. `BoardDetailCache` in `bridge/src/board-detail.ts` reads a bounded transcript tail and metadata every time the underlying file changes, memoized by path/mtime/size. `BoardScreen` renders the Needs you group with the strongest red treatment.

Chat already has the structured information the Board lacks. Every backend exposes `AgentBackend.extractQuestions(transcript)`. Pi/agy read asks from their transcripts; Claude's `claudeQuestions` also merges the live `PreToolUse` pending-ask sidecar, so an open Claude question exists before its transcript record is written. `ChatViewModel` and the bridge answer path already handle full ask rounds, stale/answered-elsewhere state, and dismissals.

Therefore the missing product layer is not another agent feed: the bridge already knows **why** a blocked agent needs the user, but `/api/agents` reduces that to `status == blocked` plus latest activity.

Precedent: Board details are bridge-normalized and bounded; question intent is backend-neutral; UI treatment follows `ui/theme/DESIGN.md`.

## Objective and why

Turn the Needs you section into a real attention inbox: show the current open ask/prompt directly on the Board, let the user make a safe one-tap decision when the ask is simple, and route complex asks into Chat with context preserved.

Done means a blocked card answers “what does this agent need from me?” without opening Chat, simple single-question option asks can be answered from the Board, and multi-question/free-text/ambiguous prompts remain one tap away in Chat rather than being squeezed into an unsafe shortcut.

## Scope

Included:

- bridge Board enrichment with one normalized current-attention summary;
- Android `AgentCard`/canonical session descriptor attention DTO;
- Needs you card UI for question/prompt preview;
- quick answer for **safe simple asks only**;
- refresh/reconciliation after Board answer;
- accessibility and stale-answer failure behavior;
- backend coverage including Claude's pending sidecar.

Non-goals:

- no second notification system;
- no full multi-question questionnaire on the Board;
- no free-text composer embedded in Board cards;
- no LLM-generated summaries;
- no change to agent questionnaire grammar;
- no separate attention endpoint/poller;
- no attempt to answer generic permission prompts that are not represented as a structured ask.

## Global constraints

- `/api/agents` remains the Board's single 3-second data source; attention enrichment must ride that response and remain bounded/memoized.
- The bridge owns backend question normalization; Android never parses raw transcript tool calls.
- Answers remain intent (`questionId`, labels/text) and are delivered through existing `answerAsk` semantics.
- Quick answer is allowed only when the Board has enough information to submit the **whole ask atomically**.
- UI stays glanceable and compact; no pill/chip explosion or nested scrolling inside Board cards.
- Final emulator/E2E acceptance happens once after review-clean/code-freeze.

## Resolved decisions

### Attention contract

Add an optional summary to the Board/session descriptor:

```ts
interface AttentionSummary {
  kind: "ask" | "prompt";
  callId: string | null;
  questionCount: number;
  currentQuestion: {
    id: string;
    header: string;
    question: string;
    options: Array<{ label: string; description: string }>;
    multiSelect: boolean;
  } | null;
  canQuickAnswer: boolean;
}
```

For a structured ask, group unanswered questions by `callId` and expose the newest open group. `currentQuestion` is its first unanswered question. `questionCount` tells the UI when more questions exist.

For a plain blocked prompt with no structured question, expose `kind = "prompt"`, no fabricated question/options, and `canQuickAnswer = false`; latest activity remains the supporting preview.

### Quick-answer rule

Board may submit directly only when all are true:

1. the open ask contains exactly one question;
2. it is single-select (`multiSelect == false`);
3. it has 1–3 authored options;
4. selecting one option constitutes the complete ask round;
5. the card is not already submitting an answer.

Anything else shows `Open`/tap-to-Chat. This keeps the Board an inbox, not a second questionnaire implementation.

A quick answer calls the same `ScoutrApi.answerAsk` used by Chat. If the HTTP-command P1 has landed, it uses HTTP automatically; otherwise the typed API hides transport.

### Staleness

The Board never marks the ask answered optimistically as remote truth. It may show a local submitting state, then immediately refresh `/api/agents`. If the ask was answered elsewhere or changed before submission, surface a short inline/toast failure and refresh; do not restore stale options as authoritative.

## Approach

Extend `BoardDetailCache` so its existing bounded transcript read also extracts the current open question group through the owning backend. Because Claude's extractor already consults its pending sidecar, this produces the same open ask Chat sees. Put a compact `AttentionSummary` onto blocked agent cards. Android renders the summary only in Needs you cards and conditionally exposes one-tap authored options.

Do not create another call per card or per ask. The Board poll remains one `/api/agents` request.

## Contracts and interfaces

### Bridge derivation

`BoardDetail` gains `attention: AttentionSummary | null`. `detailFor` already knows the backend and transcript. Derivation should call:

```ts
const questions = backend.extractQuestions(transcript);
```

Then select the newest unanswered call group. Because `BoardDetailCache` memoization is keyed to transcript stat, Claude pending-sidecar changes need an explicit cache consideration: an open/cleared Claude sidecar can change while the transcript file mtime does not. The implementation must either include pending-ask state in the cache key for Claude or bypass/recompute only the attention slice cheaply for sidecar-backed backends. Do **not** accept stale Claude attention as a cache artifact.

### Android behavior

Add optional `attention` to the Board's session/card DTO. `BoardViewModel` gains one in-flight quick-answer identity and an action such as:

```kotlin
fun quickAnswer(agent: SessionDescriptor, optionLabel: String)
```

It constructs exactly one `AskAnswer` from the server-provided question id and selected label, submits, then refreshes Board.

### Cross-change interface table

| Change | Consumes | Produces |
|---|---|---|
| 1 | backend transcript + question extractor | `AttentionSummary?` in Board detail |
| 2 | enriched `/api/agents` | Android attention DTO |
| 3 | attention DTO | Needs-you preview + quick-answer eligibility |
| 4 | safe simple ask | existing typed `answerAsk` mutation + Board refresh |

## Changes

- [x] **1 — Derive current attention through the existing Board detail seam**
  - Anchor: `bridge/src/board-detail.ts` → `BoardDetail`, `BoardDetailCache.detailFor`, `deriveBoardDetail`
  - Anchor: `bridge/src/agents/types.ts` → `AgentBackend.extractQuestions`
  - Add a pure helper that groups unanswered questions by call id and returns the newest open group summary.
  - For blocked panes with no structured ask, return prompt-kind attention only if the caller can determine that state from the agent status; do not invent prompt text beyond existing latest activity.
  - Handle Claude pending-sidecar cache invalidation explicitly; add a regression test where the sidecar appears/disappears without transcript mtime changing.
  - Proof: bridge tests show pi and Claude blocked cards expose the same question ids/options Chat would use, and answered/cleared asks disappear.

- [x] **2 — Add attention to the Board API/Android model**
  - Anchor: `bridge/src/routes/agents.ts` → `AgentCard`/canonical `SessionDescriptor` enrichment
  - Anchor: `android/app/src/main/java/dev/scoutr/app/data/Models.kt` → Board session/card DTO
  - Include attention only as normalized bounded metadata; no raw tool arguments.
  - Update fake API/builders and serialization tests.
  - Proof: `/api/agents` fixture round-trips a simple ask, multi-question ask, and null attention.

- [x] **3 — Render Needs-you context without making Board a second Chat**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt` → `AgentCardRow`
  - For Needs you cards with structured attention, place the question below the title/activity in normal UI typography; show `N questions` metadata when `questionCount > 1`.
  - Simple quick-answer asks render at most three compact action controls using existing button/press treatment, not generic decorative pills.
  - Complex ask/prompt shows one clear `Open` affordance or relies on the card's existing tap target with an explicit semantic label.
  - Preserve current swipe Review/Copy/Close behavior and vertical scrolling.
  - Proof: Compose UI tests cover simple quick answer, multi-question open-only, plain prompt, long text truncation, and accessibility semantics.

- [x] **4 — Submit safe quick answers through existing ask semantics**
  - Anchor: `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt` → actions/state
  - Add a per-call/pane submitting state so double taps cannot submit twice.
  - Revalidate `canQuickAnswer` and required ids from the current card before sending.
  - Call `ScoutrApi.answerAsk` with the complete one-question round; on success refresh Board immediately; on stale/failure report concise user-visible error then refresh anyway.
  - Do not copy Chat's ask draft/reconciliation engine into Board.
  - Proof: ViewModel tests cover success, double tap suppression, answered-elsewhere failure, transport failure, and refresh after each outcome.

## Failure handling

- Blocked but no structured question: show prompt/attention state and open Chat; never fabricate choices.
- Multi-question/multi-select/free-text ask: no quick answer.
- Claude pending sidecar appears/disappears without transcript change: Board cache must converge on next poll.
- Ask changes between poll and tap: backend rejects stale ids/group; Board surfaces failure and refreshes.
- Answer mutation succeeds but refresh fails: clear submitting state and keep previous card with a non-blocking connection error; do not claim the remote ask is gone until refreshed.
- Options with excessive text: truncate presentation only; submit the exact server label, not the truncated display string.

## Validation

1. Pure bridge attention derivation tests.
2. Claude sidecar cache invalidation regression test.
3. `/api/agents` route tests.
4. BoardViewModel quick-answer unit tests.
5. Board Compose UI tests.
6. `make bridge-test`.
7. `make android-test`.
8. Independent review with `skills/scoutr-review/SKILL.md`.
9. Final runtime acceptance only after code freeze: exercise one real pi ask and one real Claude hook-backed ask; verify simple answer from Board and complex ask routing to Chat. Use `skills/scoutr-verification/SKILL.md`.

## Local discretion

- Exact question truncation length and card spacing within existing design tokens.
- Whether the quick-answer controls are text buttons or `PressTintSurface` rows, provided they remain compact, accessible, and visually subordinate to the question.
- Exact error presentation (toast vs inline transient Board error) if consistent with existing Board action errors.

## Escalation triggers

- Any backend cannot expose its currently open ask through `extractQuestions` without a new side channel.
- Quick answering requires partially walking a multi-question TUI or maintaining questionnaire cursor state on Board.
- Attention enrichment makes `/api/agents` perform an unbounded read or extra network request per card.
- Claude sidecar freshness cannot be represented without redesigning the Board cache.
- Product scope expands to free-text/multi-question answering directly on Board; that is a separate interaction design.

## Review handoff

Reviewer must compare the Board's question id/options with Chat for the same live ask, verify quick answer is available only when it can submit the complete ask atomically, and test Claude sidecar freshness. Confirm Board polling remains a single request and no agent-specific parsing leaked into Android.

Rerun both cheap suites after review fixes; real-agent/emulator acceptance is final and terminal.

## Completion checklist

- [x] Needs-you cards expose normalized current attention.
- [x] Claude pending asks appear before transcript write and disappear when cleared.
- [x] Simple one-question single-select asks can be answered from Board.
- [x] Complex asks remain open-in-Chat only.
- [x] Double/stale submissions are safe and observable.
- [x] Board still uses one `/api/agents` poll with bounded enrichment.
- [ ] Accessibility/UI tests cover all attention variants.
- [x] Bridge and Android cheap suites pass.
- [ ] Independent review is clean.
- [ ] Real-agent runtime acceptance passes once, last.

## References

- `AGENTS.md`
- `bridge/src/board-detail.ts`
- `bridge/src/routes/agents.ts`
- `bridge/src/agents/types.ts`
- `bridge/src/agents/claude/questions.ts`
- `bridge/src/agents/claude/pending-asks.ts`
- `bridge/src/answers.ts`
- `android/app/src/main/java/dev/scoutr/app/state/BoardViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/state/ChatViewModel.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/screens/BoardScreen.kt`
- `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- `docs/adr/0006-answer-questions-through-the-agent-adapter.md`
- `docs/adr/0011-buffer-an-ask-and-submit-it-as-one-round.md`
- `.plans/p1-http-session-commands.md`
- `.plans/p1-session-model-v3.md`
- `skills/scoutr-review/SKILL.md`
- `skills/scoutr-verification/SKILL.md`
