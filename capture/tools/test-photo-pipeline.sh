#!/usr/bin/env bash
# Photo-pipeline on-device regression test for the capture service.
#
# Guards the "func-button photo returns a black 1280x720 frame" bug: the photo
# path used to branch on VideoRecorder.isRecording() (a boolean that can stick
# `true` after a non-clean teardown) instead of the authoritative recorder-surface
# signal the FN button uses (cameraSession.isRecordingOutputActive()). When the
# two disagreed, a plain photo was routed into captureVideoSnapshot against a dead
# record session and came back black. Fixed by routing all photo/ReID/warmUp
# branches through isRecordingForCapture() == isRecordingOutputActive().
#
# Asserts:
#   A) plain photo (never recorded)            -> full RAW 1504/1512 x 2016, not black
#   B) photo right after a clean start/stop     -> full RAW (the desync window), not black
#   C) photo DURING an actual recording         -> 1280x720 snapshot, lit (not black), by design
#
# Requires: adb, python3 (PIL), the glasses on USB. Uses the ADB_* test hooks in
# CaptureService -- it does NOT install or clear anything.
set -euo pipefail

S="${GLASSES_SERIAL:-1901092544026001}"
SVC="com.repository.glasses.capture/.CaptureService"
RESDIR="/sdcard/Android/data/com.repository.glasses.capture/files/adb_results"
DCIM="/storage/emulated/0/DCIM/Repository"
TMP="$(mktemp -d)"
PKG_ACT="com.repository.glasses.capture.ADB_TAKE_PHOTO"
fail=0

adb -s "$S" shell setprop debug.glasses.capture.skip_denoise 1 >/dev/null  # fast finalize; path/res is what we test

take() { # id -> prints final JSON
  local id="$1"
  adb -s "$S" shell am startservice -n "$SVC" -a "$PKG_ACT" --es id "$id" >/dev/null
  for _ in $(seq 1 25); do
    local j; j="$(adb -s "$S" shell "cat $RESDIR/$id.json 2>/dev/null" | tr -d '\r')"
    if echo "$j" | grep -qE '"status":"ok"|"status":"error"|"source":"snapshot"'; then echo "$j"; return; fi
    sleep 2
  done
  echo '{"status":"timeout"}'
}

# Pull the newest IMG_*.jpg and print "WxH mean" via PIL.
check_img() { # expect_kind(full|snapshot) label
  local expect="$1" label="$2"
  local dev; dev="$(adb -s "$S" shell "ls -t $DCIM/IMG_*.jpg | head -1" | tr -d '\r')"
  local loc="$TMP/$(basename "$dev")"
  adb -s "$S" pull "$dev" "$loc" >/dev/null 2>&1
  python3 - "$loc" "$expect" "$label" <<'PY'
import sys; from PIL import Image; import numpy as np
loc, expect, label = sys.argv[1], sys.argv[2], sys.argv[3]
im = np.asarray(Image.open(loc).convert("RGB")); h,w,_ = im.shape
mean = float(im.mean()); black = mean < 3.0
full = (w in (1504,1512) and h == 2016)
snap = (w,h) == (720,1280) or (w,h) == (1280,720)
ok = (not black) and (full if expect=="full" else snap)
print(f"  [{'PASS' if ok else 'FAIL'}] {label}: {w}x{h} mean={mean:.1f} "
      f"({'black' if black else 'lit'}, {'full-RAW' if full else 'snapshot' if snap else 'OTHER'})")
sys.exit(0 if ok else 1)
PY
}

echo "### A: plain photo (never recorded) -> full RAW, lit"
take "tA_$(date +%s)" >/dev/null
check_img full "A plain" || fail=1

echo "### B: photo right after clean start/stop (desync window) -> full RAW, lit"
adb -s "$S" shell am startservice -n "$SVC" -a com.repository.glasses.capture.ADB_START_VIDEO >/dev/null; sleep 5
adb -s "$S" shell am startservice -n "$SVC" -a com.repository.glasses.capture.ADB_STOP_VIDEO  >/dev/null; sleep 1
take "tB_$(date +%s)" >/dev/null
check_img full "B post-stop" || fail=1

echo "### C: photo DURING recording -> 1280x720 snapshot, lit (by design)"
adb -s "$S" shell am startservice -n "$SVC" -a com.repository.glasses.capture.ADB_START_VIDEO >/dev/null; sleep 4
take "tC_$(date +%s)" >/dev/null
check_img snapshot "C during-rec" || fail=1
adb -s "$S" shell am startservice -n "$SVC" -a com.repository.glasses.capture.ADB_STOP_VIDEO >/dev/null; sleep 2
led="$(adb -s "$S" shell cat /sys/class/leds/white/brightness | tr -d '\r')"
if [ "$led" = "0" ]; then echo "  [PASS] LED off after stop"; else echo "  [FAIL] LED stuck at $led after stop"; fail=1; fi

rm -rf "$TMP"
echo
if [ "$fail" = "0" ]; then echo "ALL PASS"; else echo "FAILURES PRESENT"; fi
exit "$fail"
