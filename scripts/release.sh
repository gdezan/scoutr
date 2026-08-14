#!/usr/bin/env bash
# If multiple adb devices are connected, install-app.sh presents an arrow-key picker.
# One-shot release: deploy the bridge, then build + install the app on a device.
set -euo pipefail
cd "$(dirname "$0")/.."

scripts/deploy-bridge.sh
scripts/install-app.sh "$@"
echo
echo "== scan the pairing QR (scripts/pair.sh) with the app's Connect → Scan QR code"
