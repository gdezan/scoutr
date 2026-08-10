# Cockpit development workflow

Hard-won recipes for developing and verifying Cockpit (bridge + Android app).
Read this before starting UI or bridge work so you don't rediscover the
traps. AGENTS.md points here for the details.

## The two halves

- **Bridge** (`bridge/`): Node/TS daemon. `npm run typecheck && npm test`
  (≈147 tests via `node --import tsx --test`). The daemon entry is
  `src/cli.ts serve` — `src/server.ts` only exports `createCockpitServer`
  and does nothing when run directly.
- **Android** (`android/`): Compose app. Unit tests: `./gradlew
  testDebugUnitTest`. Emulator (Gradle Managed Device): `./gradlew
  pixel2api36DebugAndroidTest`. APK: `./gradlew assembleDebug`.

## Emulator runtime loop

There is usually an emulator already running (`adb devices`). The app on it
is a real build with a saved connection.

1. **Build + install**:
   ```bash
   cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug -q
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am force-stop dev.cockpit.app && adb shell am start -n dev.cockpit.app/.MainActivity
   ```
2. **Drive it**: `adb shell input tap X Y`, `input text "..."` (use `%s` for
   spaces), `input keyevent 4` (back) / `66` (enter) / `3` (home) / `111`
   (esc). Read the current screen with:
   ```bash
   adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
   ```
   Parse bounds for tap targets: `text="..." bounds="[x1,y1][x2,y2]"`.
3. **Screenshot**: `adb exec-out screencap -p > /tmp/shot.png`, then inspect
   with the vision-pane workflow in AGENTS.md (or `read` if the model has
   vision).
4. **Repoint the app at a scratch bridge** (for features the real bridge
   can't serve, or to isolate): write the prefs directly as root:
   ```bash
   adb root
   cat > /tmp/cockpit_connection.xml <<'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map><string name="host">http://10.0.2.2:8791</string><string name="token">testtoken1234567890</string></map>
   EOF
   adb push /tmp/cockpit_connection.xml /data/data/dev.cockpit.app/shared_prefs/cockpit_connection.xml
   ```
   (10.0.2.2 is the host loopback from the emulator.)

## Scratch bridge

A second bridge instance for validation without touching the real systemd
unit (which owns `~/.config/cockpit/config.json` and port 8737):

```bash
mkdir -p /tmp/cockpit-scratch/cockpit
printf '{"token":"testtoken1234567890","port":8791,"ntfyUrl":null,"ntfyTopic":null,"publicHost":null}\n' \
  > /tmp/cockpit-scratch/cockpit/config.json
cd bridge && setsid env XDG_CONFIG_HOME=/tmp/cockpit-scratch COCKPIT_REPO_ROOTS=/home/gdezan/Dev \
  node --import tsx src/cli.ts serve > /tmp/cockpit-scratch/bridge.log 2>&1 < /dev/null &
curl -s -H "Authorization: Bearer testtoken1234567890" http://127.0.0.1:8791/api/health
```

- Config path is `$XDG_CONFIG_HOME/cockpit/config.json`; the token must be
  ≥16 chars or `loadOrCreateConfig` regenerates it.
- `COCKPIT_REPO_ROOTS` overrides the review allow-list (default
  `~/.herdr/worktrees`, which the picker hides as a dot-dir).
- Stop it with `pkill -f 'cli[.]ts serve'` (bracket trick — a plain
  `pkill -f cli.ts` also matches your own shell).

## Testing patterns

- **BridgeClient is final** — emulator ViewModel tests use a real
  BridgeClient plus a fresh **unsaved** ConnectionStore so the VM never
  starts polling (unsaved ⇒ `connectionStore.saved == null`).
- **MockWebServer on the main thread crashes** (`NetworkOnMainThreadException`
  from reverse DNS in `MockWebServer.url()`): construct the ViewModel before
  `compose.setContent { }`, and stub with `server.dispatcher = ...`.
- **Compose tests that hold input state** need real Compose state:
  `val input = mutableStateOf("")` declared outside, passed as
  `value = input.value` / `onValueChange = { input.value = it }`. A plain
  `var` inside `setContent` never recomposes and stale-closes lambdas.
- **Robolectric shares prefs across tests** — `pm clear` equivalents don't
  apply; save/clear the connection explicitly per test
  (`ConnectionStore(app).apply { save(...) }` or `.clear()`).
- Poll-loop tests advance with `ShadowLooper.idleMainLooper()` +
  `delay(25)` in a `waitFor` loop; first poll is immediate.
- The full emulator suite runs ~47 tests in ~2 min; run a single class with
  `-Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.app.ui.X`.

## Live notification / deep-link validation

The full chain (bridge → ntfy → foreground service → notification → tap) can
be validated without a real blocked agent: publish a synthetic event to the
topic with the ntfy **`click`** field (ntfy drops arbitrary JSON fields, so
`paneId` alone never reaches the app):

```bash
curl -s -X POST "https://<host>/ntfy/" -H "Content-Type: application/json" \
  -d '{"topic":"<topic>","title":"π needs you","message":"x","priority":4,"click":"cockpit://chat/<paneId>?status=blocked"}'
```

Enable monitoring in Settings, background the app (Home), wait ≤30 s for the
service poll, expand the shade (`adb shell cmd statusbar expand-notifications`),
tap, and verify the exact chat opens.

## Papercuts to record when you hit them

Recurring friction is tracked with the `papercut` CLI (`PAPERCUTS.md`).
Known candidates: readSeek stale-anchor demands after every edit;
`MockWebServer.url()` reverse-DNS; `pkill -f` self-match; Robolectric prefs
leakage; emulator GMS sign-in overlay appearing after app restarts.
