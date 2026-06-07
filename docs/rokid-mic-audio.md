# Rokid Glasses Microphone Audio Capture

## Problem

Standard Android `AudioRecord` with `CHANNEL_IN_MONO` at 16kHz returns all zeros on Rokid AR Lite glasses. The AudioRecord initializes successfully (`STATE_INITIALIZED`) but the hardware returns silence.

## Root Cause

The Rokid AR Lite has a multi-microphone array. The Built-In Mic hardware ONLY supports multi-channel input at 16kHz -- it cannot deliver mono. When `CHANNEL_IN_MONO` is requested, AudioRecord silently fails: it initializes but returns zero-filled buffers.

## Solution

Use `channelConfig=60` (4 position channels: LEFT|RIGHT|FRONT|BACK) and manually extract channel 3 (byte offsets 6-7 per 8-byte frame) for mono output. This matches exactly how Rokid's own `RokidSpriteAssistServer` captures audio.

```kotlin
val channelConfig = 60  // CHANNEL_IN_LEFT|RIGHT|FRONT|BACK
val bytesPerFrame = 8   // 4 channels * 2 bytes (16-bit PCM)

val micRecord = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    16000,          // 16kHz
    channelConfig,  // 60 = 4 position channels
    AudioFormat.ENCODING_PCM_16BIT,
    maxOf(minBuf, chunkFrames * bytesPerFrame)
)

// Extract channel 3 (BACK) for mono -- this is the voice channel
for (i in 0 until frames) {
    val srcOff = i * bytesPerFrame
    val dstOff = i * 2
    monoBuffer[dstOff] = rawBuffer[srcOff + 6]      // LSB of channel 3
    monoBuffer[dstOff + 1] = rawBuffer[srcOff + 7]  // MSB of channel 3
}
```

## Signal Level

The extracted mono audio is approximately 1% of full scale (~300 maxAmp out of 32768 during speech). 16x gain amplification is applied on the phone side to bring it to usable levels for Vosk wake word detection and Silero VAD.

## Audio Pipeline (current implementation)

```
Glasses: AudioRecord(MIC, 16kHz, 4ch)
  -> extract channel 3 (mono PCM16LE)
  -> Base64 encode
  -> BT data channel (Caps message on CH_AUDIO_DATA)
Phone: receive Base64
  -> decode to ShortArray
  -> 16x gain amplification
  -> Vosk wake word detection + Silero VAD
```

## What Does NOT Work

| Approach | Result | Why |
|----------|--------|-----|
| `CHANNEL_IN_MONO` at 16kHz | All zeros | Built-In Mic hardware only supports multi-channel |
| `VOICE_COMMUNICATION` + `CHANNEL_IN_MONO` | All zeros | Same hardware limitation |
| `cmd_start_audio_stream` + `AudioRecord` | All zeros | CXR claims exclusive mic access |
| CXR `AudioStreamListener` (phone-initiated) | Unreliable | Breaks after phone restart, native layer issue |
| `setCommunicationDevice()` (BT SCO routing) | Hijacks A2DP | Kills music playback, user explicitly forbade |

## What DOES Work

| Approach | Result | Notes |
|----------|--------|-------|
| `channelConfig=60` at 16kHz, extract ch3 | Real audio | Matches RokidSpriteAssistServer |
| `CHANNEL_IN_MONO` at 44100Hz | Likely works | Routes to Back Mic instead (Tuner app uses this) |

## Files Modified

- `AI/clients/glasses/.../service/ListenerService.kt` -- `startMicStream()`: changed from CHANNEL_IN_MONO to channelConfig=60 with channel extraction
- `AI/clients/phone/.../service/ListenerService.kt` -- `feedGlassesAudioFromMic()`: added 16x gain amplification
- `AI/clients/phone/.../bt/PhoneBtHost.kt` -- receives Base64 audio via `handleAudioDataFromGlasses()`
- `AI/clients/glasses/.../bt/GlassesBtClient.kt` -- sends Base64 audio via `sendAudioData()`
- Both `BtProtocol.kt` files -- added `CH_AUDIO_DATA` channel constant

## Reference Sources

All findings derived from decompiled Rokid OS sources and third-party Rokid apps. See `rokid-mic-audio-reference.md` for detailed analysis.
