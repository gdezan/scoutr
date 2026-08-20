# Herdr terminal session control|observe — empirically verified contract (0.8.0 / protocol 19)

Captured live against the mise-managed Herdr 0.8.0 binary on disposable panes
(`herdr status client --json` → `{"version":"0.8.0","channel":"stable","protocol":19,...}`).
This document is the contract source for `bridge/src/terminal/`; do not infer
fields not listed here — verify against a live `herdr terminal session control|observe`
run before relying on them.

## Verified against

Versions this document has been replayed against live, on a disposable pane.
This is a record, not a gate: `capability.ts` admits anything at or above
`MINIMUM_VERSION` and lets the live handshake judge it, so a newer herdr works
without touching this table — add a row once someone has actually re-run the
capture below against it.

| version | protocol | notes |
|---|---|---|
| 0.8.0 | 19 | original capture |
| 0.8.2 | 20 | re-verified: identical frame keys and `seq`/`full` behavior, identical `terminal.closed` reason strings (conflict, taken-over, detached, target-not-found), observer coexistence unchanged. Protocol 20 changes the *socket* handshake, not this CLI contract — but a 0.8.0 client is refused by a 0.8.2 server ("client version 19 is older than server version 20"), so client and server must be upgraded together. |

## Invocation

- Control (writable): `herdr terminal session control <workspace>:<pane> --cols N --rows N [--takeover]`
- Observe (read-only): `herdr terminal session observe <workspace>:<pane> --cols N --rows N`
- The process must be spawned with a **held-open stdin pipe**; stdin `ignore`
  (`/dev/null`) causes immediate EOF, `closed(detached)`, and zero frames.
- With no `--cols/--rows`, the pane's real grid is used (120x40 in the proof).
- `--cols 0` fails on stderr with a Rust clap error (`--cols must be greater than 0`).

## stdout NDJSON records (control and observe)

One JSON record per line; multi-KB frame records span multiple stdout chunks, so
parsers must buffer by newline. Records:

```json
{"bytes":"<base64>","encoding":"ansi","full":true,"height":24,"seq":1,"width":80,"type":"terminal.frame"}
{"reason":"detached","type":"terminal.closed"}
```

- `terminal.frame`: field order `bytes,encoding,full,height,seq,type,width`; `bytes`
  is base64-encoded ANSI; `seq` starts at 1 (the replay frame) and increments per
  frame; `full=true` for replay/resize full redraws, `false` for live incremental
  frames; replay begins with `\x1b[?2026h` (synchronized update), `\x1b[?25l`
  (hide cursor), an OSC 8 hyperlink reset, and a clear screen. Glyphs are emitted
  with per-character cursor positioning, so literal text is not contiguous in the
  decoded bytes.
- `terminal.closed`: process then exits 0. Reason vocabulary:

| reason | meaning | closed code |
|---|---|---|
| `detached` | explicit `terminal.release` or stdin EOF | `released` |
| `terminal attach taken over` | displaced by a `--takeover` controller | `taken-over` |
| `terminal attach failed: terminal <id> already has an attached client; retry with --takeover` | ownership conflict (stdout, exit 0, zero frames) | `ownership-conflict` |
| `terminal attach ended: terminal <id> not found` | pane/terminal closed while attached | `terminal-gone` |
| `terminal session control failed: ... not found` / `terminal session observe failed: ... not found` | nonexistent target, exit 0 | `terminal-gone` |
| `process exited with code N without a terminal.closed record` | synthetic record emitted by the bridge when the child dies without a closed record | `unknown` |

Ownership-conflict detection must match `already has an attached client` inside
the reason, **not** the generic `terminal attach failed: ` prefix.

## stdin commands (control mode only)

```json
{"type":"terminal.input","bytes":"<base64>"}
{"type":"terminal.input","text":"echo hi\n"}
{"type":"terminal.resize","cols":100,"rows":30}
{"type":"terminal.release"}
```

- `terminal.input` accepts `text` or `base64`; a `bytes` value containing a
  literal newline is rejected non-fatally on **stderr**
  (`herdr: terminal session control input ignored: invalid terminal.input bytes: ...`)
  while the session keeps running; stderr diagnostics never appear in the stdout
  NDJSON stream.
- `terminal.resize` produces a `full=true` redraw frame.
- `terminal.release` closes with `closed(detached)`, exit 0.
- Writing `terminal.input` to an **observer's** stdin is silently ignored.

## Ownership and teardown

- Unowned panes auto-control; an owned pane rejects a second non-takeover
  controller with the conflict closed record above.
- `--takeover` replaces the current controller immediately; the displaced
  controller gets `closed(taken-over)` and exits 0.
- stdin EOF, `terminal.release`, SIGTERM, and SIGKILL all release ownership
  immediately — a fresh non-takeover controller wins a probe right after any of
  them (verified), so bridge kill escalation does not strand ownership.
- No auto-release after 30 s of disconnect (a non-takeover contender still
  conflicted after a 30 s hold), and a close+reopen without takeover succeeds
  with a fresh full replay — the ownership-preserving replacement contract is
  defensible.
- An observer coexists with an owning controller and receives a full frame at
  its requested grid (45x20 phone-sized projection verified) plus live frames.

## Bridge seam (`bridge/src/terminal/`)

- `types.ts` — `TerminalRecord`, `TerminalClosedCode`, `TerminalProcess`,
  `TerminalCapability`, `TerminalOpenOptions`, `TerminalLauncher`.
- `process.ts` — `HerdrTerminalLauncher`/`HerdrTerminalProcess`: bounded NDJSON
  parser (never decodes to text), strict base64 validation, newline-buffered
  ingestion, handshake resolving `open()` on the first `terminal.frame` (kept on
  `replayFrame`, not re-delivered), typed errors
  (`TerminalStartupError`, `TerminalOwnershipConflictError`, `TerminalBoundsError`),
  `sendInput`/`resize` as base64 records, `pauseOutput`/`resumeOutput`, idempotent
  `release()` with SIGTERM→SIGKILL group escalation, bounded stderr tail.
- `capability.ts` — `probeTerminalCapability`: `--version` must be at or above
  `MINIMUM_VERSION`, `herdr status client --json` protocol must be an integer, both
  `terminal session control --help` and `observe --help` must exit 0, and with a
  target an observer handshake must complete (80x24, no takeover).
