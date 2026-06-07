#!/bin/bash
# Trigger a RAW_SENSOR capture on the glasses and get a DNG back.
#
# Usage: bash take_raw.sh [device_serial]
#
# Output: JSON on stdout with {id, path, bytes, durationMs}.
# The .dng file is placed in /storage/emulated/0/DCIM/Repository/ on device.

set -eu
DEVICE="${1:-${ADB_SERIAL:-}}"
ISO="${2:-0}"           # 0 = use TARGET_ISO (6400)
EXPOSURE_MS="${3:-0}"   # 0 = use EXPOSURE_MS constant (313)
POLL_SECONDS="${POLL_SECONDS:-60}"
ID="raw_$(date +%s)_$$"

ADB=(adb -s "$DEVICE")
EXTRA=""
if [ "$ISO" -gt 0 ] 2>/dev/null; then EXTRA="$EXTRA --ei iso $ISO"; fi
if [ "$EXPOSURE_MS" -gt 0 ] 2>/dev/null; then EXTRA="$EXTRA --ei exposure_ms $EXPOSURE_MS"; fi
"${ADB[@]}" shell am start-foreground-service \
  -n com.repository.glasses.capture/.CaptureService \
  -a com.repository.glasses.capture.ADB_TAKE_RAW \
  --es id "$ID" \
  $EXTRA \
  >/dev/null

DST="/sdcard/Android/data/com.repository.glasses.capture/files/adb_results/${ID}.json"
deadline=$(( $(date +%s) + POLL_SECONDS ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  JSON=$("${ADB[@]}" shell "cat $DST 2>/dev/null")
  if [ -n "$JSON" ] && ! echo "$JSON" | grep -q '"status":"pending"'; then
    echo "$JSON"
    exit 0
  fi
  sleep 1
done
echo "timeout waiting for $ID (last: ${JSON:-<none>})" >&2
exit 1
