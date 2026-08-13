#!/usr/bin/env bash
# Deploy the bridge: rebuild dist and restart the scoutr-bridge systemd unit.
set -euo pipefail
cd "$(dirname "$0")/../bridge"

echo "== building dist (tsc)…"
npx tsc

echo "== restarting scoutr-bridge…"
systemctl --user restart scoutr-bridge
sleep 2
systemctl --user is-active scoutr-bridge >/dev/null && echo "== unit active" || { echo "unit FAILED"; exit 1; }

TOKEN="$(python3 -c "import json;print(json.load(open('$HOME/.config/scoutr/config.json'))['token'])" 2>/dev/null || true)"
if [ -n "$TOKEN" ]; then
  HEALTH="$(curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8737/api/health || true)"
  echo "== health: $HEALTH"
else
  echo "== no config token found; /api/health requires auth"
fi
