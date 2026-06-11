#!/usr/bin/env bash
# root-firmware.sh -- build the rooted super_4.img for the Rokid AR Lite.
#
# See OVERLAY-README.md for the architecture overview. This script is the
# single source of truth for what ends up baked into the system partition:
#   - Overlay engine (diy-overlay.{sh,rc})
#   - EDL helper (enter-edl)
#   - Touchpad bring-up nudge (touchpad-nudge.{sh,rc})
#   - sinkconn-hook (libsinkconn_hook.so + setenv LD_PRELOAD in zygote rc)
#   - a2dp-sink-conn persist.prop bootstrap
#   - Phase-3 content (sthal HAL + QNN runtime + wake-word models + privapp
#     permissions + listener APK stub)
#   - Patched psoc touchpad driver + rokid-touchpad-daemon
#   - Binary-patched build.prop (root/ADB flags) + bootstat.rc (SELinux
#     permissive)
#
# Inputs (read-only, from the OS cache os-cache/current/, populated by fetch-os.sh):
#     super_4.img, xbl_s_devprg_ns.melf, gpt_main0.bin, gpt_backup0.bin,
#     rawprogram0.xml, patch0.xml  (latter two referenced in flashing docs)
#
# Prerequisites built before running this script:
#     sinkconn-hook/build/libsinkconn_hook.so       ./sinkconn-hook/build.sh
#     AI/.../touchpad-daemon/build/rokid-touchpad-daemon
#     AI/.../touchpad-daemon/build/psoc_ts_drv_right.ko
#     AI/.../sthal/build/sound_trigger.primary.neo.so
#     ~/qairt/.../lib/aarch64-android/libQnn*.so  (Qualcomm AI runtime SDK)
#     AI/.../sthal/models/{melspectrogram,embedding_model,sireneviy}.bin
#     AI/.../sthal/priv-permissions.xml
#
# Output:
#     ./super_4.img           -- flashable, AVB hashtree regenerated
#     ./rawprogram_super4.xml -- minimal flash list (GPT + super_4 only)
#
# vbmeta*, misc, super_5, boot, dtbo are NEVER modified. The device's AVB
# "orange" state tolerates the dm-verity hashtree mismatch between our
# super_4 and stock vbmeta's descriptor.

set -euo pipefail

# ----- paths + constants ------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Stock images come from the local OS cache populated by fetch-os.sh
# (firmware/os-cache/current -> <version>). Override with STOCK_DIR=... if the
# extracted build lives elsewhere.
STOCK_DIR="${STOCK_DIR:-$SCRIPT_DIR/os-cache/current}"
if [ ! -f "$STOCK_DIR/super_4.img" ]; then
    echo "stock images not found in $STOCK_DIR" >&2
    echo "run:  bash $SCRIPT_DIR/fetch-os.sh   (downloads + extracts the OTA build)" >&2
    exit 1
fi
STOCK_DIR="$(cd "$STOCK_DIR" && pwd)"
SINKCONN_DIR="$SCRIPT_DIR/sinkconn-hook/build"
# This script lives inside the client-glasses repo at firmware/; the glasses app
# modules are the repo root (one level up).
GLASSES_CLIENT="$(cd "$SCRIPT_DIR/.." && pwd)"
FN_BUTTON_SRC="$GLASSES_CLIENT/test/fn-button-daemon.sh"

# system partition inside super_4.img (first $SYSTEM_IMAGE_SIZE bytes).
readonly SYSTEM_IMAGE_SIZE=880467968
# AVB salt used for /system hashtree on stock firmware -- must match so the
# kernel's dm-verity init accepts our regenerated tree.
readonly AVB_SALT="5a36ddf1a1f44639beb698dc3f36b40b952aadbccea531b35a4913b568050563"
readonly AVBTOOL="/tmp/avbtool"
readonly WORK_DIR="$(mktemp -d)"
readonly OVERLAY_DIR="$WORK_DIR/overlay"
readonly PSOC_SYSFS="/sys/devices/platform/soc/a90000.i2c/i2c-1/1-0008"

# Phase-3 source paths (can be overridden via env).
PHASE3_HAL="${PHASE3_HAL:-$GLASSES_CLIENT/sthal/build/sound_trigger.primary.neo.so}"
QNN_SDK_ROOT="${QNN_SDK_ROOT:-/home/varingait/qairt/2.45.0.260326}"
PHASE3_LIB_QNNHTP="$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtp.so"
PHASE3_LIB_QNNSYSTEM="$QNN_SDK_ROOT/lib/aarch64-android/libQnnSystem.so"
PHASE3_LIB_QNNHTPV73STUB="$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpV73Stub.so"
PHASE3_LIB_QNNHTPV73SKEL="$QNN_SDK_ROOT/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so"
PHASE3_MODELS_DIR="${PHASE3_MODELS_DIR:-$GLASSES_CLIENT/sthal/models}"
PHASE3_PRIVPERM_XML="${PHASE3_PRIVPERM_XML:-$GLASSES_CLIENT/sthal/priv-permissions.xml}"
FILESYNC_PRIVPERM_XML="${FILESYNC_PRIVPERM_XML:-$GLASSES_CLIENT/filesync/priv-permissions.xml}"
PATCHED_PSOC_KO="${PATCHED_PSOC_KO:-$GLASSES_CLIENT/touchpad-daemon/build/psoc_ts_drv_right.ko}"
TOUCHPAD_DAEMON="${TOUCHPAD_DAEMON_BIN:-$GLASSES_CLIENT/touchpad-daemon/build/rokid-touchpad-daemon}"
POWER_DAEMON="${POWER_DAEMON_BIN:-$GLASSES_CLIENT/glasses-power-daemon/build/glasses-power-daemon}"
# bt-manager APK -- now lives in /system/priv-app/ (was /system/app/). Required
# privileged for BLUETOOTH_PRIVILEGED, which gates BluetoothA2dpSink.connect()
# and BluetoothHeadsetClient.connect()/setConnectionPolicy() -- the auto-connect
# path that brings HFP+A2DP up after pairing so calls / audio actually flow.
# Uses the same Tier-2 stub pattern as listener/filesync: 1-byte stub baked
# into super_4 to reserve the priv-app directory inode, real APK pushed into
# /data/local/diy-overlay/ at post-flash, bind-mounted at post-fs-data.
BTMANAGER_APK="${BTMANAGER_APK:-$GLASSES_CLIENT/bt-manager/build/outputs/apk/debug/bt-manager-debug.apk}"
BTMANAGER_PRIVPERM_XML="${BTMANAGER_PRIVPERM_XML:-$GLASSES_CLIENT/bt-manager/priv-permissions.xml}"

# Tier-2 (runtime, /data/local/diy-overlay/) APK overlays. Both APKs are too
# large to fit in super_4 (listener: ~100MB debug, filesync: tens of MB), so
# super carries 1-byte stubs at /system/priv-app/<pkg>/<name>.apk and
# diy-overlay.rc bind-mounts the real APKs from /data/local/diy-overlay/ at
# post-fs-data. PackageManager only treats them as privileged apps (and
# applies their privapp-permissions XMLs -- e.g. BLUETOOTH_PRIVILEGED for
# the listener, required by setScanMode(DISCOVERABLE) i.e. the triple-press
# pairing path) when loaded from /system/priv-app. A plain `adb install -r`
# sideload to /data/app silently drops those grants. Always go through
# this overlay; bash root-firmware.sh --post-flash applies it.
LISTENER_APK="${LISTENER_APK:-$GLASSES_CLIENT/app/build/outputs/apk/debug/app-debug.apk}"
FILESYNC_APK="${FILESYNC_APK:-$GLASSES_CLIENT/filesync/build/outputs/apk/debug/filesync-debug.apk}"
ADB_SERIAL="${ADB_SERIAL:-}"

SYS_PREFIX=""   # "/system" or "" depending on super's internal layout

# ----- helpers ----------------------------------------------------------------

log()  { echo "[root-firmware] $*"; }
die()  { echo "[root-firmware] ERROR: $*" >&2; exit 1; }
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

require_tools() {
    for t in debugfs python3; do
        command -v "$t" >/dev/null 2>&1 || die "missing tool: $t"
    done
    if [ ! -f "$AVBTOOL" ]; then
        log "downloading avbtool..."
        curl -fsSL "https://android.googlesource.com/platform/external/avb/+/refs/heads/main/avbtool.py?format=TEXT" \
            | base64 -d > "$AVBTOOL"
        chmod +x "$AVBTOOL"
        python3 "$AVBTOOL" version >/dev/null || die "avbtool download failed"
    fi
}

require_inputs() {
    for f in "$STOCK_DIR/super_4.img" \
             "$STOCK_DIR/xbl_s_devprg_ns.melf" \
             "$STOCK_DIR/gpt_main0.bin" \
             "$STOCK_DIR/gpt_backup0.bin" \
             "$STOCK_DIR/patch0.xml" \
             "$SINKCONN_DIR/libsinkconn_hook.so" \
             "$PHASE3_HAL" \
             "$PHASE3_LIB_QNNHTP" \
             "$PHASE3_LIB_QNNSYSTEM" \
             "$PHASE3_LIB_QNNHTPV73STUB" \
             "$PHASE3_LIB_QNNHTPV73SKEL" \
             "$PHASE3_MODELS_DIR/melspectrogram.bin" \
             "$PHASE3_MODELS_DIR/embedding_model.bin" \
             "$PHASE3_MODELS_DIR/sireneviy.bin" \
             "$PHASE3_PRIVPERM_XML" \
             "$FILESYNC_PRIVPERM_XML" \
             "$BTMANAGER_PRIVPERM_XML" \
             "$BTMANAGER_APK"; do
        [ -f "$f" ] || die "missing input: $f"
    done
}

# ----- overlay payload --------------------------------------------------------

stage_overlay_payload() {
    log "staging overlay payload..."
    mkdir -p "$OVERLAY_DIR/bin" "$OVERLAY_DIR/etc/init" \
             "$OVERLAY_DIR/lib/modules" "$OVERLAY_DIR/lib64"

    # ---- sinkconn-hook library --------------------------------------------
    cp "$SINKCONN_DIR/libsinkconn_hook.so" "$OVERLAY_DIR/lib64/libsinkconn_hook.so"

    # ---- patched psoc driver + touchpad daemon ----------------------------
    if [ -f "$PATCHED_PSOC_KO" ]; then
        cp "$PATCHED_PSOC_KO" "$OVERLAY_DIR/lib/modules/psoc_ts_drv_right.ko"
    else
        log "WARN: $PATCHED_PSOC_KO missing -- skipping"
    fi
    if [ -f "$TOUCHPAD_DAEMON" ]; then
        cp "$TOUCHPAD_DAEMON" "$OVERLAY_DIR/bin/rokid-touchpad-daemon"
    else
        log "WARN: $TOUCHPAD_DAEMON missing -- skipping"
    fi

    # ---- glasses-power-daemon: screen timeout + fold shutdown + time sync ---
    # Native arm64 C binary. Runs as init service so it survives reboots and
    # adb session teardown. Unlatches enforce_psensor/enforce_hall on startup
    # so PsensorObserver actually fires; watches /data/local/diy-overlay/ for
    # the config + glasses-time.sync drop-files (only path the listener app's
    # u0_a* UID can write to on stock Android 12); drives screen lock, forced
    # shutdown on long fold, and clock_settime for time-sync from the phone.
    if [ -f "$POWER_DAEMON" ]; then
        cp "$POWER_DAEMON" "$OVERLAY_DIR/bin/glasses-power-daemon"
    else
        log "WARN: $POWER_DAEMON missing -- skipping"
    fi

    # ---- fn-button-daemon: global capture-button bridge ------------------
    # Reads /dev/input/event0 via getevent and broadcasts ACTION_FN_KEY to
    # the listener app. Works regardless of foreground app. Shell script;
    # managed as an init service so it survives adb session teardown.
    if [ -f "$FN_BUTTON_SRC" ]; then
        cp "$FN_BUTTON_SRC" "$OVERLAY_DIR/bin/fn-button-daemon.sh"
    else
        log "WARN: $FN_BUTTON_SRC missing -- button-to-capture routing absent"
    fi

    # ---- enter-edl helper (userspace EDL trigger) -------------------------
    cat > "$OVERLAY_DIR/bin/enter-edl" <<'EOF'
#!/system/bin/sh
# Trigger Qualcomm 9008 EDL mode. Requires root.
set -u
[ "$(id -u)" -eq 0 ] || { echo "must run as root" >&2; exit 1; }
for knob in /sys/module/msm_poweroff/parameters/download_mode \
            /sys/kernel/dload/emmc_dload \
            /sys/devices/soc0/select_image \
            /sys/kernel/debug/qcom_rtb/reset \
            /proc/sys/kernel/reboot_mode; do
    [ -w "$knob" ] && echo 1 > "$knob" 2>/dev/null && echo "armed $knob"
done
sync
reboot edl 2>/dev/null || reboot
EOF
    chmod 755 "$OVERLAY_DIR/bin/enter-edl"

    # ---- diy-overlay.sh: Tier-2 bind-mount walker -------------------------
    cat > "$OVERLAY_DIR/bin/diy-overlay.sh" <<'EOF'
#!/system/bin/sh
# Walks /data/local/diy-overlay/<abs path> and bind-mounts each file over
# its absolute target. Target must already exist. Run at post-fs-data.
set -u
ROOT=/data/local/diy-overlay
LOG=/data/local/diy-overlay.log
[ -d "$ROOT" ] || exit 0
: > "$LOG"
echo "[diy-overlay] $(date) start" >> "$LOG"
echo 0 > /sys/fs/selinux/enforce 2>>"$LOG" || true
find "$ROOT" -type f 2>/dev/null | while IFS= read -r src; do
    dst="${src#$ROOT}"
    if [ ! -e "$dst" ]; then
        echo "[diy-overlay] SKIP missing target: $dst" >> "$LOG"
        continue
    fi
    if mount -o bind "$src" "$dst" 2>>"$LOG"; then
        echo "[diy-overlay] BIND $src -> $dst" >> "$LOG"
    else
        echo "[diy-overlay] FAIL $src -> $dst" >> "$LOG"
    fi
done
echo "[diy-overlay] $(date) done" >> "$LOG"
EOF
    chmod 755 "$OVERLAY_DIR/bin/diy-overlay.sh"

    # ---- post-boot-fixups.sh: miscellaneous writes needed every boot ------
    # (1) PSoC touchpad: after cold-boot the driver settles in a state where
    #     no IRQs fire for finger touches. Writing these knobs (enforce_psensor
    #     cycle in particular) forces the driver into active-scan.
    # (2) Re-arm the accessibility service that routes the capture button.
    #     `settings put secure enabled_accessibility_services ...` persists in
    #     /data/system/users/0/settings_secure.xml, but any /data wipe
    #     (factory flash, reflash with userdata erase) clears it. Running this
    #     every boot is idempotent -- no-op when already set.
    cat > "$OVERLAY_DIR/bin/post-boot-fixups.sh" <<EOF
#!/system/bin/sh
# Persistent post-boot fixups. Invoked by diy-overlay.rc at post-fs-data.
PSOC=$PSOC_SYSFS
A11Y_SVC="com.repository.glasses.listener/.service.ScreenOffAccessibilityService"

# --- Touchpad: force active scan ------------------------------------------
# Order matters. Observed experimentally: cycling enforce_psensor resets
# other knobs, so do the cycle FIRST and set auto_startup/pa_en AFTER.
# The 1->0 transition is what wakes the touch driver; we then re-latch to 1
# so stock Rokid PsensorObserver never sees wear transitions (no earcon /
# screen-wake on on/off-head -- is_take_on is deprecated, fold is the only
# off-head signal). enforce_hall stays 0 so fold/hall extcon keeps firing.
echo 1 > \$PSOC/enforce_psensor 2>/dev/null || true
sleep 1
echo 0 > \$PSOC/enforce_psensor 2>/dev/null || true
sleep 0.2
echo 1 > \$PSOC/auto_startup  2>/dev/null || true
echo 0 > \$PSOC/low_power     2>/dev/null || true
echo 0 > \$PSOC/deep_sleep    2>/dev/null || true
echo 1 > \$PSOC/pa_en         2>/dev/null || true
# Re-latch wear high (suppress stock PsensorObserver earcon + screen wake).
echo 1 > \$PSOC/enforce_psensor 2>/dev/null || true

# --- ScreenOffAccessibilityService: re-arm the capture-button route -------
# Idempotent set + verify-bound loop. \`sys.boot_completed=1\` fires before
# SettingsProvider and PackageManager are fully responsive on this device
# -- empirically \`settings put\` returns success but the value never
# persists when called too early, leaving \`settings get\` returning null
# even after 6 retries. Symptom: doubletap screen-off and the capture
# button silently no-op until the user re-runs this script by hand.
#
# Three-stage gate before doing anything:
#   (a) wait for SettingsProvider to be queryable -- probe android_id which
#       is always populated once SP is up;
#   (b) wait for PackageManager to know about the listener APK -- otherwise
#       AccessibilityManager refuses to bind the component;
#   (c) write + read-back-verify the settings value, not just dumpsys bind.
#
# Total budget ~60 s; this is a oneshot at boot, not on a hot path.
A11Y_OK=0

# (a) SettingsProvider readiness
for i in \$(seq 1 30); do
    probe="\$(settings get secure android_id 2>/dev/null)"
    case "\$probe" in
        ""|"null") sleep 1 ;;
        *) break ;;
    esac
done

# (b) PackageManager knows the listener
for i in \$(seq 1 30); do
    if pm path com.repository.glasses.listener >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

# (c) put + read-after-write verify, then bind verify
for i in \$(seq 1 15); do
    settings put secure enabled_accessibility_services "\$A11Y_SVC" 2>/dev/null
    settings put secure accessibility_enabled 1 2>/dev/null
    cur="\$(settings get secure enabled_accessibility_services 2>/dev/null)"
    case "\$cur" in
        *"\$A11Y_SVC"*) ;;
        *) sleep 2; continue ;;
    esac
    # value persisted; now wait for AccessibilityManagerService to bind it.
    for j in 1 2 3 4 5; do
        if dumpsys accessibility 2>/dev/null | \
                grep -A2 "Enabled services" | \
                grep -q "ScreenOffAccessibilityService"; then
            A11Y_OK=1
            break
        fi
        sleep 1
    done
    [ "\$A11Y_OK" = "1" ] && break
    sleep 2
done
[ "\$A11Y_OK" = "1" ] || log "WARN: ScreenOffAccessibilityService not bound after retries" >/dev/null 2>&1 || true

# --- Rokid bloatware: hide on every boot ----------------------------------
# fn-button-daemon handles the capture button directly now, so assistserver
# being hidden no longer breaks the button.
for pkg in com.rokid.os.sprite.assistserver \
           com.rokid.os.sprite.launcher \
           com.rokid.os.sprite.live \
           com.rokid.glass.ota \
           com.rokid.os.master.screenstream; do
    pm hide "\$pkg" >/dev/null 2>&1 || true
done

# --- PackageInstaller: reset corrupt OAT ----------------------------------
# The stock super_4 ships a corrupt PackageInstaller.odex (num_methods=0)
# which ART aborts on at class-load time. The OAT lives on dm-verity /system
# so we cannot delete it, but resetting the compilation state makes the
# runtime ignore the OAT and interpret from the APK's DEX instead. Idempotent.
cmd package compile --reset com.android.packageinstaller >/dev/null 2>&1 || true

# --- glasses-power-daemon: ensure running ---------------------------------
# Belt-and-braces. Post-reflash, init's \`service glasses-power-daemon\`
# (class core, declared in diy-overlay.rc) launches the daemon long before
# this script runs and the pidof check below short-circuits. Pre-reflash
# (or when the /system/bin/ binary is somehow missing), this script falls
# back to /data/local/tmp/glasses-power-daemon -- pushed there manually
# via deploy.sh -- so the daemon persists across reboots without a
# re-flash. The daemon's own flock prevents any double-start.
if ! pidof glasses-power-daemon >/dev/null 2>&1; then
    if [ -x /system/bin/glasses-power-daemon ]; then
        nohup /system/bin/glasses-power-daemon \
            >> /data/local/tmp/glasses-power-daemon.log 2>&1 < /dev/null &
    elif [ -x /data/local/tmp/glasses-power-daemon ]; then
        nohup /data/local/tmp/glasses-power-daemon \
            >> /data/local/tmp/glasses-power-daemon.log 2>&1 < /dev/null &
    fi
fi
EOF
    chmod 755 "$OVERLAY_DIR/bin/post-boot-fixups.sh"

    # ---- diy-overlay.rc: init triggers + services ------------------------
    cat > "$OVERLAY_DIR/etc/init/diy-overlay.rc" <<'EOF'
# Tier-1 (baked in super_4) + Tier-2 (runtime /data/local/diy-overlay/) overlay.

on early-init
    # Disable the ART JIT globally. ro.debuggable=1 (required for root) makes
    # PackageManager force FLAG_DEBUGGABLE onto every app, which puts ART in
    # JIT-only mode (no AOT .odex is ever produced -- dexopt stays at
    # status=verify for every package). On this kernel the JIT code-cache GC
    # ftruncates the backing memfd:jit-cache while another thread is still
    # executing JITted code in that region -> SIGBUS (BUS_ADRERR) inside
    # ExecuteNterpImpl. It hit the listener (MicStream-Thread on HFP connect),
    # filesync, and even stock PackageInstaller. Running interpreter-only
    # (nterp) removes the racy executable code cache entirely (verified: the
    # r-xs jit-cache region drops to Rss=0). Hot paths on this device are native
    # (.so: ONNX, opus, AEC), so the interpreter cost is negligible. Must be set
    # before zygote forks; property service has already loaded build.prop's
    # dalvik.vm.usejit=true by early-init, so this setprop overrides it.
    setprop dalvik.vm.usejit false

on init
    # patched psoc driver + custom sthal -- both live under /system/lib*,
    # bind-mount them over the vendor paths init's DLKM loader will read.
    mount none /system/lib/modules/psoc_ts_drv_right.ko \
               /vendor_dlkm/lib/modules/psoc_ts_drv_right.ko bind
    mount none /system/lib64/hw/sound_trigger.primary.neo.so \
               /vendor/lib64/hw/sound_trigger.primary.neo.so bind
    # Tier-2 dev-iteration bind for the hook library (matches the absolute
    # path zygote's LD_PRELOAD points at). Silent no-op when the path is
    # absent.
    mount none /data/local/diy-overlay/system/lib64/libsinkconn_hook.so \
               /system/lib64/libsinkconn_hook.so bind

on post-fs-data
    # Tier-2 bind for the listener APK (101 MB debug build wouldn't fit in
    # super, so super has a 1-byte stub and the real APK lives on /data).
    mount none /data/local/diy-overlay/system/priv-app/com.repository.glasses.listener/listener.apk \
               /system/priv-app/com.repository.glasses.listener/listener.apk bind
    mount none /data/local/diy-overlay/system/etc/permissions/privapp-permissions-com.repository.glasses.filesync.xml \
               /system/etc/permissions/privapp-permissions-com.repository.glasses.filesync.xml bind
    mount none /data/local/diy-overlay/system/priv-app/com.repository.glasses.filesync/filesync.apk \
               /system/priv-app/com.repository.glasses.filesync/filesync.apk bind
    # bt-manager: only the APK is overlaid for fast iteration. The privapp
    # XML stays baked in super_4 -- a Tier-2 bind for it inherits the source's
    # SELinux context unless chcon'd, and on Android 12 PMS silently drops
    # privapp-permissions blocks containing single-permission elements with
    # leading inline comments (a parser quirk that bit us once). Bake-only is
    # the safe path for the XML.
    mount none /data/local/diy-overlay/system/priv-app/com.repository.glasses.btmanager/btmanager.apk \
               /system/priv-app/com.repository.glasses.btmanager/btmanager.apk bind
    # Generic Tier-2 walker -- run via oneshot service (init's bare `exec`
    # rejects shell entrypoints without a domain transition even in
    # permissive mode).
    start diy-overlay-walker

# Post-boot fixups: PSoC driver finishes probing around boot_completed.
# Running the touchpad sysfs writes at post-fs-data is too early -- the
# driver resets the knobs when it probes. Same oneshot-service pattern
# to bypass init's SELinux domain-transition check.
on property:sys.boot_completed=1
    start post-boot-fixups

service diy-overlay-walker /system/bin/sh /system/bin/diy-overlay.sh
    user root
    group root
    seclabel u:r:su:s0
    oneshot
    disabled

service post-boot-fixups /system/bin/sh /system/bin/post-boot-fixups.sh
    user root
    group root
    seclabel u:r:su:s0
    oneshot
    disabled

service rokid-touchpad-daemon /system/bin/rokid-touchpad-daemon
    class core
    user root
    group root input
    seclabel u:r:su:s0

# glasses-power-daemon: root C daemon for screen/fold/power management and
# time-sync. Runs with `class core` so init auto-respawns it if it ever dies.
# Singleton self-lock via flock on /data/local/diy-overlay/glasses-power-daemon.lock
# handles the (hopefully never) case of two instances racing.
service glasses-power-daemon /system/bin/glasses-power-daemon
    class core
    user root
    group root input
    seclabel u:r:su:s0

# fn-button-daemon bridges /dev/input/event0 (qpnp_pon KEY_MENU) -> the
# listener app's ACTION_FN_KEY broadcast -> FunctionButtonHandler -> capture.
# Runs as a shell script under init; init auto-respawns on exit. Needs root
# to both open event0 (group input) and `am broadcast` across UIDs.
service fn-button-daemon /system/bin/sh /system/bin/fn-button-daemon.sh
    class core
    user root
    group root input
    seclabel u:r:su:s0
EOF
    chmod 644 "$OVERLAY_DIR/etc/init/diy-overlay.rc"

    # ---- set-a2dp-sink-conn.rc: persist prop at boot ----------------------
    # Bluetooth A2dpSinkService reads this prop in start() as
    # Math.min(prop, 2). libsinkconn_hook overrides the clamped field at
    # runtime, but having the prop at 64 minimises the brief pre-hook
    # window where the field may be 1 or 2.
    cat > "$OVERLAY_DIR/etc/init/set-a2dp-sink-conn.rc" <<'EOF'
on boot
    setprop persist.vendor.bt.a2dp.sink_conn 64
EOF
    chmod 644 "$OVERLAY_DIR/etc/init/set-a2dp-sink-conn.rc"
}

# ----- system image patching --------------------------------------------------

# Repair the ext4 block bitmap after debugfs mutations. CRITICAL: the stock
# system.img uses the ext4 `shared_blocks` feature (block-level dedup -- many
# byte-identical framework OATs/odex share the same physical blocks). debugfs
# does NOT honor shared-block refcounts: when it `rm`s a file that shares a
# block, it marks that block free even though other files still reference it;
# a later `write` then reuses the "free" block and silently corrupts the
# framework OATs that still point at it (their logical block 3 held the OAT
# dynamic section -> garbage DT_NEEDED -> linker get_string CHECK abort ->
# zygote SIGABRT -> boot loop). `e2fsck -f` rebuilds the block bitmap from the
# real inode references (it understands shared_blocks), re-marking shared
# blocks as used BEFORE any subsequent debugfs write can grab them. Run this
# after every debugfs delete/write phase. Dedup is preserved (image still fits
# the partition slot); un-sharing would overflow the slot by ~18 MiB.
fsck_bitmap() {
    local why="$1"
    log "e2fsck bitmap repair ($why)..."
    # e2fsck returns 1 when it fixed errors -- that is the expected/desired
    # outcome here, so don't let `set -e` abort. Only a hard failure (>=4)
    # is fatal.
    # `set -e` is active and e2fsck returns 1 when it FIXES errors (the normal,
    # desired outcome here) -- so the `|| rc=$?` idiom is required to capture
    # the status without the non-zero exit aborting the script. Only rc>=4
    # (operational/unrecoverable error) is fatal; 0=clean, 1=fixed, 2=fixed+
    # reboot-recommended (irrelevant for an offline image) are all success.
    local rc=0
    e2fsck -f -y "$WORK_DIR/system.img" >/dev/null 2>&1 || rc=$?
    if [ "$rc" -ge 4 ]; then
        die "e2fsck failed ($why): unrecoverable rc=$rc"
    fi
    # Explicit success: a bare `[ ... ]` test as the last statement would return
    # its (false) exit status and, under `set -e`, abort the caller.
    return 0
}

extract_system_img() {
    log "extracting system.img from stock super_4..."
    cp "$STOCK_DIR/super_4.img" super_4.img
    dd if=super_4.img of="$WORK_DIR/system.img" bs=4096 \
       count=$((SYSTEM_IMAGE_SIZE / 4096)) status=none
    # Baseline repair: the dd-carved image can have a slightly stale free-block
    # count vs the on-disk bitmap; normalize before any mutation so later
    # bitmap-diff guards only flag OUR changes.
    fsck_bitmap "post-extract baseline"
}

patch_buildprop_and_bootstat() {
    log "binary-patching build.prop + bootstat.rc..."
    python3 - "$WORK_DIR/system.img" <<'PY'
import sys
path = sys.argv[1]
with open(path, 'r+b') as f:
    data = f.read()
    patches = [
        (b'ro.debuggable=0', b'ro.debuggable=1'),
        (b'ro.secure=1',     b'ro.secure=0'),
        (b'ro.adb.secure=1', b'ro.adb.secure=0'),
        # Flip SELinux to permissive on boot_completed -- replaces an
        # innocuous comment of identical length in bootstat.rc.
        (b'    # Record boot_complete and related stats (decryption, etc).',
         b'    write /sys/fs/selinux/enforce 0                            '),
    ]
    for old, new in patches:
        assert len(old) == len(new), 'patch len mismatch'
        if old in data:
            data = data.replace(old, new)
        else:
            print(f'  WARN: {old[:40]!r} not found (skipping)', file=sys.stderr)
    f.seek(0)
    f.write(data)
    assert b'ro.debuggable=1' in data, 'missing post-patch: ro.debuggable=1'
    assert b'ro.secure=0' in data, 'missing post-patch: ro.secure=0'
print('OK')
PY
}

detect_sys_prefix() {
    if debugfs -R 'ls /system/bin' "$WORK_DIR/system.img" 2>/dev/null | grep -q .; then
        SYS_PREFIX="/system"
    elif debugfs -R 'ls /bin' "$WORK_DIR/system.img" 2>/dev/null | grep -q .; then
        SYS_PREFIX=""
    else
        die "can't locate 'bin/' inside system.img via debugfs"
    fi
    log "system root inside image: '${SYS_PREFIX:-/}'"
}

patch_init_zygote_ld_preload() {
    log "patching init.zygote64_32.rc with setenv LD_PRELOAD..."
    debugfs -R "dump $SYS_PREFIX/etc/init/hw/init.zygote64_32.rc $WORK_DIR/init.zygote64_32.rc.stock" \
        "$WORK_DIR/system.img" 2>&1 | tail -1
    [ -s "$WORK_DIR/init.zygote64_32.rc.stock" ] || die "init.zygote64_32.rc dump failed"
    python3 - "$WORK_DIR/init.zygote64_32.rc.stock" "$WORK_DIR/init.zygote64_32.rc" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
data = open(src, 'rb').read().decode()
pat = re.compile(r'(service zygote /system/bin/app_process64 [^\n]*\n)')
new = pat.sub(r'\1    setenv LD_PRELOAD /system/lib64/libsinkconn_hook.so\n',
              data, count=1)
if new == data:
    sys.exit('zygote service line not found')
open(dst, 'w').write(new)
PY
    grep -q 'setenv LD_PRELOAD /system/lib64/libsinkconn_hook.so' \
        "$WORK_DIR/init.zygote64_32.rc" \
        || die "setenv LD_PRELOAD line missing after patch"
}

# Stock system FS has ~2 MB free; Phase-3 content + our injections need ~25 MB.
# Delete removable stock apps to free space. Safe removals only; fonts and
# framework components NEVER touched.
delete_bloat() {
    log "freeing space for Phase-3 + overlay..."
    debugfs -w -f /dev/stdin "$WORK_DIR/system.img" <<EOF >/dev/null 2>&1 || true
$(_bloat_rm_script "$SYS_PREFIX")
quit
EOF
    # Repair the block bitmap NOW, before inject_files writes anything -- the
    # rm's above freed shared blocks that other (framework) files still own.
    fsck_bitmap "after delete_bloat"
    local free
    free=$(debugfs -R "show_super_stats -h" "$WORK_DIR/system.img" 2>/dev/null \
            | awk '/Free blocks:/{print $3; exit}')
    log "free after bloat delete: $free blocks (~$((free * 4 / 1024)) MB)"
}

_bloat_rm_script() {
    local p="$1"
    # Each stanza: delete APK + odex + vdex, then rmdir its structure.
    for app in BasicDreams BookmarkProvider CertInstaller CompanionDeviceManager \
               PacProcessor Protips KeyChain CameraExtensionsProxy \
               PlatformCaptivePortalLogin; do
        cat <<EOF
rm $p/app/$app/$app.apk
rm $p/app/$app/oat/arm64/$app.odex
rm $p/app/$app/oat/arm64/$app.vdex
rmdir $p/app/$app/oat/arm64
rmdir $p/app/$app/oat
rmdir $p/app/$app
EOF
    done
    # CtsShimPrebuilt + CXRService are bare-APK without oat dirs.
    cat <<EOF
rm $p/app/CtsShimPrebuilt/CtsShimPrebuilt.apk
rmdir $p/app/CtsShimPrebuilt
rm $p/app/CXRService/CXRService.apk
rmdir $p/app/CXRService
EOF
    # priv-app removals: Traceur + stray listener.apk + ManagedProvisioning.
    cat <<EOF
rm $p/priv-app/Traceur/Traceur.apk
rm $p/priv-app/Traceur/oat/arm64/Traceur.odex
rm $p/priv-app/Traceur/oat/arm64/Traceur.vdex
rmdir $p/priv-app/Traceur/oat/arm64
rmdir $p/priv-app/Traceur/oat
rmdir $p/priv-app/Traceur
rm $p/priv-app/listener.apk
rm $p/priv-app/ManagedProvisioning/ManagedProvisioning.apk
rm $p/priv-app/ManagedProvisioning/oat/arm64/ManagedProvisioning.odex
rm $p/priv-app/ManagedProvisioning/oat/arm64/ManagedProvisioning.vdex
rmdir $p/priv-app/ManagedProvisioning/oat/arm64
rmdir $p/priv-app/ManagedProvisioning/oat
rmdir $p/priv-app/ManagedProvisioning
EOF
}

# ----- injection --------------------------------------------------------------

# Emits a debugfs command list for all overlay + Phase-3 writes.
_bake_script() {
    local p="$SYS_PREFIX"
    local stub="$WORK_DIR/stub.bin"
    printf '\0' > "$stub"

    # /system/bin: overlay engine + EDL helper + touchpad nudge/daemon
    cat <<EOF
cd $p/bin
rm diy-overlay.sh
write $OVERLAY_DIR/bin/diy-overlay.sh diy-overlay.sh
sif diy-overlay.sh mode 0100755
rm enter-edl
write $OVERLAY_DIR/bin/enter-edl enter-edl
sif enter-edl mode 0100755
rm post-boot-fixups.sh
write $OVERLAY_DIR/bin/post-boot-fixups.sh post-boot-fixups.sh
sif post-boot-fixups.sh mode 0100755
EOF
    if [ -f "$OVERLAY_DIR/bin/fn-button-daemon.sh" ]; then
        cat <<EOF
rm fn-button-daemon.sh
write $OVERLAY_DIR/bin/fn-button-daemon.sh fn-button-daemon.sh
sif fn-button-daemon.sh mode 0100755
EOF
    fi
    if [ -f "$OVERLAY_DIR/bin/rokid-touchpad-daemon" ]; then
        cat <<EOF
rm rokid-touchpad-daemon
write $OVERLAY_DIR/bin/rokid-touchpad-daemon rokid-touchpad-daemon
sif rokid-touchpad-daemon mode 0100755
EOF
    fi
    if [ -f "$OVERLAY_DIR/bin/glasses-power-daemon" ]; then
        cat <<EOF
rm glasses-power-daemon
write $OVERLAY_DIR/bin/glasses-power-daemon glasses-power-daemon
sif glasses-power-daemon mode 0100755
EOF
    fi

    # /system/etc/init: our rc files + patched zygote rc
    cat <<EOF
cd $p/etc/init
rm diy-overlay.rc
write $OVERLAY_DIR/etc/init/diy-overlay.rc diy-overlay.rc
sif diy-overlay.rc mode 0100644
rm set-a2dp-sink-conn.rc
write $OVERLAY_DIR/etc/init/set-a2dp-sink-conn.rc set-a2dp-sink-conn.rc
sif set-a2dp-sink-conn.rc mode 0100644
cd $p/etc/init/hw
rm init.zygote64_32.rc
write $WORK_DIR/init.zygote64_32.rc init.zygote64_32.rc
sif init.zygote64_32.rc mode 0100644
EOF

    # /system/lib/modules: patched psoc driver
    if [ -f "$OVERLAY_DIR/lib/modules/psoc_ts_drv_right.ko" ]; then
        cat <<EOF
cd $p/lib
mkdir modules
cd modules
rm psoc_ts_drv_right.ko
write $OVERLAY_DIR/lib/modules/psoc_ts_drv_right.ko psoc_ts_drv_right.ko
sif psoc_ts_drv_right.ko mode 0100644
EOF
    fi

    # /system/lib64: Phase-3 QNN libs + sinkconn-hook + sound_trigger HAL
    cat <<EOF
cd $p/lib64
rm libQnnHtp.so
write $PHASE3_LIB_QNNHTP libQnnHtp.so
sif libQnnHtp.so mode 0100644
rm libQnnSystem.so
write $PHASE3_LIB_QNNSYSTEM libQnnSystem.so
sif libQnnSystem.so mode 0100644
rm libQnnHtpV73Stub.so
write $PHASE3_LIB_QNNHTPV73STUB libQnnHtpV73Stub.so
sif libQnnHtpV73Stub.so mode 0100644
rm libsinkconn_hook.so
write $OVERLAY_DIR/lib64/libsinkconn_hook.so libsinkconn_hook.so
sif libsinkconn_hook.so mode 0100644
mkdir hw
cd hw
rm sound_trigger.primary.neo.so
write $PHASE3_HAL sound_trigger.primary.neo.so
sif sound_trigger.primary.neo.so mode 0100644
EOF

    # /system/etc/permissions + sthal models
    cat <<EOF
cd $p/etc/permissions
rm privapp-permissions-com.repository.glasses.listener.xml
write $PHASE3_PRIVPERM_XML privapp-permissions-com.repository.glasses.listener.xml
sif privapp-permissions-com.repository.glasses.listener.xml mode 0100644
rm privapp-permissions-com.repository.glasses.filesync.xml
write $FILESYNC_PRIVPERM_XML privapp-permissions-com.repository.glasses.filesync.xml
sif privapp-permissions-com.repository.glasses.filesync.xml mode 0100644
rm privapp-permissions-com.repository.glasses.btmanager.xml
write $BTMANAGER_PRIVPERM_XML privapp-permissions-com.repository.glasses.btmanager.xml
sif privapp-permissions-com.repository.glasses.btmanager.xml mode 0100644
cd $p/etc
mkdir sthal
cd sthal
mkdir models
cd models
rm melspectrogram.bin
write $PHASE3_MODELS_DIR/melspectrogram.bin melspectrogram.bin
sif melspectrogram.bin mode 0100644
rm embedding_model.bin
write $PHASE3_MODELS_DIR/embedding_model.bin embedding_model.bin
sif embedding_model.bin mode 0100644
rm sireneviy.bin
write $PHASE3_MODELS_DIR/sireneviy.bin sireneviy.bin
sif sireneviy.bin mode 0100644
EOF

    # listener stub + QNN DSP skel
    cat <<EOF
cd $p/priv-app
mkdir com.repository.glasses.listener
cd com.repository.glasses.listener
rm listener.apk
write $stub listener.apk
sif listener.apk mode 0100644
cd $p/priv-app
mkdir com.repository.glasses.filesync
cd com.repository.glasses.filesync
rm filesync.apk
write $stub filesync.apk
sif filesync.apk mode 0100644
cd $p/priv-app
mkdir com.repository.glasses.btmanager
cd com.repository.glasses.btmanager
rm btmanager.apk
write $stub btmanager.apk
sif btmanager.apk mode 0100644
cd $p/lib
mkdir rfsa
cd rfsa
mkdir adsp
cd adsp
rm libQnnHtpV73Skel.so
write $PHASE3_LIB_QNNHTPV73SKEL libQnnHtpV73Skel.so
sif libQnnHtpV73Skel.so mode 0100644
EOF

}

inject_files() {
    log "baking overlay + Phase-3 + sinkconn-hook + zygote rc..."
    debugfs -w -f /dev/stdin "$WORK_DIR/system.img" <<EOF
$(_bake_script)
quit
EOF
    # _bake_script does `rm X; write X` for many files; each rm can free a
    # shared block. Repair the bitmap so set_selinux_xattrs (more debugfs
    # writes) and the final image are consistent.
    fsck_bitmap "after inject_files"
}

verify_file() {
    local dir="$1" name="$2" expect="$3"
    local actual
    actual=$(debugfs -R "ls -l $dir" "$WORK_DIR/system.img" 2>/dev/null \
             | awk -v n="$name" '$NF==n {print $6; exit}')
    [ "$actual" = "$expect" ] \
        || die "bake mismatch $dir/$name: expected $expect, got ${actual:-missing}"
}

_verify_pair() { verify_file "$1" "$2" "$(stat -c%s "$3")"; }

verify_baked_files() {
    log "verifying all baked files..."
    local p="$SYS_PREFIX"
    _verify_pair "$p/lib64" "libQnnHtp.so"             "$PHASE3_LIB_QNNHTP"
    _verify_pair "$p/lib64" "libQnnSystem.so"          "$PHASE3_LIB_QNNSYSTEM"
    _verify_pair "$p/lib64" "libQnnHtpV73Stub.so"      "$PHASE3_LIB_QNNHTPV73STUB"
    _verify_pair "$p/lib64" "libsinkconn_hook.so"      "$SINKCONN_DIR/libsinkconn_hook.so"
    _verify_pair "$p/lib64/hw" "sound_trigger.primary.neo.so" "$PHASE3_HAL"
    _verify_pair "$p/etc/permissions" "privapp-permissions-com.repository.glasses.listener.xml" "$PHASE3_PRIVPERM_XML"
    _verify_pair "$p/etc/permissions" "privapp-permissions-com.repository.glasses.filesync.xml" "$FILESYNC_PRIVPERM_XML"
    _verify_pair "$p/etc/permissions" "privapp-permissions-com.repository.glasses.btmanager.xml" "$BTMANAGER_PRIVPERM_XML"
    _verify_pair "$p/etc/sthal/models" "melspectrogram.bin" "$PHASE3_MODELS_DIR/melspectrogram.bin"
    _verify_pair "$p/etc/sthal/models" "embedding_model.bin" "$PHASE3_MODELS_DIR/embedding_model.bin"
    _verify_pair "$p/etc/sthal/models" "sireneviy.bin"      "$PHASE3_MODELS_DIR/sireneviy.bin"
    _verify_pair "$p/lib/rfsa/adsp" "libQnnHtpV73Skel.so"   "$PHASE3_LIB_QNNHTPV73SKEL"
    # bt-manager: only the 1-byte stub is verified here. The real APK is
    # pushed to /data/local/diy-overlay/ at post-flash and bind-mounted at
    # post-fs-data; verifying its baked size would mismatch the stub.
    _verify_pair "$p/bin" "diy-overlay.sh"             "$OVERLAY_DIR/bin/diy-overlay.sh"
    _verify_pair "$p/bin" "enter-edl"                  "$OVERLAY_DIR/bin/enter-edl"
    _verify_pair "$p/bin" "post-boot-fixups.sh"          "$OVERLAY_DIR/bin/post-boot-fixups.sh"
    [ -f "$OVERLAY_DIR/bin/fn-button-daemon.sh" ] && \
        _verify_pair "$p/bin" "fn-button-daemon.sh"    "$OVERLAY_DIR/bin/fn-button-daemon.sh"
    [ -f "$OVERLAY_DIR/bin/glasses-power-daemon" ] && \
        _verify_pair "$p/bin" "glasses-power-daemon"   "$OVERLAY_DIR/bin/glasses-power-daemon"
    _verify_pair "$p/etc/init" "diy-overlay.rc"        "$OVERLAY_DIR/etc/init/diy-overlay.rc"
    _verify_pair "$p/etc/init" "set-a2dp-sink-conn.rc" "$OVERLAY_DIR/etc/init/set-a2dp-sink-conn.rc"
    _verify_pair "$p/etc/init/hw" "init.zygote64_32.rc" "$WORK_DIR/init.zygote64_32.rc"
    log "all baked files verified at expected sizes."
}

# ----- SELinux labels ---------------------------------------------------------

# Emit ea_set commands for the specified label/path pairs.
_xattr_script() {
    local p="$SYS_PREFIX"
    # path:label pairs. "label" shorthand: f=system_file, l=system_lib_file.
    local -a pairs=(
        "$p/bin/diy-overlay.sh:f"
        "$p/bin/enter-edl:f"
        "$p/bin/post-boot-fixups.sh:f"
        "$p/etc/init/diy-overlay.rc:f"
        "$p/etc/init/set-a2dp-sink-conn.rc:f"
        "$p/etc/init/hw/init.zygote64_32.rc:f"
        "$p/etc/sthal:f"
        "$p/etc/sthal/models:f"
        "$p/etc/sthal/models/melspectrogram.bin:f"
        "$p/etc/sthal/models/embedding_model.bin:f"
        "$p/etc/sthal/models/sireneviy.bin:f"
        "$p/etc/permissions/privapp-permissions-com.repository.glasses.listener.xml:f"
        "$p/etc/permissions/privapp-permissions-com.repository.glasses.filesync.xml:f"
        "$p/etc/permissions/privapp-permissions-com.repository.glasses.btmanager.xml:f"
        "$p/lib64/libQnnHtp.so:l"
        "$p/lib64/libQnnSystem.so:l"
        "$p/lib64/libQnnHtpV73Stub.so:l"
        "$p/lib64/libsinkconn_hook.so:l"
        "$p/lib64/hw:l"
        "$p/lib64/hw/sound_trigger.primary.neo.so:l"
        "$p/priv-app/com.repository.glasses.listener:f"
        "$p/priv-app/com.repository.glasses.listener/listener.apk:f"
        "$p/priv-app/com.repository.glasses.btmanager:f"
        "$p/priv-app/com.repository.glasses.btmanager/btmanager.apk:f"
        "$p/lib/rfsa:f"
        "$p/lib/rfsa/adsp:f"
        "$p/lib/rfsa/adsp/libQnnHtpV73Skel.so:l"
    )
    if [ -f "$OVERLAY_DIR/lib/modules/psoc_ts_drv_right.ko" ]; then
        pairs+=("$p/lib/modules:f" "$p/lib/modules/psoc_ts_drv_right.ko:l")
    fi
    if [ -f "$OVERLAY_DIR/bin/rokid-touchpad-daemon" ]; then
        pairs+=("$p/bin/rokid-touchpad-daemon:f")
    fi
    if [ -f "$OVERLAY_DIR/bin/fn-button-daemon.sh" ]; then
        pairs+=("$p/bin/fn-button-daemon.sh:f")
    fi
    if [ -f "$OVERLAY_DIR/bin/glasses-power-daemon" ]; then
        pairs+=("$p/bin/glasses-power-daemon:f")
    fi
    for pair in "${pairs[@]}"; do
        local path="${pair%:*}"; local tag="${pair##*:}"
        case "$tag" in
            f) printf 'ea_set %s security.selinux u:object_r:system_file:s0\\0\n' "$path" ;;
            l) printf 'ea_set %s security.selinux u:object_r:system_lib_file:s0\\0\n' "$path" ;;
        esac
    done
}

set_selinux_xattrs() {
    log "setting SELinux xattrs..."
    debugfs -w -f /dev/stdin "$WORK_DIR/system.img" <<EOF >/dev/null
$(_xattr_script)
quit
EOF
    # Critical labels (any mislabel here = boot fail).
    for entry in \
        "$SYS_PREFIX/etc/init/hw/init.zygote64_32.rc:system_file" \
        "$SYS_PREFIX/lib64/libsinkconn_hook.so:system_lib_file" \
        "$SYS_PREFIX/bin/post-boot-fixups.sh:system_file" \
        "$SYS_PREFIX/etc/init/diy-overlay.rc:system_file"; do
        path="${entry%:*}"; want="${entry##*:}"
        ea=$(debugfs -R "ea_list $path" "$WORK_DIR/system.img" 2>&1)
        echo "$ea" | grep -q "u:object_r:${want}:s0" \
            || die "xattr mismatch on $path (expected $want); aborting"
    done
    log "SELinux xattrs verified."
}

# ----- AVB + output -----------------------------------------------------------

regen_avb_hashtree() {
    # Final consistency pass before hashing the image. The hashtree is computed
    # over the raw block device, so the on-disk bitmap and inode block maps must
    # be fully consistent or the dm-verity tree will hash stale/garbage blocks.
    fsck_bitmap "pre-AVB final"
    log "regenerating AVB hashtree footer..."
    python3 "$AVBTOOL" add_hashtree_footer \
        --image "$WORK_DIR/system.img" \
        --partition_name system \
        --salt "$AVB_SALT" \
        --hash_algorithm sha256 \
        --partition_size $SYSTEM_IMAGE_SIZE \
        --block_size 4096 \
        --do_not_generate_fec
    local salt
    salt=$(python3 "$AVBTOOL" info_image --image "$WORK_DIR/system.img" 2>&1 \
            | awk '/Salt:/ {print $2; exit}')
    [ "$salt" = "$AVB_SALT" ] || die "AVB salt mismatch"
    log "hashtree OK, salt matches stock."
}

write_super_img() {
    log "writing patched system.img back to super_4.img..."
    dd if="$WORK_DIR/system.img" of=super_4.img conv=notrunc bs=4096 status=none
    local sz
    sz=$(stat -c%s super_4.img)
    [ "$sz" -ge "$SYSTEM_IMAGE_SIZE" ] || die "super_4.img too small ($sz)"
}

emit_rawprogram_xml() {
    log "emitting rawprogram_super4.xml..."
    # 1.17.012's abl.elf MUST be flashed alongside the rooted super_4. The
    # 1.18.100 abl shipped with current stock cherry-picked the AOSP libavb
    # hardening that turns chained-vbmeta hashtree-descriptor mismatches into
    # fatal errors even on orange/unlocked. The 1.17 abl tolerates the
    # mismatch (its libavb predates the fix), and both ABLs are signed with
    # the same Qualcomm SECTOOLS Test Key 0 -- XBL accepts either, and
    # rollback fuses are not in play on test-keyed dev units. Without this
    # downgrade, our rooted super_4 bootloops on every cold flash.
    [ -f "$SCRIPT_DIR/abl_old.elf" ] || \
        die "abl_old.elf missing at $SCRIPT_DIR/abl_old.elf -- needed to neutralize 1.18 ABL strict verity"
    cat > rawprogram_super4.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- Flash list: GPT (discoverable) + 1.17 abl_a/abl_b (verity tolerance) +
     patched super_4. Intentionally omits: misc (zeroing destroyed slot state
     last time); vbmeta / vbmeta_system (strict-signed, modifications
     instant-brick); super_5 (system_ext stays stock). -->
<data>
  <program SECTOR_SIZE_IN_BYTES="512" filename="os-cache/current/gpt_main0.bin"   label="PrimaryGPT" num_partition_sectors="34" partofsingleimage="true" physical_partition_number="0" readbackverify="false" size_in_KB="17.0" sparse="false" start_byte_hex="0x0" start_sector="0" file_sector_offset="0"/>
  <program SECTOR_SIZE_IN_BYTES="512" filename="os-cache/current/gpt_backup0.bin" label="BackupGPT"  num_partition_sectors="33" partofsingleimage="true" physical_partition_number="0" readbackverify="false" size_in_KB="16.5" sparse="false" start_byte_hex="0x0" start_sector="NUM_DISK_SECTORS-33." file_sector_offset="0"/>
  <program SECTOR_SIZE_IN_BYTES="512" filename="abl_old.elf" label="abl_a" num_partition_sectors="2048" partofsingleimage="false" physical_partition_number="0" readbackverify="false" size_in_KB="1024.0" sparse="false" start_byte_hex="0x1ec900000" start_sector="16140288" file_sector_offset="0"/>
  <program SECTOR_SIZE_IN_BYTES="512" filename="abl_old.elf" label="abl_b" num_partition_sectors="2048" partofsingleimage="false" physical_partition_number="0" readbackverify="false" size_in_KB="1024.0" sparse="false" start_byte_hex="0x1eca00000" start_sector="16142336" file_sector_offset="0"/>
  <program SECTOR_SIZE_IN_BYTES="512" filename="super_4.img" label="super" num_partition_sectors="1719664" partofsingleimage="false" physical_partition_number="0" readbackverify="false" start_sector="3538944" file_sector_offset="0"/>
</data>
EOF
}

final_summary() {
    log "=== DONE ==="
    log "flash with:"
    log "  sudo qdl --storage emmc \\"
    log "      $STOCK_DIR/xbl_s_devprg_ns.melf \\"
    log "      $SCRIPT_DIR/rawprogram_super4.xml \\"
    log "      $STOCK_DIR/patch0.xml"
    log ""
    log "after first boot, push the Tier-2 priv-app APKs (listener+filesync+btmanager)"
    log "to /data/local/diy-overlay/ so PackageManager picks them up as"
    log "privileged installs (required for BLUETOOTH_PRIVILEGED, etc.):"
    log "  bash $SCRIPT_DIR/root-firmware.sh --post-flash"
    log ""
    log "dev-iteration (no reflash, adb push + reboot):"
    log "  bash $SCRIPT_DIR/sinkconn-hook/deploy.sh"
}

# ----- post-flash: deploy Tier-2 priv-app APKs to /data/local/diy-overlay/ ----
#
# Runs against a booted device (adb visible, root shell available). Pushes the
# real listener + filesync APKs over the 1-byte priv-app stubs baked into
# super_4 (via the bind-mount lines in diy-overlay.rc) and removes any
# /data/app sideload that would shadow them. After reboot, PackageManager
# scans /system/priv-app, applies privapp-permissions-com.repository.glasses.
# {listener,filesync}.xml, and the listener finally has BLUETOOTH_PRIVILEGED
# (so the triple-press fn-button -> setScanMode(DISCOVERABLE) pairing path
# actually works -- without this, setScanMode throws SecurityException and
# the discoverable scan never engages).
#
# Why this can't run inline at build time: the host running root-firmware.sh
# usually does not have the device on adb (device is in EDL or just-flashed),
# so we expose it as an explicit `--post-flash` mode the user runs once the
# glasses come back up.
deploy_runtime_overlay() {
    log "=== post-flash: Tier-2 priv-app overlay ==="
    [ -f "$LISTENER_APK" ] || die "listener APK missing: $LISTENER_APK"
    [ -f "$FILESYNC_APK" ] || die "filesync APK missing: $FILESYNC_APK"
    [ -f "$BTMANAGER_APK" ] || die "bt-manager APK missing: $BTMANAGER_APK"
    command -v adb >/dev/null 2>&1 || die "adb not on PATH"

    log "waiting for device $ADB_SERIAL on adb..."
    adb -s "$ADB_SERIAL" wait-for-device
    local bc=""
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24; do
        bc="$(adb -s "$ADB_SERIAL" shell 'getprop sys.boot_completed' 2>/dev/null | tr -d '\r\n' || true)"
        [ "$bc" = "1" ] && break
        sleep 5
    done
    [ "$bc" = "1" ] || die "device did not finish booting (sys.boot_completed != 1)"

    # Push pattern: stage to /data/local/tmp, then in-place rewrite via
    # `cat > target`. Direct `adb push` to the diy-overlay path silently
    # fails (no error output, exit 0) when the previous boot's bind-mount
    # is still active on /system/priv-app/<pkg>/<apk>: that bind keeps the
    # source file inode busy in system_server (PMS scans it on every pm
    # query the listener triggers via its 2s bindService retry loop), so
    # adb push's open(O_TRUNC) + rename is rejected and the file content
    # never changes -- but adb prints a successful "X bytes pushed" line
    # because the local-side read succeeded. Result: a stale/corrupted APK
    # gets re-bind-mounted after reboot and PMS refuses to scan it as a
    # priv-app, leaving the package uninstalled despite all the on-disk
    # state looking correct (right path, right size on the previous push,
    # right permissions). `cat > path` opens with O_WRONLY|O_TRUNC and
    # writes in-place; this works on busy bind-mount sources because we're
    # only changing file content, not inode identity.
    push_overlay_apk() {
        local pkg="$1" apk_local="$2" apk_name="$3"
        local overlay_dir="/data/local/diy-overlay/system/priv-app/$pkg"
        local target="$overlay_dir/$apk_name"
        local stage="/data/local/tmp/$apk_name"
        log "pushing $pkg APK -> $target (via staging)"
        adb -s "$ADB_SERIAL" shell "mkdir -p $overlay_dir" >/dev/null
        adb -s "$ADB_SERIAL" push "$apk_local" "$stage" >/dev/null
        adb -s "$ADB_SERIAL" shell "cat $stage > $target && chmod 644 $target && rm -f $stage"
        local want got
        want="$(sha256sum "$apk_local" | awk '{print $1}')"
        got="$(adb -s "$ADB_SERIAL" shell "sha256sum $target" | awk '{print $1}' | tr -d '\r')"
        [ "$want" = "$got" ] || die "$pkg APK sha256 mismatch after push: want=$want got=$got (in-place rewrite failed)"
    }

    push_overlay_apk com.repository.glasses.listener  "$LISTENER_APK"  listener.apk
    push_overlay_apk com.repository.glasses.filesync  "$FILESYNC_APK"  filesync.apk
    push_overlay_apk com.repository.glasses.btmanager "$BTMANAGER_APK" btmanager.apk

    # bt-manager privapp-permissions XML is baked into super_4 directly --
    # no Tier-2 push needed. (See diy-overlay.rc comment.)

    # Remove any /data/app sideload of any package -- otherwise the user-
    # installed copy keeps winning over the priv-app one and we'd be back to
    # square one with no privileged grants.
    for pkg in com.repository.glasses.listener com.repository.glasses.filesync com.repository.glasses.btmanager; do
        if adb -s "$ADB_SERIAL" shell "pm path $pkg 2>/dev/null" | grep -q '/data/app/'; then
            log "removing sideloaded $pkg from /data/app"
            adb -s "$ADB_SERIAL" uninstall "$pkg" >/dev/null || true
        fi
    done

    log "rebooting device so post-fs-data bind-mounts the new APKs and"
    log "PackageManager rescans /system/priv-app on next boot..."
    adb -s "$ADB_SERIAL" reboot

    log "waiting for device to come back up..."
    adb -s "$ADB_SERIAL" wait-for-device
    bc=""
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24; do
        bc="$(adb -s "$ADB_SERIAL" shell 'getprop sys.boot_completed' 2>/dev/null | tr -d '\r\n' || true)"
        [ "$bc" = "1" ] && break
        sleep 5
    done
    [ "$bc" = "1" ] || die "post-reboot did not complete (sys.boot_completed != 1)"

    # Tier-2 priv-app install verification. Bind-mounts at post-fs-data are
    # silent on failure -- if the source file is missing or the stub inode
    # is wrong, PackageManager scans an empty/1-byte APK and the package
    # never appears. Verify each priv-app package is present AND resolves
    # to /system/priv-app (not /data/app, which would mean a stale sideload
    # is shadowing the overlay). Retry briefly because PMS scans /priv-app
    # asynchronously after boot_completed.
    log "verifying all priv-app packages installed from /system/priv-app:"
    local pkg path ok
    for pkg in com.repository.glasses.listener com.repository.glasses.filesync com.repository.glasses.btmanager; do
        ok=""
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            path="$(adb -s "$ADB_SERIAL" shell "pm path $pkg 2>/dev/null" | tr -d '\r')"
            case "$path" in
                *"/system/priv-app/$pkg"*) ok=1; break ;;
                *"/data/app"*)             die "$pkg shadowed by /data/app sideload: $path" ;;
            esac
            sleep 2
        done
        [ -n "$ok" ] || die "$pkg not installed -- pm path returned: '${path:-<empty>}'. Check /data/local/diy-overlay/system/priv-app/$pkg/ source APK + diy-overlay.rc bind-mount + 1-byte stub in super_4."
        log "  $pkg -> $path"
    done

    log "verifying listener loaded as privileged install:"
    local cp granted
    cp="$(adb -s "$ADB_SERIAL" shell 'dumpsys package com.repository.glasses.listener | grep codePath' | tr -d '\r')"
    granted="$(adb -s "$ADB_SERIAL" shell 'dumpsys package com.repository.glasses.listener | grep BLUETOOTH_PRIVILEGED' | tr -d '\r')"
    log "  $cp"
    log "  $granted"
    case "$cp" in
        *"/system/priv-app"*) : ;;
        *) die "listener not loaded from /system/priv-app -- bind-mount or stub missing" ;;
    esac
    case "$granted" in
        *"granted=true"*) log "BLUETOOTH_PRIVILEGED granted -- pairing path is live." ;;
        *) die "BLUETOOTH_PRIVILEGED not granted -- privapp-permissions XML missing or malformed" ;;
    esac

    # Runtime grants / appops / system roles. Idempotent. MUST be kept in sync
    # with Recon/scripts/deploy-to-glasses.sh apply_runtime_grants. Privapp
    # XMLs cover signature/system grants; this block covers the remainder
    # (special-permission appops, runtime perms with no UI, role assignments).
    log "applying runtime grants / appops / roles"
    for pkg in com.repository.glasses.capture com.repository.glasses.filesync; do
        adb -s "$ADB_SERIAL" shell appops set "$pkg" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
    done
    adb -s "$ADB_SERIAL" shell appops set com.repository.glasses.listener WRITE_SETTINGS allow 2>/dev/null || true
    # BLUETOOTH_SCAN/CONNECT: Lone mode scans for nearby BT devices from the listener process.
    for perm in \
        android.permission.BLUETOOTH_SCAN \
        android.permission.BLUETOOTH_CONNECT; do
        adb -s "$ADB_SERIAL" shell pm grant com.repository.glasses.listener "$perm" 2>/dev/null || true
    done
    for perm in \
        android.permission.NETWORK_SETTINGS \
        android.permission.OVERRIDE_WIFI_CONFIG \
        android.permission.WRITE_SECURE_SETTINGS; do
        adb -s "$ADB_SERIAL" shell pm grant com.repository.glasses.filesync "$perm" 2>/dev/null || true
    done
    for perm in \
        android.permission.ACCESS_FINE_LOCATION \
        android.permission.ACCESS_COARSE_LOCATION; do
        adb -s "$ADB_SERIAL" shell pm grant com.repository.glasses.filesync "$perm" 2>/dev/null || true
    done
    adb -s "$ADB_SERIAL" shell cmd notification allow_listener \
        com.repository.glasses.listener/com.repository.glasses.listener.media.MediaNotificationListener 2>/dev/null || true
    adb -s "$ADB_SERIAL" shell cmd role add-role-holder android.app.role.HOME com.repository.glasses.listener 2>/dev/null || true

    # ScreenOffAccessibilityService bind verify. post-boot-fixups runs at
    # sys.boot_completed=1 and re-arms this; verify it actually bound, and
    # force-enable + re-trigger the script if not. Without this, doubletap
    # touchpad -> screen off and the capture-button accessibility hook
    # both no-op silently.
    local a11y_svc="com.repository.glasses.listener/.service.ScreenOffAccessibilityService"
    local bound=""
    for _ in 1 2 3 4 5 6; do
        bound="$(adb -s "$ADB_SERIAL" shell "dumpsys accessibility | grep -A2 'Enabled services' | grep -o ScreenOffAccessibilityService" 2>/dev/null | tr -d '\r')"
        [ -n "$bound" ] && break
        sleep 2
    done
    if [ -z "$bound" ]; then
        log "ScreenOffAccessibilityService not bound -- forcing enable + re-running post-boot-fixups"
        adb -s "$ADB_SERIAL" shell "settings put secure enabled_accessibility_services '$a11y_svc'; settings put secure accessibility_enabled 1; start post-boot-fixups" >/dev/null
        sleep 4
        bound="$(adb -s "$ADB_SERIAL" shell "dumpsys accessibility | grep -A2 'Enabled services' | grep -o ScreenOffAccessibilityService" 2>/dev/null | tr -d '\r')"
    fi
    case "$bound" in
        *ScreenOffAccessibilityService*) log "ScreenOffAccessibilityService bound -- doubletap screen-off + capture button live." ;;
        *) die "ScreenOffAccessibilityService still not bound after force-enable -- check listener install + service declaration" ;;
    esac
}

# ----- main -------------------------------------------------------------------

main() {
    cd "$SCRIPT_DIR"
    if [ "${1:-}" = "--post-flash" ]; then
        deploy_runtime_overlay
        return
    fi
    require_tools
    require_inputs
    stage_overlay_payload
    extract_system_img
    patch_buildprop_and_bootstat
    detect_sys_prefix
    patch_init_zygote_ld_preload
    delete_bloat
    inject_files
    verify_baked_files
    set_selinux_xattrs
    regen_avb_hashtree
    write_super_img
    emit_rawprogram_xml
    final_summary
}

main "$@"
