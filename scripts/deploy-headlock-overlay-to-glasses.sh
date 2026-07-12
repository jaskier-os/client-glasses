#!/usr/bin/env bash
# Build the headlock-overlay-test app and install/launch it on the glasses if connected.
# Standalone test app (see docs/plans/2026-07-12-headlocked-overlay-mockup-*.md).
# No emojis, no color codes.

set -u

PKG="com.repository.glasses.headlockoverlay"
ACTIVITY=".MainActivity"
GLASSES_MODEL="RG-glasses"

MODULE_DIR="/media/varingait/Lobotomite/Repository/AI/clients/glasses/headlock-overlay-test"
APK_PATH="${MODULE_DIR}/app/build/outputs/apk/debug/app-debug.apk"
BUILD_LOG="$(mktemp -t headlock-overlay-build.XXXXXX.log)"

echo "[headlock-overlay] Module: ${MODULE_DIR}"
echo "[headlock-overlay] Building :app:assembleDebug ..."

if ! ( cd "${MODULE_DIR}" && ./gradlew :app:assembleDebug ) > "${BUILD_LOG}" 2>&1; then
    echo "[headlock-overlay] BUILD FAILED. Tail of build log:"
    tail -n 80 "${BUILD_LOG}"
    echo "[headlock-overlay] (full log: ${BUILD_LOG})"
    exit 1
fi

echo "[headlock-overlay] Build OK. APK: ${APK_PATH}"

if [ ! -f "${APK_PATH}" ]; then
    echo "[headlock-overlay] ERROR: APK not found at ${APK_PATH}"
    exit 2
fi

# Find the connected glasses by product model (do not hard-code a serial).
GLASSES_SERIAL=""
while read -r line; do
    serial=$(echo "$line" | awk '$2=="device" {print $1}')
    [ -z "$serial" ] && continue
    model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    if [ "$model" = "${GLASSES_MODEL}" ]; then
        GLASSES_SERIAL="$serial"
        break
    fi
done < <(adb devices 2>/dev/null)

if [ -z "${GLASSES_SERIAL}" ]; then
    echo "[headlock-overlay] Glasses (${GLASSES_MODEL}) not connected; APK built at ${APK_PATH}"
    exit 0
fi

echo "[headlock-overlay] Glasses connected (${GLASSES_SERIAL})."
echo "[headlock-overlay] Force-stopping ${PKG} ..."
adb -s "${GLASSES_SERIAL}" shell am force-stop "${PKG}" >/dev/null 2>&1 || true

echo "[headlock-overlay] Installing APK (-r, keep data) ..."
if ! adb -s "${GLASSES_SERIAL}" install -r "${APK_PATH}"; then
    echo "[headlock-overlay] ERROR: install failed"
    exit 3
fi

echo "[headlock-overlay] Launching ${PKG}/${ACTIVITY} ..."
adb -s "${GLASSES_SERIAL}" shell am start -n "${PKG}/${ACTIVITY}"

echo "[headlock-overlay] Done."
