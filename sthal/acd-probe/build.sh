#!/usr/bin/env bash
# build.sh — one-shot NDK build of acd-probe for arm64-v8a / API 31.
#
# Does NOT auto-deploy. After build prints the binary path and the exact
# adb command to push+run on the glasses (serial $SERIAL).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"

: "${ANDROID_NDK:=${ANDROID_NDK_HOME:-}}"
if [[ -z "${ANDROID_NDK}" ]]; then
    for cand in \
        "${HOME}/Android/Sdk/ndk/27.1.12297006" \
        "${HOME}/Android/Sdk/ndk/26.1.10909125" \
        "${HOME}/Android/Sdk/ndk/25.1.8937393" \
        "/opt/android-ndk"; do
        if [[ -d "${cand}" ]]; then ANDROID_NDK="${cand}"; break; fi
    done
fi
if [[ -z "${ANDROID_NDK}" || ! -d "${ANDROID_NDK}" ]]; then
    echo "ERROR: ANDROID_NDK not found. Set ANDROID_NDK env var (sibling sthal/deploy.sh has the same probe)." >&2
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
    echo "ERROR: cmake not found." >&2
    exit 3
fi

echo "== Configure (NDK=${ANDROID_NDK}) =="
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
"${CMAKE_BIN}" -S "${SCRIPT_DIR}" -B "${BUILD_DIR}" \
    -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-31 \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo

echo "== Build =="
"${CMAKE_BIN}" --build "${BUILD_DIR}" -j

BIN="${BUILD_DIR}/acd-probe"
if [[ ! -x "${BIN}" ]]; then
    echo "ERROR: build did not produce ${BIN}" >&2
    exit 4
fi

echo
echo "Built: ${BIN}"
file "${BIN}" 2>/dev/null || true
echo
cat <<EOF
== Deploy + run on glasses (serial $SERIAL) ==
adb ${SERIAL:+-s $SERIAL} push "${BIN}" /data/local/tmp/acd-probe
adb ${SERIAL:+-s $SERIAL} shell chmod +x /data/local/tmp/acd-probe
adb ${SERIAL:+-s $SERIAL} shell /data/local/tmp/acd-probe

# In another shell, watch the PAL stack:
adb ${SERIAL:+-s $SERIAL} shell logcat -c
adb ${SERIAL:+-s $SERIAL} shell logcat -b all | \\
    grep -iE 'acd-probe|StreamACD|pal_stream_open|PAL_STREAM_ACD|acd_session|LPI|low_power_island|configureLpi|setupSessionDevice'
EOF
