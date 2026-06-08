#!/usr/bin/env bash
# Cross-compile liba2dpduck_hook.so for arm64 Android (API 32).
# Mirrors sinkconn-hook/build.sh. Hook only (reuses the zygote LD_PRELOAD
# injection chain; no separate wrapper).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
NDK_VER="${NDK_VER:-27.1.12297006}"
NDK_DIR="$SDK_DIR/ndk/$NDK_VER"
API="${API:-32}"

CLANG="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"
if [ ! -x "$CLANG" ]; then
    echo "ERROR: missing NDK clang at $CLANG" >&2
    exit 1
fi

OUT_DIR="$HERE/build"
mkdir -p "$OUT_DIR"
HOOK_OUT="$OUT_DIR/liba2dpduck_hook.so"

echo "[a2dpduck-hook] compiling hook lib..."
"$CLANG" \
    -Wall -Wextra -Wpedantic -std=c11 \
    -O2 \
    -fPIC -shared \
    -fvisibility=hidden \
    -Wl,-z,max-page-size=16384 \
    -Wl,--no-undefined \
    -Wl,--version-script="$HERE/src/version.map" \
    -o "$HOOK_OUT" \
    "$HERE/src/hook.c" \
    -llog

echo "[a2dpduck-hook] built:"
ls -la "$HOOK_OUT"
file "$HOOK_OUT" 2>/dev/null || true
