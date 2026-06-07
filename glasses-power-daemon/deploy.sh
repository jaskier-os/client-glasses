#!/usr/bin/env bash
# Push the built glasses power daemon and (optionally) start it.
# Usage:
#   ./deploy.sh              -- push only, no start
#   ./deploy.sh push         -- same as above
#   ./deploy.sh run          -- push + kill existing + start detached in background
#   ./deploy.sh stop         -- kill any running instance
#   ./deploy.sh logs         -- tail log file from the running instance
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${GLASSES_SERIAL:-}"
ADB="adb -s $SERIAL"
BIN_NAME="glasses-power-daemon"
LOCAL_BIN="$HERE/build/$BIN_NAME"
REMOTE_DIR="/data/local/tmp"
REMOTE_BIN="$REMOTE_DIR/$BIN_NAME"
REMOTE_LOG="$REMOTE_DIR/$BIN_NAME.log"

if [ ! -x "$LOCAL_BIN" ]; then
    echo "No built binary at $LOCAL_BIN. Run ./build.sh first." >&2
    exit 1
fi

case "${1:-push}" in
push)
    $ADB push "$LOCAL_BIN" "$REMOTE_BIN"
    $ADB shell chmod 755 "$REMOTE_BIN"
    echo "Pushed to $REMOTE_BIN"
    ;;
run)
    # Ensure adbd runs as root so we can kill previous (root-owned) instances.
    adb -s "$SERIAL" root >/dev/null 2>&1 || true
    sleep 0.3
    $ADB push "$LOCAL_BIN" "$REMOTE_BIN"
    $ADB shell chmod 755 "$REMOTE_BIN"
    # Kill ALL existing daemons aggressively (SIGKILL, by full path+name).
    $ADB shell "pkill -9 -f 'glasses-power-daemon' >/dev/null 2>&1 || true"
    # Small grace window so flock from the old process is released cleanly
    # before the new one tries to acquire it.
    sleep 0.5
    # setsid + & detaches fully from the adb shell session. No --config flag:
    # the daemon's compiled-in default points at /data/local/diy-overlay/
    # which is the only path the listener app can write to.
    $ADB shell "setsid $REMOTE_BIN < /dev/null >> $REMOTE_LOG 2>&1 &"
    sleep 0.3
    echo "Started. Tail: $0 logs"
    ;;
stop)
    adb -s "$SERIAL" root >/dev/null 2>&1 || true
    sleep 0.3
    $ADB shell "pkill -9 -f 'glasses-power-daemon' >/dev/null 2>&1 || true"
    echo "Stopped."
    ;;
logs)
    $ADB shell "tail -f $REMOTE_LOG"
    ;;
*)
    echo "unknown action: $1" >&2
    echo "usage: $0 [push|run|stop|logs]" >&2
    exit 2
    ;;
esac
