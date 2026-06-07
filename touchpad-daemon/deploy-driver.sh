#!/usr/bin/env bash
# Deploy the patched psoc_ts_drv_right.ko to the glasses and reload it.
# Usage:
#   ./deploy-driver.sh                 patch + push + reload + verify
#   ./deploy-driver.sh revert          load the stock vendor_dlkm module
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${GLASSES_SERIAL:-}"
ADB="adb -s $SERIAL"
STOCK_ON_HOST=/tmp/glasses-touchpad/psoc_ts_drv_right.ko
PATCHED_ON_HOST="$HERE/build/psoc_ts_drv_right.ko"
REMOTE_PATH=/data/local/tmp/psoc_ts_drv_right.ko

reload() {
    local LOCAL_KO="$1"
    local LABEL="$2"
    echo "=== Pushing $LABEL ==="
    $ADB push "$LOCAL_KO" "$REMOTE_PATH"
    echo "=== Stopping the daemon so event1 is free ==="
    $ADB shell 'pkill -f rokid-touchpad-daemon >/dev/null 2>&1 || true'
    sleep 0.2
    echo "=== rmmod old driver ==="
    $ADB shell 'rmmod psoc_ts_drv_right 2>&1' || true
    sleep 0.2
    echo "=== insmod $LABEL ==="
    $ADB shell "insmod $REMOTE_PATH 2>&1" || {
        echo "insmod FAILED; dmesg tail:"
        $ADB shell 'dmesg 2>/dev/null | tail -20'
        exit 1
    }
    echo "=== probe state ==="
    $ADB shell 'lsmod | grep psoc'
    sleep 0.5
    $ADB shell 'dmesg 2>/dev/null | grep -iE "psoc|Rokid,PSOC" | tail -15'
    echo "=== input devices ==="
    $ADB shell 'ls /dev/input/; cat /proc/bus/input/devices | grep -A3 "Rokid"'
}

case "${1:-patch}" in
patch)
    if [ ! -f "$PATCHED_ON_HOST" ]; then
        echo "No patched .ko at $PATCHED_ON_HOST -- run patch-driver.py first" >&2
        exit 1
    fi
    reload "$PATCHED_ON_HOST" "PATCHED driver"
    ;;
revert)
    if [ ! -f "$STOCK_ON_HOST" ]; then
        $ADB pull /vendor_dlkm/lib/modules/psoc_ts_drv_right.ko "$STOCK_ON_HOST"
    fi
    reload "$STOCK_ON_HOST" "STOCK driver"
    ;;
*)
    echo "usage: $0 [patch|revert]" >&2
    exit 2
    ;;
esac
