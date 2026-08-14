#!/usr/bin/env bash
# One-command verification: the four gates from AGENTS.md, fail-fast.
# Slow Android gates run in a sibling herdr pane and complete on a unique
# completion marker (`herdr pane wait-output`); the --timeout on the wait is a
# safety ceiling only, never the completion mechanism.
# Usage: scripts/verify.sh [--no-emulator]   (skips the GMD suite)
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v herdr >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  echo "verify: needs 'herdr' and 'jq' on PATH (run inside herdr)" >&2
  exit 2
fi

# Fast gate, direct.
(cd bridge && npm run typecheck && npm test)

# One sibling pane for the three serial Android gates.
split=$(herdr pane split --current --direction right --cwd "$PWD" --no-focus)
vp=$(printf '%s\n' "$split" | jq -r '.result.pane.pane_id')
if [[ -z "$vp" ]]; then
  echo "verify: could not split a herdr pane (run inside herdr)" >&2
  exit 2
fi
root="$PWD"
trap 'herdr pane close "$vp" >/dev/null 2>&1 || true' EXIT

gate() { # $1=name; rest=gradle args (executed inside android/, one Gradle invocation at a time)
  local name="$1"; shift
  local m="__VERIFY_$(date +%s%N)_${name}__"
  herdr pane run "$vp" \
    "(cd \"$root/android\" && ANDROID_HOME=\"${ANDROID_HOME:-$HOME/Android/sdk}\" ./gradlew $*); rc=\$?; printf '\n${m}:%s\n' \"\$rc\""
  local out rc
  out=$(herdr pane wait-output "$vp" --regex "^${m}:[0-9]+$" --timeout 1800000)
  rc=$(printf '%s\n' "$out" | jq -r '.result.matched_line | split(":")[-1] | tonumber')
  if [[ "$rc" != "0" ]]; then
    echo "verify: gate '$name' failed (rc=$rc)" >&2
    herdr pane read "$vp" --source recent-unwrapped --lines 100 >&2 || true
    exit 1
  fi
  echo "verify: $name ok"
}

gate unit-tests testDebugUnitTest --rerun-tasks
if [[ "${1:-}" != "--no-emulator" ]]; then
  gate gmd pixel2api36DebugAndroidTest
fi
gate assemble assembleDebug
echo "verify: all gates green"
