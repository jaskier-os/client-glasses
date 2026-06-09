#!/bin/bash
set -e

# Deploy glasses app + bt-manager + capture + filesync: build debug, install via ADB.
# Usage: bash deploy-to-glasses.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GLASSES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_APK="$GLASSES_DIR/app/build/outputs/apk/debug/app-debug.apk"
BT_MGR_APK="$GLASSES_DIR/bt-manager/build/outputs/apk/debug/bt-manager-debug.apk"
CAPTURE_APK="$GLASSES_DIR/capture/build/outputs/apk/debug/capture-debug.apk"
FILESYNC_APK="$GLASSES_DIR/filesync/build/outputs/apk/debug/filesync-debug.apk"

# --- Build all modules ---
echo "Building glasses debug (bt-manager + capture + filesync + app)..."
BUILD_LOG=$(mktemp)
if ! "$GLASSES_DIR/gradlew" -p "$GLASSES_DIR" \
        :bt-manager:assembleDebug \
        :capture:assembleDebug \
        :filesync:assembleDebug \
        :app:assembleDebug > "$BUILD_LOG" 2>&1; then
    echo "BUILD FAILED. Log:"
    cat "$BUILD_LOG"
    rm -f "$BUILD_LOG"
    exit 1
fi
rm -f "$BUILD_LOG"
echo "Build OK."

for apk_path in "$BT_MGR_APK" "$CAPTURE_APK" "$FILESYNC_APK" "$APP_APK"; do
    if [ ! -f "$apk_path" ]; then
        echo "APK not found after build: $apk_path"
        exit 1
    fi
done

# --- Try ADB direct install first ---

GLASSES_SERIAL=""
while IFS= read -r line; do
    serial=$(echo "$line" | awk '$2=="device" {print $1}')
    [ -z "$serial" ] && continue
    model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    if [ "$model" = "RG-glasses" ]; then
        GLASSES_SERIAL="$serial"
        break
    fi
done < <(adb devices 2>/dev/null)

if [ -n "$GLASSES_SERIAL" ]; then
    echo "Glasses found via ADB ($GLASSES_SERIAL), installing directly..."

    # Any package shipping a privapp-permissions XML in /system/etc/permissions/
    # MUST be installed via the /system/priv-app/ overlay slot. A /data/app/
    # install (plain `adb install -r`) shadows the priv-app slot and PMS refuses
    # to apply the privapp grants. Without those grants:
    #   listener  : setScanMode(DISCOVERABLE) -> SecurityException; pairing dies.
    #   bt-manager: privileged BT bridge ops fail.
    #   filesync  : setWifiEnabled / createGroup return false; audio sync stalls.
    # The overlay slot is /data/local/diy-overlay/system/priv-app/<pkg>/<apk>,
    # bind-mounted onto /system/priv-app/<pkg>/<apk> at post-fs-data by
    # /system/bin/diy-overlay.sh. After replacing the overlay APK we MUST reboot
    # so PMS rescans /system/priv-app and applies the privapp-permissions XML.
    #
    # Push pattern: stage to /data/local/tmp, then `cat > target` (in-place
    # rewrite). Direct `adb push` to the overlay path silently no-ops when the
    # previous boot's bind-mount is still active (see root-firmware.sh
    # push_overlay_apk for the full diagnosis).
    push_priv_overlay() {
        local pkg="$1" apk_local="$2" apk_name="$3"
        local overlay_dir="/data/local/diy-overlay/system/priv-app/$pkg"
        local target="$overlay_dir/$apk_name"
        local stage="/data/local/tmp/$apk_name"

        local local_hash remote_hash
        local_hash="$(sha256sum "$apk_local" | cut -d' ' -f1)"
        remote_hash="$(adb -s "$GLASSES_SERIAL" shell "sha256sum $target 2>/dev/null | cut -d' ' -f1" | tr -d '\r')"
        if [ "$local_hash" = "$remote_hash" ]; then
            # Even if hash matches the overlay, a stale /data/app sideload may
            # be shadowing the priv-app slot from a prior buggy deploy. Drop it
            # and force a reboot so PMS rescans /system/priv-app cleanly.
            if adb -s "$GLASSES_SERIAL" shell "pm path $pkg 2>/dev/null" | grep -q '/data/app/'; then
                echo "${pkg} APK unchanged at overlay slot but shadowed by /data/app -- removing shadow."
                adb -s "$GLASSES_SERIAL" uninstall "$pkg" >/dev/null 2>&1 || true
                return 0
            fi
            echo "${pkg} APK unchanged at overlay slot, skipping push."
            return 1
        fi

        adb -s "$GLASSES_SERIAL" shell "mkdir -p $overlay_dir" >/dev/null
        adb -s "$GLASSES_SERIAL" push "$apk_local" "$stage" >/dev/null
        adb -s "$GLASSES_SERIAL" shell "cat $stage > $target && chmod 0644 $target && rm -f $stage"
        local got
        got="$(adb -s "$GLASSES_SERIAL" shell "sha256sum $target" | cut -d' ' -f1 | tr -d '\r')"
        if [ "$local_hash" != "$got" ]; then
            echo "${pkg} OVERLAY PUSH FAILED (sha256 mismatch want=$local_hash got=$got -- in-place rewrite did not stick)"
            exit 1
        fi
        # Drop any /data/app shadow left from older deploys.
        if adb -s "$GLASSES_SERIAL" shell "pm path $pkg 2>/dev/null" | grep -q '/data/app/'; then
            echo "removing sideloaded $pkg from /data/app"
            adb -s "$GLASSES_SERIAL" uninstall "$pkg" >/dev/null 2>&1 || true
        fi
        echo "${pkg} APK pushed to priv-app overlay slot."
        return 0
    }

    PRIV_PUSHED=0
    push_priv_overlay com.repository.glasses.btmanager "$BT_MGR_APK"  btmanager.apk && PRIV_PUSHED=1
    push_priv_overlay com.repository.glasses.filesync  "$FILESYNC_APK" filesync.apk  && PRIV_PUSHED=1
    push_priv_overlay com.repository.glasses.listener  "$APP_APK"     listener.apk  && PRIV_PUSHED=1

    # capture has no privapp-permissions XML; plain adb install is correct.
    if ! adb -s "$GLASSES_SERIAL" install -r "$CAPTURE_APK"; then
        echo "capture INSTALL FAILED."
        exit 1
    fi
    echo "capture installed."

    # Reboot so post-fs-data re-binds the new priv-app APKs and PMS rescans
    # /system/priv-app applying their privapp-permissions XMLs. Bind mount
    # targets the overlay file by inode; the in-place cat-rewrite preserved the
    # inode but the running mount still points at pre-rewrite content until
    # post-fs-data re-binds. Reparse + re-bind both happen at boot.
    if [ "$PRIV_PUSHED" = "1" ]; then
        echo "Priv-app APKs updated -- rebooting glasses so bind-mounts and privapp permissions re-apply..."
        adb -s "$GLASSES_SERIAL" reboot
        adb -s "$GLASSES_SERIAL" wait-for-device
        bc=""
        for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24; do
            bc="$(adb -s "$GLASSES_SERIAL" shell 'getprop sys.boot_completed' 2>/dev/null | tr -d '\r\n' || true)"
            [ "$bc" = "1" ] && break
            sleep 5
        done
        if [ "$bc" != "1" ]; then
            echo "Glasses did not finish booting (sys.boot_completed != 1). Aborting permission grants."
            exit 1
        fi

        # Verify each priv-app package resolves to /system/priv-app (not
        # /data/app). PMS scans /priv-app asynchronously after boot_completed,
        # so retry briefly.
        for pkg in com.repository.glasses.btmanager com.repository.glasses.filesync com.repository.glasses.listener; do
            ok=""
            path=""
            for _ in 1 2 3 4 5 6 7 8 9 10; do
                path="$(adb -s "$GLASSES_SERIAL" shell "pm path $pkg 2>/dev/null" | tr -d '\r')"
                case "$path" in
                    *"/system/priv-app/$pkg"*) ok=1; break ;;
                    *"/data/app"*)
                        echo "$pkg shadowed by /data/app sideload after reboot: $path"
                        exit 1
                        ;;
                esac
                sleep 2
            done
            if [ -z "$ok" ]; then
                echo "$pkg not installed from /system/priv-app -- pm path returned: '${path:-<empty>}'."
                exit 1
            fi
            echo "  $pkg -> $path"
        done
    fi

    # Runtime grants / appops / system roles. Idempotent. Applied AFTER any
    # reboot so newly-rescanned priv-app packages exist in PMS. MUST be kept in
    # sync with root-firmware.sh deploy_runtime_overlay grants section.
    apply_runtime_grants() {
        local serial="$1"
        # MANAGE_EXTERNAL_STORAGE: capture + filesync write to /sdcard/DCIM/Repository/.
        for pkg in com.repository.glasses.capture com.repository.glasses.filesync; do
            adb -s "$serial" shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
        done
        # WRITE_SETTINGS: listener writes screen_brightness; appops since the
        # special-permission UI prompt isn't reachable from a headless service.
        adb -s "$serial" shell appops set com.repository.glasses.listener WRITE_SETTINGS allow 2>/dev/null || true
        # BLUETOOTH_SCAN/CONNECT: Lone mode scans for nearby BT devices from the listener
        # process. Runtime perms (targetSdk 34); no UI prompt reachable headless.
        for perm in \
            android.permission.BLUETOOTH_SCAN \
            android.permission.BLUETOOTH_CONNECT; do
            adb -s "$serial" shell pm grant com.repository.glasses.listener "$perm" 2>/dev/null || true
        done
        # filesync signature-level Wi-Fi perms (rooted firmware accepts pm grant).
        for perm in \
            android.permission.NETWORK_SETTINGS \
            android.permission.OVERRIDE_WIFI_CONFIG \
            android.permission.WRITE_SECURE_SETTINGS; do
            adb -s "$serial" shell pm grant com.repository.glasses.filesync "$perm" 2>/dev/null || true
        done
        # filesync runtime location perms (LocalOnlyHotspot prerequisite).
        for perm in \
            android.permission.ACCESS_FINE_LOCATION \
            android.permission.ACCESS_COARSE_LOCATION; do
            adb -s "$serial" shell pm grant com.repository.glasses.filesync "$perm" 2>/dev/null || true
        done
        # NLS for MediaSessionMonitor.
        adb -s "$serial" shell cmd notification allow_listener \
            com.repository.glasses.listener/com.repository.glasses.listener.media.MediaNotificationListener 2>/dev/null || true
        # Accessibility service for screen-off on double-tap.
        # NOTE: do NOT fire-and-forget here. A bare `settings put` right after an
        # install often no-ops because AccessibilityManager hasn't finished
        # processing the (re)scanned listener package yet -- the write "succeeds"
        # but the value never persists / the service never binds, silently
        # breaking double-tap-to-screen-off. The verify+retry loop below
        # (mirrors root-firmware.sh post-flash) is REQUIRED, not optional.
        adb -s "$serial" shell settings put secure enabled_accessibility_services \
            com.repository.glasses.listener/com.repository.glasses.listener.service.ScreenOffAccessibilityService 2>/dev/null || true
        adb -s "$serial" shell settings put secure accessibility_enabled 1 2>/dev/null || true
        # Default home.
        adb -s "$serial" shell cmd role add-role-holder android.app.role.HOME com.repository.glasses.listener 2>/dev/null || true
    }
    apply_runtime_grants "$GLASSES_SERIAL"
    echo "Runtime grants applied."

    # Verify the accessibility service ACTUALLY bound (the one grant that
    # routinely fails to stick). Retry the enable until `dumpsys accessibility`
    # lists it under Enabled services, or warn loudly. Without this, a deploy
    # can report success while double-tap-to-screen-off stays dead. Kept in
    # sync with the equivalent block in root-firmware.sh deploy_runtime_overlay.
    a11y_svc="com.repository.glasses.listener/com.repository.glasses.listener.service.ScreenOffAccessibilityService"
    a11y_bound=""
    for _ in 1 2 3 4 5 6 7 8; do
        bound="$(adb -s "$GLASSES_SERIAL" shell "dumpsys accessibility | grep -A2 'Enabled services' | grep -o ScreenOffAccessibilityService" 2>/dev/null | tr -d '\r')"
        if [ -n "$bound" ]; then a11y_bound=1; break; fi
        adb -s "$GLASSES_SERIAL" shell "settings put secure enabled_accessibility_services '$a11y_svc'; settings put secure accessibility_enabled 1" >/dev/null 2>&1 || true
        sleep 2
    done
    if [ -n "$a11y_bound" ]; then
        echo "Accessibility service bound -- double-tap screen-off live."
    else
        echo "WARNING: ScreenOffAccessibilityService did NOT bind after retries -- double-tap screen-off will not work. Re-run the deploy or check listener install." >&2
    fi

    # Write BT MAC to a file readable by bt-manager (all ContentProvider/Settings
    # approaches blocked by ROM permission checks for app UIDs).
    adb -s "$GLASSES_SERIAL" shell "settings get secure bluetooth_address > /data/local/tmp/glasses_bt_mac && chmod 644 /data/local/tmp/glasses_bt_mac" 2>/dev/null || true

    echo "Done (ADB direct install)."
    exit 0
fi

echo "Glasses not found via ADB. Connect the glasses over USB and retry."
exit 1
