# Interactive terminal

Attach restores pane history: the bridge prefetches the pane's existing
scrollback (`herdr pane read --source recent`) and emits it, reflowed for the
phone grid, before the screen replay frame, so the emulator transcript holds
pre-attach output too. The prefetch is best-effort (byte cap + timeout, never
rejects) and can delay attach by at most its timeout if a pane read stalls.

Scoutr's terminal is a shipped, full-screen destination for interacting with one
existing Herdr pane at a time. It replaces the former Live Output screen; there
is no `pane.read` or read-only-output fallback.

## Current contract

- Android shows one pane edge to edge. The terminal route hides Scoutr's bottom
  navigation but leaves the system bars visible.
- The hierarchy drawer can select and manage Herdr workspaces, tabs, and panes.
  Scoutr does not reproduce Herdr's split layout or synchronize desktop focus.
- A supported pane can be observed, controlled, resized, taken over after
  confirmation, disconnected, resumed, renamed, and closed.
- Unsupported Herdr capabilities remain visible as a terminal destination with
  a precise capability explanation; they do not fall back to Chat output.
- The viewport follows live output only while already pinned at the bottom. Scrolling
  back into the transcript freezes what is on screen against incoming output (agent
  streams included); a generation replay — pane switch, reconnect, takeover — re-pins
  to the bottom.
- Terminal traffic uses the dedicated authenticated `/ws/terminal` WebSocket.
  Hierarchy snapshots and mutations use typed authenticated HTTP. Terminal
  bytes are binary and are not sent through the ordinary JSON `/ws` feed.

## Ownership boundaries

- **Herdr** owns processes, PTYs, terminal state, screen replay, writable
  controller arbitration, hierarchy, and persistence.
- **Bridge** owns capability detection, the terminal child-process adapter,
  authenticated transport, backpressure, hierarchy validation, and the mobile
  reconnect grace window.
- **Android** owns the terminal emulator, active-visit scrollback, viewport
  measurement, input UX, mobile selection, and per-connection preferences.

The bridge's terminal child-process transport belongs under `bridge/src/terminal/`
and must not be added to `HerdrPort`. Android's renderer and route live under
`android/app/src/main/java/dev/scoutr/app/terminal/` and
`ui/screens/terminal/`.

## Lifecycle and safety

There is one active terminal session per device connection (each WebSocket
upgrade gets its own broker identity), one mobile writer, and no queued
terminal input or content across generations. Devices attaching different
panes coexist; a second device attaching an already-attached pane falls back
to read-only observe with a takeover offer. Leaving the route permits a
30-second bridge grace window so returning to the same pane can resume its
session, and a reconnect during grace regains control immediately. A new
generation resets transport state before accepting bytes.

Incoming output reaches the emulator only through `TerminalOutputPump`. Transport
callbacks enqueue and return; one long-lived consumer on `scoutr-terminal-io`
drains what is already available into a single batch of at most 64 KiB, so a
batch costs one emulator append and one screen update regardless of how many
WebSocket frames it came from. There is no render timer: a lone keystroke echo
is delivered on its own. Screen updates reach `TerminalView` through a coalesced
post to the UI thread, since repainting mutates view state.

Pending output for one generation is bounded (4 MiB, or 65,536 queued frames).
Exceeding it — or an emulator append that throws — retires that generation and
reconnects, which replays the screen, rather than dropping bytes inside a live
generation. Repeated delivery failures within a minute stop the retry and settle
the route into a failure the pane menu can recover from.

The terminal does not claim unmeasured performance guarantees. Queue bounds,
slow-client handling, and the 50,000-row scrollback cap remain implementation
limits until benchmark evidence changes them. Measured emulator counters for the
batching path — including the finding that batching did not reduce emulator or
repaint work, because Herdr already coalesces output into periodic frames — are
recorded in `docs/performance-study.md`. Current runtime evidence covers the
emulator; a physical-phone integration walk remains outstanding.

## Source of truth

Use ADR 0001 for the bridge/WebSocket and Herdr ownership rationale, ADR 0002
for the vendored Termux renderer and licensing boundary, and the design contract
at `android/app/src/main/java/dev/scoutr/app/ui/theme/DESIGN.md` for terminal
visual rules. The ignored pre-implementation terminal plan is historical and is
not a current contract or verification checklist.
