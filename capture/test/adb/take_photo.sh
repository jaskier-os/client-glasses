#!/bin/bash
# Trigger a glasses photo capture via ADB broadcast, poll result JSON.
#
# Usage: bash take_photo.sh [adb_device_serial]
#
# The glasses capture APK's CaptureService.onStartCommand handles
# ACTION_ADB_TAKE_PHOTO. It writes a JSON result to
#   /sdcard/Android/data/com.repository.glasses.capture/files/adb_results/<id>.json
# once capture+merge completes. We poll up to POLL_SECONDS and print the JSON.
#
# Works regardless of listener/MainActivity state; no keyevents involved.

set -eu

DEVICE="${1:-${ADB_SERIAL:-}}"
BURST_N="${2:-0}"  # 0 = use app default (3); 1/2/... override per-request
POLL_SECONDS="${POLL_SECONDS:-120}"
ID="cap_$(date +%s)_$$"

ADB=(adb -s "$DEVICE")

# Foreground-service start: Android 12+ blocks plain startservice from
# shell UID. CaptureService already declares foregroundServiceType=camera.
EXTRA_BURST=""
if [ "$BURST_N" -gt 0 ] 2>/dev/null; then
  EXTRA_BURST="--ei burst_n $BURST_N"
fi
"${ADB[@]}" shell am start-foreground-service \
  -n com.repository.glasses.capture/.CaptureService \
  -a com.repository.glasses.capture.ADB_TAKE_PHOTO \
  --es id "$ID" \
  $EXTRA_BURST \
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
