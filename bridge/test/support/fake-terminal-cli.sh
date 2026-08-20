#!/usr/bin/env bash
# Fake `herdr` binary for terminal-process unit tests.
#
# Scenario selection: FAKE_HERDR_SCENARIO (default "ok").
# Diagnostics: FAKE_HERDR_ARG_LOG (one "$*" line per invocation),
# FAKE_HERDR_STDIN_LOG (one NDJSON line per stdin record).
#
# Emits the captured herdr 0.8.0 record shapes; field order mirrors the
# live contract (bridge/reference/terminal-contract-0.8.0.md).
set -u

SCENARIO="${FAKE_HERDR_SCENARIO:-ok}"
[ -n "${FAKE_HERDR_ARG_LOG:-}" ] && printf '%s\n' "$*" >>"$FAKE_HERDR_ARG_LOG"

ANSI_B64=$(printf '\033[2J\033[Hhello-contract\n' | base64 -w0)
LIVE_B64=$(printf '\033[2Khello-live\n' | base64 -w0)

emit_frame() { # seq full b64
  printf '{"bytes":"%s","encoding":"ansi","full":%s,"height":%s,"seq":%s,"width":%s,"type":"terminal.frame"}\n' \
    "$3" "$2" "$ROWS" "$1" "$COLS"
}

read_loop() {
  while IFS= read -r line; do
    [ -n "${FAKE_HERDR_STDIN_LOG:-}" ] && printf '%s\n' "$line" >>"$FAKE_HERDR_STDIN_LOG"
    if [ "$line" = '{"type":"terminal.release"}' ]; then
      printf '{"reason":"detached","type":"terminal.closed"}\n'
      exit 0
    fi
  done
  # stdin EOF: detached (captured live behavior)
  printf '{"reason":"detached","type":"terminal.closed"}\n'
  exit 0
}

if [ "$1" = "--version" ]; then
  case "$SCENARIO" in
    old-version) echo "herdr 0.7.0" ;;
    no-version) echo "herdr something-else" ;;
    verified-0.8.2) echo "herdr 0.8.2" ;;
    future-version|future-no-handshake) echo "herdr 0.10.0" ;;
    *) echo "herdr 0.8.0" ;;
  esac
  exit 0
fi

if [ "$1" = "status" ] && [ "$2" = "client" ]; then
  if [ "${3:-}" = "--json" ]; then
    if [ "$SCENARIO" = "verified-0.8.2" ]; then
      printf '%s\n' '{"version":"0.8.2","channel":"stable","protocol":20,"binary":"fake-herdr"}'
    elif [ "$SCENARIO" = "future-version" ] || [ "$SCENARIO" = "future-no-handshake" ]; then
      printf '%s\n' '{"version":"0.10.0","channel":"stable","protocol":21,"binary":"fake-herdr"}'
    else
      printf '%s\n' '{"version":"0.8.0","channel":"stable","protocol":19,"binary":"fake-herdr"}'
    fi
  else
    echo "herdr 0.8.0 client"
  fi
  exit 0
fi

if [ "$1" = "pane" ] && [ "$2" = "read" ]; then
  # Pane history prefetch (best-effort): scenarios script failure, slowness,
  # and overflow; the default emits two plain history rows.
  case "$SCENARIO" in
    no-scrollback) exit 1 ;;
    slow-scrollback) sleep 30; printf 'scrollback-slow\n' ;;
    big-scrollback) head -c 8000000 /dev/zero | tr '\0' 'S' ;;
    *) printf 'scrollback-row-1\r\nscrollback-row-2\r\n' ;;
  esac
  exit 0
fi

if [ "$1" = "terminal" ] && [ "$2" = "session" ]; then
  MODE="$3"
  if [ "${4:-}" = "--help" ]; then
    if [ "$SCENARIO" = "no-surface" ]; then
      echo "error: unrecognized subcommand 'session'" >&2
      exit 1
    fi
    echo "usage: herdr terminal session $MODE <target> [--cols N] [--rows N] [--takeover]"
    exit 0
  fi
  TARGET="$4"
  COLS=80
  ROWS=24
  shift 4
  while [ $# -gt 0 ]; do
    case "$1" in
      --cols) COLS="$2"; shift 2 ;;
      --rows) ROWS="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  case "$SCENARIO" in
    conflict)
      printf '%s\n' '{"reason":"terminal attach failed: terminal term_abc already has an attached client; retry with --takeover","type":"terminal.closed"}'
      exit 0
      ;;
    gone)
      printf '%s\n' '{"reason":"terminal attach ended: terminal term_abc not found","type":"terminal.closed"}'
      exit 0
      ;;
    stderr-startup)
      echo "fatal: herdr configuration is broken" >&2
      exit 3
      ;;
    exit-before-frame|future-no-handshake)
      exit 1
      ;;
    invalid-json)
      printf 'this is not json\n'
      exit 1
      ;;
    invalid-base64)
      printf '{"bytes":"not!!base64!!","encoding":"ansi","full":true,"height":24,"seq":1,"width":80,"type":"terminal.frame"}\n'
      exit 1
      ;;
    huge-line)
      BIG=$(head -c 2200000 /dev/zero | tr '\0' 'A')
      printf '{"bytes":"%s","encoding":"ansi","full":true,"height":24,"seq":1,"width":80,"type":"terminal.frame"}\n' "$BIG"
      exit 1
      ;;
    multi)
      emit_frame 1 true "$ANSI_B64"
      emit_frame 2 false "$LIVE_B64"
      read_loop
      ;;
    fragmented)
      printf '{"bytes":"%s' "${ANSI_B64:0:20}"
      sleep 0.05
      printf '%s' "${ANSI_B64:20}"
      sleep 0.05
      printf '","encoding":"ansi","full":true,"height":%s,"seq":1,"width":%s,"type":"terminal.frame"}\n' "$ROWS" "$COLS"
      read_loop
      ;;
    taken-over)
      emit_frame 1 true "$ANSI_B64"
      sleep 0.1
      printf '%s\n' '{"reason":"terminal attach taken over","type":"terminal.closed"}'
      exit 0
      ;;
    exit-without-closed)
      emit_frame 1 true "$ANSI_B64"
      exit 0
      ;;
    hang)
      sleep 100
      ;;
    no-release-response)
      emit_frame 1 true "$ANSI_B64"
      # Ignore terminal.release forever; exit 0 only on SIGTERM so the test
      # can prove release() escalated past the grace period.
      trap 'exit 0' TERM
      while IFS= read -r _; do :; done
      ;;
    live)
      emit_frame 1 true "$ANSI_B64"
      sleep 0.3
      emit_frame 2 false "$LIVE_B64"
      read_loop
      ;;
    *)
      emit_frame 1 true "$ANSI_B64"
      read_loop
      ;;
  esac
  exit 0
fi

echo "fake-herdr: unknown invocation: $*" >&2
exit 2
