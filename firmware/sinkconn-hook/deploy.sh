#!/usr/bin/env bash
# Dev-iteration deploy: push wrapper + hook lib to /data/local/diy-overlay on glasses,
# set SELinux xattrs, reboot. Assumes the DIY overlay engine is already installed
# in super_4 and the baked init.rc contains the post-fs-data binds for these paths.
#
# First-time install (cold boot with no overlay engine yet) requires instead running
# root-firmware.sh -> qdl flash; after that, this script is all you need.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WRAPPER="$HERE/build/app_process64"
HOOK="$HERE/build/libsinkconn_hook.so"
SERIAL="${GLASSES_SERIAL:-}"

if [ ! -f "$WRAPPER" ] || [ ! -f "$HOOK" ]; then
    echo "Missing build artefacts; run ./build.sh first." >&2
    exit 1
fi

adb_g() { adb -s "$SERIAL" "$@"; }

echo "[deploy] Checking device ..."
adb_g wait-for-device
adb_g shell 'id' >/dev/null

REMOTE_DIR_BIN=/data/local/diy-overlay/system/bin
REMOTE_DIR_LIB=/data/local/diy-overlay/system/lib64

echo "[deploy] Creating overlay directories ..."
adb_g shell "su -c 'mkdir -p $REMOTE_DIR_BIN $REMOTE_DIR_LIB'"

echo "[deploy] Pushing wrapper to $REMOTE_DIR_BIN/app_process64 ..."
adb_g push "$WRAPPER" "/data/local/tmp/app_process64"
adb_g shell "su -c 'cp /data/local/tmp/app_process64 $REMOTE_DIR_BIN/app_process64 && \
                    chmod 755 $REMOTE_DIR_BIN/app_process64 && \
                    chcon u:object_r:zygote_exec:s0 $REMOTE_DIR_BIN/app_process64'"

echo "[deploy] Pushing hook lib to $REMOTE_DIR_LIB/libsinkconn_hook.so ..."
adb_g push "$HOOK" "/data/local/tmp/libsinkconn_hook.so"
adb_g shell "su -c 'cp /data/local/tmp/libsinkconn_hook.so $REMOTE_DIR_LIB/libsinkconn_hook.so && \
                    chmod 644 $REMOTE_DIR_LIB/libsinkconn_hook.so && \
                    chcon u:object_r:system_lib_file:s0 $REMOTE_DIR_LIB/libsinkconn_hook.so'"

echo "[deploy] Removing adb staging copies ..."
adb_g shell 'rm -f /data/local/tmp/app_process64 /data/local/tmp/libsinkconn_hook.so'

echo "[deploy] Arming prop so post-fs-data setprop is not strictly required this boot ..."
adb_g shell 'su -c "setprop persist.vendor.bt.a2dp.sink_conn 64"'

echo "[deploy] Rebooting glasses ..."
adb_g reboot
echo ""
echo "Reboot issued. Wait for sys.boot_completed then verify with:"
echo "  adb -s $SERIAL shell dumpsys bluetooth_manager | grep -iE 'sink|max'"
echo "  adb -s $SERIAL shell 'grep libsinkconn_hook /proc/\$(pidof com.android.bluetooth)/maps'"
