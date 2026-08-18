# Scoutr

A self-hosted Android scoutr for your terminal agents (pi, herdr panes).
A free alternative to Moshi's paid herdr integration — no Scoutr-owned
cloud or backend, and no required subscription.

```
┌──────────────┐   HTTPS/WSS   ┌──────────────┐   JSONL-RPC   ┌─────────────┐
│  Android     │ ────────────► │  scoutr-     │ ────────────► │  herdr 0.8  │
│  app (Scoutr)│   configured  │  bridge      │               │  (socket)   │
└──────────────┘    exposure   └──────┬───────┘               └─────────────┘
   tailscale serve / cloudflared /    │ creates a herdr pane
   your own reverse proxy             ▼
                              ┌───────────────────────────────┐
                              │  herdr pane                   │
                              │  (pi / claude / agy agent)    │
                              └───────────────────────────────┘
```

- **bridge/** — Node/TS daemon. Owns the herdr Unix socket (never exposes it
  raw), serves a token-authenticated HTTP + WebSocket API on localhost, fronted
  by whichever **exposure** you configure — `tailscale serve`, a Cloudflare
  Tunnel, or your own reverse proxy (step 2). Publishes blocked/done events to a self-hosted
  ntfy. New chats are herdr panes: the bridge creates the pane and the agent
  CLI runs inside it (`pi --model …`, `claude …`, or `agy …`); the bridge never spawns
  agent processes itself.
- **android/** — Kotlin + Jetpack Compose (Material 3, dark-first) app:
  live agent board, chat + steering of live panes, usage rings, ntfy push
  notifications.

Verified against herdr 0.8.0 (protocol 19) and pi (node 26 / mise).

### Scoutr API compatibility

The Android-to-bridge contract has its own integer protocol, separate from the
app version and Herdr's protocol. `/api/health` advertises the bridge protocol
and additive feature names. The app accepts only its declared supported range;
a missing or out-of-range protocol blocks feature traffic without deleting the
saved pairing, so deploying a matching bridge and retrying can recover it.

Additive optional response fields and informational feature names stay on the
current protocol. Removing or renaming required fields, changing required
semantics, or requiring a new command/response behavior bumps the integer in
`bridge/src/api-protocol.ts` and the Android supported range together.

---

## 1. Host prerequisites

| Thing | Notes |
|---|---|
| herdr 0.8.0 (protocol 19) running | `herdr --version`; socket at `~/.config/herdr/herdr.sock`. The terminal capability gate rejects unverified versions until their controller contract is captured. |
| pi installed | agent CLI for chat panes; `~/.local/bin/pi` or mise `node/26/bin/pi` |
| Node ≥ 22 | tested on node 26 |
| An exposure provider | something that publishes `127.0.0.1:8737` to the phone over TLS: Tailscale (easiest, the default), a Cloudflare Tunnel, or your own reverse proxy — see *Expose the bridge* in step 2 |
| ntfy (optional but recommended) | for push; one binary, see step 3 |

Check the bridge can see herdr before going further:

```bash
cd bridge
npm install
npm run build        # tsc -> dist/
npm test             # socket client, transcript parsing, routes offline via fake herdr, usage, ntfy, agents
npm run cli -- herdr status    # smoke: ping + version + protocol
```

---

## 2. Bridge daemon

### First run

`serve` creates `~/.config/scoutr/config.json` (mode 0600) on first start and
generates a random pairing token — print it with:

```bash
cd bridge
node dist/cli.js serve        # foreground, prints the token
```

The file looks like:

```json
{
  "token": "scoutr_<random>",
  "port": 8737,
  "ntfyUrl": "https://artemis.tail7dc568.ts.net/ntfy",
  "ntfyTopic": "scoutr_<random>",
  "exposure": { "kind": "tailscale" }
}
```

`ntfyUrl`/`ntfyTopic` are added automatically once ntfy is reachable (step 3);
`/api/health` exposes them so the app discovers push after connecting.

`exposure` says who is responsible for making a public URL reach this
loopback listener. `kind` is exactly one of `tailscale`, `cloudflare`, or
`custom`, with an optional `publicUrl`; *Expose the bridge* below has a
recipe for each. A
config written before `exposure` existed is normalized to
`{"kind":"tailscale"}` on the next start, and a legacy top-level `publicHost`
is migrated into `exposure.publicUrl` — existing Tailscale deployments need no
edit. An unknown `kind` is a hard configuration error (the bridge will not
silently pick a provider you did not choose).

### Run as a systemd user unit (recommended)

`~/.config/systemd/user/scoutr-bridge.service`:

```ini
[Unit]
Description=Scoutr bridge (herdr socket -> local HTTP/WSS API)
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
systemctl --user enable --now scoutr-bridge
systemctl --user status scoutr-bridge        # active; port 8737 open
loginctl enable-linger $USER                  # keep units running after logout
```

Verify:

```bash
curl -s http://127.0.0.1:8737/api/health                    # -> {"ok":false,"error":"unauthorized"} (auth works)
TOKEN=$(python3 -c "import json;print(json.load(open('$HOME/.config/scoutr/config.json'))['token'])")
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8737/api/health
# {"ok":true,"service":"scoutr-bridge","api":{"protocol":2,"features":[...]},"herdr":{"connected":true,...},...}
```

### Claude question cards (one time)

Claude Code writes an `AskUserQuestion` call to its session file only *after*
the ask is answered, so without a hook the app can show the question only once
it is too late to answer it. Install the hook that reports open asks:

```bash
cd bridge && npm run build && node dist/cli.js install-claude-hook
```

It appends a `PreToolUse`/`PostToolUse` entry to `~/.claude/settings.json`,
keeping every other hook and setting as it is. The command it installs is
absolute (this node, this checkout's `dist/cli.js`) because a hook inherits
the agent's environment, not yours — there is no `scoutr-bridge` on `PATH`.
Re-run it after moving the checkout.

Restart any running `claude` session afterwards: hooks are read at session
start, so a session that was already open when you installed it keeps asking
invisibly. Open asks then show up as
answerable cards in chat; answering one from the app drives Claude's own
questionnaire in the pane. pi needs no hook — it records the call immediately.

### Expose the bridge

The bridge always listens on `127.0.0.1:8737` and never binds a public
interface itself. Something in front of it terminates TLS and forwards to that
loopback port; `exposure` in `config.json` tells `scoutr-bridge pair` which
URL to advertise. Pick **one** of the three recipes below.

| `exposure.kind` | Who owns reachability | `publicUrl` | QR version |
|---|---|---|---|
| `tailscale` | `tailscale serve` on this host | optional (auto-discovered from `tailscale status`) | v1 |
| `cloudflare` | a named Cloudflare Tunnel you already run | **required**, must be `https://` | v2 |
| `custom` | any reverse proxy you own | **required**, `https://` (or `http://` as explicit dev intent) | v2 |

Only `tailscale` ever executes the `tailscale` binary. `cloudflare` and
`custom` fail `pair` with an actionable message when no public URL is
configured, rather than emitting a QR that points nowhere. `serve` still runs
locally in that state.

#### Recipe A — Tailscale (easiest, the default)

The phone and this machine are on the same tailnet; nothing is reachable from
the Internet.

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

Config — nothing to write by hand; this is what a fresh (or legacy) config
normalizes to:

```json
{ "exposure": { "kind": "tailscale" } }
```

Set `exposure.publicUrl` (or export `SCOUTR_PUBLIC_HOST`) only to override the
discovered MagicDNS name. `scoutr-bridge pair` emits a **v1** QR here, byte-for-byte
the contract older Scoutr builds already scan.

The app then talks to `https://<host>.ts.net` over TLS — no cleartext needed on
a real phone.

#### Recipe B — Cloudflare Tunnel (reachable from anywhere)

Use this when the phone is not on a tailnet. **Scoutr consumes a tunnel you
already own**: you create the named tunnel, the DNS routes, and the
`cloudflared` service through Cloudflare's own tooling; Scoutr never reads or
writes Cloudflare credentials, and `scripts/bridge-service.mjs` does not manage
`cloudflared`.

Two public hostnames, because the bridge and ntfy are two separate origins —
do **not** try to keep the Tailscale `/ntfy` path prefix, since the ntfy
client expects its configured `baseUrl` to be the ntfy root:

| Public hostname | Origin |
|---|---|
| `scoutr.example.com` | `http://127.0.0.1:8737` (bridge) |
| `scoutr-ntfy.example.com` | `http://127.0.0.1:8382` (ntfy, step 3) |

In your own `cloudflared` config (owned by you, e.g. `~/.cloudflared/config.yml`
or `/etc/cloudflared/config.yml` — never `~/.config/scoutr/config.json`):

```yaml
tunnel: <tunnel-uuid-or-name>
credentials-file: /path/to/<tunnel-uuid>.json

ingress:
  - hostname: scoutr.example.com
    service: http://127.0.0.1:8737
  - hostname: scoutr-ntfy.example.com
    service: http://127.0.0.1:8382
  - service: http_status:404
```

Then, in `~/.config/scoutr/config.json`:

```json
{
  "token": "scoutr_<random>",
  "port": 8737,
  "ntfyUrl": "https://scoutr-ntfy.example.com",
  "ntfyTopic": "scoutr_<random>",
  "exposure": { "kind": "cloudflare", "publicUrl": "https://scoutr.example.com" }
}
```

`publicUrl` must be `https://` — TLS terminates at Cloudflare, and an
`http://` value is rejected rather than silently upgraded. Restart the bridge
and verify both hostnames:

```bash
node scripts/bridge-service.mjs restart
curl -s https://scoutr.example.com/api/health                       # {"ok":false,"error":"unauthorized"}
curl -s -H "Authorization: Bearer $TOKEN" https://scoutr.example.com/api/health
curl -s https://scoutr-ntfy.example.com/v1/health                   # {"healthy":true}
cd bridge && node dist/cli.js pair                                  # prints "exposure: cloudflare → https://scoutr.example.com"
```

`pair` emits a **v2** QR carrying `"exposure":{"kind":"cloudflare"}` and the
separate ntfy URL. Scoutr builds older than v2 support reject that QR by
version instead of half-connecting — update the app before pairing.

**This hostname is on the public Internet.** Read step 8 before using it.

#### Recipe C — custom (your own reverse proxy)

Caddy, nginx, Traefik, a VPS with a WireGuard hop — anything you already
operate. Scoutr validates the URL and nothing else; the proxy, its TLS, and
its access controls are entirely yours.

```json
{ "exposure": { "kind": "custom", "publicUrl": "https://scoutr.internal.example" } }
```

Requirements for whatever sits in front:

- forward to `http://127.0.0.1:8737` and pass the `Authorization` header through untouched;
- proxy WebSocket upgrades (`/ws/terminal`, the topology feed) — Scoutr uses no
  fallback transport;
- point `ntfyUrl` at the ntfy **root**, not a subpath, if you expose ntfy too.

`http://` is accepted for `custom` only as explicit dev intent (an emulator
against `10.0.2.2`, a LAN test). Android release builds refuse cleartext, so
a real phone still needs `https://`. `pair` emits a **v2** QR with
`"exposure":{"kind":"custom"}`.

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
Description=ntfy push server for scoutr
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
```

Then publish it through the same exposure you chose in step 2:

```bash
# Tailscale: a path on the one tailnet hostname
tailscale serve --set-path /ntfy 8382
curl -s https://<host>.ts.net/ntfy/v1/health     # healthy over the tailnet

# Cloudflare / custom: a SEPARATE hostname whose root is ntfy
curl -s https://scoutr-ntfy.example.com/v1/health
```

Set `ntfyUrl` in `config.json` to whichever of those base URLs applies
(`https://<host>.ts.net/ntfy` or `https://scoutr-ntfy.example.com`) — the
bridge POSTs to that URL's root, so it must be the ntfy root itself.

Restart the bridge so it discovers ntfy, then check health shows the topic:

```bash
systemctl --user restart scoutr-bridge
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8737/api/health | python3 -m json.tool
```

> The bridge POSTs JSON to the ntfy **root** path (`{baseUrl}/` with `topic` in
> the body) — `BoardViewModel` polls the topic while Board is STARTED; the
> opt-in `ScoutrMonitorService` also polls every 30 seconds while background
> monitoring is enabled. Each path shows local notifications with its own cursor.

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
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew pixel2api36DebugAndroidTest   # Compose UI tests on Gradle Managed Device
```

### Emulator (headless)

```bash
avdmanager create avd -n scoutr -k "system-images;android-36;google_apis;x86_64" -d pixel_5
emulator -avd scoutr -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 3072 &
adb wait-for-device
# wait for: adb shell getprop sys.boot_completed -> 1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.scoutr.app/.MainActivity
```

Debug builds allow cleartext HTTP to `10.0.2.2` (the host loopback from the
emulator), so on the **emulator** the Connect screen takes:

- Bridge address: `http://10.0.2.2:8737`
- Pairing token: from `~/.config/scoutr/config.json` on the host

On a **physical phone** use the public HTTPS URL from your exposure
(`https://<host>.ts.net`, `https://scoutr.example.com`, …) + the same token —
TLS, no cleartext. Easier still: **Scan QR code** and let
`scoutr-bridge pair` fill both fields.

---

## 5. Using the app

- **Connect** — bridge address + pairing token, or **Scan QR code** to scan the
  code printed by `scoutr-bridge pair` (fills both fields and connects
automatically).
- **Board** — live agent cards grouped Needs you / Working / Done / Idle with
  counts, status dots, and time-in-state pills. 3s polling; self-heals when the
  bridge restarts. The **+** button opens the new-session sheet.
- **New session** — the **+** FAB opens a sheet with a folder picker (browser
  rooted at the host home, with ~ and ~/Dev quick picks) and the full model
  catalog from `models-store.json` (grouped by provider). Create spawns a fresh
  herdr workspace with one pane running `pi --model <model>`, then opens its
  chat.
- **Chat** — tap an agent card (or create a session): transcript from the pi
  session JSONL, input steers the live pane (`agent.prompt`); when the agent is
  blocked on `ask_user_question` the input becomes "Answer the question…" and
  your answer is typed into the questionnaire. The **⋮** menu in the header
  offers Abort / Retry / Compact / Fork / Rename… / Cycle thinking, grounded in
  pi's TUI commands (escape, `/compact`, `/fork`, `shift+tab`, workspace
  label).
- **Terminal** — the terminal icon in the top bar (or **Open terminal** in the
  Chat **⋮** menu) opens one herdr pane full-screen, rendered by the vendored
  Termux emulator over the bridge's `/ws/terminal` socket. An unowned pane opens
  writable; a pane owned elsewhere opens read-only with **Take control** behind
  a confirmation. The **☰** drawer lists workspaces → tabs → panes with search,
  create, rename, and close; leaving the route keeps a 30s bridge grace window
  so coming straight back lands on the same live pane.
- **Usage** — Codex 5h/7d rate windows and DeepSeek balance from
  `~/.pi/agent/auth.json` (read-only).
- **Push** — blocked → "π needs you" (high priority), done → "π finished",
  delivered as Android notifications on the `agents` channel.

---

## 6. Day-to-day scripts

```bash
scripts/deploy-bridge.sh   # rebuild dist + restart the bridge service + deployed gate
scripts/pair.sh            # print the QR the app scans to connect
scripts/install-app.sh     # build + install on a phone/emulator (--serial X, --no-build)
scripts/release.sh         # deploy bridge + build + install in one go

node scripts/bridge-service.mjs install       # write/update the service definition (idempotent)
node scripts/bridge-service.mjs restart       # what `npm run deploy` calls
node scripts/bridge-service.mjs status --json # { manager, active, pid, startedAtMs, ... }
```

`bridge-service.mjs` is the one place that knows whether this host supervises
the bridge with a systemd user unit (Linux, `scoutr-bridge.service`) or a user
LaunchAgent (macOS, `dev.scoutr.bridge`); everything else speaks that one
contract. It never manages `cloudflared`. See `docs/dev-workflow.md` for the
macOS install and the tunnel diagnostics.

Common workflows are also available via `make help`, including `make release`,
`make install`, and the bridge and Android test targets.

`install-app.sh` installs to the only connected device automatically. With several
ADB devices, it opens an arrow-key picker; use `--serial <serial>` (or
`SCOUTR_SERIAL`) to select one explicitly. Install on the S24 over wireless adb
looks like: `adb pair 100.78.204.15:<port> <code>` then `scripts/install-app.sh`.

**Updating a phone does not need adb at all.** Settings → Update builds the APK
on the host, downloads it over the same bridge connection, and installs it
through Android's package installer — so a phone that can reach the bridge can
update itself from anywhere. The
phone needs "install unknown apps" allowed for Scoutr once (the button offers
that screen), and Android asks for confirmation on every install. adb stays the
path for the first install on a fresh device, and for emulators.

---

## 7. Configuration reference

| Item | Value |
|---|---|
| Bridge listen | `127.0.0.1:8737` (`port` in `~/.config/scoutr/config.json`) |
| ntfy listen | `127.0.0.1:8382` |
| `exposure.kind` | `tailscale` \| `cloudflare` \| `custom` in `config.json` |
| `exposure.publicUrl` | public bridge base URL; required for `cloudflare` (https only) and `custom`, optional override for `tailscale` |
| Tailscale paths | `/` → bridge, `/ntfy` → ntfy on one tailnet hostname |
| Cloudflare/custom hostnames | one per origin: bridge → `127.0.0.1:8737`, ntfy → `127.0.0.1:8382` |
| Pairing token | `scoutr_<18 random bytes>` in config.json (0600) |
| ntfy topic | `scoutr_<12 random bytes>` (shared secret, also in config.json) |
| `XDG_CONFIG_HOME` | config dir for `config.json` (else `~/.config/scoutr/`) |
| `HERDR_SOCKET_PATH` | herdr Unix socket (else `~/.config/herdr/herdr.sock`) |
| `SCOUTR_REPO_ROOTS` | allow-list of review roots (403 outside it; appears in the 403 message) |
| `PI_CODING_AGENT_DIR` | pi agent dir for usage/models (else `~/.pi/agent`) |
| `SCOUTR_ANTIGRAVITY_CLIENTS` | JSON array of Antigravity OAuth `{id, secret}` clients, kept outside the repository |
| `CLAUDECONFIGDIR` | claude config dir (else `~/.claude`) |
| `PI_CODING_AGENT_SESSION_DIR` | pi session dir for the catalog (else the agent dir's `sessions/`) |
| `PI_BIN` | path to the pi script (else `~/.local/bin/pi`, mise paths, PATH) |
| `PI_NODE_BIN` | node used to run pi (else the node next to pi, mise paths) |
| `SCOUTR_PUBLIC_HOST` | public bridge URL override for QR pairing (config `exposure.publicUrl` wins; then this; then, for `tailscale` only, the discovered DNS name; else loopback) |
| `publicHost` (legacy) | pre-`exposure` top-level key; migrated into `exposure.publicUrl` with `kind: "tailscale"` on the next start |
| QR payload version | v1 for `tailscale`, v2 (with `exposure.kind`) for `cloudflare`/`custom` |
| App data | SharedPreferences `scoutr_connection` (host, exposure, ntfy; token as AES-GCM ciphertext under an Android Keystore key) |

---

## 8. Security notes

- The herdr socket is **never exposed** over the network. The bridge is the
  only process that opens it, and it only accepts loopback connections
  authenticated by bearer token (constant-time compare).
- The bridge is read-only on herdr state except deliberate `agent.prompt` /
  `pane.send_text` when you act from the app.
- Usage adapters read `~/.pi/agent/auth.json` and `~/.claude/.credentials.json`,
  and write back **only** refreshed OAuth tokens. Anthropic and OpenAI rotate the
  refresh token on every exchange, so persisting it is what keeps `pi`'s and
  `claude`'s own logins working; discarding it strands the on-disk token one
  rotation behind. Writes are atomic (temp file + rename, original mode
  preserved) and merge into the file as read at write time, so fields the bridge
  does not manage are never dropped.
- **The bearer token is the only application authentication.** With
  `exposure.kind: "tailscale"`, `tailscale serve` restricts the API to your
  tailnet and the token is a second layer on top. With `cloudflare` or
  `custom`, the bridge is **Internet-routable** and the 144-bit
  `scoutr_<18 random bytes>` token is the entire boundary. Every HTTP route
  and WebSocket upgrade is rejected with 401 before any body parsing or route
  work, using a constant-time compare, and the token travels only in the
  `Authorization` header — never in a URL.
- **There is no Cloudflare Access layer**, by explicit decision
  (`docs/decisions.md`). Nothing in Scoutr sends or expects
  `CF-Access-Client-*` credentials. If you want Access, mTLS, or device
  posture in front of the tunnel, that is yours to configure on the Cloudflare
  side; Scoutr neither requires nor manages it.
- **A public ntfy hostname is protected only by its random topic.** ntfy runs
  with no auth (`listen-http: 127.0.0.1:8382`); the
  `scoutr_<12 random bytes>` topic is a bare capability. Anyone who learns it
  can read your blocked/done events and publish fake ones to your phone
  through the public hostname. That is a weaker posture than the bridge's
  bearer auth — do not mistake one for the other.
- **Treat a leaked QR, token, or topic as a full credential compromise.** The
  pairing QR contains both the token and the topic. To rotate them in place
  (keeping `port`, `ntfyUrl`, and `exposure` — deleting the file would reset
  those to defaults):

  ```bash
  python3 - <<'EOF'
  import json, os, secrets, base64, pathlib
  p = pathlib.Path.home() / ".config/scoutr/config.json"
  c = json.loads(p.read_text())
  b64 = lambda n: base64.urlsafe_b64encode(secrets.token_bytes(n)).rstrip(b"=").decode()
  c["token"] = "scoutr_" + b64(18)
  if c.get("ntfyTopic"):
      c["ntfyTopic"] = "scoutr_" + b64(12)
  p.write_text(json.dumps(c, indent=2) + "\n")
  os.chmod(p, 0o600)
  EOF
  node scripts/bridge-service.mjs restart
  cd bridge && node dist/cli.js pair     # new QR; re-pair every phone
  ```

  Every old pairing 401s immediately. Rotating the topic still leaves the old
  topic's cached messages readable until ntfy's `cache-duration` expires, so
  shorten or clear the ntfy cache too if the topic leaked.

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| `serve` exits 127 | systemd unit must use the absolute node path (mise bin), not `node` |
| stale API after code changes | `npm run build` first, then `systemctl --user restart scoutr-bridge` |
| 401 on curl/ws | export the token: `TOKEN=$(...); curl -H "Authorization: Bearer $TOKEN" ...` (a bare shell var is not exported to child processes) |
| ntfy shows raw JSON as message | the bridge POSTs JSON to the ntfy root `/`; if you published to `/<topic>`, messages already stored that way stay raw |
| monitoring notifications do not arrive | enable the opt-in foreground monitoring session; check the ntfy health endpoint and grant `POST_NOTIFICATIONS` on Android 13+; Android 15 sessions are time-bounded |
| app shows "Disconnected" forever | board polls unconditionally after connect — it recovers when the bridge returns; check the bridge is up |
| WS steer crashes the app | the long-lived WS feed was removed in favor of 3s polling; use the current APK |
| `pair` says the exposure has no public URL | `cloudflare`/`custom` never guess — set `exposure.publicUrl` in `config.json` (or export `SCOUTR_PUBLIC_HOST`) |
| `pair` warns the QR points at 127.0.0.1 | Tailscale discovery found nothing; start `tailscaled`, or set `exposure.publicUrl` |
| local health OK but the public URL times out | an exposure/infrastructure failure, not a bridge one — diagnose `cloudflared`/your proxy separately (`docs/dev-workflow.md`) |
| the app rejects a scanned QR | a v2 (Cloudflare/custom) QR needs a Scoutr build that knows v2; update the app rather than downgrading the payload |

## 10. Known limits (v1)

- The interactive terminal ships and replaces Live Output. Its current
  ownership, lifecycle limits, and runtime evidence are documented in
  `docs/terminal.md`.
- Claude Code: status, steer, and transcripts all work via herdr — the session
  path guard is multi-backend. Residual limits: no fork-at-path resume (use
  `/fork` inside the session), no model catalog (the app hides the model
  picker), and a smaller control set (`abort`, `compact`, `close`,
  `set_model`).
- Antigravity / Gemini CLI (`agy`): full launch (`agy --model … --effort …`),
  resume (`agy --conversation <id>`), transcript parsing under `brain/`, model catalog
  (Gemini + thinking levels), slash commands & skills discovery, question/prompt handling,
  and controls (`abort`, `compact`, `close`, `set_model`, `set_thinking`).
- Time-in-state pills only track agents whose status events flow (pi panes).
- Notification monitoring has two lifecycle owners: `BoardViewModel` while Board is
  STARTED, and the opt-in `ScoutrMonitorService` while background monitoring is
  enabled. The service polls every 30 seconds, persists its cursor, and Android
  15 can stop its data-sync foreground session after six hours. Opening Board
  starts its separate foreground cursor; it does not replay the service cursor.
- One bridge/host; multi-host pairing is future work.
- Design rationale: `docs/decisions.md`.
