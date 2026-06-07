#!/usr/bin/env bash
# Push the built daemon to the glasses and (optionally) start it.
# Usage:
#   ./deploy.sh              -- push only, no start
#   ./deploy.sh run          -- push + kill existing + start in background with --debug
#   ./deploy.sh run-fg       -- push + run in foreground (blocks terminal)
#   ./deploy.sh stop         -- kill any running instance
#   ./deploy.sh logs         -- tail stderr from the running instance
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${GLASSES_SERIAL:-}"
ADB="adb -s $SERIAL"
BIN_NAME="rokid-touchpad-daemon"
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
    $ADB push "$LOCAL_BIN" "$REMOTE_BIN"
    $ADB shell chmod 755 "$REMOTE_BIN"
    $ADB shell "pkill -f $BIN_NAME >/dev/null 2>&1 || true"
    # Double-fork via setsid + & detaches fully from the adb shell session.
    $ADB shell "setsid $REMOTE_BIN --debug < /dev/null > $REMOTE_LOG 2>&1 &"
    sleep 0.3
    echo "Started. Tail: $0 logs"
    ;;
run-fg)
    $ADB push "$LOCAL_BIN" "$REMOTE_BIN"
    $ADB shell chmod 755 "$REMOTE_BIN"
    $ADB shell "pkill -f $BIN_NAME >/dev/null 2>&1 || true"
    $ADB shell "$REMOTE_BIN --debug"
    ;;
stop)
    $ADB shell "pkill -f $BIN_NAME >/dev/null 2>&1 || true"
    echo "Stopped."
    ;;
logs)
    $ADB shell "tail -f $REMOTE_LOG"
    ;;
*)
    echo "unknown action: $1" >&2
    echo "usage: $0 [push|run|run-fg|stop|logs]" >&2
    exit 2
    ;;
esac
