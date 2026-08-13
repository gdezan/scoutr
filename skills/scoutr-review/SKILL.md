---
name: scoutr-review
description: Run a fresh independent Pi review of Scoutr's current uncommitted changes before committing. Delegation is orchestrated through Herdr lifecycle state; use the global herdr-agent-delegation skill.
---

# Scoutr pre-commit review

Run this after implementation and cheap targeted checks, **before** emulator/integration/E2E/runtime acceptance.

This review is part of the code-finalization phase. Resolve review findings first; expensive runtime verification happens only after the work is review-clean.

## Delegation rule

Use the global `herdr-agent-delegation` skill as the canonical orchestration procedure. Do not duplicate its mechanics here and do not replace lifecycle waits with sleeps, grep loops, or ad-hoc sentinels.

For a recognized Pi reviewer, completion is Herdr agent lifecycle state. `agent prompt --wait` is the preferred submit-and-wait primitive; no caller timeout is required for normal operation.

## Review recipe

1. Split a sibling pane, preserving cwd and focus:

   ```bash
   split=$(herdr pane split --current --direction right --cwd "$PWD" --no-focus)
   pane_id=$(printf '%s\n' "$split" | jq -r '.result.pane.pane_id')
   ```

2. Start a fresh reviewer in that pane:

   ```bash
   herdr agent start reviewer --kind pi --pane "$pane_id" -- --model openai-codex/gpt-5.6-sol --thinking low
   ```

   `agent start` already waits until the expected agent owns the pane and is interactive. If `reviewer` is already a live agent name, choose another valid unique name and use it consistently below.

3. Submit the review and wait on lifecycle settlement:

   ```bash
   herdr agent prompt reviewer \
     "Review the current uncommitted work (git status, git diff, git diff --cached). Report concrete correctness bugs, spec mismatches, and violations of AGENTS.md, each with file and line; skip style nits." \
     --wait --until idle --until done --until blocked
   ```

   Herdr observes lifecycle transitions. Do not add a timeout merely to estimate how long review should take.

4. Inspect the returned `agent_status`:
   - `idle` / `done` → read the answer.
   - `blocked` → inspect the question/approval UI, answer it with `agent prompt` or `agent send-keys`, then wait again with `agent wait` if needed.
   - `agent_prompt_stalled` → lifecycle never advanced; diagnose the agent/integration instead of sleeping and retrying blindly.

5. Read the result:

   ```bash
   herdr agent read reviewer --source recent-unwrapped --lines 200 > /tmp/code-review.md
   ```

   If alternate-screen history still truncates the answer, ask the reviewer to write Markdown to a temp file and reply only with the path, then read that file directly.

6. Resolve every concrete finding:
   - fix it and re-run only the narrowest **cheap** checks relevant to that edit; or
   - consciously dismiss it because it is incorrect or outside the requested scope.

   Do **not** start emulator, instrumentation, integration, E2E, or runtime screenshot verification in response to review findings. Finish the review/fix loop first.

7. Close only the pane created for this review:

   ```bash
   herdr pane close "$pane_id"
   ```

## Failure handling

- `blocked` is a valid settled state, not a timeout. Read the agent, satisfy the approval/question, and continue.
- `agent_prompt_stalled` means Herdr did not observe the expected lifecycle change. Use `herdr agent get`, `herdr agent explain`, and the `herdr-agent-delegation` recovery path before retrying.
- If lifecycle reporting is genuinely unavailable, fall back to the delegation skill's anchored `pane wait-output` method; do not introduce a polling loop.
- Do not treat an empty/incomplete read or a failed delegation harness as a successful review.

Done when the review settled successfully, every concrete finding has been resolved or consciously dismissed, and no further review-driven edits are planned. At that point hand off to `scoutr-verification` for the **final** runtime acceptance phase. Do not review again after a successful final acceptance pass unless that pass exposed a defect that required code changes.
