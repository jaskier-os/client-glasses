#!/usr/bin/env bash
# Live-stream glasses microphone to PC speakers.
#
# Captures the same mic the glasses app uses for LISTENING (raw, pre-gain).
# Audio plays through the system default sink in real time.
#
# Requirements: scrcpy >= 2.0 (Ubuntu: `sudo apt install scrcpy`)
# Glasses must be connected via ADB (USB or `adb connect <ip>`).
#
# Notes:
#   - --no-video: audio-only, no display window.
#   - --audio-source=mic: pulls from MIC (same source as the app).
#   - This captures the RAW mic at the hardware level, BEFORE the app's
#     24x software gain. If raw sounds clean and the upstream STT only
#     sees garbled audio, the gain is the culprit.
#   - To capture the post-gain stream that actually reaches anthropic-stt,
#     tap the phone-side decoded PCM instead (separate flow).

set -euo pipefail

GLASSES_SERIAL="${GLASSES_SERIAL:-}"

if ! command -v scrcpy >/dev/null 2>&1; then
  echo "scrcpy not found. Install with: sudo apt install scrcpy" >&2
  exit 1
fi

if ! adb -s "$GLASSES_SERIAL" get-state >/dev/null 2>&1; then
  echo "Glasses ($GLASSES_SERIAL) not connected via ADB" >&2
  echo "Available devices:" >&2
  adb devices >&2
  exit 1
fi

echo "Streaming mic from glasses ($GLASSES_SERIAL) -- Ctrl+C to stop"
exec scrcpy \
  --serial "$GLASSES_SERIAL" \
  --no-video \
  --audio-source=mic \
  --audio-codec=raw \
  --audio-buffer=50
