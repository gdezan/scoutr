# Cockpit — shipping report

**Cockpit**: a self-hosted Android cockpit for your herdr panes and terminal
agents. Built as a free alternative to Moshi's paid herdr integration.

Status: **v0.1.0 — all six layers shipped and verified live.**

## What shipped

| Layer | What | Evidence |
|---|---|---|
| 1 | herdr socket client (`bridge/src/herdr/`), event feed, CLI `status/snapshot/watch` | 26 bridge tests (live socket), commit e4d972e |
| 2 | HTTP/WSS API + pairing token + tailscale serve; pi session parser; usage adapters | tailnet HTTPS verified, commit 3b0326e |
| 3 | Android scaffold: connect, live agent board, dark Compose M3 theme | APK + GMD UI tests, commit d9f76bd |
| 4 | Session chat + steer + answer ask_user_question + usage rings | live steer + answer loops, commits d9f76bd, aacdc31 |
| 5 | ntfy push: bridge publishes blocked-agent events; app polls + shows notifications | live E2E push, commits edc937a, 4dad68b |
| 6 | Polish: status-since pills, header counts, dark-first fix, empty states | taste-reviewed board, this commit |

## Live E2E (headless emulator, real herdr + real pi)

1. **Connect** — probe-before-save; first-ever connect fixed (probe with form
   values before persisting).
2. **Board** — live agent cards grouped Needs you / Working / Idle with counts;
   status dots; time-in-state pills (e.g. "now", "12m"); accent "needs you" pill
   for blocked agents; 3s polling, self-heals after bridge restarts.
3. **Chat** — real pi transcript from the session JSONL; steer works end-to-end
   (pi replied exactly as steered); answering a live `ask_user_question` from the
   app unblocked the agent (text + Enter submits the questionnaire).
4. **Usage** — live Codex 26% (7d window), DeepSeek $0.18 USD, xAI graceful
   "not configured" message.
5. **Push** — a genuinely blocked pi produced an ntfy message → the app polled
   the topic → Android notification with the unicode "π needs you" title.
6. **Resilience** — restarting the bridge while connected no longer crashes the
   app (removed the long-lived WS); the board reconnects automatically. The
   bridge runs as a supervised systemd user unit.

Screenshots: /tmp/cockpit-e2e/06-board-final.png, 07-chat-final.png,
08-usage-final.png (verified dark theme + layout by a vision model).

## Gates

- Bridge: `npx tsc --noEmit` clean; `npm test` 26/26 (live-socket + unit).
- Android: `assembleDebug` builds; JVM unit tests pass (Models, board format);
  Compose UI tests 3/3 on Gradle Managed Device `pixel2api36` (aosp-atd).
- Tailnet: `https://artemis.tail7dc568.ts.net/api/health` ok; ntfy healthy.

## Deployed services on artemis

- `cockpit-bridge` — systemd user unit, listens 127.0.0.1:8737, served at
  tailnet 443 root with token auth.
- `ntfy` — systemd user unit, listens 127.0.0.1:8382, served at
  https://artemis.tail7dc568.ts.net/ntfy.
- Bridge + ntfy topics live in `~/.config/cockpit/config.json` (mode 0600).

## Known limits (out of v1 scope)

- No live terminal rendering (herdr `terminal observe/control` is the v2 path).
- Status-since time only tracks agents whose status events flow (pi panes);
   claude/etc. fall back to the status word.
- Push relies on the app polling ntfy (30s cadence) — a foreground service or
   ntfy's own app would give instant delivery.
- One bridge/host; multi-host pairing is future work.

## How to run

```bash
# host
cd bridge && npm install && npx tsc && npm test
systemctl --user restart cockpit-bridge

# android
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# app: enter https://<tailnet-host>:443 and the pairing token from
# ~/.config/cockpit/config.json on the host
```

## Commits

e4d972e bridge layer 1 · 3b0326e bridge layer 2 · 7baab7e vision workflow ·
d9f76bd android 3/4 · aacdc31 E2E fixes + bottom nav · edc937a bridge ntfy ·
4dad68b android push + resilience · ac91f84 product decisions · this commit
(board polish + status-since + shipping report)
