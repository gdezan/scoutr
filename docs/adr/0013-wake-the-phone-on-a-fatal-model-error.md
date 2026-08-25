# Wake the phone when an agent dies on a model error

A conversation that stops because the model API keeps failing must reach the phone as a needs-you notification, not sit looking alive in the app while nothing happens.

## Context

When a provider returns 503s long enough, pi retries internally three or four times, appends one failed assistant record per attempt, then gives up. The session file simply stops growing, with `stopReason:"error"` on the last record. Nothing crashes: `_runAgentPrompt` still fires `agent_settled` in its `finally`, so herdr marks the turn finished — the same transition a clean finish produces. The bridge treated every turn end as "nothing to say" beyond its optional Finished ping, and the phone showed a conversation that looked alive while nothing was happening. The user only found out by opening the terminal and typing `continue` themselves.

The transcript is what distinguishes the two turn ends. A clean finish ends on a real assistant message (`stopReason:"stop"`) or on tool results; a death by 503 ends on a record whose `stopReason` is `"error"` with empty content. Reading the last few entries of the session file at the moment the turn lands answers the question with no new infrastructure: the bridge already resolves each pane's transcript path and reads tails for board summaries.
## Decision

- **Detection rides the turn-end transition plus a transcript tail check.** Verified live against herdr's full-lifecycle hook authority: the extension reports `idle` internally, but herdr derives the pane status as `done` for every completed turn — clean or fatal alike — so a naive idle watcher never fires. Instead, when a pane's status lands on `done` (or `idle`, for herdr builds that surface the raw state), the publisher asks an injected probe whether that pane's transcript ends in a failed model call. An error tail converts that transition into an `errored` ping instead of the ordinary Finished ping; a clean finish behaves exactly as before.
- **The rule walks backwards past tool results and system records and requires the first speaking entry to be an assistant record with `stopReason:"error"`.** Trailing tool results are stepped over because a retry can die after a tool call resolved. A trailing user or bash-execution entry means the conversation moved on and reads as not errored. Aborts (`stopReason:"aborted"`) are deliberate and never alert.
- **Every probe failure is a quiet finish.** Unknown pane, missing session reference, unreadable file, unparsable backend — all resolve false. A missed alert costs one manual terminal check; a false alarm trains the user to ignore the channel.
- **`errored` is a fourth ping kind, contentless like the others (ADR 0007).** High priority, 15-minute TTL. The app fetches session detail on receipt, so old apps that ignore the unknown kind degrade to today's behavior.
- **On Android it shares the blocked slot, channel, preference, and actions.** Both mean "the agent cannot proceed without you", so both post into needs-you gated by the same toggle, one notification per pane with latest-wins. Replying from the notification steers the pane — which is precisely the recovery an errored stop wants ("continue") — so the Reply action carries over unchanged. Muting works per pane as before.
- **Edge-triggered per settle, re-armed by movement.** One ping per transition into an errored idle; working, blocked, or done clears it with a resolve; pruning a vanished pane resolves too. A repeat idle event neither re-pings nor clears its own notification.
- **Foregrounded phones stay silent**, matching blocked: if the app is open, the pane's own status already says what happened.
- **Bridge restarts lose errored state**, consistent with how blocked state behaves today.

## Consequences

The failure that started this — forty silent minutes after a burst of 503s, fixed by manually typing `continue` — now arrives on the lock screen within seconds of the give-up, with a reply box attached to the fix.

The check runs once per settle on a bounded tail read (last 8 entries), not on any polling loop; a busy day of normal idles costs a few hundred bytes of file reads each.

What it deliberately does not do: retry or restart anything. The notification is awareness only; the decision to continue, steer elsewhere, or leave it belongs to the user. Nor does it cover agents that hang without settling — a pane stuck `working` forever looks identical to a pane doing real work, and distinguishing those is a different problem.

The detection is written against the normalized transcript model all three backends share, but only pi writes `stopReason:"error"` records today. Claude Code and AGY panes get the same machinery for free if their transcripts ever carry the same shape; until then their settles probe and find nothing.
