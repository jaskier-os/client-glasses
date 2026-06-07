#!/usr/bin/env bash
# sthal/deploy-privapp.sh
#
# DANGEROUS: installs the glasses listener APK as a PRIVILEGED system app by
# dropping it into /data/local/diy-overlay/system/priv-app/. On reboot the DIY
# overlay bind-mounts that tree over /system, so from PackageManager's point of
# view the APK lives in /system/priv-app/com.repository.glasses.listener/ and
# is treated as a platform-privileged package.
#
# Why this is needed: deploy.sh pushes priv-permissions.xml which grants
#   android.permission.CAPTURE_AUDIO_HOTWORD
# to com.repository.glasses.listener. That grant is INERT unless the APK
# itself is under /system/priv-app/. Without this step the HAL path is fully
# deployed but dormant -- framework denies the reflection call into
# SoundTriggerManager.attachGenericSoundModel because the caller is still a
# non-privileged user-installed app.
#
# ====================================================================
# RISK PROFILE (read before running):
# ====================================================================
# Once the overlay is in place and the device reboots, the listener APK
# runs as a PRIVILEGED system app. That means:
#   - It gets signature|privileged permissions listed in priv-permissions.xml
#   - Any regression / bug / crash in the APK now happens inside a privileged
#     process context. A bug here is more impactful than the same bug in a
#     user-installed build.
#   - The priv-app APK SHADOWS the user-installed copy after reboot. Running
#     `pm install -r` of the same package *after* this step can cause signing
#     conflicts (privapp is loaded first; user install is rejected if the
#     cert chain differs in any edge case).
#
# Recommended workflow once this script has been run once:
#   - For subsequent APK rebuilds, use THIS script, not `deploy-to-glasses.sh`.
#   - Rebuild the APK (adb install path in deploy-to-glasses.sh is fine for
#     building, just skip the install step), then re-run deploy-privapp.sh.
#   - Reboot to pick up the new APK. The overlay bind-mount re-materialises
#     the priv-app layout fresh at every post-fs-data.
#
# To roll back to a non-privileged install, see ROLLBACK section at the
# bottom of this script.
# ====================================================================
#
# Gate: set GLASSES_PRIVAPP_INSTALL=1 to proceed. This is deliberately a
# SEPARATE env var from GLASSES_STHAL_DEPLOY because privapp install is a
# distinct decision with its own risk profile -- you might want the APK
# promoted to privileged for testing CAPTURE_AUDIO_HOTWORD without deploying
# a new HAL build, or vice versa.

set -euo pipefail

if [[ "${GLASSES_PRIVAPP_INSTALL:-0}" != "1" ]]; then
    cat >&2 <<EOF
deploy-privapp.sh refuses to run without explicit authorization.
  This installs com.repository.glasses.listener as a PRIVILEGED system app
  by dropping its APK into /data/local/diy-overlay/system/priv-app/. After
  reboot the app runs with signature|privileged permissions -- any bug in
  the APK hits with elevated privileges.
  To proceed: GLASSES_PRIVAPP_INSTALL=1 bash deploy-privapp.sh
EOF
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STHAL_DIR="${SCRIPT_DIR}"
GLASSES_DIR="$(cd "${STHAL_DIR}/.." && pwd)"
PKG="com.repository.glasses.listener"
APK_SRC="${GLASSES_APK_PATH:-${GLASSES_DIR}/app/build/outputs/apk/debug/app-debug.apk}"
SERIAL="${ADB_SERIAL:-}"
if [ -z "$SERIAL" ]; then SERIAL="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | head -1)"; fi
OVERLAY_DIR="/data/local/diy-overlay/system/priv-app/${PKG}"
OVERLAY_APK="${OVERLAY_DIR}/listener.apk"

echo "== Verify APK =="
if [[ ! -f "${APK_SRC}" ]]; then
    cat >&2 <<EOF
ERROR: APK not found at ${APK_SRC}
  Build it first via the normal glasses deploy pipeline:
  (if no device is connected the script still builds the APK and exits
  cleanly), or run gradle directly:
    ${GLASSES_DIR}/gradlew -p ${GLASSES_DIR} :app:assembleDebug
  Then re-run this script. Override path with GLASSES_APK_PATH=...
EOF
    exit 2
fi
apk_size=$(stat -c%s "${APK_SRC}")
echo "  APK: ${APK_SRC} (${apk_size} bytes)"

echo "== Verify adb device =="
adb start-server >/dev/null 2>&1 || true
if ! adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "${SERIAL}"; then
    cat >&2 <<EOF
ERROR: glasses serial ${SERIAL} not present / not authorized.
  Connect via USB and run \`adb devices\` to verify. Override with
  ADB_SERIAL=<serial> if your glasses report a different number.
EOF
    exit 3
fi
adb -s "${SERIAL}" wait-for-device
echo "  device ${SERIAL} ready"

# NOTE: we intentionally DO NOT run `pm uninstall com.repository.glasses.listener`
# here. User policy forbids destructive app-state commands (pm uninstall /
# pm clear) unless explicitly requested. The existing user-installed copy is
# harmless -- after reboot PackageManager prefers the priv-app version and
# the user-install becomes shadowed.

echo "== Overlay dirs =="
adb -s "${SERIAL}" shell "mkdir -p ${OVERLAY_DIR}"

echo "== Push APK to priv-app overlay =="
echo "  src: ${APK_SRC}"
echo "  dst: ${OVERLAY_APK}"
adb -s "${SERIAL}" push "${APK_SRC}" "${OVERLAY_APK}"

# Android is picky about priv-app mode bits. 644 on the APK, 755 on the dir.
adb -s "${SERIAL}" shell "chmod 755 ${OVERLAY_DIR} && chmod 644 ${OVERLAY_APK}"

echo "== Verify overlay layout =="
adb -s "${SERIAL}" shell "ls -la ${OVERLAY_DIR}"

cat <<EOF

== Privapp install staged ==

REBOOT REQUIRED. The DIY overlay only activates at post-fs-data, so the APK
is not yet seen as a privileged system app. Reboot when you are ready:

  adb -s ${SERIAL} reboot

After reboot, verify CAPTURE_AUDIO_HOTWORD is granted:

  adb -s ${SERIAL} shell 'dumpsys package ${PKG} | grep -iE "CAPTURE_AUDIO_HOTWORD|privileged"'

Expected: a line like
  android.permission.CAPTURE_AUDIO_HOTWORD: granted=true
and somewhere in the package dump:
  privileged=true  (or  pkgFlags=... PRIVILEGED ...)

If granted=false after reboot, check:
  1. priv-permissions.xml made it to /system/etc/permissions/ (deploy.sh
     pushes this; re-run it if missing).
  2. The overlay bind-mount actually fired:
       adb shell 'mount | grep priv-app'
     should show a bind on /system/priv-app/${PKG}.
  3. logcat during boot for "PackageManager" errors about ${PKG}.

This script did NOT reboot the device. Your call when.
EOF

# ====================================================================
# ROLLBACK
# ====================================================================
# To remove the privapp install and revert to the regular user-installed
# copy of the APK:
#
#   adb -s ${SERIAL} shell "rm -rf ${OVERLAY_DIR}"
#   adb -s ${SERIAL} reboot
#
# After reboot, PackageManager no longer sees the APK under /system/priv-app,
# so the user-installed copy (from `pm install` / deploy-to-glasses.sh) is
# used instead. CAPTURE_AUDIO_HOTWORD reverts to denied.
#
# The priv-permissions.xml pushed by deploy.sh is harmless to leave in place;
# it only grants the permission when an APK with the matching package is
# present in /system/priv-app/. Remove it with:
#
#   adb -s ${SERIAL} shell "rm /data/local/diy-overlay/system/etc/permissions/privapp-permissions-${PKG}.xml"
#   adb -s ${SERIAL} reboot
# ====================================================================
