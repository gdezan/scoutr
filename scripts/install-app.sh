#!/usr/bin/env bash
# Build the Scoutr APK and install it on a device (USB, wireless adb, or emulator).
#
# Usage:
#   scripts/install-app.sh                 # build, then install (prompts if needed)
#   scripts/install-app.sh --serial X      # install on a specific device serial
#   scripts/install-app.sh --no-build      # reuse the existing APK
#
# The serial is also read from $SCOUTR_SERIAL. With several devices connected
# and no --serial, the script opens an arrow-key picker.
set -euo pipefail
cd "$(dirname "$0")/../android"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

SERIAL="${SCOUTR_SERIAL:-}"
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

pick_device() {
  local tty_fd
  if ! exec {tty_fd}<>/dev/tty; then
    echo "cannot open a terminal to select an install device; use --serial <serial>" >&2
    return 2
  fi

  local selected=0
  local count="${#DEVICE_SERIALS[@]}"
  local key
  local i
  local marker
  local status=0

  printf '\n== choose an install device ==\n' >&"$tty_fd"
  printf 'Use the up/down arrows and Enter to select.\n' >&"$tty_fd"
  while :; do
    for i in "${!DEVICE_SERIALS[@]}"; do
      if [ "$i" -eq "$selected" ]; then
        marker='> '
      else
        marker='  '
      fi
      printf '\033[2K\r%s%s\n' "$marker" "${DEVICE_LABELS[$i]}" >&"$tty_fd"
    done

    if ! IFS= read -r -s -n 1 -u "$tty_fd" key; then
      status=1
      break
    fi
    case "$key" in
      '')
        break
        ;;
      $'\033')
        if ! IFS= read -r -s -n 2 -u "$tty_fd" key; then
          status=1
          break
        fi
        case "$key" in
          '[A')
            if [ "$selected" -eq 0 ]; then
              selected=$((count - 1))
            else
              selected=$((selected - 1))
            fi
            ;;
          '[B')
            if [ "$selected" -eq $((count - 1)) ]; then
              selected=0
            else
              selected=$((selected + 1))
            fi
            ;;
        esac
        ;;
      k)
        if [ "$selected" -eq 0 ]; then
          selected=$((count - 1))
        else
          selected=$((selected - 1))
        fi
        ;;
      j)
        if [ "$selected" -eq $((count - 1)) ]; then
          selected=0
        else
          selected=$((selected + 1))
        fi
        ;;
    esac

    printf '\033[%dA' "$count" >&"$tty_fd"
  done

  exec {tty_fd}<&-
  if [ "$status" -eq 0 ]; then
    SERIAL="${DEVICE_SERIALS[$selected]}"
  fi
  return "$status"
}

if [ -z "$SERIAL" ]; then
  declare -a DEVICE_SERIALS=()
  declare -a DEVICE_LABELS=()
  while IFS=$'\t' read -r device_serial device_label; do
    [ -n "$device_serial" ] || continue
    DEVICE_SERIALS+=("$device_serial")
    DEVICE_LABELS+=("$device_label")
  done < <(
    adb devices -l | awk '
      NR > 1 && $2 == "device" {
        label = ""
        for (i = 3; i <= NF; i++) {
          if ($i ~ /^model:/) {
            sub(/^model:/, "", $i)
            gsub(/_/, " ", $i)
            label = $i
            break
          }
        }
        if (label == "") label = "unknown device"
        print $1 "\t" label " (" $1 ")"
      }
    '
  )

  device_count="${#DEVICE_SERIALS[@]}"
  if [ "$device_count" -eq 0 ]; then
    echo "no connected devices found" >&2
    adb devices
    exit 2
  elif [ "$device_count" -eq 1 ]; then
    SERIAL="${DEVICE_SERIALS[0]}"
  else
    pick_device
  fi
fi

echo "== installing on $SERIAL…"
adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell pm grant dev.scoutr.app android.permission.POST_NOTIFICATIONS || true
adb -s "$SERIAL" shell am start -n dev.scoutr.app/.MainActivity
echo "== done — open the app, then Connect → Scan QR code (run scripts/pair.sh on the host)"
