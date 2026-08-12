---
name: cockpit-verification
description: Verify Cockpit changes (bridge + Android) and gather runtime evidence on the emulator before committing. Run the gates, install the APK, drive the app with adb, capture and inspect screenshots, and re-run gates after the final state. Use when finishing a bridge/UI change, before a commit, or when the user asks for screenshots/runtime proof.
---

# Cockpit verification loop

Use this whenever a bridge or Android change is ready to verify or commit.

## 1. Gates (always, in order)

```bash
cd bridge && npm run typecheck && npm test
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew testDebugUnitTest --rerun-tasks
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew pixel2api36DebugAndroidTest   # ~47 tests, ~2 min
cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug
```

Run the emulator suite once at the end, not just mid-way. A single class:
`-Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.<Test>`.

## 2. Runtime evidence (when the change is user-visible)

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop dev.cockpit.app && adb shell am start -n dev.cockpit.app/.MainActivity
```

Drive with `adb shell input tap X Y` / `input text` (use `%s` for spaces) /
`input keyevent 4|66|3|111`. Read the screen:
`adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`
(tap targets come from `bounds="[x1,y1][x2,y2]"`).

Capture: `adb exec-out screencap -p > /tmp/shot.png`. Inspect via the
vision-pane workflow in AGENTS.md (or `read` with a vision model). Save
meaningful stills to `/tmp/cockpit-<name>.png` and cite them in the commit.

## 3. Gotchas that cost real time

- **BridgeClient is final** — emulator tests: real client + fresh *unsaved*
  ConnectionStore so VMs never poll.
- **MockWebServer.url() does reverse DNS** — never on the main thread; build
  the ViewModel before `compose.setContent`.
- **`pkill -f cli.ts` matches your own shell** — use `pkill -f 'cli[.]ts'`.
- **Robolectric shares prefs** — save/clear the connection per test.
- **readSeek needs fresh anchors after every edit** — re-grep first, or use
  the plain edit tool.
- **ntfy drops custom fields** — deep links travel in the `click` field.
- **Composer Enter must never send** — multiline + `ImeAction.None` +
  no-op KeyboardActions; `ChatComposerKeyTest` pins it.

Full recipes (scratch bridge, adb prefs injection, notification deep-link
validation) are in `docs/dev-workflow.md`.

## 4. Finish

Re-run the four gates after the last code change and commit with evidence cited
(files, test counts, screenshot paths). If a change came from a design plan, update
its status in `design-plans/README.md`.

## Installing this skill outside the repo

Copy to `~/.pi/agent/skills/cockpit-verification/` (or
`~/.agents/skills/cockpit-verification/`) to make it loadable from any repo.
