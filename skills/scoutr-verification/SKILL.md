---
name: scoutr-verification
description: Verify Scoutr changes in two phases: cheap targeted checks during implementation/review, then emulator/integration/E2E/runtime acceptance only after reviews are resolved and the code is otherwise final. Use before committing bridge/Android work or when runtime proof is requested.
---

# Scoutr verification

Verification has a strict phase boundary. **Expensive runtime verification is the final acceptance step, not part of the implementation/review loop.**

## Core workflow

Use this order for material work:

1. **Implement** the change.
2. **Inner-loop verification:** run only cheap, targeted checks that help develop the change.
3. **Review:** simplify/self-review and run `skills/scoutr-review/SKILL.md`; fix all accepted findings.
4. After review-driven edits, re-run only cheap targeted checks that those edits could invalidate.
5. Reach **review-clean / code-frozen** state: no known review findings remain and no planned implementation edits remain.
6. **Final acceptance:** only now run emulator, instrumentation, integration, E2E, runtime walkthroughs, and screenshot/visual verification as warranted.
7. Commit after final acceptance succeeds. **Do not run another code-review pass after a successful final acceptance run.**

This ordering is intentional. Do not alternate emulator → review → fix → emulator → review. Reviews happen before expensive runtime acceptance.

If final acceptance exposes a real defect, fix it, return to the review phase, resolve any review impact with cheap checks, then perform final acceptance again once the code is review-clean. A failed acceptance test is evidence that justifies another final pass; a successful one is terminal.

## 1. Inner-loop verification — cheap checks only

During implementation and review, use the narrowest inexpensive checks that can produce useful evidence.

Typical inner-loop checks:

- **Bridge-only change:** relevant bridge test(s) and/or typecheck.
- **Android logic/state change:** relevant JVM/unit test class or focused test set.
- **Android compile/API uncertainty:** a focused compile/build task when needed to answer a concrete question.
- **Bridge + Android contract change:** focused non-device checks on both sides of the changed contract.
- **Text-only, assertion-only, plan/status, or similarly low-risk change:** no broad verification unless executable behavior can actually be affected.

Examples:

```bash
cd bridge && npm run typecheck
cd bridge && npm test -- <focused-test-or-pattern>

cd android && ANDROID_HOME="$HOME/Android/sdk" ./gradlew testDebugUnitTest --tests '<TestClassOrPattern>'
```

Prefer incremental Gradle work. Do not use `--rerun-tasks` unless stale output is suspected or a task genuinely needs forcing.

### Forbidden in the inner loop by default

Do **not** run these before review is clean merely because the change is UI-related:

- Gradle Managed Device / instrumentation suites;
- emulator installation or app walkthroughs;
- E2E/integration flows involving the emulator/device;
- runtime screenshots or visual acceptance passes;
- the full verification suite.

Run one early only when it is itself the **diagnostic experiment required to understand an otherwise unresolvable bug**. If you make that exception, treat it as diagnosis, not acceptance; the real acceptance pass still happens once, at the end.

## 2. Review boundary

Before any final emulator/integration/E2E verification:

1. implementation is complete;
2. cheap targeted checks are passing;
3. simplification/self-review is complete;
4. `skills/scoutr-review/SKILL.md` has completed;
5. every accepted review finding is fixed;
6. cheap checks invalidated by those fixes pass;
7. no further code edits are planned.

At this point the code is **review-clean / code-frozen** for acceptance.

Do not start final runtime verification while a reviewer is still pending or while review findings remain unresolved.

## 3. Final acceptance — emulator/integration/E2E last

Only after the review boundary is satisfied, choose the smallest final acceptance set that covers the material runtime risks.

Examples:

- **Android UI/interaction change:** relevant instrumentation test(s) and runtime evidence.
- **Bridge ↔ Android contract change:** integration coverage across the changed boundary, plus emulator flow if user-visible behavior depends on it.
- **Cross-cutting/architectural change:** broader integration/full verification when the risk justifies it.
- **Release/merge milestone or explicit request:** full suite.

A focused instrumentation class:

```bash
cd android && ANDROID_HOME="$HOME/Android/sdk" ./gradlew \
  pixel2api36DebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.<Test>
```

Do not automatically run both a focused instrumentation suite and the full managed-device suite unless they cover distinct risks.

## 4. Herdr completion for slow final checks

For a fast command, direct execution is fine. For slow/noisy/device-bound work, use a sibling Herdr pane and wait on explicit completion rather than elapsed time.

Create one pane for a serial verification sequence:

```bash
split=$(herdr pane split --current --direction right --cwd "$PWD" --no-focus)
verify_pane=$(printf '%s\n' "$split" | jq -r '.result.pane.pane_id')
```

For each ordinary shell command, create a unique completion marker carrying its exit status:

```bash
marker="__SCOUTR_CHECK_$(date +%s%N)__"

herdr pane run "$verify_pane" \
  "cd android && ANDROID_HOME=\"$HOME/Android/sdk\" ./gradlew pixel2api36DebugAndroidTest; rc=\$?; printf '\\n${marker}:%s\\n' \"\$rc\""

result=$(herdr pane wait-output "$verify_pane" --regex "^${marker}:[0-9]+$")
rc=$(printf '%s\n' "$result" | jq -r '.result.matched_line | split(":")[-1] | tonumber')
```

Inspect output as needed:

```bash
herdr pane read "$verify_pane" --source recent-unwrapped --lines 200
```

Rules:

- do not reuse completion markers;
- anchor the marker as a whole line because terminal input may be echoed;
- record the exit code; seeing the marker only proves the command exited;
- keep Android/Gradle commands serial in the same checkout;
- close the pane after the final acceptance sequence:

```bash
herdr pane close "$verify_pane"
```

For recognized coding agents, use the global `herdr-agent-delegation` skill and lifecycle state instead of output markers.

## 5. Runtime evidence

Runtime evidence belongs to **final acceptance**, after reviews are done.

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop dev.scoutr.app
adb shell am start -n dev.scoutr.app/.MainActivity
```

Drive with `adb shell input tap X Y`, `input text` (use `%s` for spaces), or `input keyevent 4|66|3|111`. For bounds/state:

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml
```

Capture screenshots with:

```bash
adb exec-out screencap -p > /tmp/scoutr-<name>.png
```

Inspect screenshots directly when the active model supports vision; otherwise use `skills/scoutr-vision/SKILL.md`, which delegates through Herdr lifecycle state. Save only evidence that answers a concrete acceptance question.

## 6. Full verification

Run the full suite only when at least one is true:

- the user explicitly asks for full verification;
- preparing a release, merge milestone, or equivalent acceptance point;
- the change is substantial or architectural;
- the change crosses bridge/Android boundaries or affects widely shared infrastructure;
- focused final acceptance leaves material risk that the broader suite can actually address.

Full commands:

```bash
cd bridge && npm run typecheck && npm test
cd android && ANDROID_HOME="$HOME/Android/sdk" ./gradlew testDebugUnitTest
cd android && ANDROID_HOME="$HOME/Android/sdk" ./gradlew pixel2api36DebugAndroidTest
cd android && ANDROID_HOME="$HOME/Android/sdk" ./gradlew assembleDebug
```

Or use `scripts/verify.sh`; use `--no-emulator` only when intentionally skipping the managed-device suite.

The full suite is a **final acceptance gate**. Do not run it before review and then repeat it because review changed the code.

## 7. Gotchas that cost real time

- **BridgeClient is final** — emulator tests use the real client plus a fresh **unsaved** `ConnectionStore` so ViewModels never start polling.
- **MockWebServer.url() does reverse DNS** — never call it on the main thread; build the ViewModel before `compose.setContent`.
- **`pkill -f cli.ts` can match your own shell** — use a bracketed pattern such as `pkill -f 'cli[.]ts'`.
- **Robolectric shares SharedPreferences** — save/clear connections explicitly per test.
- **readSeek needs fresh anchors after every edit** — re-grep/re-digest first, or use plain `edit` for small changes.
- **ntfy drops custom JSON publish fields** — deep links travel in the documented `click` field.
- **Composer Enter must never send** — keep multiline + `ImeAction.None` + no-op `KeyboardActions`; `ChatComposerKeyTest` pins the contract.
- Instrumentation runs only on the emulator, never the physical Pixel.
- Keep one Gradle invocation per checkout and one instrumentation run on `emulator-5554` at a time.
- `pane wait-output` can match stale or echoed text. Unique marker + anchored whole-line regex is the standard completion pattern.

Full scratch-bridge, adb preference injection, notification deep-link validation, and failure-recovery recipes live in `docs/dev-workflow.md`.

## 8. Finish

After final acceptance succeeds:

1. Do not start another review/verification cycle without new evidence of a defect.
2. If the change came from a design plan, update its status in `design-plans/README.md`.
3. Record useful verification evidence in the commit: checks run, important runtime observations, and meaningful screenshot paths.
4. Commit.

The desired terminal state is: **review clean → final runtime acceptance passes → commit**.

## Installing this skill outside the repo

Copy to `~/.pi/agent/skills/scoutr-verification/` (or `~/.agents/skills/scoutr-verification/`) to make it loadable from any repo.
