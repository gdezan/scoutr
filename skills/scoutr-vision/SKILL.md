---
name: scoutr-vision
description: Inspect Scoutr screenshots, mockups, rendered UI, or diagrams when the active agent cannot directly use vision. Delegate through Herdr lifecycle state using the global herdr-agent-delegation skill.
---

# Scoutr vision workflow

Use this only when the task depends on visual evidence and the active model cannot inspect the image directly.

## Delegation rule

Use the global `herdr-agent-delegation` skill as the canonical sibling-pane orchestration procedure. Pi must run in interactive/TUI mode so Herdr receives its lifecycle state. Do not use `pi -p`, sleep loops, or sentinel polling for a recognized agent.

## Vision recipe

1. Split a sibling pane, preserving cwd and focus:

   ```bash
   split=$(herdr pane split --current --direction right --cwd "$PWD" --no-focus)
   pane_id=$(printf '%s\n' "$split" | jq -r '.result.pane.pane_id')
   ```

2. Start a named Pi agent with a vision-capable model:

   ```bash
   herdr agent start vision --kind pi --pane "$pane_id" -- --model opencode-go/gpt-5.6-luna
   ```

   If that model is unavailable, choose a model reporting `images: yes` from `pi --list-models`. If `vision` is already a live agent name, choose another valid unique name.

3. Submit the image request and wait on lifecycle settlement:

   ```bash
   herdr agent prompt vision \
     "@<abs-image-path> Describe only the visual evidence relevant to this task: layout, text, colors, state, interaction cues, and visible errors." \
     --wait --until idle --until done --until blocked
   ```

   No caller timeout is needed for normal completion; Herdr returns when the agent settles. `agent_prompt_stalled` handles the case where a prompt never produces the expected lifecycle transition.

4. Handle the settled state:
   - `idle` / `done` → read the answer.
   - `blocked` → inspect what the agent needs, respond with `agent prompt` or `agent send-keys`, then use `agent wait` for the next state transition when appropriate.

5. Read the answer:

   ```bash
   herdr agent read vision --source recent-unwrapped --lines 200 > /tmp/vision-answer.md
   ```

   If the transcript is truncated, ask the agent to write its answer to a temp Markdown file and return only the path.

6. Close only the pane created for this workflow:

   ```bash
   herdr pane close "$pane_id"
   ```

7. Use `/tmp/vision-answer.md` only as evidence for the visual question that triggered the workflow. Do not infer visual correctness from implementation code.

## Failure handling

- Empty answer / missing attachment → diagnose the model call or `@path` attachment before retrying.
- `agent_prompt_stalled` → inspect `herdr agent get` / `herdr agent explain`; do not replace the failed lifecycle signal with sleeps.
- If lifecycle reporting is genuinely unavailable, use the fallback in `herdr-agent-delegation`: an explicit, anchored `pane wait-output` condition rather than a polling loop.

Done when the visual evidence answers the specific question that triggered this workflow.
