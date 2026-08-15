# Buffer an ask in the app and submit it as one round

A multi-question ask is one card, buffered in the app until Submit, then delivered to the pane in a single pass.

## Context

ADR 0006 moved the questionnaire grammar into the agent adapters and left the wire carrying intent: one `answer_question` per card. That shape had two consequences that only show up on a phone with a multi-question ask.

**Every question got its own card.** `ChatList` rendered one `QuestionCard` per `QuestionEntry`, so a four-question ask arrived as four stacked cards in the scroll — no shared frame, no sense of a round, and the last card often below the fold.

**An answer became irreversible the moment it was tapped.** `answer_question` delivered its keystrokes into the live TUI immediately, and the app recorded a `localAnswers` entry that flipped the card to an answer bubble. Changing question 1 after answering it was impossible from the app, and undoing it in the TUI would have meant re-navigating a tab strip whose state the bridge could only guess at.

The second one is the interesting failure, because **the TUIs themselves do not work that way**. Claude's questionnaire holds every question on a tab strip and commits nothing until its trailing Submit tab; pi's does the same with a review tab. The app was stricter than the thing it was driving.

Carrying that strictness forward also cost cross-request state: `answers.ts` held an `openAsks` map of `AnswerProgress { answered, cursorTab }` for the lifetime of the daemon, because neither agent writes a partly answered ask to disk. A bridge restart mid-ask lost the tab position; a bounded map with an eviction policy existed solely to stop a never-finished ask from growing it.

## Decision

- **The round is buffered in the app.** `ChatUiState.askDrafts` holds a `AskDraft` per tool call id — a page index and one `DraftAnswer` per question — and nothing reaches the pane until the user submits. Every answer stays editable until then, because none of them has happened yet.
- **One ask is one card.** `AskCard` shows a chip row (status, tap to jump) over the focused question, with a `Back / dots / Next` footer where `Next` becomes `Submit` on the last question. Submit is disabled until every question has a pick or text: the review tab will not accept an incomplete round, so an incomplete one is never offered. A lone question hides all of that chrome and keeps its one-tap answer.
- **One command carries the round.** `answer_ask` takes `paneId`, `callId`, and one answer per question; `dismiss_ask` cancels the ask. `AgentBackend.answerQuestion` becomes `answerAsk(herdr, AskRequest)`, which either lands the whole ask or throws.
- **The plans walk the strip once.** Because answering tab `k` lands on tab `k + 1`, a plan that answers in ask order from a freshly opened questionnaire needs no navigation key at all — `claudeAskPlan` emits no `Left`/`Right`, `piAskPlan` no `tab`. `AnswerProgress` and the `openAsks` map are deleted outright.
- **agy joins the round into one prompt.** It has no questionnaire — its answers are plain text at a prompt — so a round is formatted one labelled line per question and sent once, keeping "one submit, one delivery" true for all three backends.
- **Nothing is shown as delivered on an ack.** The card holds a sending state until the ask leaves the open set, which happens when the transcript's toolResult lands; then the whole round collapses into a single answer bubble. `localAnswers` and its overlay are gone. Past 15s the card says it has had no response, but never unlocks — re-enabling it could answer a landed ask twice.
- **A failed round is reported, not retried.** The steps are individual sends into a live TUI, so a throw part-way leaves the questionnaire on an unknown tab. The draft is kept and the error shown; no retry button, because replaying from tab 0 would re-toggle checkboxes the first pass already set.
- **The composer steps aside while a card is open.** It is disabled on `hasPendingQuestion` — not on `agentStatus == "blocked"`, which is also true for permission prompts the composer must still answer. Dismiss on the card is the way out.
- **Dismiss is local first, and unconditional.** The app records the dismissed call id in `ChatUiState.dismissedCallIds` and drops the ask from `questionCards`, `openAsks` and `hasPendingQuestion` immediately — before the bridge is told, and whether or not telling it succeeds. The two sides do drift: a question closed in the terminal is never written back as answered, `mergeQuestions` upserts and never removes, and claude's ask is served from a sidecar file that can outlive the pane. With the composer gated on `hasPendingQuestion`, any of those left the session unsteerable for good. A failed `dismiss_ask` is reported as a notice, not as an undo.
- **A stalled round can be dismissed too.** Dismiss is hidden only while the keystrokes are actually in flight; once the submit passes [ASK_SLOW_SUBMIT_MS] and the card admits it has had no response, the button returns. This is the one path that clears `submittingCallId` without a toolResult, and it is a deliberate user action rather than a timer — the risk of double-answering is theirs to take, and the alternative was a lock with no exit.
- **Dismiss clears Claude's sidecar.** Escape closes the ask in the TUI, but the card is served from the `PreToolUse` sidecar that only `PostToolUse` clears — and a cancelled tool call never reaches `PostToolUse`. `claudeDismissAsk` resolves the pane's session id and removes the file it wrote, so a dismissed card cannot come back on the next poll.

## Consequences

The bridge holds no state between answer requests at all: an ask is resolved, planned, and delivered inside one call. That removes the restart-loses-the-tab-position failure and the bounded-map bookkeeping with it.

Dismissals are saved alongside drafts and pruned as soon as their ask stops being open, so the set cannot grow for the life of the screen. A dismissed ask that turns out to have been answered after all still shows its answer bubble: the transcript wins once it has something to say.

Drafts survive process death via `SavedStateHandle`, encoded as one string because a bundle carries no nested map. They are dropped as soon as their ask stops being open — including when it is answered in the terminal or on another device, which surfaces as a notice rather than a silently vanished card.

Two behaviours are still mapped empirically rather than from a contract, and both should be re-verified against a live pane before trusting them: whether escape leaves Claude's sidecar behind (the dismiss path assumes it might, and clears it either way, so it is safe if the assumption is wrong), and whether either TUI would accept a partial submit at all — the app never offers one, so this only matters if that constraint is ever relaxed.

`answer_question` is gone rather than aliased: the bridge is a local daemon updated alongside the app, so an old pairing fails loudly instead of carrying a second code path and the `openAsks` map that came with it.

On agy, Dismiss maps to escape, which is agy's ordinary abort — it ends the agent's turn rather than closing a question, since there is no question widget to close.
