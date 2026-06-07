#!/bin/bash
# Pushes the NightVision UNet ONNX model to the glasses at the app-private
# external storage path where MainActivity.startNightVision() expects it.
#
# The ONNX file is ~31 MB. It lives here in the repo (NOT in the APK assets)
# because baking it into the APK bloats every deploy. It's pushed once per
# device; survives app reinstall; does NOT survive a /data wipe (push again
# after a factory reset).
#
# The load path inside the app is:
#   getExternalFilesDir(null)/nightvision_unet.onnx
# which maps to:
#   /sdcard/Android/data/com.repository.glasses.listener/files/nightvision_unet.onnx

set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-}"
SRC="$(dirname "$(readlink -f "$0")")/nightvision_unet.onnx"
DST="/sdcard/Android/data/com.repository.glasses.listener/files/nightvision_unet.onnx"

if [ ! -f "$SRC" ]; then
    echo "ERROR: source not found: $SRC" >&2
    exit 2
fi

echo "Source: $SRC ($(stat -c %s "$SRC") bytes)"
echo "Target: $DST on ADB serial $ADB_SERIAL"

if ! adb -s "$ADB_SERIAL" get-state >/dev/null 2>&1; then
    echo "ERROR: device $ADB_SERIAL not reachable via adb" >&2
    exit 3
fi

# Ensure target directory exists (it should, app creates it on first launch, but
# may not exist if NightVision has never been opened on this install).
adb -s "$ADB_SERIAL" shell "mkdir -p $(dirname "$DST")"
adb -s "$ADB_SERIAL" push "$SRC" "$DST"
adb -s "$ADB_SERIAL" shell "ls -la $DST"

echo "NightVision UNet model deployed."
