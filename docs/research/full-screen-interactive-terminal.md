# Full-screen interactive terminal for Cockpit Android

_Status: selected technical direction for the terminal implementation plan; no production code changed._

## Summary

Use a **native Android terminal based on Termux `terminal-emulator` and `terminal-view`**, wrapped in Compose with `AndroidView`, and adapt the view/session seam so Cockpit supplies remote bytes instead of starting a local Android PTY. Pin upstream commit `3df69d1da197dd9bd71a3bafd902dffd720576b4`, vendor only those two Apache-2.0 modules, preserve provenance, and exclude Termux app/shared and local PTY/JNI paths. Termux is the only researched Android implementation that already demonstrates the required mobile interaction surface: IME composition, hardware/special keys, mouse reporting, selection, clipboard, scrollback, zoom, and resize. [Termux license](https://github.com/termux/termux-app/blob/master/LICENSE.md) [TerminalView source](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java)

On the bridge, do not reconstruct a PTY or terminal state from `pane.read`. Spawn Herdr's purpose-built stream, `herdr terminal session control|observe <pane> --cols … --rows …`: Herdr emits the current rendered terminal followed by live ANSI-byte frames; control accepts input, resize, and release. The exact 0.8.0 controller record contract is a protocol-spike gate because it is not included in `bridge/reference/herdr-schema.json`. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)

Prove that contract with bridge tests and a tiny CLI client, then integrate the native renderer. xterm.js/WebView remains a researched alternative, not a spike, production fallback, or shipped compatibility path.

## Findings

### 1. Android rendering and widget options

| Option | Primary-source evidence | Fit for Cockpit | Decision |
|---|---|---|---|
| **Termux `terminal-emulator` + `terminal-view`** | Termux publishes both as reusable Android libraries; `terminal-view` depends on `terminal-emulator`. [Termux Libraries](https://github.com/termux/termux-app/wiki/Termux-Libraries) `TerminalView` is an Android `View` with a terminal session/emulator, renderer, selection controller, gesture recognizer, scroll position and a custom `InputConnection`. [TerminalView source](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java) | Best demonstrated mobile behavior. Compose officially embeds a `View` with `AndroidView`. [Views in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose) The integration cost is real: `TerminalView.attachSession` takes Termux's concrete `TerminalSession`, whose normal implementation creates and owns a local subprocess/PTY. [TerminalView source](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java) [TerminalSession source](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java) Cockpit therefore needs a small, explicit transport-neutral fork/adaptation, not the stock session class. | **Recommend.** Vendor a pinned upstream revision of only the two terminal modules, preserve notices, and replace the local-process coupling with a Cockpit byte sink/source. Do not import `termux-shared` or the Termux app. |
| **ConnectBot Terminal (`connectbot/termlib`)** | A native Jetpack Compose component using libvterm through JNI. It is deliberately display-only: the caller owns PTY/SSH and I/O. Its documented implementation includes Canvas rendering, scrollback, pinch zoom, selection, resizing, Unicode double-width and combining characters, and key dispatch. [ConnectBot Terminal docs](https://termlib.connectbot.org/main/) [Repository](https://github.com/connectbot/termlib) ConnectBot identifies this as its maintained terminal library, and ConnectBot 1.10 adopted it. [ConnectBot repository](https://github.com/connectbot/connectbot) [ConnectBot 1.10 release](https://github.com/connectbot/connectbot/releases/tag/v1.10.0) | Architecturally excellent for remote bytes and already Compose-native. However, its own current README lists pasting as **planned**, not current, and does not claim mouse reporting or complete Android IME behavior. [termlib README](https://github.com/connectbot/termlib#planned) Those are acceptance requirements, not optional polish. | **Promising second choice, not yet the default.** Re-evaluate after upstream documents/ships paste and after an app-level spike proves Gboard composition, Ctrl/Alt/function keys, bracketed paste, and terminal mouse modes. |
| **xterm.js in WebView** | xterm.js is MIT licensed, has zero core dependencies, and claims curses applications, mouse events, CJK, emoji and IME support. It provides maintained attach, fit, clipboard, serialize, Unicode and WebGL addons. [xterm.js](https://github.com/xtermjs/xterm.js) Its API accepts `string | Uint8Array`, emits input and resize events, and exposes selection, scrolling, terminal modes and buffer state. [xterm typings](https://github.com/xtermjs/xterm.js/blob/master/typings/xterm.d.ts) | Easy to attach to WebSocket bytes, but Android WebView is outside the project's documented desktop browser support and introduces another runtime, lifecycle, input, and security seam. Android warns that `addJavascriptInterface` is exposed to every frame without origin verification. [Android native bridge guidance](https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge) | **Reject for Cockpit v1.** Do not carry a WebView spike or fallback; bridge contract tests and a tiny CLI client isolate transport failures before native integration. |
| **libvterm + new JNI/Compose widget** | libvterm is an abstract C99 VT220/xterm/ECMA-48 emulator that invokes host rendering callbacks and deliberately supplies no graphics toolkit. [libvterm upstream](https://www.leonerd.org.uk/code/libvterm/) It is MIT-licensed as vendored by ConnectBot Terminal. [termlib used libraries](https://github.com/connectbot/termlib#used-libraries) | The parser is credible, but Cockpit would still have to build and maintain JNI, rendering, font metrics, selection, touch, scrollback policy, accessibility and Android input. ConnectBot Terminal already exists to do that work. | **Reject direct integration.** Prefer `connectbot/termlib` if choosing libvterm. |
| **AndroidIDE terminal** | AndroidIDE integrated Termux and supported multiple persistent terminal sessions. [AndroidIDE 2.7 release](https://github.com/AndroidIDEOfficial/AndroidIDE/releases/tag/v2.7.0-beta) Its source contains separate Termux application/emulator/view/shared modules and credits Termux. [AndroidIDE repository](https://github.com/AndroidIDEOfficial/AndroidIDE) | Useful as a first-party study of embedding/forking Termux in a larger Android developer tool. The repository is archived, explicitly unmaintained, and GPLv3. [AndroidIDE repository](https://github.com/AndroidIDEOfficial/AndroidIDE) | **Study only; reject as a dependency or fork base.** Use current Termux upstream instead. |
| **Jackpal Android Terminal Emulator / older widgets** | Termux states its handling was based on Jackpal's Android Terminal Emulator and labels that project inactive. [Termux README](https://github.com/termux/termux-app#readme) | Historical provenance, superseded by maintained descendants. | **Reject.** |

#### License boundary

The Termux repository as a whole is GPLv3-only, but its license file expressly identifies `terminal-view` and `terminal-emulator` as Apache-2.0-derived exceptions. [Termux license](https://github.com/termux/termux-app/blob/master/LICENSE.md) Cockpit will pin commit `3df69d1da197dd9bd71a3bafd902dffd720576b4`, vendor only those two module boundaries, and preserve their license headers, license text, and provenance record. [Termux Libraries](https://github.com/termux/termux-app/wiki/Termux-Libraries)

ConnectBot Terminal is Apache-2.0 and identifies embedded libvterm as MIT. [termlib license](https://github.com/connectbot/termlib/blob/main/LICENSE) [termlib README](https://github.com/connectbot/termlib#used-libraries) xterm.js is MIT. [xterm.js license](https://github.com/xtermjs/xterm.js/blob/master/LICENSE)

### 2. The correct Herdr integration point

Herdr's ordinary socket API is newline-delimited JSON and offers pane listing, lifecycle, reads, key/text input, layout and event subscriptions. [Herdr Socket API](https://herdr.dev/docs/socket-api/) That API is appropriate for Cockpit's pane selector and management model, but `pane.read` returns snapshots and is not the interactive terminal byte stream. [Herdr pane reads](https://herdr.dev/docs/socket-api/#event-subscriptions)

Herdr separately exposes exactly the stream Cockpit needs:

- `herdr terminal session control <target> --takeover --cols C --rows R` opens a writable terminal controller. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
- It first streams the current rendered terminal state and then live ANSI frames as NDJSON `terminal.frame` records containing base64 bytes; terminal closure is a `terminal.closed` record. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
- Its stdin accepts NDJSON `terminal.input` (text or base64 bytes), `terminal.resize`, `terminal.scroll`, and `terminal.release`. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
- Only one controller owns input and resize; `--takeover` replaces the current controller, while multiple observer sessions are read-only. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
- Herdr panes remain alive when clients detach, so a mobile WebSocket reconnect must reconnect to the existing Herdr terminal rather than create a new process. [Herdr detach/reattach](https://herdr.dev/docs/persistence-remote/#detach-and-reattach)

**Recommended bridge shape:** one bounded child process for the actively viewed pane, using controller mode when writable and observer mode otherwise; parse NDJSON, decode base64 once, and forward binary bytes to Android. Use typed HTTP snapshots/mutations plus the route-scoped Herdr feed for hierarchy, and keep a separately routed terminal WebSocket for bytes, resize, ownership, and lifecycle. This replaces Live Output rather than running beside it.

### 3. Cockpit WebSocket application protocol

WebSocket supplies ordered messages over TCP, but it deliberately leaves binary payload meaning and application metadata to the application protocol. Text frames are UTF-8; binary interpretation is application-defined; one WebSocket message may be fragmented into multiple protocol frames. [RFC 6455 §§1.5, 5](https://datatracker.ietf.org/doc/html/rfc6455#section-5)

Use a small versioned protocol; do not use JSON/base64 for the hot output path:

| Direction | WebSocket message | Meaning |
|---|---|---|
| server → Android | binary | raw ANSI/PTY bytes from one Herdr `terminal.frame` |
| server → Android | text JSON | `ready`, `closed`, `error`, `ownership`, and reconnect generation metadata |
| Android → server | binary | exact terminal input bytes |
| Android → server | text JSON | `hello {version,paneId,cols,rows,intent}`, `resize`, and `release`; takeover uses a replacement socket with confirmed intent |

This follows a proven first-party xterm.js consumer pattern: ttyd prefixes binary messages with a command discriminator and defines separate input, resize, pause and resume commands; its server reconstructs fragmented WebSocket messages before dispatch and pauses/resumes the PTY around output writes. [ttyd protocol constants](https://github.com/tsl0922/ttyd/blob/master/src/server.h) [ttyd protocol implementation](https://github.com/tsl0922/ttyd/blob/master/src/protocol.c)

#### Required semantics

1. **PTY bytes, not lines or decoded strings.** Preserve byte order and escape sequences end to end. Decode to Unicode only inside the terminal emulator. WebSocket text is necessarily UTF-8 whereas binary content is application-defined, so binary is the unambiguous PTY transport. [RFC 6455 §5](https://datatracker.ietf.org/doc/html/rfc6455#section-5)
2. **Resize is authoritative and debounced.** Calculate columns/rows from the measured terminal viewport and font metrics, send an initial size in `hello`, and send the latest changed size after layout/IME/inset/font changes. Herdr's controller has a dedicated `terminal.resize`; do not use pane split-layout resize, which changes Herdr's outer layout instead. [Herdr controller commands](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach) A conventional PTY API also treats resize as columns/rows, optionally pixel dimensions. [node-pty API](https://github.com/microsoft/node-pty/blob/main/typings/node-pty.d.ts)
3. **Input must come from the emulator/widget.** Send the bytes generated after terminal mode interpretation, not Android key codes. xterm.js, for example, exposes `onData`/`onBinary`, while Termux routes IME/hardware input through its terminal client and emulator. [xterm API](https://github.com/xtermjs/xterm.js/blob/master/typings/xterm.d.ts) [Termux TerminalViewClient](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalViewClient.java)
4. **IME and Unicode are a separate acceptance area.** Termux's `InputConnection` handles composition commit, composing completion, surrounding deletion, code points and keyboard-specific workarounds; its session encodes Unicode code points as UTF-8 before writing. [TerminalView input connection](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java) [TerminalSession UTF-8 input](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java) Do not normalize, split by Kotlin `Char`, or reinterpret those bytes in the bridge.
5. **Terminal modes stay in the emulator.** Application cursor/keypad modes, bracketed-paste mode, mouse tracking, focus reporting and alternate-screen behavior are state established by terminal escape sequences; xterm.js exposes these as emulator modes rather than transport flags. [xterm.js mode API](https://github.com/xtermjs/xterm.js/blob/master/src/browser/public/Terminal.ts) Clipboard paste should therefore enter through the widget's paste path so bracketed-paste delimiters are emitted when active.
6. **Mouse is conditional.** Touch normally scrolls/selects; when the emulator reports terminal mouse tracking, convert supported taps/drags/wheel gestures to the emulator's mouse-reporting bytes. Termux's view explicitly distinguishes mouse-tracking-active interaction from selection/normal touch. [TerminalView source](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java)
7. **Clipboard policy is explicit.** Copy remains user-driven. Ordinary single-line paste is immediate; multiple lines or control characters require confirmation, and paste enters through the widget so bracketed-paste mode is honored. Block OSC 52 and unknown terminal integrations so remote output cannot silently cross the Android clipboard boundary. [TerminalSession clipboard callbacks](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)
8. **Android alone owns v1 scrollback.** The emulator keeps bounded, selectable, copyable, touch-scrollable history for the active pane visit, starting around 10,000 rows subject to managed-Pixel memory evidence. Keep Herdr at the live viewport; do not send `terminal.scroll`, retain history between pane visits, or add search/export in v1.
9. **Reconnect resets the client emulator.** A new Herdr controller begins with current rendered state before live frames. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach) On each server-issued stream generation, clear/reset the Android emulator, apply the initial resize, then consume that generation's frames. Do not append a fresh initial screen to stale local state. Re-run the normal session snapshot after reconnect for pane IDs and lifecycle because Herdr documents `session.snapshot` as the bootstrap/reconciliation operation. [Herdr session snapshot](https://herdr.dev/docs/socket-api/#what-you-can-control)
10. **Bound every queue.** The browser WebSocket API reports queued outbound bytes through `bufferedAmount`; the standard notes that sends can fail when buffering is full. [WHATWG WebSockets](https://websockets.spec.whatwg.org/#dom-websocket-bufferedamount) OkHttp similarly exposes `queueSize`; its WebSocket has a 16 MiB outgoing limit and `send` returns false when a message cannot be queued. [OkHttp WebSocket](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-web-socket/index.html) The bridge should stop reading/pause the Herdr controller when its per-client high-water mark is reached, resume below a low-water mark, coalesce resize messages, and disconnect a persistently slow client instead of retaining unbounded terminal output. ttyd demonstrates explicit PTY pause/resume around WebSocket output. [ttyd protocol implementation](https://github.com/tsl0922/ttyd/blob/master/src/protocol.c)
11. **Ownership is visible.** Because Herdr permits only one writable controller, the server must report whether Cockpit acquired, lost or took over control and make read-only state obvious. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)

### 4. Mobile UX: one terminal, pane management outside the canvas

Use a **full-screen terminal route showing exactly one pane**. The terminal canvas should not contain Cockpit split panes; Herdr remains the owner of workspace/tab/pane topology. This follows Termux's proven Android arrangement: only one session is foregrounded in `TerminalView`, while a sessions list selects, renames and creates sessions. [TermuxActivity](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxActivity.java) [Termux session controller](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java)

Selected mobile layout:

- Dedicated full-screen route: Android system bars stay visible; Cockpit bottom navigation hides.
- Compact top bar: back, pane-selector button, resolved pane title, and quiet exceptional connection/ownership labels. OSC title updates do not steal focus.
- Terminal fills the remaining width. A toggleable two-page extra-key row provides Esc/Ctrl/Alt/Tab/arrows, then Home/End/PgUp/PgDn/Insert/Delete/common shell symbols; modifiers are one-shot with long-press lock.
- Button-only modal side drawer overlays without changing terminal columns. It is searchable and collapses workspace → tab → pane; pane rows show name and cwd. Edge-swipe is always disabled. [Material 3 modal drawer](https://developer.android.com/reference/kotlin/androidx/compose/material3/ModalNavigationDrawer)
- Management supports New tab, New workspace, and rename/close for any pane, tab, or workspace. Pane moves, split creation, and layout editing are deferred.
- Unowned panes auto-control; owned panes observe and offer confirmed takeover. Switching or explicit exit releases immediately; disconnect/background gets a 30-second grace.
- Preserve terminal focus through status/title changes and keep Cockpit errors in chrome, never in the terminal byte stream. [ConnectBot 1.10 release](https://github.com/connectbot/connectbot/releases/tag/v1.10.0)

Continue rendering exactly one terminal on larger screens; the selector remains an overlay so choosing a pane never mutates the authoritative phone grid.

### 5. Recommendation and rejection reasons

#### Recommended implementation order

1. **Delete Live Output.** Remove its Android polling screen and the bridge `pane.read` route first; the temporary regression is explicitly accepted.
2. **Prove the Herdr controller through the bridge.** Capture the exact 0.8.0 NDJSON contract in tests and a tiny CLI client before Android renderer work. [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
3. **Add the dedicated terminal WebSocket and hierarchy API.** Keep terminal binary traffic separate from HTTP snapshots/mutations and the route-scoped topology feed.
4. **Vendor the pinned Termux subset.** Preserve emulator/view behavior, exclude local PTY/JNI, and expose a transport-neutral remote session hosted with Compose `AndroidView`. [Views in Compose](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)
5. **Ship and harden the one-pane UX.** Gate release on the full IME/Unicode/key/mouse/scrollback/reconnect/ownership/output matrix and measured latency, frame-time, memory, queue, and recovery budgets.

#### Explicit rejections

- **Do not render `pane.read` text as a terminal.** It is a snapshot/read API; Herdr provides a separate live ANSI controller. [Herdr Socket API](https://herdr.dev/docs/socket-api/) [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
- **Do not add node-pty or spawn a second shell in Cockpit.** Herdr already owns the real pane terminal and exposes input/resize/current-screen replay. [Herdr concepts](https://herdr.dev/docs/concepts/) [Herdr direct terminal attach](https://herdr.dev/docs/persistence-remote/#direct-terminal-attach)
- **Do not build a terminal parser/widget from scratch or integrate bare libvterm.** libvterm intentionally supplies no rendering toolkit, while maintained Android widgets already exist. [libvterm upstream](https://www.leonerd.org.uk/code/libvterm/)
- **Do not adopt AndroidIDE's fork.** It is archived, unmaintained and GPLv3; current Termux is the relevant upstream. [AndroidIDE repository](https://github.com/AndroidIDEOfficial/AndroidIDE)
- **Do not choose ConnectBot Terminal until required input gaps are proven closed.** Its architecture is attractive, but its own feature list still marks paste as planned. [termlib README](https://github.com/connectbot/termlib#planned)
- **Do not ship xterm.js/WebView as a spike or fallback.** A WebView adds a second runtime and input/security seam; bridge contract tests plus the tiny CLI client isolate transport failures before the native renderer.

## Sources

### Kept

- [Herdr: Persistence and remote access](https://herdr.dev/docs/persistence-remote/) — authoritative controller, replay, ownership and reconnect behavior.
- [Herdr: Socket API](https://herdr.dev/docs/socket-api/) — authoritative pane catalog/management, snapshots, events and scroll metadata.
- [Termux app and terminal source](https://github.com/termux/termux-app) — maintained Android emulator/view, input behavior, session UX and licensing.
- [Termux Libraries](https://github.com/termux/termux-app/wiki/Termux-Libraries) — official reuse and packaging guidance.
- [ConnectBot Terminal](https://github.com/connectbot/termlib) and [docs](https://termlib.connectbot.org/main/) — maintained Compose/libvterm alternative and explicit current/planned features.
- [ConnectBot](https://github.com/connectbot/connectbot) — production consumer of ConnectBot Terminal.
- [xterm.js](https://github.com/xtermjs/xterm.js) — mature web emulator, API, addons, browser support and MIT license.
- [ttyd protocol](https://github.com/tsl0922/ttyd/blob/master/src/protocol.c) — maintained xterm.js consumer with binary command framing and pause/resume.
- [libvterm upstream](https://www.leonerd.org.uk/code/libvterm/) — authoritative scope of the C emulator core.
- [RFC 6455](https://datatracker.ietf.org/doc/html/rfc6455) and [WHATWG WebSockets](https://websockets.spec.whatwg.org/) — wire semantics and buffering API.
- [Android Compose/View interop](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose), [WebView bridge security](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges), and [Material 3 drawer](https://developer.android.com/develop/ui/compose/components/drawer) — first-party Android integration, security and UX guidance.
- [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE) — historical first-party embedding example and explicit maintenance/license status.

### Dropped

- Maven index/aggregator pages — useful for discovery but inferior to Termux's repository, license and library wiki.
- Blog tutorials and generic “Android terminal” lists — no authoritative API, maintenance or license evidence.
- Jackpal Android Terminal Emulator — Termux identifies it as inactive; retained only as provenance. [Termux README](https://github.com/termux/termux-app#readme)
- WeTTY and miscellaneous xterm.js demos — credible examples, but ttyd exposes a clearer primary-source wire protocol with resize and flow control.
- AOSP's old `external/libvterm` copy — its repository marks the project dead; upstream libvterm and ConnectBot's maintained Android integration are more relevant. [AOSP libvterm](https://android.googlesource.com/platform/external/libvterm)

## Gaps and validation needed

1. `bridge/reference/herdr-schema.json` from Herdr 0.8.0 / protocol 19 has no terminal stream records despite the CLI commands. Capture real bounded control/observe handshakes and pin parser tests before network or Android work.
2. Confirm the smallest adaptation against pinned commit `3df69d1da197dd9bd71a3bafd902dffd720576b4` that lets `TerminalView` use a transport-neutral remote session while retaining no local PTY/JNI runtime path. [TerminalView](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java) [TerminalSession](https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)
3. Measure direct attach and native rendering before fixing queue high/low water marks, slow-client timeout, and the final Android scrollback cap. Production requires p95 echo/render overhead no greater than 50 ms over direct attach, bounded queues without ANRs, and a stable memory plateau near the 10,000-row target.
4. Validate read-only phone-sized replay and the 30-second grace experiment against real Herdr before the broker is built: hold control, discard output, release/reopen without takeover, verify fresh replay, and observe whether a contender can win. If ownership-preserving replacement cannot be defended, stop and revise the grace contract rather than inventing semantics.
