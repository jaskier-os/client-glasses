#!/usr/bin/env bash
# Cross-compile appsu (permissive setuid-root helper) for arm64 Android (API 32).
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
OUT="$OUT_DIR/appsud"

"$CLANG" \
    -Wall -Wextra -Wpedantic -std=c11 \
    -O2 \
    -static-libgcc \
    -Wl,-z,max-page-size=16384 \
    -o "$OUT" \
    "$HERE/src/appsud.c"

echo "Built: $OUT"
ls -la "$OUT"
file "$OUT" 2>/dev/null || true
