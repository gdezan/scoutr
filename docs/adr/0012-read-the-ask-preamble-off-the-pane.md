# Read an ask's preamble off the pane

The prose an agent writes above a question is part of the question. While the card is open the pane is the only place it exists, so the bridge reads it there — once per ask.

## Context

An `AskUserQuestion` card arrived on the phone with no background: just the questions and their options, stripped of the paragraphs the agent had written to explain what it had found and why it was asking. Dismissing the card made that text appear, which is what gave the failure away — the text was never missing, only late.

ADR 0006 established that Claude Code writes the assistant record holding an `AskUserQuestion` call only once the ask is answered, which is why an open ask is served from a `PreToolUse` sidecar at all. The buffering is wider than that record. Measured live against 2.1.241 by polling a session's JSONL while a card was on screen: the file did not grow by a single byte for the fourteen seconds the questionnaire was up, then the whole assistant turn — thinking, the preamble text, and the tool call — landed in one write the moment the ask was answered. The preamble record's own timestamp is fourteen seconds older than its write. Dismissing cancels the tool call, which flushes the same turn; that is why Dismiss "produces" the text.

So the transcript cannot supply it, and neither can the hook. `PreToolUse` carries `session_id`, `transcript_path`, `cwd`, `permission_mode`, `tool_name`, `tool_input` and `tool_use_id` — no assistant text — and Claude Code's own hook documentation says `transcript_path` "may lag behind the current turn's messages". A hook that read the transcript tail would read a file that still ends at the user's prompt.

The pane has it. It is on screen, which is how the terminal shows it to anyone sitting in front of it.

## Decision

- **The bridge reads the preamble off the pane, once per ask.** `claudeCaptureAskContext(herdr, paneId, path)` takes one `agent read --source visible` snapshot and stores the extracted prose in the same sidecar the card is already served from. `visible` is not a preference: `recent_unwrapped` refuses a pane that is not idle (`agent_not_idle`), and a pane blocked on a questionnaire is not idle.
- **The Claude adapter owns the layout, as it owns the keystrokes.** `ask-preamble.ts` parses the TUI's own shape — the last `●` bullet before the questionnaire's box rule — the same way `questionnaire.ts` owns the answer key sequence. Nothing about a TUI's rendering reaches the app.
- **Refusing beats guessing.** The text renders as if the agent had said it, so a block that cannot be identified as the prose immediately above the box is dropped: a tool-call bullet, a block carrying tool output, a pane with no questionnaire on it, and a preamble whose bullet has already scrolled off the top all yield nothing. A card with no background is what shipped before this change.
- **The capture rides the agents poll, not the session read.** Session reads are deliberately file-bound; `/api/agents` is already herdr-bound and already knows both the pane and the transcript path. Chat polls it immediately before the session read, so the background arrives with its card rather than a tick behind it. The sidecar write moves the sidecar's stamp, which is what makes the next question read notice there is something new.
- **One attempt per ask, whatever the outcome.** The sidecar records that the pane was read, so a failed or empty read is never retried on the next poll — the read is a herdr round trip on a 2.5s path, bounded at 2s, and a pane that cannot answer it once will not answer it three seconds later.
- **The app renders it as an ordinary agent message above the card.** `ChatRow.Preamble` uses the same markdown and spacing as a real assistant entry, because it is the same prose. It is emitted only while the ask is open: once the round lands the transcript carries that text as a genuine entry, and showing both would say it twice.

## Consequences

The card now answers "why am I being asked this" without leaving the screen, which is the only moment the answer is useful.

What comes back is TUI-rendered, not markdown: emphasis and code fences are already flattened by the time the pane draws them, and the text is hard-wrapped to the terminal's width, so the bridge rejoins wrapped paragraphs and keeps list items on their own lines. It reads as prose on a phone, but it is not a faithful copy of what the agent wrote — the transcript's version, which is, replaces it as soon as the round resolves.

A long preamble whose bullet has scrolled above the visible screen is lost. Scrolling the pane to recover it would mean driving someone else's TUI to read it, which is a much larger promise than this makes.

This is Claude-only. `captureAskContext` is an optional seam, and pi records its ask in the session file as it happens, so its cards already carry the surrounding transcript.

Like the questionnaire grammar in ADR 0006, the layout being parsed is empirical: a `●` bullet, a two-space continuation indent, and a full-width rule opening the question box, verified against 2.1.241. An upstream redesign breaks the extraction, and it degrades to the pre-change behaviour rather than to a wrong preamble.
