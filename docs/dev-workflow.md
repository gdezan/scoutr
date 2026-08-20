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
   can't serve, or to isolate): pair through the Connect screen. The saved
   token is AES-GCM ciphertext under an Android Keystore key, so a
   hand-authored `scoutr_connection.xml` no longer produces a usable pairing —
   only app code can write a decryptable credential.
   ```bash
   adb -s emulator-5554 shell pm clear dev.scoutr.app     # back to Connect
   adb -s emulator-5554 shell am start -n dev.scoutr.app/.MainActivity
   # tap the "Bridge address" field (bounds from the uiautomator dump), then:
   adb -s emulator-5554 shell input text "http://10.0.2.2:8791"
   # tap "Pairing token", then:
   adb -s emulator-5554 shell input text "testtoken1234567890"
   # tap "Connect"; the ViewModel probes /api/health before it saves.
   ```
   (10.0.2.2 is the host loopback from the emulator.) The Connect fields carry
   test tags `connect_host`, `connect_token`, and `connect_button`, so
   instrumentation tests drive the same path without adb taps.

## Scratch bridge

A second bridge instance for validation without touching the real systemd
unit (which owns `~/.config/scoutr/config.json` and port 8737):

```bash
mkdir -p /tmp/scoutr-scratch/scoutr
printf '{"token":"testtoken1234567890","port":8791,"publicHost":null}\n' \
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

The apps talk to the **real supervised service** (port 8737,
`~/.config/scoutr/config.json`, reachable from the phone through the
configured exposure), which runs the **compiled `dist/`** — NOT the `tsx`
scratch bridge above. The scratch bridge is source-level validation only.

`scripts/bridge-service.mjs` owns that service on both hosts — a systemd user
unit (`scoutr-bridge.service`) on Linux, a user LaunchAgent
(`dev.scoutr.bridge`, logs in `~/Library/Logs/scoutr/`) on macOS:

```bash
node scripts/bridge-service.mjs install       # write/update the definition (idempotent)
node scripts/bridge-service.mjs restart       # what `npm run deploy` calls
node scripts/bridge-service.mjs status --json # { manager, active, pid, startedAtMs, ... }
```

`install` only rewrites the definition when it actually differs (new checkout,
new Node path, new resolved `HERDR_BIN`), so an existing valid service is never
churned. Anything other than Linux/macOS exits with an unsupported-manager
error and installs nothing. `cloudflared` is not managed here — the tunnel is
owned by Cloudflare's own service tooling.

The review-403 incident (fixes 5/13) shipped with every test green while the
user still hit `bridge 403: path outside allowed repo roots`: `dist/` had been
built at 17:41, before the fixes landed, and the service was never rebuilt or
restarted. A stale deployed artifact is invisible to source-level tests.

When a bridge change is finalized — the phone or live app should see it — end with:

```bash
cd bridge && npm run deploy          # tsc build + `bridge-service.mjs restart` + run the gate
npm run check:deployed               # gate: dist >= src AND service restarted after build AND real /api/health OK
```

(`make deploy-bridge` / `scripts/deploy-bridge.sh` run the same three steps.)

`check:deployed` reads `bridge-service.mjs status --json` and fails on any of:
dist older than the newest src file, the service started before the dist build,
or the supervised PID not owning port 8737 and answering authenticated health
(`ss` proves the port owner on Linux, `lsof` on macOS). Treat a failing gate as a hard stop — the
app in your hand talks to that process, not to `tsx`.

### macOS host (LaunchAgent)

Same three commands, different supervisor. `install` resolves everything
launchd cannot look up for itself **at install time** and bakes it into
`~/Library/LaunchAgents/dev.scoutr.bridge.plist`:

- the absolute Node path (`process.execPath`, realpath-resolved) as
  `ProgramArguments[0]`, with `WorkingDirectory` at this checkout's `bridge/`;
- `HERDR_BIN`, from `$HERDR_BIN` if exported, else `which herdr`. A LaunchAgent
  gets no login shell, so an unresolved herdr would fail at pane-create time
  rather than at install time. The systemd unit pins the same value as
  `Environment=HERDR_BIN=…`: systemd --user *does* inherit the session PATH, and
  a stale version-manager shim ahead of the real herdr fails the terminal
  capability probe and takes the terminal route down;
- an explicit `PATH`: the Node dir, the herdr dir, then
  `/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin`.

```bash
node scripts/bridge-service.mjs install       # writes the plist, bootstraps it into gui/<uid>
node scripts/bridge-service.mjs status --json # manager: "launchd"
cd bridge && npm run deploy                   # build + restart + the same gate as Linux
tail -f ~/Library/Logs/scoutr/scoutr-bridge.err.log
```

Because those paths are baked in, **re-run `install` after switching Node
versions, moving the checkout, or installing herdr somewhere else** — it
rewrites the plist only when the rendered definition actually differs, so it is
safe to run every time. `status --json` reports `definitionCurrent: false` when
the installed plist has drifted from what this checkout would render.

### cloudflared is a separate ownership boundary

Scoutr never creates, reads, or writes Cloudflare credentials, tunnel configs,
or DNS routes, and `bridge-service.mjs` refuses to know about `cloudflared`.
The tunnel is installed and supervised by Cloudflare's own documented service
mechanism:

```bash
# after `cloudflared tunnel login` / `create` / `route dns` and writing your own config.yml
sudo cloudflared service install              # installs com.cloudflare.cloudflared (launchd daemon on macOS)
sudo launchctl stop com.cloudflare.cloudflared
sudo launchctl start com.cloudflare.cloudflared
```

Two processes, two owners: `dev.scoutr.bridge` (this repo, user LaunchAgent,
serves `127.0.0.1:8737`) and `com.cloudflare.cloudflared` (Cloudflare's
installer, system daemon, publishes it). A failure in one is diagnosed without
touching the other. Cloudflare's macOS service reference:
<https://developers.cloudflare.com/tunnel/advanced/local-management/as-a-service/macos/>

### Diagnosing the public path

Bisect from the inside out; only the last step involves Cloudflare at all.

```bash
# 1. bridge itself (this repo's problem)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8737/api/health
node scripts/bridge-service.mjs status --json

# 2. the tunnel process (Cloudflare's problem)
cloudflared tunnel info <name-or-uuid>     # active connectors, or none
cloudflared tunnel diag                    # bundles logs/config/connectivity for a report
sudo launchctl print system/com.cloudflare.cloudflared | head -30

# 3. the public hostnames
curl -sv https://scoutr.example.com/api/health          # expect 401 without a bearer
curl -s -H "Authorization: Bearer $TOKEN" https://scoutr.example.com/api/health
```

A green step 1 with a failing step 2 or 3 is an **exposure/infrastructure**
failure. Do not add Cloudflare branches to bridge or app code to work around
it; nothing in `BridgeClient`, `TopologyFeedClient`, the terminal transport, or
the route dispatcher may learn which provider is in front.

Restarting `cloudflared` drops long-lived sockets. Terminal and topology
WebSockets reconnect through the app's existing grace/reconnect behavior;
verify that explicitly after a tunnel restart rather than assuming the socket
survived.

#### Managed WARP hosts

On a company-managed device, WARP may route or block `cloudflared`'s outbound
edge connections. `cloudflared` needs **outbound** access to Cloudflare's edge
on port `7844` (TCP for `http2`, UDP for `quic`); a WARP or firewall policy
that drops it shows up as a tunnel with zero registered connectors while local
health is perfectly green.

```bash
warp-cli status                            # is WARP connected / which policy
cloudflared tunnel diag                    # records the connectivity result
nc -vz region1.v2.argotunnel.com 7844
```

**This is an infrastructure/policy outcome, not a Scoutr bug and not a reason
to work around a VPN.** Record what the diagnostic says and stop: do not modify
WARP routes, split-tunnel settings, or security policy, and do not add
VPN-coexistence logic to Scoutr. Either the policy is changed by whoever owns
it, or that host uses a different exposure (`tailscale`, or `custom` behind a
proxy the policy already permits).

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

The full chain is bridge → FCM → `ScoutrMessagingService` → `/api/agents`
fetch → notification → tap (see ADR 0007). The notification carries no pushed
text, so there is nothing to inject: the ping only names a pane, and the app
builds everything the user sees from the bridge.

Drive it from a real transition — block an agent on the host — with Scoutr
force-stopped, and confirm a heads-up notification arrives within seconds.
Expand the shade with
`adb -s emulator-5554 shell cmd statusbar expand-notifications`, tap, and
verify the exact chat opens. Unblock the agent and confirm the notification
clears with no interaction.

To exercise the app half alone, POST a ping to FCM for a known `paneId`; to
exercise the presenter alone, prefer `NotificationPresenterTest`, which pins
slots, the group summary, and mute suppression without a device.

## Papercuts to record when you hit them

Recurring friction is tracked with the `papercut` CLI (`PAPERCUTS.md`).
Known candidates: readSeek stale-anchor demands after every edit;
`MockWebServer.url()` reverse-DNS; `pkill -f` self-match; Robolectric prefs
leakage; emulator GMS sign-in overlay appearing after app restarts.
