# Scoutr — product decisions

Working name: **Scoutr**. A self-hosted Android scoutr for your herdr panes and
pi agents, as an alternative to Moshi's paid herdr integration.

## Architecture (settled, 2026-08-09)

- **Transport**: a Node/TS bridge daemon owns the herdr Unix socket and exposes a
  private HTTP/WS API on localhost, fronted by `tailscale serve` TLS. The socket
  itself is never exposed (it equals arbitrary code execution on the host).
- **Bridge runtime**: `scoutr-bridge` runs as a systemd user unit
  (`~/.config/systemd/user/scoutr-bridge.service`) with `Restart=on-failure`.
  Node path is the mise install (no `node` on /usr/bin).
- **App**: Kotlin + Jetpack Compose Material 3, dark-first, package
  `dev.scoutr.app`, minSdk 26 / targetSdk 36. No Hilt/Room; manual DI via
  `ScoutrApp.AppContainer`.
- **Chat**: steer live herdr pi panes — read the pi session JSONL, `agent.prompt`
  to steer, and answer question cards through the agent adapter — the app sends
  the card id plus the picked labels, the adapter drives its own TUI
  questionnaire (see `docs/adr/0006-answer-questions-through-the-agent-adapter.md`).
- **Usage**: provider adapters read credential stores without writing them: Codex OAuth
  5h/7d windows and DeepSeek/xAI credentials from `~/.pi/agent/auth.json`, plus
  Claude Code quota windows from `~/.claude/.credentials.json`. Claude's own last
  usage snapshot remains visible when its endpoint is unavailable. Expired xAI access
  tokens are refreshed in memory only.

- **Push monitoring**: self-hosted ntfy on the host, tailscale-served at `/ntfy`;
  `BoardViewModel` polls the topic while Board is STARTED, while the opt-in
  `ScoutrMonitorService` polls every 30 seconds in the background. Both paths
  show local notifications with separate cursors; the service persists its
  cursor. The foreground service is time-bounded, returns `START_NOT_STICKY`,
  and is not an always-on push channel.
- **Interactive terminal (shipped)**: Live Output is gone; a full-screen, one-pane
  Herdr terminal replaced it. Herdr owns processes, PTYs, and terminal state; the
  bridge owns capability, child lifecycle, and the 30s reconnect grace; Android
  owns the emulator, local scrollback, and input UX. The current contract and
  evidence live in `docs/terminal.md`; ADRs retain historical decisions.

## Versioning and in-app self-update (2026-08-15)

- **Version identity** is one shared Node script, `scripts/version.mjs`, run at
  Android build time (Gradle `Exec`) and at bridge runtime, so both sides always
  agree. It anchors on the most recent tag, bumps from conventional commits
  since it, and stamps version, `versionCode`, short commit, dirty flag, and
  build time.
- **Semantic versioning**: `feat` bumps minor; `fix` and every other subject
  (including non-conventional prose and `docs`/`chore`/`test`/`refactor`) bump
  patch; `feat!`/`fix!` or a `BREAKING CHANGE:` footer bump major. The strongest
  bump since the tag wins, not a running tally.
- **`versionCode`** = `major*1_000_000 + minor*1_000 + patch`, floored to 1
  (Android requires `versionCode >= 1`). `versionName` is the semver string.
- **Tags are manual.** The version script never creates tags; tagging `HEAD` is
  the human "pin this release" step. Commit messages are conventional going
  forward, with no commitlint tooling.
- **Update signal stays commit-based**, with semver as display-only:
  `updateAvailable = (host HEAD != installed commit) || hostDirty`. The app
  sends its stamped commit/version/dirty to `GET /api/update/status`; the bridge
  computes the host side and the flag.
- **The install pulls, so adb is not in the loop.** The bridge cannot reach the
  phone, so every step is a phone-initiated request: `POST /api/update/apk/build`
  starts `scripts/install-app.sh --build-only` on the host, `GET
  /api/update/apk/status` is polled until the build leaves `building`, and
  `GET /api/update/apk` streams the APK. The app checks the bytes against the
  host's sha256 and commits them through Android's `PackageInstaller`. This
  replaced an `adb devices -l` + `adb install -r` push, which only worked while
  a USB cable or a live `adb pair` session existed.
- **One build at a time, tracked by the bridge.** `ApkBuilder` is process-wide
  (one checkout, one gradle) and a start during a running build returns that
  build's id instead of queueing a second. A failed build refuses to serve the
  stale APK still sitting in `app/build/outputs`.
- **Install is app-only.** The build never runs `make release`, because a bridge
  restart would kill the request mid-flight. Deploys stay a host-side
  `make deploy-bridge`.
- **The confirmation sheet is unavoidable.** Silent self-install needs
  device-owner privileges Scoutr does not have, so an update ends in Android's
  own prompt, and the user grants "install unknown apps" once beforehand. The
  APK must also carry the host debug keystore's signature — building on a
  second machine yields a different key and the commit fails.

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
- **ntfy cursors**: the service persists the last shown message ID and polls with
  `since=<id>`, so service restarts resume without re-delivery. The Board's
  lifecycle-scoped poll is separate and seeds the current latest ID.
- **Publish path**: blocked (high priority) and done events are throttled to one
  per pane per 60s and are best-effort (never break the bridge).
- **Monitoring lifecycle**: notification monitoring is an opt-in, time-bounded
  `ScoutrMonitorService` foreground session. It polls ntfy every 30 seconds,
  resumes from the stored cursor, and stops at Android 15's six-hour data-sync
  foreground-service limit. `BoardViewModel` has a separate topic poll only while
  Board is STARTED and seeds the current latest ID; opening Board does not resume
  the service cursor or replay service messages. If the service is inactive, ntfy
  retains messages for the configured cache period.
- **Bridge-owned `pi --mode rpc` sessions** answer extension_ui_request dialogs
  programmatically: the bridge surfaces pending dialogs to the app
  (`GET /api/rpc/:id`) and the app responds with a value via
  `POST /api/rpc/:id/respond`, unblocking the agent without any pane input.
- herdr quirk: external `pane.report_agent` cannot override the pane's live pi
  extension reports — the last report wins. Status transitions for testing must
  come from a real pi (steer it to block, answer to unblock).

## QR pairing (added with the library-first rule)

- Pairing is a QR code printed by `scoutr-bridge pair`: compact v1 JSON
  `{v, host, token, ntfy}` — the address, the pairing token, and ntfy discovery
  in one scan. The app's Connect screen has a **Scan QR code** button
  (zxing-android-embedded, the standard Android QR scanner) that fills the
  fields and connects automatically, mirroring Moshi's Easy Pair flow.
- The public host is resolved automatically from `tailscale status` (Self
  DNSName), overridable via `publicHost` in config or `SCOUTR_PUBLIC_HOST`.
- Carrying the token in the QR is equivalent to typing it: the code is printed
  only on the host terminal, and the token remains the sole credential.
- Libraries: QR generation uses `qrcode-terminal` (battle-tested, no native
  deps); scanning uses zxing-android-embedded. The payload format itself is
  hand-rolled (a 60-line parser) because no library covers the scoutr-specific
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

- Bridge: run `npm test` and `tsc --noEmit` (or the current bridge check script).
- Android: JVM/Compose tests on the Gradle Managed Device `pixel2api36`, plus
  runtime acceptance on `emulator-5554` when the changed risk is user-visible.
- Runtime evidence should record the exercised flow and checks; do not embed
  volatile test counts in product decisions.
## Security notes

- The bridge token (`scoutr_<18 random bytes>`) is stored in
  `~/.config/scoutr/config.json` (mode 0600) and on-device in
  SharedPreferences. Current clients send it as a Bearer header; the bridge
  still accepts a WebSocket query token from older APKs during migration.
- ntfy topic (`scoutr_<12 random bytes>`) is a shared secret between bridge and
  app; the server listens on 127.0.0.1:8382, fronted by tailscale serve (no auth).
- Never expose the herdr socket raw; never modify herdr-agent-state.ts /
  moshi-hooks.ts; never write auth.json.

## Sessions v2 (layer 3) — pane-native create flow

- **App-owned sessions are real herdr panes** (`pi --model <model>` is typed into
  a pre-created root pane). The bridge `pi --mode rpc` layer was removed with no
  shims.
- **Workspaces are per folder, sessions are tabs in them** (2026-08-14). A new
  session lands in the workspace whose *root pane* sits in the folder, as a new
  tab labeled with the session name; only an unclaimed folder gets a fresh
  workspace, and its label is left to herdr. herdr's snapshot has no workspace
  cwd, so the root pane (first pane of the workspace) is the folder of record —
  a pane the user `cd`-ed elsewhere never claims one. Several workspaces on one
  folder resolve to the lowest-numbered. Resume/fork of a stored session follows
  the same rule via its recorded cwd; the Terminal's explicit "New workspace"
  action still always creates one.
- **Home is discovered from the bridge, never derived on-device.** The first
  `/api/dirs` call (no path) returns the host home; `System.getProperty("user.name")`
  on Android is unreliable (emulator reports "root"), so `~/...` paths must come
  from the listing response.
- **Controls map to pi's documented TUI**: abort = `escape`, retry =
  `agent.prompt` with the last user message, compact/fork = typed slash
  commands, rename = tab label, cycle thinking = `shift+tab`.
- **Close and rename are tab-scoped** (2026-08-14), because a workspace now holds
  the folder's other sessions: closing a session closes its own tab, and only the
  last tab takes the workspace with it.
- **The create sheet must scroll** — folder list + model catalog + name +
  create exceed one screen; the content column uses `verticalScroll`.
- **MockWebServer dispatchers must be path-aware** — the sheet fires dirs and
  models concurrently, and enqueue-order races swap responses; key the stub on
  the request path instead.
- Robolectric 4.14 caps at targetSdk 35, so JVM ViewModel tests pin
  `@Config(sdk = [35])` and idle the main looper for viewModelScope work.
## Sessions v2 (layer 2) — Cursor-iOS product patterns

The Cursor iOS brief remains a product-pattern reference for supervision, chat, review, and cache-first recovery. Its visual recommendations are superseded for Scoutr Android by ADR 0005 and `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md`.

## Design system v2 (layer 3)

Scoutr Android is always dark and uses green `#8DF08D` for live/AI-owned state, gray for done, red for user attention, and teal `#2C6F72` only for charts/data. Space Grotesk is the UI face; Martian Mono is used for small machine facts and code; JetBrains Mono is reserved for full-screen terminal content. Compact 4/6/8dp geometry, unfilled 9dp status rings, and no loading spinners or skeletons are the shared visual contract.

- **Chat** keeps the transcript-centric event stream, assistant spine, inline tool details, thinking toggle, true-bottom follow behavior, and multiline Enter-never-sends composer. The send action is a filled 6dp square.
- **Sessions** uses recency-ordered repository tabs derived from session `cwd`, plus a scope filter that keeps Active, Completed, Pinned, and Archived reachable.
- **Motion** keeps the intentional live-state ripple and blocked pulse; reduce motion renders a static ring. Loading feedback is text or static feedback, not a spinner or skeleton.
- **Mono** remains restricted to code, data, paths, commands, model ids, and terminal output.


## Session controls

- Backend adapters own each agent's control grammar. The session-control surface includes Abort, Retry, Compact, Fork, Rename, and thinking-level selection; each backend advertises and implements only the controls it supports.
- Pi's pane-native controls use its documented TUI grammar: Compact and Fork are typed `/compact` and `/fork` commands, and thinking levels cycle with `shift+tab` because Pi's interactive transport has no direct set-level command.
- Claude Code sets a level with its interactive `/effort <level>` command; `low`, `medium`, and `high` are available on all cataloged models, while `xhigh` and `max` depend on the selected model.
- Controls type into the live pane, so they act on the agent's real TUI state.
