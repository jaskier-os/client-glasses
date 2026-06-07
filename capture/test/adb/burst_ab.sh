#!/bin/bash
# A/B test: BURST_N=3 vs BURST_N=1, same scene, via ADB harness.
#
# Usage: bash burst_ab.sh [device_serial]
#
# Outputs:
#   /tmp/burst_ab/burst3.jpg
#   /tmp/burst_ab/single1.jpg
#   /tmp/burst_ab/sbs_plain.png (center 768 crop, no gamma)
#   Prints laplacian variance + bytes for each.
#
# Leaves device on BURST_N=3 at exit.

set -eu

DEVICE="${1:-${ADB_SERIAL:-}}"
# Resolve the project root from this script's location
# (capture/test/adb/burst_ab.sh -> repo root is three levels up from capture/).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GLASSES_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
PH="$GLASSES_DIR/capture/src/main/java/com/repository/glasses/capture/PhotoCapturer.kt"
CAPTURE_APK="$GLASSES_DIR/capture/build/outputs/apk/debug/capture-debug.apk"
TRIGGER="$GLASSES_DIR/capture/test/adb/take_photo.sh"

OUT="/tmp/burst_ab"
mkdir -p "$OUT"

set_burst_n() {
    # Rewrite the BURST_N constant line (preserving indentation + comment tail).
    sed -i -E "s/^(\s*private const val BURST_N = )[0-9]+( *.*)?$/\1$1\2/" "$PH"
}

build_install() {
    "$GLASSES_DIR/gradlew" -p "$GLASSES_DIR" :capture:assembleDebug -q >/dev/null
    adb -s "$DEVICE" install -r "$CAPTURE_APK" >/dev/null
}

pull_latest() {
    local dst="$1"
    local path
    path=$(adb -s "$DEVICE" shell 'ls -t /storage/emulated/0/DCIM/Repository/*.jpg | head -1' | tr -d '\r\n')
    adb -s "$DEVICE" pull "$path" "$dst" >/dev/null
}

analyze() {
    python3 - <<PY
from PIL import Image
import numpy as np
from scipy.ndimage import laplace
import os
for label, p in [("SINGLE (N=1)", "$OUT/single1.jpg"), ("BURST  (N=3)", "$OUT/burst3.jpg")]:
    im = Image.open(p).convert("RGB")
    a = np.asarray(im, dtype=np.float32) / 255.
    g = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    print(f"{label}  {im.size[0]}x{im.size[1]}  bytes={os.path.getsize(p)//1024}KB  meanY={g.mean():.3f}  laplVar={laplace(g).var()*1e6:.0f}")
# Plain crop side-by-side, no gamma
crops = []
for p in ["$OUT/single1.jpg", "$OUT/burst3.jpg"]:
    im = Image.open(p).convert("RGB")
    w, h = im.size; cx, cy = w // 2, h // 2; S = 768
    crops.append(np.asarray(im.crop((cx - S // 2, cy - S // 2, cx + S // 2, cy + S // 2))))
Image.fromarray(np.concatenate(crops, axis=1)).save("$OUT/sbs_plain.png")
print("$OUT/sbs_plain.png")
PY
}

echo "=== BURST_N=3 ==="
set_burst_n 3
build_install
bash "$TRIGGER" "$DEVICE"
sleep 1
pull_latest "$OUT/burst3.jpg"

echo "=== BURST_N=1 ==="
set_burst_n 1
build_install
bash "$TRIGGER" "$DEVICE"
sleep 1
pull_latest "$OUT/single1.jpg"

echo "=== restore BURST_N=3 ==="
set_burst_n 3
build_install

echo "=== analysis ==="
analyze
