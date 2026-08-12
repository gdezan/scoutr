## Git workflow

- Work directly on `main` and commit changes there unless the user explicitly specifies another branch or workflow.

## Architecture map (Cockpit)

Cockpit is a self-hosted mobile cockpit for herdr panes and pi agents: a Node/TS bridge daemon owns the herdr Unix socket and exposes a private HTTP/WS API; the Android app (Kotlin + Jetpack Compose Material 3, package `dev.cockpit.app`) talks only to that API.

- `bridge/` — Node/TS daemon. Entry: `src/cli.ts serve` (server.ts only exports `createCockpitServer`). Per-feature modules: `herdr/` (socket client + feed + `port.ts` seam), `routes/` (route table + dispatcher owning auth/body/error-mapping; one module per feature), `agents/` (backend registry; `agents/pi/` and `agents/claude/` adapters; `agents/types.ts` is the `AgentBackend` interface), `transcript.ts` (the one JSONL parser — chat, catalog, and board all read through it), `sessions.ts`, `session-catalog.ts`, `questions.ts`, `commands.ts`, `live-output.ts`, `review.ts`, `attachments.ts`, `notify.ts`, `board-detail.ts`, `usage/`. Tests live in `bridge/test/` and run with `npm run typecheck && npm test` (`node --import tsx --test`); offline HTTP tests build on the fakes in `bridge/test/support/` (`fake-herdr.ts` implements the `HerdrPort` seam in `herdr/port.ts`; `fake-feed.ts` implements `HerdrEventFeed` in `herdr/feed.ts`).
- `android/` — Compose app, manual DI via `CockpitApp.AppContainer` (no Hilt/Room). Source dirs under `app/src/main/java/dev/cockpit/app/`: `data/` (DTOs + SharedPreferences stores), `net/` (BridgeClient behind the `CockpitApi` interface, NtfyClient), `state/` (ViewModels), `service/` (monitor service, deep links, reply receiver), `ui/components/`, `ui/screens/`, `ui/theme/` (Theme.kt + DiffPalette.kt), `ui/motion/` (motion vocabulary + haptics).
- Design contract: always-dark Material 3, one accent `#5B8CFF` reserved for AI-owned states, calm surface cards, mono only for paths/commands/tool output, state is the color. See `ui/theme/Theme.kt` and `docs/DESIGN.md`.
- Motion vocabulary: nothing bounces or spins (`ui/motion/Motion.kt`). The one looping animation in the app is the working indicator's expanding ripple (`ui/components/WorkingIndicator.kt`) — it expands rather than rotates, which is why it does not violate the no-spin rule; under `LocalReduceMotion` it collapses to a static ring. Prefer it over `CircularProgressIndicator` for agent-busy states.
- Status→color is one language across screens: `blocked/NeedsYou → error`, `working → primary`, `done → secondary` (`ui/screens/BoardScreen.kt`, `ui/components/WorkingIndicator.kt`).
- Live output is an escape hatch, not ambient chrome: the raw pane tail lives on its own route (`chat/{paneId}/live`, opened from the session menu) and `LiveOutputViewModel`'s poll runs only while that screen is visible. Do not reintroduce output text onto the chat screen — its busy signal is the working indicator.
- Long-running goal contract: `docs/production-goal-checklist.md` (live item map) with `docs/COMPLETION-REPORT.md` and `docs/AUDIT.md`.
- Extendability plans (adding a second agent backend, new screens/endpoints): `docs/architecture/README.md` — read before any refactor in `bridge/src/agents/` (and its `pi/`, `claude/` subdirs), `bridge/src/routes/`, `bridge/src/sessions.ts`, `bridge/src/server.ts`, or `android/…/net/BridgeClient.kt`.
- Verification recipes and traps: `docs/dev-workflow.md`; the `skills/cockpit-verification/SKILL.md` skill bundles the same loop (install to `~/.pi/agent/skills/` to make it loadable from any repo).

## Verification workflow

Run these before committing UI/bridge work, and treat them as the acceptance gates —
or run them all at once with `scripts/verify.sh` (add `--no-emulator` to skip the GMD suite):

```bash
cd bridge && npm run typecheck && npm test                       # 290 tests / 43 suites, ~10s
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest   # Gradle Managed Device, ~2 min
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug
```

For runtime/UI evidence, install the APK on the running emulator (`adb install -r .../app-debug.apk`), drive it with `adb shell input tap/text/keyevent`, capture with `adb exec-out screencap -p`, and inspect screenshots with the vision-pane workflow below. The full emulator recipe (scratch bridge, adb prefs injection, uiautomator bounds, taste reviews) is in `docs/dev-workflow.md`.

## Never wait on an unbounded command

A hung command produces no output, so waiting on it is indistinguishable from progress —
that is what makes it expensive. Bound it up front instead of discovering it afterwards.

1. **Bound before you run, not after it hangs.** Anything touching a socket, device,
   emulator, or network gets an explicit limit: `timeout 120 <cmd>`, `--test-timeout` for
   `node --test`, `--timeout` for `herdr agent prompt --wait` / `herdr agent wait`, `-timeout` for gradle. A
   command with no natural end (`tail -f`, `adb logcat`) needs one every time.
2. **Decide the expected duration first, then hold it to ~2x.** `cd bridge && npm test` is
   ~10s; gradle managed-device tests are ~2 min. Past twice that, kill it and diagnose —
   never re-run the same unbounded command hoping it finishes.
3. **Never poll with a pattern that matches your own shell.** `pgrep -f 'import tsx test/'`
   in an `until` loop matches the loop's own command line and so never exits. Bracket the
   pattern (`'impor[t] tsx'`), or wait on a pidfile or sentinel file instead.
4. **Fix a hang at its source.** Add the bound to `package.json`, the test file, or the
   script so the next session inherits it, then record it below. A bound you applied only
   at the call site is a bound the next agent will not have.

Known unbounded spots, already bounded — keep them that way: the live-socket suites in
`bridge/test/herdr-client.test.ts` (a real herdr under load leaves `snapshot()`/`subscribe()`
pending forever, which used to hang the whole run silently), `npm test` overall, gradle
managed-device tasks, and every `herdr agent prompt … --wait` in the Vision and Code
review workflows below.

## Gotchas (read before touching)

- ViewModels talk to `CockpitApi` (`net/CockpitApi.kt`), implemented by `BridgeClient`; unit tests stub it with `FakeCockpitApi` (`app/src/commonTest/`, shared with the emulator suite). Emulator tests still use a real BridgeClient + a fresh **unsaved** ConnectionStore so ViewModels never start polling.
- Tests run ONLY on the emulator — never install the APK or run instrumentation suites on the physical Pixel phone (it spazzes its screen). If `emulator-5554` is absent, boot the `cockpit` AVD: `$ANDROID_HOME/emulator/emulator -avd cockpit &` (only AVD, ~25-60s to boot; confirm via `adb devices`). Use the phone sparingly, for key integration walks only.
- `MockWebServer.url()` does a reverse-DNS lookup — never call it on the main thread (build ViewModels before `setContent` in tests).
- `pkill -f` matches your own command line if it contains the pattern — use `pkill -f 'cli[.]ts'` style brackets (see "Never wait on an unbounded command").
- Robolectric shares SharedPreferences across tests — save/clear connections explicitly (savedConnection helper pattern).
- readSeek tools demand fresh anchors after every edit to the same file; re-grep/re-digest first, or use the plain `edit` tool for small changes.
- Bridge envs: `XDG_CONFIG_HOME` picks the config dir; `COCKPIT_REPO_ROOTS` allow-lists review repos; config tokens must be ≥16 chars.
- ntfy drops custom JSON publish fields — deep links must travel in ntfy's documented `click` field (see `notify.ts`).
- Composer keyboard contract: Enter inserts a newline and must never send; keep multiline + `ImeAction.None` + no-op `KeyboardActions` (pinned by `ChatComposerKeyTest`).
- `herdr pane wait-output --match` also matches the pane's echoed command line (the sentinel text is in the command itself) — anchor with `--regex '^SENTINEL$'` or use a sentinel absent from the command.

## Vision

When a task involves an image — a screenshot, mockup, rendered UI, or diagram — inspect it directly when the current model supports vision. Otherwise, delegate the description to a vision-capable pi in a sibling herdr pane (`HERDR_ENV=1`). The wait is event-driven: pi in TUI mode pushes `working`/`idle` lifecycle events to herdr over its socket, and `herdr agent prompt --wait` returns the moment the answer is done — no sentinels, no polling. Run pi in TUI mode (no `-p`); print mode is headless and never reports lifecycle state.

1. Split a sibling pane without stealing focus, then parse the new pane id from `.result.pane.pane_id`:

   ```bash
   herdr pane split --current --direction right --cwd "$PWD" --no-focus
   ```

2. Start pi in that pane as a named agent with the vision model (`agent start` returns once pi is detected and ready):

   ```bash
   herdr agent start vision --kind pi --pane <pane-id> -- --model opencode-go/gpt-5.6-luna
   ```

3. Submit the description request with the image attached via `@path`; `--wait` returns as soon as pi settles (or with `blocked` if it raises a question UI):

   ```bash
   herdr agent prompt vision "@<abs-image-path> Describe this image in detail: layout, text, colors, and any UI state or errors visible." --wait --timeout 180000
   ```

4. Read the answer, then close the pane you created:

   ```bash
   herdr agent read vision --source recent-unwrapped --lines 200 > /tmp/vision-answer.md
   herdr pane close <pane-id>
   ```

Done when the description answers the specific question you had about the image. If the read is empty or the agent says the image was not attached, the model call or `@path` failed — fix and re-run. If `opencode-go/gpt-5.6-luna` no longer works, pick a model with `images: yes` from `pi --list-models`.

## Code review

Before committing, review the current work with a fresh pi in a sibling herdr pane (`HERDR_ENV=1`), using `openai-codex/gpt-5.6-sol` at low reasoning. Use the same pane workflow as Vision (split, start, prompt, read, close), with the reviewer model and prompt:

```bash
herdr agent start reviewer --kind pi --pane <pane-id> -- --model openai-codex/gpt-5.6-sol --thinking low
herdr agent prompt reviewer "Review the current uncommitted work (git status, git diff, git diff --cached). Report concrete correctness bugs, spec mismatches, and violations of the conventions in AGENTS.md, each with file and line; skip style nits." --wait --timeout 300000
herdr agent read reviewer --source recent-unwrapped --lines 200 > /tmp/code-review.md
```

Read `/tmp/code-review.md`, then close the pane. Fix every issue it raises, or consciously dismiss it, before committing. If the wait returns `blocked`/`agent_prompt_stalled`/`timeout` or the read is empty, the review did not complete — inspect `herdr agent get reviewer` and `herdr agent read reviewer`, unblock or re-prompt, and re-run. If `openai-codex/gpt-5.6-sol` no longer works, pick another model from `pi --list-models`.

## Communication principles

- When speaking with the user, use reader-centred plain language adapted to an experienced software engineer.
- State the main point first, then provide only the context needed to understand or act on it.
- Use precise technical terms when they add meaning, but avoid unnecessary jargon and define terms that may be ambiguous or domain-specific.
- Organize complex information with clear headings, bullets, examples, and explicit distinctions between facts, assumptions, options, risks, and recommendations.
- Do not oversimplify technical subjects; simplify the wording and structure instead.
- Prefer concrete, concise, actionable explanations over background, repetition, hedging, or conversational filler.

## Engineering principles

- Do not preserve backward compatibility. Remove obsolete paths instead of adding compatibility layers, fallbacks, or migrations.
- Choose the simplest implementation that fully meets the current requirements. Avoid speculative abstractions, configuration, and indirection.
- Grow the system in layers. Start from the smallest version that works end to end, and add each new capability on top of a product that already works. Never trade a working product for unfinished complexity. Keep components modular and concerns clearly separated.
- Default to established libraries. For any common capability — dates, HTTP, storage, state, build tooling — assume a well-maintained package already covers it: check the project's existing dependencies first, then look for a battle-tested package before writing your own, and read its docs before assuming it can't do the job. Reimplement only when a library would cost more than the code it saves, and say why in the commit.
- Make architectural decisions for the long term. Do not accept a stopgap that only works for now and is meant to be replaced later.
