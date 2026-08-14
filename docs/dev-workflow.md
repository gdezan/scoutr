# Scoutr development workflow

Hard-won recipes for developing and verifying Scoutr (bridge + Android app).
Use this for bridge deployment, scratch-bridge validation, emulator operation,
Android test diagnostics, notification validation, and failure recovery. The
verification phase boundary and check selection live in
`skills/scoutr-verification/SKILL.md`.
## The two halves

- **Bridge** (`bridge/`): Node/TS daemon. Run `npm run typecheck && npm test`;
  the daemon entry is `src/cli.ts serve` and `src/server.ts` only exports
  `createScoutrServer`.
- **Android** (`android/`): Compose app. Unit tests: `./gradlew
  testDebugUnitTest`. Emulator (Gradle Managed Device): `./gradlew
  pixel2api36DebugAndroidTest`. APK: `./gradlew assembleDebug`.

## Emulator runtime loop

There is usually an emulator already running (`adb devices`). If not, boot
the `scoutr` AVD (the only one installed):

```bash
$ANDROID_HOME/emulator/emulator -avd scoutr &
# boot takes ~25-60s; confirm with:
adb devices && adb -s emulator-5554 shell getprop sys.boot_completed   # prints 1 when done
```

Instrumentation suites and test APKs go only on `emulator-5554` or the
`pixel2api36` managed device, never a physical phone. Use a physical device
only for an explicitly requested integration walk.

The app on the interactive emulator is a real build with a saved connection.


1. **Build + install**:
   ```bash
   cd android && ANDROID_HOME=$HOME/Android/sdk ./gradlew assembleDebug -q
   adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s emulator-5554 shell am force-stop dev.scoutr.app && adb -s emulator-5554 shell am start -n dev.scoutr.app/.MainActivity
   ```
2. **Drive it**: `adb -s emulator-5554 shell input tap X Y`, `input text
   "..."` (use `%s` for spaces), `input keyevent 4` (back) / `66` (enter) / `3`
   (home) / `111` (esc). Read the current screen with:
   ```bash
   adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5554 shell cat /sdcard/ui.xml
   ```
   Parse bounds for tap targets: `text="..." bounds="[x1,y1][x2,y2]"`.
3. **Screenshot**: `adb -s emulator-5554 exec-out screencap -p > /tmp/shot.png`, then inspect
   with the vision-pane workflow in AGENTS.md (or `read` if the model has vision).
4. **Repoint the app at a scratch bridge** (for features the real bridge
   can't serve, or to isolate): write the prefs directly as root:
   ```bash
   adb -s emulator-5554 root
   cat > /tmp/scoutr_connection.xml <<'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map><string name="host">http://10.0.2.2:8791</string><string name="token">testtoken1234567890</string></map>
   EOF
   adb -s emulator-5554 push /tmp/scoutr_connection.xml /data/data/dev.scoutr.app/shared_prefs/scoutr_connection.xml
   ```
   (10.0.2.2 is the host loopback from the emulator.)

## Scratch bridge

A second bridge instance for validation without touching the real systemd
unit (which owns `~/.config/scoutr/config.json` and port 8737):

```bash
mkdir -p /tmp/scoutr-scratch/scoutr
printf '{"token":"testtoken1234567890","port":8791,"ntfyUrl":null,"ntfyTopic":null,"publicHost":null}\n' \
  > /tmp/scoutr-scratch/scoutr/config.json
cd bridge && setsid env XDG_CONFIG_HOME=/tmp/scoutr-scratch SCOUTR_REPO_ROOTS=/home/gdezan/Dev \
  node --import tsx src/cli.ts serve > /tmp/scoutr-scratch/bridge.log 2>&1 < /dev/null &
curl -s -H "Authorization: Bearer testtoken1234567890" http://127.0.0.1:8791/api/health
```

- Config path is `$XDG_CONFIG_HOME/scoutr/config.json`; the token must be
  ≥16 chars or `loadOrCreateConfig` regenerates it.
- `SCOUTR_REPO_ROOTS` overrides the review allow-list (default
  `~/.herdr/worktrees`, which the picker hides as a dot-dir).
- Stop it with `pkill -f 'cli[.]ts serve'` (bracket trick — a plain
  `pkill -f cli.ts` also matches your own shell).

## Deploying bridge changes

The apps talk to the **real** systemd unit (`scoutr-bridge.service`, port
8737, `~/.config/scoutr/config.json`, exposed to the phone via
`tailscale serve`), which runs the **compiled `dist/`** — NOT the `tsx`
scratch bridge above. The scratch bridge is source-level validation only.

The review-403 incident (fixes 5/13) shipped with every test green while the
user still hit `bridge 403: path outside allowed repo roots`: `dist/` had been
built at 17:41, before the fixes landed, and the service was never rebuilt or
restarted. A stale deployed artifact is invisible to source-level tests.

Every bridge change ends with:

```bash
cd bridge && npm run deploy          # tsc build + restart the service + run the gate
npm run check:deployed               # gate: dist >= src AND service restarted after build AND real /api/health OK
```

`check:deployed` fails on any of: dist older than the newest src file, the
service started before the dist build, or the real bridge not answering
health with the deployed token. Treat a failing gate as a hard stop — the
app in your hand talks to that process, not to `tsx`.

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
- To run a single class, use
  `-Pandroid.testInstrumentationRunnerArguments.class=dev.scoutr.app.ui.X`.

### Gradle Managed Device (pixel2api36) diagnostics

`pixel2api36DebugAndroidTest` runs on a separate managed emulator instance
that your adb never sees — `adb logcat` on the phone or `emulator-5554`
shows nothing from it. When a managed-device test fails:

- Per-test logcat: `app/build/outputs/androidTest-results/managedDevice/debug/pixel2api36/logcat-dev.scoutr.app.ui.<Class>-<method>.txt`.
- Results XML (test names + counts): `app/build/outputs/androidTest-results/managedDevice/debug/pixel2api36/TEST-pixel2api36-_app-.xml` — written only when the run completes.
- A very fast BUILD FAILED with no XML usually means the managed device
  failed to come up (cold start) — rerun once before debugging the test.
- If a successful compile unexpectedly skips execution as UP-TO-DATE, capture
  that evidence and use `--rerun-tasks` for the diagnostic rerun.
- To see in-test state, dump the semantics tree from the test itself:
  `onRoot(useUnmergedTree = true).printToLog("DIAG")` (or `Thread.sleep`
  + printToLog) and read it from the per-test logcat file above.

### ChatList / LazyColumn test traps

Hard-won rules for scroll and placement assertions in Compose tests:

- **Memoize the rows list.** A LazyColumn `items(rows)` block whose list is
  rebuilt per recomposition oscillates the layout: the scroll position
  resets to 0 every other frame and never converges. Build the list with
  `remember(entries, questions, ...)` so it is stable between polls.
- **`canScrollForward` is estimate-based.** Off-viewport items are measured
  lazily (~90px estimated each), so `canScrollForward` and the scroll range
  can read false/stale mid-list — a retry loop can bail ~100px short of the
  true bottom with a tall last item still clipped. For "at the true
  bottom" checks combine it with the last visible row index
  (`canScrollForward || lastVisible < totalItemsCount - 1`), and let each
  scroll retry include a frame delay (`delay(16)`) so the last item
  measures at its real height before the next step.
- **`performScrollToNode(hasText(...))` needs `substring = true`.**
  Markdown renders a whole paragraph as one merged text node, and `hasText`
  defaults to exact match — substring search is required to find anything
  inside a long assistant bubble.
- **The list opens auto-scrolled to the bottom.** Top-anchored content
  (question bubbles, first entries) is never composed until scrolled up.
  Scroll deterministically with
  `performScrollToNode(hasText("message 0", substring = true))`, not with
  `swipeDown` — a touch swipe drags only ~1400px (~9 rows), which may not
  reach the top of a long list.

## Live notification / deep-link validation

The full chain (bridge → ntfy → foreground service → notification → tap) can
be validated without a real blocked agent: publish a synthetic event to the
topic with the ntfy **`click`** field (ntfy drops arbitrary JSON fields, so
`paneId` alone never reaches the app):

```bash
curl -s -X POST "https://<host>/ntfy/" -H "Content-Type: application/json" \
  -d '{"topic":"<topic>","title":"π needs you","message":"x","priority":4,"click":"scoutr://chat/<paneId>?status=blocked"}'
```

Enable monitoring in Settings, background the app (Home), wait ≤30 s for the
service poll, expand the shade (`adb -s emulator-5554 shell cmd statusbar expand-notifications`),
tap, and verify the exact chat opens.

## Papercuts to record when you hit them

Recurring friction is tracked with the `papercut` CLI (`PAPERCUTS.md`).
Known candidates: readSeek stale-anchor demands after every edit;
`MockWebServer.url()` reverse-DNS; `pkill -f` self-match; Robolectric prefs
leakage; emulator GMS sign-in overlay appearing after app restarts.
