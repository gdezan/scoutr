#!/usr/bin/env bash
# Build the Cockpit APK and install it on a device (USB, wireless adb, or emulator).
#
# Usage:
#   scripts/install-app.sh                 # build, then install on the single connected device
#   scripts/install-app.sh --serial X      # install on a specific device serial
#   scripts/install-app.sh --no-build      # reuse the existing APK
#
# The serial is also read from $COCKPIT_SERIAL. With several devices connected
# and no --serial, the script lists them and stops (no guessing).
set -euo pipefail
cd "$(dirname "$0")/../android"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

SERIAL="${COCKPIT_SERIAL:-}"
BUILD=1
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

APK="app/build/outputs/apk/debug/app-debug.apk"

if [ "$BUILD" = 1 ]; then
  echo "== building debug APK…"
  ./gradlew assembleDebug >/dev/null
fi
[ -f "$APK" ] || { echo "no APK at $APK — run without --no-build" >&2; exit 1; }

DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$SERIAL" ]; then
  COUNT="$(echo "$DEVICES" | grep -c . || true)"
  if [ "$COUNT" -eq 1 ]; then
    SERIAL="$DEVICES"
  else
    echo "multiple/no devices — pick one:"
    adb devices
    echo "usage: $0 --serial <serial>"
    exit 2
  fi
fi

echo "== installing on $SERIAL…"
adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell pm grant dev.cockpit.app android.permission.POST_NOTIFICATIONS || true
adb -s "$SERIAL" shell am start -n dev.cockpit.app/.MainActivity
echo "== done — open the app, then Connect → Scan QR code (run scripts/pair.sh on the host)"
