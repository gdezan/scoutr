# Scoutr agent instructions

## Git workflow

- Work directly on `main` and commit there unless the user explicitly specifies another branch or workflow.

## Project map

Scoutr is a self-hosted mobile scoutr for herdr panes and pi agents. A Node/TypeScript bridge daemon owns the herdr Unix socket and exposes a private HTTP/WS API; the Android app talks only to that API.

- `bridge/` — Node/TS daemon. Entry: `src/cli.ts serve`; `server.ts` only exports `createScoutrServer`.
  - `herdr/` owns socket client/feed and the `port.ts` seam.
  - `routes/` owns auth/body/error mapping.
  - Agent adapters live under `agents/`; shared JSONL parsing is in `transcript.ts`.
  - Terminal child-process transport belongs under `terminal/`; do not put it on `HerdrPort`.
  - Tests live in `bridge/test/`; offline HTTP tests use `bridge/test/support/fake-herdr.ts` and `fake-feed.ts`.
- `android/` — Kotlin + Jetpack Compose Material 3, package `dev.scoutr.app`; manual DI via `ScoutrApp.AppContainer` (no Hilt/Room).
  - `data/` DTOs + SharedPreferences stores
  - `net/` `BridgeClient` behind `ScoutrApi`, plus `NtfyClient`
  - `state/` ViewModels
  - `service/` monitor service, deep links, reply receiver
  - `ui/components/`, `ui/screens/`, `ui/theme/`, `ui/motion/`
  - Terminal route: `terminal/RemoteTerminalSession.kt` (emulator seam, no PTY/JNI), `net/TerminalSocketClient.kt` + `TerminalTransport`, `state/TerminalViewModel.kt`, `ui/screens/terminal/`.
  - `android/vendor/termux/` holds the pinned Apache-2.0 `terminal-emulator` and `terminal-view` subset; keep `UPSTREAM.md` accurate and do not vendor `termux-shared` or app code.

Primary references:

- Design contract: `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`
- Current UX work and execution order: `design-plans/README.md`
- Interactive terminal handoff: `.plans/full-screen-interactive-terminal.md`
- Durable architecture decisions: `docs/adr/`
- Verification recipes, emulator workflow, and failure recovery: `docs/dev-workflow.md`
- Verification skill: `skills/scoutr-verification/SKILL.md`
- Herdr agent orchestration: global `herdr-agent-delegation` skill

## Operating rules

- **Avoid debugging loops.** Before each new attempt, identify what new evidence it can produce or what hypothesis it can eliminate. If it is materially similar to previous attempts or only succeeds by chance, stop, reassess assumptions, and choose a different diagnostic path.
- **Run the narrowest useful experiment.** Prefer inspection, tracing, focused tests, or direct state verification over speculative edits. Unexpected results are evidence against the current mental model; do not work around them without understanding them.
- **Prefer completion signals over elapsed-time guesses.** For recognized agents, use Herdr lifecycle waits (`agent prompt --wait`, `agent wait`). For ordinary shell processes, use `pane run` plus `pane wait-output` on a specific completion condition. Do not build sleep/poll loops. A timeout is a safety ceiling only when a reliable completion signal is unavailable or an external bound is actually required; it is not the normal completion mechanism.
- **Use Herdr's semantic control surface.** Use agent commands for recognized agents and pane commands for ordinary terminals/processes. When delegating to an agent, follow the global `herdr-agent-delegation` skill rather than inventing sentinels or polling.
- **Diagnose infrastructure failures before changing product behavior.** AAPT2, packaging, missing-dex, emulator-focus, socket, or harness failures are not evidence that production logic is wrong.
- **Keep Android verification serial.** One Gradle invocation per checkout and one instrumentation run on `emulator-5554` at a time. Do not overlap Gradle jobs.

## Verification

- **Keep expensive runtime verification terminal.** The normal order is: implement → cheap targeted checks → review/fix → review-clean/code-frozen → emulator/integration/E2E/runtime acceptance → commit. Do not alternate emulator runs with code-review cycles.
- During implementation and review, run only the narrowest inexpensive checks that help develop or validate the code. Device/emulator/instrumentation/E2E work is not part of the normal inner loop.
- Start final emulator/integration/E2E/runtime verification only after all review findings are resolved and no further implementation edits are planned. After a successful final acceptance pass, do not run another review merely because verification completed.
- If final acceptance exposes a real defect, fix it, return to the review/cheap-check phase, then perform final acceptance again only once the code is review-clean.
- Verification must remain proportional to risk. Each additional suite or runtime check must cover a material risk not already addressed.
- Prefer incremental builds; use `--rerun-tasks` only when stale Gradle output is suspected or a specific task must genuinely be forced.
- For slow/noisy/device-bound checks inside Herdr, prefer a sibling pane and `pane wait-output` on an explicit unique completion condition rather than sleeps or guessed durations.
- Use `skills/scoutr-verification/SKILL.md` for the phase boundary, targeted checks, final runtime evidence, and full-verification criteria.

## Project invariants and traps

- Android design is always-dark Material 3. Accent `#5B8CFF` is reserved for AI-owned states; use mono only for paths, commands, and tool output. Status is the color language.
- Motion does not bounce or spin. Agent-busy state uses the expanding-ripple `WorkingIndicator`; under `LocalReduceMotion` it becomes a static ring. Prefer it over `CircularProgressIndicator` for agent-busy states.
- Status mapping is consistent across screens: `blocked/NeedsYou -> error`, `working -> primary`, `done -> secondary`.
- Interactive terminal is one full-screen Herdr pane at a time with an overlay hierarchy selector; do not put raw terminal output back on Chat.
- ViewModels talk to `ScoutrApi`; `BridgeClient` implements it. Unit tests use `FakeScoutrApi`. Emulator tests use a real `BridgeClient` with a fresh **unsaved** `ConnectionStore` so ViewModels do not start polling.
- Run instrumentation only on the emulator, never the physical Pixel. If `emulator-5554` is absent, boot the `scoutr` AVD and confirm the target with `adb devices`.
- `MockWebServer.url()` performs reverse DNS; never call it on the main thread.
- Robolectric shares SharedPreferences across tests; explicitly save/clear connections.
- readSeek anchors are invalid after editing the same file; re-grep/re-digest, or use plain `edit` for small changes.
- `XDG_CONFIG_HOME` selects the bridge config dir; `SCOUTR_REPO_ROOTS` allow-lists review repos; config tokens must be at least 16 characters.
- ntfy drops custom JSON publish fields; deep links belong in ntfy's documented `click` field.
- Composer contract: Enter inserts a newline and must never send; keep multiline + `ImeAction.None` + no-op `KeyboardActions`.
- `pkill -f` / `pgrep -f` can match their own shell command. Use bracketed patterns or a pid mechanism.
- `pane wait-output` checks existing output first. For command-completion markers, make the marker unique per run and match an anchored whole line so echoed command text or stale output cannot satisfy the wait.

## Task-specific workflows

- **Herdr agent delegation:** use the global `herdr-agent-delegation` skill for sibling-pane agent start/prompt/wait/read/cleanup. Lifecycle state is the source of truth; do not replace it with sleep/poll/sentinel loops.
- **Visual/UI evidence:** inspect screenshots or rendered UI directly when vision is available; otherwise use `skills/scoutr-vision/SKILL.md`. Do not infer visual correctness from code alone.
- **Pre-commit review:** use `skills/scoutr-review/SKILL.md`. Every concrete finding must be fixed or consciously dismissed before committing.
- **Verification/emulator work:** use `skills/scoutr-verification/SKILL.md` and `docs/dev-workflow.md` for the full procedure and recovery steps.

## Engineering principles

- Remove obsolete paths rather than adding backward-compatibility layers, fallbacks, or migrations unless the user explicitly requires compatibility.
- Choose the simplest implementation that fully satisfies current requirements without knowingly creating an imminent rewrite. Do not add abstractions, configuration, or indirection for hypothetical future requirements.
- Grow the system in working end-to-end layers. Keep components modular and concerns separated; do not trade a working product for unfinished complexity.
- Prefer existing dependencies, then well-maintained established libraries, before custom implementations. Read the library documentation before concluding it cannot support the requirement. Reimplement only when the dependency would cost more than the code it replaces, and record why in the commit.
