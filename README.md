# Cockpit

A self-hosted Android cockpit for your terminal agents (pi, herdr panes).
A free alternative to Moshi's paid herdr integration — no cloud, no
subscriptions.

```
┌─────────────┐   HTTPS/WSS (tailnet)   ┌──────────────┐   JSONL-RPC   ┌─────────────┐
│  Android    │ ──────────────────────► │  cockpit-    │ ────────────► │  herdr 0.8  │
│  app (Cockpit)│                        │  bridge      │               │  (socket)   │
└─────────────┘                         └──────┬───────┘               └─────────────┘
                                               │ spawns `pi --mode rpc`
                                               ▼
                                    ┌───────────────────────┐
                                    │  pi RPC sessions      │
                                    │  (app-owned chat)     │
                                    └───────────────────────┘
```

- **bridge/** — Node/TS daemon. Owns the herdr Unix socket (never exposes it
  raw), serves a token-authenticated HTTP + WebSocket API on localhost, fronted
  by `tailscale serve` TLS. Publishes blocked/done events to a self-hosted
  ntfy. Spawns app-owned `pi --mode rpc` sessions.
- **android/** — Kotlin + Jetpack Compose (Material 3, dark-first) app:
  live agent board, chat + steering (live panes and RPC sessions), usage rings,
  ntfy push notifications.

Verified against herdr 0.8.0 (protocol 19) and pi (node 26 / mise).

---

## 1. Host prerequisites

| Thing | Notes |
|---|---|
| herdr ≥ 0.8.0 running | `herdr --version`; socket at `~/.config/herdr/herdr.sock` |
| pi installed | used for RPC sessions; `~/.local/bin/pi` or mise `node/26/bin/pi` |
| Node ≥ 22 | tested on node 26 |
| Tailscale | the phone and this machine on the same tailnet |
| ntfy (optional but recommended) | for push; one binary, see step 3 |

Check the bridge can see herdr before going further:

```bash
cd bridge
npm install
npm run build        # tsc -> dist/
npm test             # 36 tests (socket client, parser, usage, RPC, ntfy)
npm run cli -- herdr status    # smoke: ping + version + protocol
```

---

## 2. Bridge daemon

### First run

`serve` creates `~/.config/cockpit/config.json` (mode 0600) on first start and
generates a random pairing token — print it with:

```bash
cd bridge
node dist/cli.js serve        # foreground, prints the token
```

The file looks like:

```json
{
  "token": "cockpit_<random>",
  "port": 8737,
  "ntfyUrl": "https://artemis.tail7dc568.ts.net/ntfy",
  "ntfyTopic": "cockpit_<random>"
}
```

`ntfyUrl`/`ntfyTopic` are added automatically once ntfy is reachable (step 3);
`/api/health` exposes them so the app discovers push after connecting.

### Run as a systemd user unit (recommended)

`~/.config/systemd/user/cockpit-bridge.service`:

```ini
[Unit]
Description=Cockpit bridge (herdr socket -> local HTTP/WSS API)
After=herdr.service ntfy.service

[Service]
Type=simple
WorkingDirectory=/home/<you>/agents-mobile/bridge
ExecStart=/abs/path/to/node dist/cli.js serve
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

Use the **absolute node path** (e.g. `$(mise where node)/bin/node` or
`/home/<you>/.local/share/mise/installs/node/26.5.0/bin/node`) — the unit has no
mise PATH. Then:

```bash
systemctl --user daemon-reload
systemctl --user enable --now cockpit-bridge
systemctl --user status cockpit-bridge        # active; port 8737 open
loginctl enable-linger $USER                  # keep units running after logout
```

Verify:

```bash
curl -s http://127.0.0.1:8737/api/health                    # -> {"ok":false,"error":"unauthorized"} (auth works)
TOKEN=$(python3 -c "import json;print(json.load(open('$HOME/.config/cockpit/config.json'))['token'])")
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8737/api/health
# {"ok":true,"service":"cockpit-bridge","herdr":{"connected":true,"version":"0.8.0","protocol":19},...}
```

### Expose on the tailnet

```bash
sudo tailscale set --operator=$USER     # one time, lets serve run passwordless
tailscale serve --bg 8737               # https://<host>.ts.net/  -> 127.0.0.1:8737
tailscale serve --set-path /ntfy 8382   # https://<host>.ts.net/ntfy -> ntfy (step 3)
tailscale serve status
```

You should see:

```
https://<host>.ts.net (tailnet only)
|-- /     proxy http://127.0.0.1:8737
|-- /ntfy proxy http://127.0.0.1:8382
```

The app then talks to `https://<host>.ts.net` over TLS — no cleartext needed on
a real phone.

---

## 3. ntfy push (self-hosted)

```bash
# install (one binary, no root)
curl -sL https://github.com/binwiederhier/ntfy/releases/latest/download/ntfy_2.27.0_linux_amd64.tar.gz | tar xz
install -m755 ntfy_2.27.0_linux_amd64/ntfy ~/.local/bin/ntfy
```

`~/.config/ntfy/server.yml`:

```yaml
listen-http: "127.0.0.1:8382"
cache-file: "/home/<you>/.cache/ntfy/cache.db"   # mkdir -p ~/.cache/ntfy first
cache-duration: "48h"
```

`~/.config/systemd/user/ntfy.service`:

```ini
[Unit]
Description=ntfy push server for cockpit
After=network.target

[Service]
ExecStart=/home/<you>/.local/bin/ntfy serve --config /home/<you>/.config/ntfy/server.yml
Restart=on-failure

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload && systemctl --user enable --now ntfy
curl -s http://127.0.0.1:8382/v1/health          # {"healthy":true}
tailscale serve --set-path /ntfy 8382
curl -s https://<host>.ts.net/ntfy/v1/health     # healthy over the tailnet
```

Restart the bridge so it discovers ntfy, then check health shows the topic:

```bash
systemctl --user restart cockpit-bridge
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8737/api/health | python3 -m json.tool
```

> The bridge POSTs JSON to the ntfy **root** path (`{baseUrl}/` with `topic` in
> the body) — ntfy only parses JSON bodies there. The app polls the topic
> (`?poll=1&since=<id>`, 30s) and shows an Android notification.

---

## 4. Android build + test

### Toolchain

```bash
# JDK 17 (AGP 8.13 requires it)
mise install java@17 && mise use -g java@17
export JAVA_HOME=$(mise where java@17)

# Android SDK (cmdline-tools at ~/Android/sdk/cmdline-tools/latest)
export ANDROID_HOME=$HOME/Android/sdk
yes | sdkmanager --licenses
sdkmanager "platform-tools" "emulator" "platforms;android-36" \
  "build-tools;36.0.0" "system-images;android-36;google_apis;x86_64"
```

### Build

```bash
cd android
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest          # JVM unit tests (8)
./gradlew pixel2api36DebugAndroidTest   # Compose UI tests on Gradle Managed Device
```

### Emulator (headless)

```bash
avdmanager create avd -n cockpit -k "system-images;android-36;google_apis;x86_64" -d pixel_5
emulator -avd cockpit -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 3072 &
adb wait-for-device
# wait for: adb shell getprop sys.boot_completed -> 1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.cockpit.app/.MainActivity
```

Debug builds allow cleartext HTTP to `10.0.2.2` (the host loopback from the
emulator), so on the **emulator** the Connect screen takes:

- Bridge address: `http://10.0.2.2:8737`
- Pairing token: from `~/.config/cockpit/config.json` on the host

On a **physical phone** use `https://<host>.ts.net` + the same token (TLS, no
cleartext).

---

## 5. Using the app

- **Board** — live agent cards grouped Needs you / Working / Done / Idle with
  counts, status dots, and time-in-state pills. 3s polling; self-heals when the
  bridge restarts. The **+** button starts a new app-owned pi session.
- **Chat (pane)** — tap an agent card: transcript from the pi session JSONL,
  input steers the live pane (`agent.prompt`); when the agent is blocked on
  `ask_user_question` the input becomes "Answer the question…" and your answer
  is typed into the questionnaire.
- **Chat (RPC)** — the **+** FAB spawns a bridge-owned `pi --mode rpc` session.
  When the agent asks a question, a "Waiting for your answer" banner shows the
  question + options; answering delivers it programmatically via
  `extension_ui_response` (no terminal involved).
- **Usage** — Codex 5h/7d rate windows and DeepSeek balance from
  `~/.pi/agent/auth.json` (read-only).
- **Push** — blocked → "π needs you" (high priority), done → "π finished",
  delivered as Android notifications on the `agents` channel.

---

## 6. Configuration reference

| Item | Value |
|---|---|
| Bridge listen | `127.0.0.1:8737` (`port` in `~/.config/cockpit/config.json`) |
| ntfy listen | `127.0.0.1:8382` |
| Tailnet paths | `/` → bridge, `/ntfy` → ntfy |
| Pairing token | `cockpit_<18 random bytes>` in config.json (0600) |
| ntfy topic | `cockpit_<12 random bytes>` (shared secret, also in config.json) |
| Env override `PI_BIN` | path to the pi script (else `~/.local/bin/pi`, mise paths, PATH) |
| Env override `PI_NODE_BIN` | node used to run pi (else the node next to pi, mise paths) |
| App data | SharedPreferences `cockpit_connection` (host, token, ntfy) |

---

## 7. Security notes

- The herdr socket is **never exposed** over the network. The bridge is the
  only process that opens it, and it only accepts loopback connections
  authenticated by bearer token (constant-time compare).
- The bridge is read-only on herdr state except deliberate `agent.prompt` /
  `pane.send_text` when you act from the app.
- The app never writes `~/.pi/agent/auth.json`; usage adapters read it
  read-only.
- `tailscale serve` restricts the API to your tailnet; the token is a second
  layer on top.

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `serve` exits 127 | systemd unit must use the absolute node path (mise bin), not `node` |
| RPC session fails "spawn pi ENOENT" | bridge runs without a mise PATH — it resolves known pi paths, or set `PI_BIN` / `PI_NODE_BIN` in the unit |
| stale API after code changes | `npm run build` first, then `systemctl --user restart cockpit-bridge` |
| 401 on curl/ws | export the token: `TOKEN=$(...); curl -H "Authorization: Bearer $TOKEN" ...` (a bare shell var is not exported to child processes) |
| ntfy shows raw JSON as message | the bridge POSTs JSON to the ntfy root `/`; if you published to `/<topic>`, messages already stored that way stay raw |
| push doesn't arrive on the phone | app polls every 30s; check `https://<host>.ts.net/ntfy/v1/health`; grant POST_NOTIFICATIONS (Android 13+) |
| app shows "Disconnected" forever | board polls unconditionally after connect — it recovers when the bridge returns; check the bridge is up |
| WS steer crashes the app | the long-lived WS feed was removed in favor of 3s polling; use the current APK |

## 9. Known limits (v1)

- No live terminal rendering (herdr `terminal observe/control` is the v2 path).
- Claude Code: status + steer work via herdr; transcripts are not exposed (the
  session path guard is pi-only).
- Time-in-state pills only track agents whose status events flow (pi panes).
- Push to a **killed** app: messages accumulate on ntfy (48h) and are delivered
  on next launch via the `since` cursor. Instant delivery needs FCM or a
  foreground service — out of scope (no cloud).
- One bridge/host; multi-host pairing is future work.
- Full details: `docs/shipping-report.md` (evidence vs. approximations) and
  `docs/decisions.md` (design rationale).
