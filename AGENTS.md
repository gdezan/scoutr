# Scoutr agent instructions

## Git workflow

- Work directly on `main` and commit there unless the user explicitly specifies another branch or workflow.
- When committing, push the branch and its release tags together (`git push --follow-tags`) so a new `vX.Y.Z` tag always travels with the commit.
- Never add "Co-authored-by" lines to commits; the user is the only author of record.

## Project map

Scoutr is a self-hosted Android console for herdr panes and pi agents. A
Node/TypeScript bridge owns the herdr Unix socket and exposes a private HTTP/WS
API; the Android app talks only to that API.

- `bridge/` — Node/TS daemon. Entry: `src/cli.ts serve`; `server.ts` only exports `createScoutrServer`.
  - `herdr/` owns the socket client/feed and `port.ts` seam.
  - `routes/` owns auth, body, and error mapping.
  - `agents/` contains agent adapters; `transcript.ts` owns shared JSONL parsing.
  - `terminal/` owns terminal child-process transport; keep it off `HerdrPort`.
  - Tests live in `bridge/test/`; offline HTTP tests use `test/support/fake-herdr.ts` and `fake-feed.ts`.
- `android/` — Kotlin + Jetpack Compose Material 3, package `dev.scoutr.app`; manual DI through `ScoutrApp.AppContainer` (no Hilt/Room).
  - `data/` DTOs and SharedPreferences stores; `net/` API clients; `state/` ViewModels;
    `service/` the FCM messaging service, notification receivers, and deep-link validation;
    `notify/` owns every notification the app posts; `ui/` screens, components, theme, and motion.
  - Terminal code is under `terminal/`, `net/TerminalSocketClient.kt`, `state/TerminalViewModel.kt`, and `ui/screens/terminal/`.
  - `android/vendor/termux/` contains only the pinned Apache-2.0 renderer subset; keep `UPSTREAM.md` accurate.

## Operating rules

- **Avoid debugging loops:** before each attempt, name the new evidence it can produce or hypothesis it can eliminate. If it repeats a materially similar attempt or succeeds only by chance, stop and reassess.
- **Run the narrowest useful experiment:** prefer inspection, tracing, focused tests, or direct state verification over speculative edits.
- **Diagnose infrastructure first:** AAPT2, packaging, missing-dex, emulator-focus, socket, and harness failures are not evidence that product behavior is wrong.
- **Use completion signals:** recognized agents use Herdr lifecycle waits; ordinary long-running commands use a sibling pane and an explicit completion condition. Timeouts are safety ceilings, not completion mechanisms.
- **Keep Android verification serial:** one Gradle invocation per checkout and one instrumentation run on `emulator-5554` at a time.
- **Emulator is on-demand (artemis is always-on):** boot `emulator-5554` (`cockpit` AVD) only for final acceptance; kill with `adb -s emulator-5554 emu kill` when done. See `docs/dev-workflow.md` for boot/kill recipes.
- **Deploy finalized bridge work:** when the phone or live app would only see the change through the supervised service, run the deploy recipe in `docs/dev-workflow.md`. Source-green is not live.

## Verification boundary

Keep the normal flow terminal: implement → cheap targeted checks → review/fix →
review-clean and code-frozen → final runtime acceptance → commit. Do not run
emulator, instrumentation, integration, E2E, or visual acceptance in the normal
inner loop. If final acceptance exposes a real defect, return to review and cheap
checks before another final pass; a successful final pass is terminal.

Use `skills/scoutr-verification/SKILL.md` when selecting checks or running
emulator/integration/E2E acceptance. Use `docs/dev-workflow.md` for the live
bridge deploy recipe, scratch-bridge, emulator diagnostics, and recovery
recipes. `--rerun-tasks` is exceptional: use it only after evidence of stale or
skipped Gradle output.

## Product and architecture invariants

- Android is always-dark Material 3. The design contract, tokens, typography, status colors, and motion rules live in `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`; follow it for UI work.
- The terminal is one full-screen Herdr pane with an overlay hierarchy selector; raw terminal output never returns to Chat. Current terminal ownership, lifecycle, and limitations live in `docs/terminal.md`.
- ViewModels talk to `ScoutrApi`; `BridgeClient` implements it; unit tests use `FakeScoutrApi`.
- Instrumentation runs only on an emulator, never a physical Pixel. If `emulator-5554` is absent, boot the `cockpit` AVD per `docs/dev-workflow.md` and verify with `adb devices`.
- Composer Enter inserts a newline and never sends. Keep multiline input, `ImeAction.None`, and no-op `KeyboardActions`.
- Question answers travel as intent (`questionId`, `selectedLabels`, `text`); the adapter owns each TUI's questionnaire grammar, not the app. Claude's live ask hook and transcript timing are documented by ADR 0006.

## Task-specific pointers

- **Live bridge deploy, scratch-bridge, or Android/emulator diagnostics:** use `docs/dev-workflow.md`. Deploy after finalized bridge work the phone talks to.
- **Verification selection and final runtime evidence:** use `skills/scoutr-verification/SKILL.md`.
- **Pre-commit review:** use `skills/scoutr-review/SKILL.md` and resolve or dismiss every finding. An explicit ask to commit, tag, or push without requesting review is the commit itself.
- **Visual evidence when the active model cannot inspect images:** use `skills/scoutr-vision/SKILL.md`.
- **Herdr sibling-pane delegation:** use the global `herdr-agent-delegation` skill for start, prompt, wait, read, recovery, and cleanup.
- **Terminal implementation or review:** use `docs/terminal.md` plus ADRs 0001 and 0002.
