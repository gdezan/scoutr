# Cockpit — product decisions

Working name: **Cockpit**. A self-hosted Android cockpit for your herdr panes and
pi agents, as an alternative to Moshi's paid herdr integration.

## Architecture (settled, 2026-08-09)

- **Transport**: a Node/TS bridge daemon owns the herdr Unix socket and exposes a
  private HTTP/WS API on localhost, fronted by `tailscale serve` TLS. The socket
  itself is never exposed (it equals arbitrary code execution on the host).
- **Bridge runtime**: `cockpit-bridge` runs as a systemd user unit
  (`~/.config/systemd/user/cockpit-bridge.service`) with `Restart=on-failure`.
  Node path is the mise install (no `node` on /usr/bin).
- **App**: Kotlin + Jetpack Compose Material 3, dark-first, package
  `dev.cockpit.app`, minSdk 26 / targetSdk 36. No Hilt/Room; manual DI via
  `CockpitApp.AppContainer`.
- **Chat**: steer live herdr pi panes — read the pi session JSONL, `agent.prompt`
  to steer, answer `ask_user_question` by sending the answer + Enter into the pane.
- **Usage**: provider adapters read credential stores without writing them: Codex OAuth
  5h/7d windows and DeepSeek/xAI credentials from `~/.pi/agent/auth.json`, plus
  Claude Code quota windows from `~/.claude/.credentials.json`. Claude's own last
  usage snapshot remains visible when its endpoint is unavailable. Expired xAI access
  tokens are refreshed in memory only.

- **Push**: self-hosted ntfy on the host, tailscale-served at `/ntfy`; the Android
  app polls the topic itself and shows a local notification (no separate ntfy app).
- **Live terminal**: not in v1.

## Decisions learned from live E2E

- **ntfy JSON bodies only parse when POSTed to the root path `/`** with `topic`
  inside the body. POSTing JSON to `/<topic>` stores the raw body as the message
  text (title lost). The publisher posts to `{baseUrl}/`.
- **OkHttp WebSocket reader crashes on abrupt server close** (EOFException escapes
  through `callbackFlow.close(cause)` into the collector coroutine). The board uses
  a 3s poll of `/api/agents` instead of a long-lived WS; the bridge re-snapshots
  every 30s anyway, so polling loses almost nothing. Short-lived command WS
  (steer/answer) is kept.
- **`answer_question` sends the text then Enter** — pi's questionnaire UI needs the
  Enter to submit; bare text is inert.
- **Board self-heals**: the poll loop runs unconditionally after connect (not only
  on a successful first probe), so a down bridge recovers automatically.
- **ntfy `since=<id>` cursor** makes polling resume-safe: the app seeds the last
  message id and re-polls after it, so it never re-delivers or misses messages.
- **Publish path**: blocked (high priority) and done events are throttled to one
  per pane per 60s and are best-effort (never break the bridge).
- **App-killed push**: the app does not own a push channel of its own — it polls
  the ntfy topic (`?poll=1&since=<id>`) from a coroutine in BoardViewModel. While
  the app is killed, messages simply accumulate on the ntfy server (48h cache);
  on relaunch the poll seeds from the latest message id and delivers anything
  published after it. So "push" to a killed app arrives as a normal notification
  on next launch, not instantly; live delivery only works while the app runs.
  A dedicated push token (FCM) or a foreground service would be needed for true
  instant delivery to a killed app, and both are deliberately out of scope.
- **Bridge-owned `pi --mode rpc` sessions** answer extension_ui_request dialogs
  programmatically: the bridge surfaces pending dialogs to the app
  (`GET /api/rpc/:id`) and the app responds with a value via
  `POST /api/rpc/:id/respond`, unblocking the agent without any pane input.
- herdr quirk: external `pane.report_agent` cannot override the pane's live pi
  extension reports — the last report wins. Status transitions for testing must
  come from a real pi (steer it to block, answer to unblock).

## QR pairing (added with the library-first rule)

- Pairing is a QR code printed by `cockpit-bridge pair`: compact v1 JSON
  `{v, host, token, ntfy}` — the address, the pairing token, and ntfy discovery
  in one scan. The app's Connect screen has a **Scan QR code** button
  (zxing-android-embedded, the standard Android QR scanner) that fills the
  fields and connects automatically, mirroring Moshi's Easy Pair flow.
- The public host is resolved automatically from `tailscale status` (Self
  DNSName), overridable via `publicHost` in config or `COCKPIT_PUBLIC_HOST`.
- Carrying the token in the QR is equivalent to typing it: the code is printed
  only on the host terminal, and the token remains the sole credential.
- Libraries: QR generation uses `qrcode-terminal` (battle-tested, no native
  deps); scanning uses zxing-android-embedded. The payload format itself is
  hand-rolled (a 60-line parser) because no library covers the cockpit-specific
  pairing payload.

## Chat scroll crash — duplicate LazyColumn keys (fixed)

- Symptom: scrolling to the end of a conversation crashed often with
  `IllegalArgumentException: Key "…" was already used` (3 crashes on the phone
  in 15 minutes of normal use).
- Root cause: the bridge filtered incremental session reads with a **lexical**
  `entryId > since` comparison, but pi message ids are random 8-hex strings
  whose lexical order does not match file order. Every poll after the initial
  load therefore re-sent a large subset of already-loaded entries, and the app
  blindly appended them — producing duplicate LazyColumn keys that crash when
  the duplicate region is composed (scrolling to the end).
- Fix: the bridge resolves the cursor to a **file position** (entries after the
  cursor index; unknown cursor returns a full snapshot with `since: null` so
  the app replaces its list), and the app dedupes on append
  (`mergeSessionEntries`) as a hard guarantee.
- Evidence: the old API call returned ~1000 duplicate entries on the first
  incremental poll; after the fix it returns 0. Stress scroll on emulator (30
  swipes) and phone (20 swipes) with the poll appending: no crash either
  device.

## Testing (self-served)

- Bridge: `npm test` (26 live-socket/unit tests), `tsc --noEmit`.
- Android: JVM unit tests, Compose UI tests on Gradle Managed Device
  `pixel2api36` (aosp-atd), live E2E on a headless emulator via adb/uiautomator,
  screenshots under /tmp/cockpit-e2e.
- Live E2E includes: connect → board → chat/steer → ask_user_question answer →
  usage rings → ntfy notification → bridge-restart survival.

## Security notes

- The bridge token (`cockpit_<18 random bytes>`) is stored in
  `~/.config/cockpit/config.json` (mode 0600) and on-device in
  SharedPreferences; sent as Bearer or WS query param.
- ntfy topic (`cockpit_<12 random bytes>`) is a shared secret between bridge and
  app; the server listens on 127.0.0.1:8382, fronted by tailscale serve (no auth).
- Never expose the herdr socket raw; never modify herdr-agent-state.ts /
  moshi-hooks.ts; never write auth.json.

## Sessions v2 (layer 3) — pane-native create flow

- **App-owned sessions are real herdr panes** in a new workspace (workspace.create
  pre-creates a root pane; `pi --model <model>` is typed into it). The bridge
  `pi --mode rpc` layer was removed with no shims.
- **Home is discovered from the bridge, never derived on-device.** The first
  `/api/dirs` call (no path) returns the host home; `System.getProperty("user.name")`
  on Android is unreliable (emulator reports "root"), so `~/...` paths must come
  from the listing response.
- **Controls map to pi's documented TUI**: abort = `escape`, retry =
  `agent.prompt` with the last user message, compact/fork = typed slash
  commands, rename = workspace label, cycle thinking = `shift+tab`.
- **The create sheet must scroll** — folder list + model catalog + name +
  create exceed one screen; the content column uses `verticalScroll`.
- **MockWebServer dispatchers must be path-aware** — the sheet fires dirs and
  models concurrently, and enqueue-order races swap responses; key the stub on
  the request path instead.
- Robolectric 4.14 caps at targetSdk 35, so JVM ViewModel tests pin
  `@Config(sdk = [35])` and idle the main looper for viewModelScope work.
## Sessions v2 (layer 2) — Cursor-iOS design language

Design north star is the Cursor iOS app (docs/cursor-ios-design-brief.md): a
near-black canvas with off-white type and one electric-blue accent reserved for
AI-owned states. Applied per the brief's patterns (source of truth:
android/app/src/main/java/dev/cockpit/app/ui/theme/DESIGN.md, committed with
layer 2):

- **Dark-first, always**: `CockpitTheme` forces `darkTheme = true` (emulator
  light mode was rendering light) and the window + system bars are dark in both
  system modes (`windowLightStatusBar/NavigationBar` false + `enableEdgeToEdge`,
  added in layer 3 after a device check).
- **One accent color**: blue `#5B8CFF` only for AI-owned states (active run
  pill, "needs you", composer send). User bubbles are neutral surfaces, not
  primary (brief: "user vs AI" visual split).
- **Mono only for code/data/paths**: tool output, model ids, workspace paths
  (brief's type hierarchy).
- **Chat opens at the last message** and follows while pinned; scrolling up
  shows the scroll-to-end FAB (brief's "jump to latest" pattern). Auto-scroll
  is guarded (`scrollToItem` + `scrollBy(Float.MAX_VALUE)`, which clamps; a
  single `scrollToItem(index, Int.MAX_VALUE)` overflows).
- **Tool calls are one muted chip**, collapsed, tap to expand command + output
  inline; thinking blocks dimmed, hidden by the details toggle (brief: activity
  details are opt-in).
- **Motion 150-250ms** via `animateItem`/`AnimatedVisibility` (brief's
  restraint).

## Session controls — v1 limits

- Abort (`escape`), Retry (re-send last user message via `agent.prompt`),
  Compact and Fork (typed `/compact` `/fork` + Enter), Rename (workspace
  label), Cycle thinking (`shift+tab`) are grounded in pi's documented TUI
  commands (docs: keybindings.md — `escape` = app.interrupt, `shift+tab` =
  app.thinking.cycle, `/compact`, `/fork`).
- **Setting a specific thinking level** (e.g. "high") headlessly is a v1 limit:
  only cycling via `shift+tab` works through a pane; a direct
  `setThinkingLevel` would need pi's `--mode rpc` layer, which was retired.
- Controls type into the live pane, so they act on pi's real TUI state.
