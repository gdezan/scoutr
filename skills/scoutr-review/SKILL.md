---
name: scoutr-review
description: Run an independent review of Scoutr's uncommitted changes before final runtime acceptance or committing. Use after implementation and cheap targeted checks. Skip when the user explicitly asks to commit, tag, or push and does not ask for review.
---

# Scoutr pre-commit review

Use this skill after implementation and cheap targeted checks, before emulator,
instrumentation, integration, E2E, or runtime screenshot acceptance. An explicit
user request to commit, tag, or push that does not ask for review skips this
skill — commit instead.

## Review contract

Ask an independent reviewer to inspect the current uncommitted work, including
`git status`, `git diff`, and `git diff --cached`. The review should report only
concrete correctness bugs, specification mismatches, and violations of
`AGENTS.md`, with file and line references; skip style nits.

Use the global `herdr-agent-delegation` skill for sibling-pane creation, agent
startup, lifecycle waits, transcript reads, blocked-agent recovery, and cleanup.
Do not duplicate or replace that orchestration with sleeps, polling, or ad-hoc
sentinels.

## Resolve findings

For every concrete finding:

- fix it and rerun only the narrowest cheap checks that the edit could invalidate; or
- consciously dismiss it because it is incorrect or outside the requested scope.

Do not start emulator, instrumentation, integration, E2E, or runtime screenshot
verification in response to review findings. Finish the review/fix loop first.

A review is complete only when the reviewer settled successfully, every concrete
finding is fixed or consciously dismissed, and no further review-driven edits
are planned. Then hand off to `scoutr-verification` for final runtime
acceptance. Do not review again after a successful final acceptance pass unless
that pass exposed a defect requiring code changes.
