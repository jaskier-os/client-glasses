#!/usr/bin/env bash
# Cross-compile the touchpad daemon for arm64 Android (API 32) using NDK.
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

CFLAGS=(-Wall -Wextra -Wpedantic -std=c11 -O2 -static-libgcc -Wl,-z,max-page-size=16384)

# Single daemon — kmsg/event1 path + direct-I2C ABS_X path in one binary.
# ATRACE: written via direct trace_marker writes (no libandroid/libcutils
# dependency), see comment in main.c. So no extra link flags needed.
OUT="$OUT_DIR/rokid-touchpad-daemon"
"$CLANG" "${CFLAGS[@]}" -o "$OUT" "$HERE/src/main.c"
echo "Built: $OUT"
ls -la "$OUT"
