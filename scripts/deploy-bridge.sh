#!/usr/bin/env bash
# Deploy the bridge: rebuild dist, restart the supervised service (systemd user
# unit on Linux, user LaunchAgent on macOS — scripts/bridge-service.mjs picks),
# then prove the deployed process is the fresh one.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/../bridge"

echo "== building dist (tsc)…"
npx tsc

echo "== restarting the bridge service…"
node "$here/bridge-service.mjs" restart

echo "== checking the deployed bridge…"
npm run --silent check:deployed
