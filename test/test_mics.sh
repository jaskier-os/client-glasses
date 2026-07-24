#!/usr/bin/env bash
# Live-stream glasses microphones to PC speakers for testing.
#
# Rokid Glasses mic routing (determined by channel count, NOT AudioSource):
#   INWARD (back/temple) mic: AudioRecord MONO at 16kHz -- captures the WEARER
#   FRONT (outward array) mic: AudioRecord 4-channel at 16kHz -- captures person you FACE
#
# Usage:
#   bash test_mics.sh inward    # live stream inward/back mic (your voice)
#   bash test_mics.sh front     # record front mic via translation pipeline, then play
#   bash test_mics.sh array     # record all 8 mic channels, split into separate WAVs, play each
#
# Ctrl+C to stop (inward mode).

set -euo pipefail

GLASSES_SERIAL="${GLASSES_SERIAL:-}"
if [ -z "$GLASSES_SERIAL" ]; then GLASSES_SERIAL="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | head -1)"; fi
PHONE_SERIAL="${PHONE_SERIAL:-}"
DURATION_SEC="${DURATION_SEC:-10}"
MODE="${1:-inward}"
DUMP_FILE="/tmp/glasses_front_mic.pcm"
WAV_FILE="/tmp/glasses_front_mic.wav"
TRANSCRIBER_ENV="${TRANSCRIBER_ENV:-}"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v adb >/dev/null 2>&1 || die "adb not found"
adb -s "$GLASSES_SERIAL" get-state >/dev/null 2>&1 || die "Glasses ($GLASSES_SERIAL) not connected"

case "$MODE" in
  inward)
    command -v scrcpy >/dev/null 2>&1 || die "scrcpy not found (apt install scrcpy)"
    echo "=== INWARD (back/temple) mic -- captures YOUR voice ==="
    echo "  Method: scrcpy (Android AudioRecord MONO -> back mic)"
    echo "  Press Ctrl+C to stop"
    echo ""
    exec scrcpy \
      --serial "$GLASSES_SERIAL" \
      --no-video \
      --audio-source=mic \
      --audio-codec=raw \
      --audio-buffer=50
    ;;

  front)
    adb -s "$PHONE_SERIAL" get-state >/dev/null 2>&1 || die "Phone ($PHONE_SERIAL) not connected"
    command -v ffplay >/dev/null 2>&1 || die "ffplay not found (apt install ffmpeg)"

    echo "=== FRONT (outward array) mic -- captures person you FACE ==="
    echo "  Method: start translation -> front mic audio flows to local transcriber"
    echo "  -> raw PCM dumped to $DUMP_FILE -> converted to WAV -> played"
    echo ""

    # Enable audio dump in transcriber (optional cross-service integration).
    rm -f "$DUMP_FILE"
    if [ -n "$TRANSCRIBER_ENV" ] && [ -f "$TRANSCRIBER_ENV" ]; then
      if grep -q "^AUDIO_DUMP_PATH=" "$TRANSCRIBER_ENV" 2>/dev/null; then
        sed -i "s|^AUDIO_DUMP_PATH=.*|AUDIO_DUMP_PATH=$DUMP_FILE|" "$TRANSCRIBER_ENV"
      else
        # Ensure trailing newline before appending
        [ -n "$(tail -c1 "$TRANSCRIBER_ENV")" ] && echo "" >> "$TRANSCRIBER_ENV"
        echo "AUDIO_DUMP_PATH=$DUMP_FILE" >> "$TRANSCRIBER_ENV"
      fi
      echo "  Restarting transcriber with audio dump enabled..."
      sudo systemctl restart transcriber
      sleep 12  # wait for model load
    else
      echo "  TRANSCRIBER_ENV not set; skipping transcriber audio-dump wiring."
    fi

    echo "  Starting translation for ${DURATION_SEC}s -- speak toward the FRONT of the glasses..."
    adb -s "$PHONE_SERIAL" shell am broadcast \
      -n com.repository.listener/.adb.AdbCommandReceiver \
      -a com.repository.listener.ADB_COMMAND \
      --es type "start_translation" \
      --es command_id "mic_test_front" \
      --es params '{"from_language":"en","to_language":"ru","from_nllb":"eng_Latn","to_nllb":"rus_Cyrl","font_size":14,"audio_source":"glasses","provider":"default"}' \
      >/dev/null 2>&1

    sleep "$DURATION_SEC"

    echo "  Stopping translation..."
    adb -s "$PHONE_SERIAL" shell am broadcast \
      -n com.repository.listener/.adb.AdbCommandReceiver \
      -a com.repository.listener.ADB_COMMAND \
      --es type "stop_translation" \
      --es command_id "mic_test_front_stop" \
      --es params '{}' \
      >/dev/null 2>&1

    sleep 2

    # Disable audio dump
    if [ -n "$TRANSCRIBER_ENV" ] && [ -f "$TRANSCRIBER_ENV" ]; then
      sed -i "s|^AUDIO_DUMP_PATH=.*|AUDIO_DUMP_PATH=|" "$TRANSCRIBER_ENV"
    fi

    if [ ! -f "$DUMP_FILE" ] || [ "$(stat -c%s "$DUMP_FILE" 2>/dev/null)" -lt 100 ]; then
      echo "  No audio captured. The transcriber may not have received audio."
      echo "  Check: journalctl -u transcriber --since '2 min ago' | grep -i dump"
      exit 1
    fi

    # Convert raw PCM to WAV
    local_size=$(stat -c%s "$DUMP_FILE")
    python3 -c "
import struct
with open('$DUMP_FILE', 'rb') as f:
    pcm = f.read()
rate = 16000
nch = 1
bps = 16
with open('$WAV_FILE', 'wb') as f:
    f.write(b'RIFF')
    f.write(struct.pack('<I', 36 + len(pcm)))
    f.write(b'WAVEfmt ')
    f.write(struct.pack('<IHHIIHH', 16, 1, nch, rate, rate*nch*2, nch*2, bps))
    f.write(b'data')
    f.write(struct.pack('<I', len(pcm)))
    f.write(pcm)
dur = len(pcm) / (rate * nch * 2)
print(f'  WAV: {dur:.1f}s, {len(pcm)} bytes -> $WAV_FILE')
"

    echo "  Playing front mic recording..."
    ffplay -nodisp -autoexit -loglevel warning "$WAV_FILE"

    echo ""
    echo "  Done. WAV saved at: $WAV_FILE"
    ;;

  array)
    command -v ffplay >/dev/null 2>&1 || die "ffplay not found (apt install ffmpeg)"
    command -v python3 >/dev/null 2>&1 || die "python3 not found"

    ARRAY_DURATION="${DURATION_SEC}"
    TIMESTAMP="$(date +%H%M%S)"
    BEAMFORM_LABEL=""
    if [ "${BEAMFORM_SCENE:--1}" = "1" ]; then BEAMFORM_LABEL="_cardioid"
    elif [ "${BEAMFORM_SCENE:--1}" = "2" ]; then BEAMFORM_LABEL="_omni"
    elif [ "${BEAMFORM_SCENE:--1}" = "0" ]; then BEAMFORM_LABEL="_idle"
    fi
    ARRAY_PCM="/tmp/glasses_mic_8ch${BEAMFORM_LABEL}_${TIMESTAMP}.pcm"
    ARRAY_DIR="/tmp/glasses_mic${BEAMFORM_LABEL}_${TIMESTAMP}"

    echo "=== MIC ARRAY -- all 8 channels separately ==="
    echo "  Method: ADB broadcast -> glasses records 8-ch PCM -> pull -> split -> play"
    echo "  Duration: ${ARRAY_DURATION}s"
    echo ""

    # Trigger recording on glasses via TEST_COMMAND broadcast
    BEAMFORM_SCENE="${BEAMFORM_SCENE:--1}"
    if [ "$BEAMFORM_SCENE" -ge 0 ] 2>/dev/null; then
      echo "  Beamform scene: $BEAMFORM_SCENE (0=idle, 1=cardioid, 2=omni, 3=conference)"
    fi
    echo "  Sending record_mic_array command to glasses..."
    AUDIO_SOURCE="${AUDIO_SOURCE:-1}"  # 1=MIC, 6=VOICE_RECOGNITION, 7=VOICE_COMMUNICATION
    PARAMS_JSON="{\"duration_seconds\":$ARRAY_DURATION,\"scene\":$BEAMFORM_SCENE,\"audio_source\":$AUDIO_SOURCE}"
    adb -s "$GLASSES_SERIAL" shell "am broadcast -a com.repository.glasses.listener.TEST_COMMAND --es command record_mic_array --es params '$PARAMS_JSON'" \
      >/dev/null 2>&1

    # Wait for beamform init (~2s) + recording + margin
    WAIT_SEC=$((ARRAY_DURATION + 8))
    echo "  Recording for ${ARRAY_DURATION}s (waiting ${WAIT_SEC}s total for init + recording)..."
    sleep "$WAIT_SEC"

    # Delete old file on glasses, wait, and re-check that new file exists
    echo "  Pulling 8-channel PCM from glasses..."
    rm -f "$ARRAY_PCM"
    adb -s "$GLASSES_SERIAL" shell "sync" 2>/dev/null
    adb -s "$GLASSES_SERIAL" pull /sdcard/Download/mic_8ch.pcm "$ARRAY_PCM" 2>&1

    if [ ! -f "$ARRAY_PCM" ] || [ "$(stat -c%s "$ARRAY_PCM" 2>/dev/null)" -lt 100 ]; then
      echo ""
      echo "  ERROR: No audio captured or file too small."
      echo "  Check glasses log: adb -s $GLASSES_SERIAL logcat -s MicArrayTest:* --pid=\$(adb -s $GLASSES_SERIAL shell pidof com.repository.glasses.listener)"
      exit 1
    fi

    local_size=$(stat -c%s "$ARRAY_PCM")
    echo "  Pulled $local_size bytes"

    # Split into 8 mono WAV files
    echo "  Splitting into 8 mono WAV files..."
    rm -rf "$ARRAY_DIR"
    mkdir -p "$ARRAY_DIR"

    python3 -c "
import struct, os, sys

pcm_path = '$ARRAY_PCM'
out_dir = '$ARRAY_DIR'
rate = 16000
num_ch = 8
bps = 16

with open(pcm_path, 'rb') as f:
    raw = f.read()

bytes_per_frame = num_ch * 2  # 16 bytes per frame
total_frames = len(raw) // bytes_per_frame
duration = total_frames / rate
print(f'  Total: {total_frames} frames, {duration:.1f}s, {num_ch} channels')

if total_frames == 0:
    print('  ERROR: no complete frames in PCM file')
    sys.exit(1)

# Parse interleaved 16-bit samples
import array
samples = array.array('h')
samples.frombytes(raw[:total_frames * bytes_per_frame])

# Deinterleave and write each channel as a mono WAV
for ch in range(num_ch):
    ch_samples = samples[ch::num_ch]
    ch_bytes = ch_samples.tobytes()

    # Compute RMS for quick level check
    rms = 0.0
    for s in ch_samples:
        rms += s * s
    rms = (rms / len(ch_samples)) ** 0.5

    wav_path = os.path.join(out_dir, f'ch{ch}.wav')
    with open(wav_path, 'wb') as f:
        f.write(b'RIFF')
        f.write(struct.pack('<I', 36 + len(ch_bytes)))
        f.write(b'WAVEfmt ')
        f.write(struct.pack('<IHHIIHH', 16, 1, 1, rate, rate * 2, 2, bps))
        f.write(b'data')
        f.write(struct.pack('<I', len(ch_bytes)))
        f.write(ch_bytes)

    print(f'    ch{ch}: RMS={rms:8.1f}  -> {wav_path}')

print()
print('  Channel RMS guide:')
print('    High RMS (>500)  = active mic or loud signal')
print('    Low RMS  (<100)  = quiet mic or echo ref')
print('    Zero RMS         = unused/dead channel')
"

    if [ $? -ne 0 ]; then
      echo "  ERROR: Failed to split channels"
      exit 1
    fi

    echo ""
    echo "  Done. Channel WAVs saved in: $ARRAY_DIR/"
    echo ""
    echo "  Play any channel:"
    for ch in $(seq 0 7); do
      echo "    ffplay -nodisp -autoexit $ARRAY_DIR/ch${ch}.wav"
    done
    ;;

  cardioid)
    # Record 8-ch with beamforming scene 1 (cardioid/directional)
    echo "=== CARDIOID (directional beamforming) -- face the speaker ==="
    echo "  Sets DSP beamform scene 1, records ${DURATION_SEC}s, then plays ch0."
    echo ""
    BEAMFORM_SCENE=1 bash "$0" array
    ;;

  omni)
    # Record 8-ch with beamforming scene 2 (omnidirectional)
    echo "=== OMNI (omnidirectional) -- all directions ==="
    echo "  Sets DSP beamform scene 2, records ${DURATION_SEC}s, then plays ch0."
    echo ""
    BEAMFORM_SCENE=2 bash "$0" array
    ;;

  both)
    echo "=== Testing both mics ==="
    echo ""
    echo "Step 1: FRONT mic (record ${DURATION_SEC}s, then play)"
    bash "$0" front
    echo ""
    echo "Step 2: INWARD mic (live stream -- Ctrl+C to stop)"
    bash "$0" inward
    ;;

  *)
    echo "Usage: $0 {inward|front|both|array|cardioid|omni}"
    echo ""
    echo "  inward    Live-stream back/temple mic (captures wearer's voice)"
    echo "  front     Record front mic array via translation pipeline, then play"
    echo "  array     Record all 8 mic channels separately, split and play each"
    echo "  cardioid  Record with DSP beamforming aimed forward (scene 1)"
    echo "  omni      Record with DSP omnidirectional mode (scene 2)"
    echo "  both      Test front first, then stream inward"
    exit 1
    ;;
esac
