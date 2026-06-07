#!/usr/bin/env bash
# sthal/deploy.sh
#
# DANGEROUS: deploys a system HAL replacement to the glasses via the DIY
# overlay. A buggy HAL takes audioserver down. First-time deploy requires
# explicit authorization via GLASSES_STHAL_DEPLOY=1 AND USB cable plugged for
# rollback.
#
# What gets deployed (all under /data/local/diy-overlay/ so the magisk-style
# post-fs-data overlay script bind-mounts them over /system and /vendor):
#   1. sound_trigger.primary.neo.so          -- our custom HAL
#   2. privapp-permissions-*.xml             -- CAPTURE_AUDIO_HOTWORD grant
#   3. libQnnHtp.so + libQnnSystem.so        -- QNN runtime (ARM host-side)
#   4. libQnnHtpV73Skel.so                   -- Hexagon v73 aDSP skel
#   5. <stage>.bin x N                       -- wake-word pipeline graphs
#
# Source of the QNN libs: QAIRT SDK at $QNN_SDK_ROOT. On this machine the SDK
# is installed under $HOME/qairt/2.45.0.260326/ (set $QNN_SDK_ROOT
# accordingly or source $HOME/qairt-env.sh before running).
#
# NOTE: for privapp-permissions to take effect the APK must also live at
# /system/priv-app/com.repository.glasses.listener/. Regular `pm install`
# does NOT make the app privileged. See DESIGN.md privapp-permissions.
#
# Rollback: `adb -s <serial> shell rm /data/local/diy-overlay/vendor/lib64/hw/sound_trigger.primary.neo.so && adb reboot`

set -euo pipefail

if [[ "${GLASSES_STHAL_DEPLOY:-0}" != "1" ]]; then
    cat >&2 <<EOF
deploy.sh refuses to run without explicit authorization.
  This replaces /vendor/lib64/hw/sound_trigger.primary.neo.so (via overlay).
  A buggy HAL = audioserver crash loop. Ensure USB cable is attached so you
  can rm the overlay file and reboot if it bricks audio.
  To proceed: GLASSES_STHAL_DEPLOY=1 bash deploy.sh
EOF
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STHAL_DIR="${SCRIPT_DIR}"
BUILD_DIR="${STHAL_DIR}/build"
SERIAL="${ADB_SERIAL:-}"
if [ -z "$SERIAL" ]; then SERIAL="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | head -1)"; fi

: "${ANDROID_NDK:=${ANDROID_NDK_HOME:-}}"
if [[ -z "${ANDROID_NDK}" ]]; then
    for cand in \
        "${HOME}/Android/Sdk/ndk/27.1.12297006" \
        "${HOME}/Android/Sdk/ndk/25.1.8937393" \
        "/opt/android-ndk"; do
        if [[ -d "${cand}" ]]; then ANDROID_NDK="${cand}"; break; fi
    done
fi
if [[ -z "${ANDROID_NDK}" || ! -d "${ANDROID_NDK}" ]]; then
    echo "ERROR: ANDROID_NDK not found; set ANDROID_NDK env var." >&2
    exit 2
fi

: "${CMAKE_BIN:=}"
if [[ -z "${CMAKE_BIN}" ]]; then
    for cand in \
        "${HOME}/Android/Sdk/cmake/3.22.1/bin/cmake" \
        "$(command -v cmake 2>/dev/null || true)"; do
        if [[ -n "${cand}" && -x "${cand}" ]]; then CMAKE_BIN="${cand}"; break; fi
    done
fi
if [[ -z "${CMAKE_BIN}" ]]; then
    echo "ERROR: cmake not found. Install via SDK manager or apt." >&2
    exit 3
fi

echo "== Build =="
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
"${CMAKE_BIN}" \
    -S "${STHAL_DIR}" -B "${BUILD_DIR}" \
    -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-31 \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo
"${CMAKE_BIN}" --build "${BUILD_DIR}" -j

SO="${BUILD_DIR}/sound_trigger.primary.neo.so"
if [[ ! -f "${SO}" ]]; then
    echo "ERROR: build did not produce ${SO}" >&2
    exit 4
fi

echo "== Sanity =="
# NOTE: avoid `grep -q` in a pipe under `set -o pipefail` -- grep -q closes stdin
# after first match, which sends SIGPIPE (exit 141) to the upstream echo/nm, and
# pipefail then reports the whole pipeline as failed.
# Read full nm output into a variable and use bash pattern match instead.
NM_OUT="$("${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" "${SO}")"
if [[ ! "${NM_OUT}" =~ (^|$'\n')[0-9a-f]+\ [DTBW]\ HMI($|$'\n') ]]; then
    echo "ERROR: HAL_MODULE_INFO_SYM (HMI) not exported in ${SO}" >&2
    exit 5
fi

echo "== Push to overlay =="
adb -s "${SERIAL}" wait-for-device
# Overlay target layout:
#   vendor/lib64/hw/   -- HAL .so (stock HAL lives at /vendor/lib64/hw/, overlay
#                         must target that path; /system/lib64/hw/ has no HAL
#                         to shadow, so the framework never loads from there)
#   system/lib64/      -- libQnnHtp.so + libQnnSystem.so (QNN runtime stubs
#                         injected into system.img by root-firmware.sh Step 3c)
#   system/etc/permissions/ -- privapp permissions XML (stub injected by firmware)
#   system/etc/sthal/models/ -- wake-word QNN context binaries (stubs injected
#                               by firmware; dir created inside system.img)
#   system/priv-app/com.repository.glasses.listener/ -- listener APK privapp
#                                                       mountpoint (stub injected)
#   system/lib/rfsa/adsp/   -- Hexagon aDSP skel libs. Stock /vendor/dsp/adsp/
#                              and /vendor/lib/rfsa/adsp/ lack a Qnn skel target
#                              to bind over; Android's libadsprpc also searches
#                              /system/lib/rfsa/adsp/ as DSP_LIBRARY_PATH, so we
#                              inject stub + overlay there instead.
adb -s "${SERIAL}" shell 'mkdir -p \
    /data/local/diy-overlay/vendor/lib64/hw \
    /data/local/diy-overlay/system/lib64 \
    /data/local/diy-overlay/system/etc/permissions \
    /data/local/diy-overlay/system/etc/sthal/models \
    /data/local/diy-overlay/system/lib/rfsa/adsp'

# 1. HAL .so -- overlays the stock vendor HAL at /vendor/lib64/hw/
adb -s "${SERIAL}" push "${SO}" /data/local/diy-overlay/vendor/lib64/hw/sound_trigger.primary.neo.so

# 2. privapp permissions XML
adb -s "${SERIAL}" push "${STHAL_DIR}/priv-permissions.xml" \
    /data/local/diy-overlay/system/etc/permissions/privapp-permissions-com.repository.glasses.listener.xml

# 3. QNN runtime libs (ARM host-side, loaded by the HAL via dlopen).
#    Skipped if already present unless FORCE_QNN_RUNTIME=1.
: "${QNN_SDK_ROOT:=${HOME}/qairt/2.45.0.260326}"
: "${QNN_HTP_ARCH:=v73}"   # Rokid Neo aDSP (Luna-V2)
if [[ ! -d "${QNN_SDK_ROOT}" ]]; then
    echo "WARN: QNN_SDK_ROOT=${QNN_SDK_ROOT} missing; skipping QNN runtime push."
    echo "      HAL will fall back to Null runtime and detection will not fire."
else
    HTP_LIB="${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtp.so"
    SYS_LIB="${QNN_SDK_ROOT}/lib/aarch64-android/libQnnSystem.so"
    HTP_STUB_LIB="${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtp${QNN_HTP_ARCH^^}Stub.so"
    SKEL_LIB="${QNN_SDK_ROOT}/lib/hexagon-${QNN_HTP_ARCH}/unsigned/libQnnHtp${QNN_HTP_ARCH^^}Skel.so"

    check_present() {
        local remote="$1"
        adb -s "${SERIAL}" shell "[ -f ${remote} ] && echo YES || echo NO" 2>/dev/null | tr -d '\r\n'
    }

    for src in "${HTP_LIB}" "${SYS_LIB}"; do
        [[ -f "${src}" ]] || { echo "WARN: missing ${src}"; continue; }
        name=$(basename "${src}")
        dst="/data/local/diy-overlay/system/lib64/${name}"
        if [[ "${FORCE_QNN_RUNTIME:-0}" = "1" ]] || [[ "$(check_present "${dst}")" = "NO" ]]; then
            echo "  push ${name} -> ${dst}"
            adb -s "${SERIAL}" push "${src}" "${dst}"
        else
            echo "  skip ${name} (already at ${dst}; FORCE_QNN_RUNTIME=1 to overwrite)"
        fi
    done

    # The per-arch stub lives next to libQnnHtp.so so the HTP backend can load it at runtime.
    if [[ -f "${HTP_STUB_LIB}" ]]; then
        dst="/data/local/diy-overlay/system/lib64/$(basename "${HTP_STUB_LIB}")"
        if [[ "${FORCE_QNN_RUNTIME:-0}" = "1" ]] || [[ "$(check_present "${dst}")" = "NO" ]]; then
            echo "  push $(basename "${HTP_STUB_LIB}") -> ${dst}"
            adb -s "${SERIAL}" push "${HTP_STUB_LIB}" "${dst}"
        fi
    fi

    # 4. Hexagon aDSP skel. Stock /vendor/dsp/adsp/ and /vendor/lib/rfsa/adsp/
    #    have no Qnn skel target to bind-mount over, so we overlay on
    #    /system/lib/rfsa/adsp/ (a DSP_LIBRARY_PATH fallback searched by
    #    libadsprpc). The stub + directory are injected into system.img by
    #    root-firmware.sh Step 3c.
    if [[ -f "${SKEL_LIB}" ]]; then
        dst="/data/local/diy-overlay/system/lib/rfsa/adsp/$(basename "${SKEL_LIB}")"
        if [[ "${FORCE_QNN_RUNTIME:-0}" = "1" ]] || [[ "$(check_present "${dst}")" = "NO" ]]; then
            echo "  push $(basename "${SKEL_LIB}") -> ${dst}"
            adb -s "${SERIAL}" push "${SKEL_LIB}" "${dst}"
        else
            echo "  skip $(basename "${SKEL_LIB}") (already present)"
        fi
    else
        echo "WARN: skel ${SKEL_LIB} missing -- aDSP execution will fail."
    fi
fi

# 5. Wake-word pipeline graphs. One context binary per stage. HAL walks the
#    directory for known stage names (silero_vad, melspectrogram,
#    embedding_model, sireneviy) and loads whichever exist.
pushed_any=0
for stage in silero_vad melspectrogram embedding_model sireneviy wakeword; do
    src="${STHAL_DIR}/models/${stage}.bin"
    if [[ -f "${src}" ]]; then
        dst="/data/local/diy-overlay/system/etc/sthal/models/${stage}.bin"
        echo "  push ${stage}.bin -> ${dst}"
        adb -s "${SERIAL}" push "${src}" "${dst}"
        pushed_any=1
    fi
done
if [[ "${pushed_any}" = "0" ]]; then
    echo "NOTE: no ${STHAL_DIR}/models/*.bin present."
    echo "      Run sthal/tools/compile_onnx_to_qnn.sh first; HAL will otherwise find no graphs."
fi

echo "== Reboot and wait =="
adb -s "${SERIAL}" reboot
adb -s "${SERIAL}" wait-for-device
sleep 5

echo "== Verify =="
adb -s "${SERIAL}" shell 'dumpsys media.sound_trigger_hw 2>&1 | head -60' || true
adb -s "${SERIAL}" shell 'logcat -d -t 2000 | grep -iE "sthal|sound_trigger" | head -40' || true

cat <<'EOF'

== Persistence model ==

Overlay files pushed to /data/local/diy-overlay/ are the Tier-2 iteration
layer. They bind-mount ON TOP of the Tier-1 baked-in versions that live
inside super.img (written there by root-firmware.sh Step 3c).

If /data is wiped (factory reset / FBE rekey), the Tier-2 overlay is gone
BUT Tier 1 remains -- Phase 3 still works on first boot after the wipe.

To revert to the baked-in Tier-1 defaults (drop your iteration overlay):
    adb -s <serial> shell 'rm -rf /data/local/diy-overlay/*' && adb reboot

To update Tier 1 (new baked-in baseline): rebuild super via
root-firmware.sh and reflash via EDL/qdl. Do this only when shipping a
new stable baseline; day-to-day iteration stays in Tier 2.

== Next step: privapp install ==

The HAL .so, QNN runtime libs, Hexagon skels, QNN graph binaries, and
privapp-permissions XML have been pushed. The listener APK has NOT been
promoted to a privileged app yet, so CAPTURE_AUDIO_HOTWORD is still denied
and the HAL path stays dormant even though everything else is in place.

Note: the APK is NOT baked into Tier 1 (176MB debug APK doesn't fit the
system partition). You MUST run deploy-privapp.sh after every /data wipe
to restore the privapp overlay, or the listener app won't run as a priv
app after the wipe. Other Phase-3 content (HAL/QNN/models/XML) survives
a wipe from Tier 1.

To finish: run the separate privapp installer (distinct risk profile, so
it has its own env-var gate):

    GLASSES_PRIVAPP_INSTALL=1 bash deploy-privapp.sh

Then reboot. See deploy-privapp.sh header for the full risk writeup and
rollback instructions.
EOF

echo "DONE"
