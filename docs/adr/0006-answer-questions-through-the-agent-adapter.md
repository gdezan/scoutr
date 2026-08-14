# Answer question cards through the agent adapter, not the app

The app sends what the user chose; the bridge's agent adapter turns that into keystrokes for its own TUI questionnaire.

## Context

pi and Claude Code both ask structured questions from a tool call (`ask_user_question`, `AskUserQuestion`) and both render them as a keyboard-driven questionnaire in the pane. Scoutr shows them as native cards, so answering means replaying a key sequence into someone else's TUI.

That sequence was computed in the Android app (`ChatViewModel.answerNavigationKeys`) and shipped over the wire as `keys`/`trailingKeys`. It encoded pi's grammar specifically: arrows move, space toggles, enter chooses, tab advances, "Type something" sits just after the authored options.

Claude Code's questionnaire does not work that way. Verified live against 2.1.x:

- Questions are tabs in a strip with a trailing "Submit" review tab; `right`/`left` move between them and **do not wrap**.
- Options are numbered, and the digit acts on that option wherever the cursor is — single-select picks it and advances one tab, multi-select toggles it and stays.
- Once the "Type something" field has focus, digits and arrows are typed into the field instead of navigating.
- Multi-select submits from the "Next"/"Submit" row under the list, not from a digit.
- A batch of keys delivered as one chunk is misparsed (a batched `down down space` toggled the row the cursor had already left), so steps must be sent one at a time.

Encoding a second grammar in the app would have put two agents' TUI dialects in a client that is supposed to know only that a question exists — and Claude's dialect needs *typed digits*, which the `keys` field cannot even express.

There is a second, sharper problem. **Claude does not write the `AskUserQuestion` call to its session JSONL until the ask is answered** — verified live against 2.1.232: while the questionnaire is on screen the file still ends at the user's prompt. A transcript-only card could therefore only ever appear after someone answered in the terminal, which is exactly when it is no longer useful. The `PreToolUse` hook does fire while the ask is open, and carries `session_id`, `tool_use_id`, and the full questions — the same ids the transcript uses later.

## Decision

- `answer_question` carries intent only: `paneId`, `questionId`, `text`, `selectedLabels`. The `keys`/`trailingKeys` fields are gone.
- `AgentBackend.answerQuestion(herdr, AnswerRequest)` owns the grammar. Each adapter keeps its plan in its own `questionnaire.ts` (`piAnswerPlan`, `claudeAnswerPlan`) and returns the `AnswerProgress` the next answer starts from.
- `answers.ts` resolves the card id against the pane's transcript, groups the ask, and carries that progress in memory — neither agent writes a partly answered ask to its session file, so it cannot be recovered from disk.
- Claude questions are extracted from `AskUserQuestion` calls; the answers come from the tool-result record's `toolUseResult.answers`, keyed by question **text** (Claude records no index). Only that slice of `toolUseResult` is kept on the entry, so ordinary tool results do not bloat every transcript poll.
- A `PreToolUse`/`PostToolUse` hook (`scoutr-bridge hook claude`, installed by `scoutr-bridge install-claude-hook`) writes the open ask to a sidecar under `<XDG_CONFIG_HOME>/scoutr/pending-asks/<session>.json`. The claude adapter merges it with the transcript and drops it as soon as the transcript carries the same call, so a card is never shown twice and a missed `PostToolUse` heals itself. The hook reads only stdin and always exits 0 — it can never make the agent wait on Scoutr.
- The app records the answer it just sent (`ChatUiState.localAnswers`) and overlays it on the card. Nothing else can: the transcript flips every question of an ask to answered at once, when the ask is submitted.

## Consequences

Adding a third agent means writing one `questionnaire.ts` and no app change. Answer delivery is now testable without an emulator — a plan is a value — and the app's `answerNavigationKeys` and its test are gone.

Claude question cards need the hook installed; without it Claude asks still render, but only as answered bubbles once someone answered in the terminal. A session killed mid-ask leaves a sidecar, which the daemon prunes at startup and the reader ignores after 24h.

The bridge reads the pane's transcript to answer (memoized, so the steady-state cost is a stat) and holds per-ask progress in memory, so a bridge restart mid-ask loses the tab position; the next answer then navigates from tab 0. Claude's grammar was mapped empirically, not from a published contract, and a TUI redesign upstream will break it — the mapping and how it was verified are documented in `bridge/src/agents/claude/questionnaire.ts`.
