#!/usr/bin/env bash
# Build a Scoutr APK from a git commit and share it over a public
# Cloudflare quick tunnel so it can be downloaded from any device.
#
# Usage:
#   scripts/build-apk.sh                  # latest commit on the current branch
#   scripts/build-apk.sh <ref>            # any commit, branch, or tag
#
# The APK is built in a throwaway git worktree (your working tree is never
# touched), copied to ~/.cache/scoutr-apk/, and served via cloudflared.
# The script prints a public download URL and stays in the foreground;
# Ctrl-C stops the tunnel, kills the file server, and removes the worktree.
set -euo pipefail
cd "$(dirname "$0")/.."

REF="${1:-HEAD}"

for cmd in cloudflared python3 git; do
  command -v "$cmd" >/dev/null || { echo "error: $cmd not found" >&2; exit 1; }
done

SHA=$(git rev-parse --verify "$REF^{commit}")
SHORT=$(git rev-parse --short "$SHA")
SUBJECT=$(git show -s --format=%s "$SHA")
DATE=$(git show -s --format=%cs "$SHA")

WORKTREE=/tmp/scoutr-apk-worktree
SERVE_DIR="$HOME/.cache/scoutr-apk"
APK_NAME="scoutr-${SHORT}-debug.apk"

cleanup() {
  trap - EXIT INT TERM
  [ -n "${HTTP_PID:-}" ] && kill "$HTTP_PID" 2>/dev/null || true
  [ -n "${TUNNEL_PID:-}" ] && kill "$TUNNEL_PID" 2>/dev/null || true
  git worktree remove --force "$WORKTREE" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "== resolving $REF -> $SHORT ($DATE): $SUBJECT"

git worktree remove --force "$WORKTREE" 2>/dev/null || true
git worktree add --detach "$WORKTREE" "$SHA" >/dev/null

echo "== building (first build in a fresh worktree may take a few minutes)"
(
  cd "$WORKTREE/android"
  ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}" ./gradlew assembleDebug
)

APK_SRC="$WORKTREE/android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK_SRC" ] || { echo "error: build produced no APK at $APK_SRC" >&2; exit 1; }
mkdir -p "$SERVE_DIR"
cp "$APK_SRC" "$SERVE_DIR/$APK_NAME"

PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$SERVE_DIR" \
  >/tmp/scoutr-apk-http.log 2>&1 &
HTTP_PID=$!
sleep 0.5
kill -0 "$HTTP_PID" 2>/dev/null || { echo "error: file server died:" >&2; cat /tmp/scoutr-apk-http.log >&2; exit 1; }

echo "== starting cloudflare quick tunnel"
cloudflared tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate \
  >/tmp/scoutr-apk-tunnel.log 2>&1 &
TUNNEL_PID=$!

URL=""
for _ in $(seq 1 60); do
  URL=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' /tmp/scoutr-apk-tunnel.log 2>/dev/null | head -1 || true)
  [ -n "$URL" ] && break
  kill -0 "$TUNNEL_PID" 2>/dev/null || break
  sleep 0.5
done
if [ -z "$URL" ]; then
  echo "error: tunnel failed to start:" >&2
  tail -5 /tmp/scoutr-apk-tunnel.log >&2
  exit 1
fi

echo
echo "== APK ready: $SERVE_DIR/$APK_NAME"
echo "   direct download: $URL/$APK_NAME"
echo "   file listing:    $URL/"
echo
echo "Public URL - anyone with the link can download. Ctrl-C to stop the tunnel."
wait "$TUNNEL_PID"
