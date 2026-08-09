# Cockpit — shipping report

**Cockpit**: a self-hosted Android cockpit for your herdr panes and terminal
agents. Built as a free alternative to Moshi's paid herdr integration.

Status: **v0.1.0 shipped** — all six layers plus the RPC layer, verified live on
this machine (artemis, herdr 0.8.0 / protocol 19). Zero cloud or subscription
dependencies.

## What shipped

| Layer | What | Evidence |
|---|---|---|
| 1 | herdr socket client (`bridge/src/herdr/`), event feed, CLI `status/snapshot/watch` | bridge tests, commit e4d972e |
| 2 | HTTP/WSS API + pairing token + tailscale serve; pi session JSONL parser; usage adapters | tailnet HTTPS/WSS verified, commit 3b0326e |
| 3 | Android scaffold: connect, live agent board, dark-first Compose M3 | APK + GMD UI tests, commit d9f76bd |
| 4 | Session chat + steer + pane answer of ask_user_question + usage rings | live steer + answer loops, commits d9f76bd, aacdc31 |
| 5 | ntfy push: bridge publishes blocked events; app polls + shows notifications | live push E2E, commits edc937a, 4dad68b |
| 6 | Polish: status-since pills, header counts, dark-first fix, empty states | taste-reviewed board, commit ea30b0b |
| 7 | RPC: app-owned `pi --mode rpc` sessions + programmatic ask_user_question answers; done push | commit c6f966d (this round) |
| 8 | QR pairing: `cockpit-bridge pair` prints a scannable code; app Connect has **Scan QR code** (zxing) | this round, pending commit |

## Confirmed evidence

**Gates (run fresh for this report)**
- Bridge: `npx tsc --noEmit` clean; `npm test` **36/36** — herdr socket client
  against the live socket, pi JSONL parser, usage adapters, RPC client against a
  fake-pi subprocess, `/api/rpc` HTTP routes, ntfy publisher (blocked + done).
- Android: `./gradlew assembleDebug` builds an installable APK (reinstalled on
  the emulator); JVM unit tests **8/8** (models, board time-format, RPC DTO
  decode); Compose UI tests **3/3** on Gradle Managed Device `pixel2api36`
  (aosp-atd) — re-run with `--rerun-tasks` (cold device boot, ~1h40m).
- Services: `cockpit-bridge` and `ntfy` systemd user units active.

**Tailnet (through `tailscale serve`)**
- `https://artemis.tail7dc568.ts.net/api/health` → `ok:true`, herdr connected
  0.8.0 protocol 19 (with pairing token).
- `wss://artemis.tail7dc568.ts.net/ws?token=…` → pong (bearer auth works end to
  end over the tailnet).
- `https://artemis.tail7dc568.ts.net/ntfy/v1/health` → healthy.

**Live E2E on the emulator against the real herd**
1. Connect — probe-before-save; first-ever connect fixed (probe with form
   values before persisting). Screenshot 01-connect.png, 02-board.png.
2. Board — real agent cards grouped Needs you / Working / Idle with counts,
   status dots, time-in-state pills ("now", "12m"), accent "needs you" pill for
   blocked agents; 3s polling; self-heals after a bridge restart (proven live:
   `systemctl --user restart cockpit-bridge` while connected → no crash, board
   reconnected). Screenshots 05-board-dark.png, 06-board-final.png.
3. Chat (pane) — real pi transcript from the session JSONL; steer works (pi
   replied exactly as steered); answering a live `ask_user_question` from the
   app unblocked the agent (text + Enter submits the questionnaire). Screenshot
   07-chat-final.png.
4. Usage — live Codex 26% (7d window), DeepSeek $0.18 USD, xAI graceful
   "not configured" message. Screenshot 08-usage-final.png.
5. Push (blocked) — a genuinely blocked pi (steered to ask_user_question)
   produced an ntfy message → app polled the topic → notification with the
   unicode "π needs you" title.
6. Push (done) — publisher emits "π finished" on `agent_status done`; app-side
   delivery verified by publishing the same payload → notification shade showed
   "Cockpit" + "π finished". Screenshot 12-done-push.png.
7. QR pairing — `cockpit-bridge pair` prints a QR whose module matrix is bit-identical to the payload encoded by the reference encoder (verified by decoding the rendered code with zbarimg); the app's **Scan QR code** opens the zxing capture activity (verified on the emulator with the camera permission flow). The camera→decode step itself is a documented approximation: the headless emulator's virtual-scene camera cannot present a QR to the lens, so the scan was not completed end-to-end on-device; zxing's decode path and the payload round-trip are each verified independently.
8. RPC sessions — board **+ FAB** spawns a bridge-owned `pi --mode rpc`
   session and opens its chat; the transcript renders; when the agent calls
   ask_user_question the chat shows a "Waiting for your answer" banner with the
   question + options and the input switches to "Answer the question…"; an
   answer is delivered via `extension_ui_response` and the agent continues
   ("User answers: Yes"). Verified over HTTP (RPC_E2E_OK reply) and in the app.
   Screenshot 11-rpc-chat.png.

Screenshots: /tmp/cockpit-e2e/*.png (14 files; dark layout + board + chat +
usage + RPC + push verified by a vision model per AGENTS.md).

## Approximations

- **Claude Code**: herdr status + steer work (herdr's `agent.prompt` is
  generic; the earlier taste-review claude agent appeared on the board with
  status). **Transcripts are not exposed**: claude session paths do not live
  under the pi agent directory, so the `/api/sessions` path guard rejects them
  (read-only constraint). Chat is pi-only in v1.
- **Status-since pills** only track agents whose status events flow (pi panes);
  herdr-managed agents that never emit `pane.agent_status_changed` fall back to
  the plain status word.
- **App-killed push**: the app polls the ntfy topic (`?poll=1&since=<id>`,
  30s cadence) from a coroutine; there is no push channel to a killed app.
  Messages accumulate on the self-hosted ntfy server (48h cache) and are
  delivered on next launch via the seeded `since` cursor. Instant delivery to a
  killed app needs FCM or a foreground service — both out of scope (no cloud
  dependencies). Documented in docs/decisions.md.
- **xAI usage** is a registered adapter but shows a "not configured" message
  (xai is configured in auth.json but not in active model rotation).
- **RPC sessions accumulate** on the bridge until it restarts (no app-side
  session list/delete UI yet; the `/api/rpc` list + DELETE routes exist).

## Blocked claims / out of v1 scope

- **Live terminal rendering** — herdr `terminal observe/control` is the v2
  path; explicitly not in v1.
- **Instant push to a killed app** — needs FCM (cloud) or a foreground service;
  deliberately not built.
- **Multi-host pairing** — one bridge/host in v1.
- **Physical phone install** — verified on the emulator only; the APK can be
  sideloaded to the S24 over the tailnet but that final device check is left to
  the user (optional).
- **Play Store** — out of scope by constraint.

## Deployed services on artemis

- `cockpit-bridge` — systemd user unit, listens 127.0.0.1:8737, served at
  tailnet 443 root with bearer-token auth. Spawns `pi --mode rpc` through an
  explicit node binary (the unit has no mise PATH).
- `ntfy` — systemd user unit, listens 127.0.0.1:8382, served at
  https://artemis.tail7dc568.ts.net/ntfy.
- Secrets: `~/.config/cockpit/config.json` (mode 0600) — pairing token +
  ntfy topic.

## How to run

```bash
# host
cd bridge && npm install && npx tsc && npm test
systemctl --user restart cockpit-bridge

# android
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# app: enter https://artemis.tail7dc568.ts.net and the pairing token from
# ~/.config/cockpit/config.json on the host
```

## Commits

e4d972e bridge layer 1 · 3b0326e bridge layer 2 · 7baab7e vision workflow ·
d9f76bd android 3/4 · aacdc31 E2E fixes + bottom nav · edc937a bridge ntfy ·
4dad68b android push + resilience · ac91f84 product decisions · ea30b0b layer 6
polish + shipping report · c6f966d RPC layer: app-owned pi sessions + done push
