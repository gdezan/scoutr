## Git workflow

- Work directly on `main` and commit changes there unless the user explicitly specifies another branch or workflow.

## Architecture map (Cockpit)

Cockpit is a self-hosted mobile cockpit for herdr panes and pi agents: a Node/TS bridge daemon owns the herdr Unix socket and exposes a private HTTP/WS API; the Android app (Kotlin + Jetpack Compose Material 3, package `dev.cockpit.app`) talks only to that API.

- `bridge/` — Node/TS daemon. Entry: `src/cli.ts serve` (server.ts only exports `createCockpitServer`). Per-feature modules: `herdr/` (socket client + feed), `sessions.ts`, `session-catalog.ts`, `questions.ts`, `pi/commands.ts`, `live-output.ts`, `review.ts`, `attachments.ts`, `notify.ts`, `board-detail.ts`, `usage/`. Tests live in `bridge/test/` and run with `npm run typecheck && npm test` (`node --import tsx --test`).
- `android/` — Compose app, manual DI via `CockpitApp.AppContainer` (no Hilt/Room). Source dirs under `app/src/main/java/dev/cockpit/app/`: `data/` (DTOs + SharedPreferences stores), `net/` (BridgeClient, NtfyClient — BridgeClient is `final`), `state/` (ViewModels), `service/` (monitor service, deep links, reply receiver), `ui/components/`, `ui/screens/`, `ui/theme/` (Theme.kt + DiffPalette.kt), `ui/motion/` (motion vocabulary + haptics).
- Design contract: always-dark Material 3, one accent `#5B8CFF` reserved for AI-owned states, calm surface cards, mono only for paths/commands/tool output, state is the color. See `ui/theme/Theme.kt` and `docs/DESIGN.md`.
- Long-running goal contract: `docs/production-goal-checklist.md` (live item map) with `docs/COMPLETION-REPORT.md` and `docs/AUDIT.md`.
- Verification recipes and traps: `docs/dev-workflow.md`; the `skills/cockpit-verification/SKILL.md` skill bundles the same loop (install to `~/.pi/agent/skills/` to make it loadable from any repo).

## Verification workflow

Run these before committing UI/bridge work, and treat them as the acceptance gates:

```bash
cd bridge && npm run typecheck && npm test                       # ~147 tests
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest   # Gradle Managed Device, ~2 min
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug
```

For runtime/UI evidence, install the APK on the running emulator (`adb install -r .../app-debug.apk`), drive it with `adb shell input tap/text/keyevent`, capture with `adb exec-out screencap -p`, and inspect screenshots with the vision-pane workflow below. The full emulator recipe (scratch bridge, adb prefs injection, uiautomator bounds, taste reviews) is in `docs/dev-workflow.md`.

## Gotchas (read before touching)

- `BridgeClient` is `final` and cannot be stubbed. Emulator tests use a real BridgeClient + a fresh **unsaved** ConnectionStore so ViewModels never start polling.
- `MockWebServer.url()` does a reverse-DNS lookup — never call it on the main thread (build ViewModels before `setContent` in tests).
- `pkill -f` matches your own command line if it contains the pattern — use `pkill -f 'cli[.]ts'` style brackets.
- Robolectric shares SharedPreferences across tests — save/clear connections explicitly (savedConnection helper pattern).
- readSeek tools demand fresh anchors after every edit to the same file; re-grep/re-digest first, or use the plain `edit` tool for small changes.
- Bridge envs: `XDG_CONFIG_HOME` picks the config dir; `COCKPIT_REPO_ROOTS` allow-lists review repos; config tokens must be ≥16 chars.
- ntfy drops custom JSON publish fields — deep links must travel in ntfy's documented `click` field (see `notify.ts`).
- Composer keyboard contract: Enter inserts a newline and must never send; keep multiline + `ImeAction.None` + no-op `KeyboardActions` (pinned by `ChatComposerKeyTest`).

## Vision

When a task involves an image — a screenshot, mockup, rendered UI, or diagram — inspect it directly when the current model supports vision. Otherwise, delegate the description to a vision-capable pi in a sibling herdr pane (`HERDR_ENV=1`).

1. Split a sibling pane without stealing focus, then parse the new pane id from `.result.pane.pane_id`:

   ```bash
   herdr pane split --current --direction right --cwd "$PWD" --no-focus
   ```

2. Run non-interactive pi with the vision model, attach the image with `@path` (one or more), write the answer to a temp file, and echo a sentinel the wait can match:

   ```bash
   herdr pane run <pane-id> 'pi -p --model opencode-go/gpt-5.6-luna @<abs-image-path> "Describe this image in detail: layout, text, colors, and any UI state or errors visible." > /tmp/vision-answer.md 2>&1; echo VISION_DONE'
   ```

3. Wait for the sentinel, then read the answer:

   ```bash
   herdr pane wait-output <pane-id> --match VISION_DONE --timeout 180000
   cat /tmp/vision-answer.md
   ```

4. Close the pane you created once the description is in hand: `herdr pane close <pane-id>`.

Done when the description answers the specific question you had about the image. An empty file means a bad image path or a failed model call — fix and re-run. If `opencode-go/gpt-5.6-luna` no longer works, pick a model with `images: yes` from `pi --list-models`.

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
