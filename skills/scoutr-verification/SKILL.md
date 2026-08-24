---
name: scoutr-verification
description: Verify Scoutr changes with cheap targeted checks during implementation, then emulator/integration/E2E/runtime acceptance only after review is resolved and code is final. Use before committing bridge/Android work or when runtime proof is requested.
---

# Scoutr verification

Verification has two phases. Cheap checks support implementation and review;
expensive runtime checks are terminal acceptance.

## Phase boundary

Use this order for material work:

1. Implement the change.
2. Run only cheap, targeted checks that answer the current development question.
3. Run `scoutr-review` and resolve or dismiss every concrete finding.
4. Re-run only cheap checks invalidated by review fixes.
5. Reach review-clean/code-frozen state: no known findings remain and no planned implementation edits remain.
6. Run the smallest final emulator, instrumentation, integration, E2E, and visual acceptance set that covers the material runtime risks.
7. Commit after final acceptance succeeds.

Do not alternate runtime acceptance with review and implementation. If final
acceptance exposes a real defect, return to implementation/review and repeat
final acceptance only after the code is review-clean. A successful final pass is
terminal.

## Cheap targeted checks

Choose the narrowest check that can produce useful evidence:

- Bridge-only change: `cd bridge && npm run typecheck` and the relevant `npm test` pattern.
- Android logic/state change: the relevant JVM or focused Compose test class.
- Android compile/API uncertainty: a focused Gradle compile or build task.
- Bridge/Android contract change: focused non-device checks on both sides.
- Text-only, assertion-only, plan/status, or similarly low-risk change: no broad verification unless executable behavior can be affected.

Prefer incremental Gradle work. Use `--rerun-tasks` only after evidence of stale
or incorrectly skipped output, not as a default.

`npm run lint` (oxlint) is not clean at baseline and is not a gate: a large
backlog of anti-slop errors predates any current change. Expecting zero, or
fixing the backlog, is not the job. If you run it, judge only your own delta —
compare the error count against the pre-change tree (`git stash`) and keep it
at zero.
A focused Android JVM class should print results shortly after
`:app:testDebugUnitTest` starts. Silence there is a hang: inspect
`GradleWorkerMain` / the test process, then stop waiting. Robolectric parking
scars live in `docs/dev-workflow.md`.

## Final acceptance selection

Run the smallest final set that covers the changed risk:

- Android UI or interaction change: relevant instrumentation and runtime evidence.
- Bridge/Android contract change: integration coverage across the boundary and an emulator flow when behavior is user-visible.
- Cross-cutting or architectural change: broader integration or full verification when justified.
- Release, merge milestone, or explicit full-verification request: full suite.

Do not automatically run both focused instrumentation and the full managed-device
suite unless they cover distinct risks. Instrumentation runs only on an emulator,
never a physical phone.

A focused managed-device test uses:

```bash
cd android && ANDROID_HOME="$HOME/Android/sdk" ./gradlew \
  pixel2api36DebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.<Test>
```

## Completion signals for slow checks

For slow, noisy, or device-bound commands, use a sibling Herdr pane and wait on
an explicit completion signal rather than elapsed time. Keep Gradle invocations
serial in one checkout and close the pane after the sequence.

For an ordinary shell command, set `SCOUTR_CHECK_COMMAND` to the exact check to
run. The example defaults to `npm test` so the copied block is executable:

```bash
split=$(herdr pane split --current --direction right --cwd "$PWD" --no-focus)
verify_pane=$(printf '%s\n' "$split" | jq -r '.result.pane.pane_id')
trap 'herdr pane close "$verify_pane" >/dev/null 2>&1 || true' EXIT
check_command="${SCOUTR_CHECK_COMMAND:-cd bridge && npm test}"
marker="__SCOUTR_CHECK_$(date +%s%N)__"
herdr pane run "$verify_pane" \
  "$check_command; rc=\$?; printf '\n${marker}:%s\n' \"\$rc\""
result=$(herdr pane wait-output "$verify_pane" --regex "^${marker}:[0-9]+$")
rc=$(printf '%s\n' "$result" | jq -r '.result.matched_line | split(":")[-1] | tonumber')
```

The marker proves only that the command exited; inspect the exit code. Never
reuse markers because `wait-output` checks existing output first, and anchor the
whole line because terminal input may be echoed. If the next expected stage is
late, inspect pane output and the worker and stop the wait instead of letting
the ceiling expire. For an interactive sequence that cannot emit a final marker,
emit and wait on a heartbeat after each stage. Recognized agents use lifecycle
state through the global `herdr-agent-delegation` skill instead.

## Runtime evidence

Runtime evidence belongs after review is complete. Use explicit
`adb -s emulator-5554` commands for the interactive emulator, `uiautomator` for
semantic state, and `adb exec-out screencap -p` for screenshots. Inspect images
directly when the active model supports vision; otherwise use
`skills/scoutr-vision/SKILL.md`. Save only evidence that answers a concrete
acceptance question.

## Full verification

Use the full suite only for an explicit request, release/merge milestone,
substantial or architectural change, a bridge/Android boundary change, widely
shared infrastructure, or material risk left by focused acceptance.

The executable full-suite entry point is `scripts/verify.sh` (or `make verify`).
It runs bridge typecheck/tests and serial Android unit, managed-device, and APK
build gates. Use `--no-emulator` only when intentionally skipping the managed
device suite.

After final acceptance succeeds, do not start another review or verification
cycle without new evidence of a defect. Record meaningful checks and runtime
evidence before committing.

## Detailed diagnostics

Use `docs/dev-workflow.md` for scratch bridge setup, bridge deployment freshness,
emulator boot and installation, managed-device logs, Compose test traps,
notification/deep-link validation, and failure recovery. That document owns
those recipes and hard-won testing scars.
