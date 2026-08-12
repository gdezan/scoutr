#!/usr/bin/env bash
# One-command verification: the four gates from AGENTS.md, fail-fast.
# Usage: scripts/verify.sh [--no-emulator]   (skips the GMD suite)
set -euo pipefail
cd "$(dirname "$0")/.."

# Every gradle invocation is bounded (AGENTS.md: never wait on an unbounded
# command); gradle 8.13 has no --timeout CLI flag, so shell timeout it is.
(cd bridge && npm run typecheck && npm test)
(cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}" timeout 600 ./gradlew testDebugUnitTest --rerun-tasks)
if [[ "${1:-}" != "--no-emulator" ]]; then
  (cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}" timeout 900 ./gradlew pixel2api36DebugAndroidTest)
fi
(cd android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}" timeout 600 ./gradlew assembleDebug)
echo "verify: all gates green"
