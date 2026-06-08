#!/usr/bin/env bash
# Cross-compile sinkconn-hook wrapper + library for arm64 Android (API 32).
# Mirrors the toolchain used by AI/clients/glasses/touchpad-daemon/build.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
NDK_VER="${NDK_VER:-27.1.12297006}"
NDK_DIR="$SDK_DIR/ndk/$NDK_VER"
API="${API:-32}"

CLANG="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
if [ ! -x "$CLANG" ]; then
    echo "ERROR: missing NDK clang at $CLANG" >&2
    echo "Set NDK_VER or ANDROID_SDK_ROOT if your NDK lives elsewhere." >&2
    exit 1
fi

OUT_DIR="$HERE/build"
mkdir -p "$OUT_DIR"

WRAPPER_OUT="$OUT_DIR/app_process64"
HOOK_OUT="$OUT_DIR/libsinkconn_hook.so"

echo "[sinkconn-hook] compiling wrapper..."
"$CLANG" \
    -Wall -Wextra -Wpedantic -std=c11 \
    -O2 \
    -Wl,-z,max-page-size=16384 \
    -o "$WRAPPER_OUT" \
    "$HERE/src/wrapper.c" \
    -ldl

echo "[sinkconn-hook] compiling hook lib..."
"$CLANG" \
    -Wall -Wextra -Wpedantic -std=c11 \
    -O2 \
    -fPIC -shared \
    -fvisibility=hidden \
    -Wl,-z,max-page-size=16384 \
    -Wl,--no-undefined \
    -o "$HOOK_OUT" \
    "$HERE/src/hook.c" \
    -llog

echo ""
echo "[sinkconn-hook] built:"
ls -la "$WRAPPER_OUT" "$HOOK_OUT"
file "$WRAPPER_OUT" 2>/dev/null || true
file "$HOOK_OUT" 2>/dev/null || true
