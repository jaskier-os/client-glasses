# Rokid Mic Audio -- Decompiled Source Reference

Detailed findings from decompiling Rokid OS system apps, audio policy, and analyzing third-party Rokid apps. This document provides the evidence base for the mic audio capture approach described in `rokid-mic-audio.md`.

## 1. Audio Policy Configuration

**File:** `Recon/rokid-docs/yodaos/DECOMPILED/vendor/etc/audio_policy_configuration.xml`

### Built-In Mic (primary mic array)

```xml
<devicePort tagName="Built-In Mic" type="AUDIO_DEVICE_IN_BUILTIN_MIC" role="source">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="16000"
             channelMasks="AUDIO_CHANNEL_IN_8"/>
</devicePort>
```

- Only supports 16kHz
- Only supports 8-channel input (`AUDIO_CHANNEL_IN_8`)
- No mono or stereo support at the hardware level
- This is the multi-mic array (likely 4 physical mics, 8 logical channels)

### Built-In Back Mic (secondary mic)

```xml
<devicePort tagName="Built-In Back Mic" type="AUDIO_DEVICE_IN_BACK_MIC" role="source">
    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"
             samplingRates="8000 11025 12000 16000 22050 24000 32000 44100 48000"
             channelMasks="AUDIO_CHANNEL_IN_MONO AUDIO_CHANNEL_IN_STEREO AUDIO_CHANNEL_IN_FRONT_BACK"/>
</devicePort>
```

- Supports wide range of sample rates (8kHz-48kHz)
- Supports MONO, STEREO, FRONT_BACK
- This is why the Tuner app works with `CHANNEL_IN_MONO` at 44100Hz -- it routes to Back Mic

### Input Mix Ports

**Primary input** (main recording path):
- Supports: MONO, STEREO, FRONT_BACK, IN_4, IN_8
- Sample rates: 8000-48000Hz
- maxOpenCount=2, maxActiveCount=2
- Route sources: Built-In Mic, Built-In Back Mic, Wired Headset Mic, BT SCO Headset Mic, FM Tuner, Telephony Rx, A2DP In

**VoIP TX** (voice call path):
- Supports: MONO only
- Sample rates: 8000, 16000, 32000, 48000Hz
- Flag: AUDIO_INPUT_FLAG_VOIP_TX
- Route sources: Built-In Mic, Built-In Back Mic, BT SCO Headset Mic, USB Device In, USB Headset In, Wired Headset Mic

**Fast input** (low-latency path):
- Supports: MONO, STEREO, FRONT_BACK
- Sample rates: 8000-48000Hz
- Flag: AUDIO_INPUT_FLAG_FAST

**Quad mic** (4-channel beamforming):
- Supports: CHANNEL_INDEX_MASK_4
- Sample rates: 48000Hz only

### Key Insight

When requesting `CHANNEL_IN_MONO` at 16kHz, Android's audio policy must route to a device that supports it. The Built-In Mic doesn't support mono -- it only has IN_8. The primary input mixPort DOES list MONO, but the actual hardware device connected to that mixPort cannot deliver it at 16kHz. Result: AudioRecord initializes (mixPort says MONO is ok) but hardware returns silence.

When requesting `CHANNEL_IN_MONO` at 44100Hz, Android routes to Back Mic (which supports mono at 44100Hz). This works but uses a different physical microphone.

## 2. RokidSpriteAssistServer (System AI Assistant)

**Location:** `Recon/rokid-docs/yodaos/DECOMPILED-APPS/product/app/RokidSpriteAssistServer/`

This is Rokid's built-in AI assistant app, pre-installed as a system app (`android.uid.system`). It is the definitive reference for how to capture audio on these glasses.

### WavAudioCapture.java

**File:** `cfr/sources/com/rokid/os/sprite/assist/media/audio/WavAudioCapture.java`

AudioRecord constructor call:
```java
new AudioRecord(1, 16000, 60, 2, this.bufferSize)
```

Parameters:
- `1` = `MediaRecorder.AudioSource.MIC`
- `16000` = 16kHz sample rate
- `60` = channel config (see below)
- `2` = `AudioFormat.ENCODING_PCM_16BIT`

#### Channel Config Value 60

`60 = 0x3C = CHANNEL_IN_LEFT (0x04) | CHANNEL_IN_RIGHT (0x08) | CHANNEL_IN_FRONT (0x10) | CHANNEL_IN_BACK (0x20)`

This requests 4 position channels. Each frame = 4 channels * 2 bytes = 8 bytes.

#### Channel Extraction (writeAudioData)

```java
private final void writeAudioData(byte[] byArray, int n) {
    int n2 = n / 4;              // output size in bytes
    byte[] byArray2 = new byte[n2];
    int n3 = n / 8;              // number of frames
    for (n = 0; n < n3; ++n) {
        int n4 = n * 2;          // output byte offset
        int n5 = n * 8;          // input frame offset
        byArray2[n4] = byArray[n5 + 6];      // LSB of channel 3 (BACK)
        byArray2[n4 + 1] = byArray[n5 + 7];  // MSB of channel 3 (BACK)
    }
    randomAccessFile.write(byArray2, 0, n2);
}
```

Per-frame byte layout (4-channel 16-bit PCM, little-endian):
```
Offset:  0-1     2-3     4-5     6-7
Channel: LEFT    RIGHT   FRONT   BACK
```

Extracts channel 3 (BACK, byte offsets 6-7) as the voice channel. Output: mono 16-bit PCM WAV at 16kHz.

### SpriteMediaService Architecture

**File:** `jadx/sources/com/rokid/os/sprite/assist/media/SpriteMediaService.java`

Service hierarchy:
```
SpriteMediaService (LifecycleService, ISpriteMediaServer)
  -> AudioFuncManager (manages recording lifecycle)
    -> WavAudioCapture (actual AudioRecord + channel extraction)
```

- Runs on dedicated HandlerThread: "Assist_Media_Thread"
- Max recording time: 12 hours (43200000ms)
- Recording file format: `record-{timestamp}-{UUID}.wav`
- Fires sound effect callbacks (40=start, 39=end)
- Saves to MediaStore after recording

### Permissions

```xml
android:sharedUserId="android.uid.system"
android:persistent="true"
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
```

Runs as system UID, persistent (auto-restarts). No runtime permission prompts needed.

### Audio Scene System

**File:** `cfr/sources/com/rokid/os/sprite/assist/instruct/scene/scene/AudioRecordScene.java`

Scenes control what can run concurrently:
- Coexistent with: ai_assist, phone_call, ar_picture, mix_record, navigation, music_word, custom_view
- Mutually exclusive with: word_tips, live_broadcast, translate, payment, ai_chat, audio_record (can't have two recordings)

## 3. RokidSpriteLauncher (System Launcher)

**Location:** `Recon/rokid-docs/yodaos/DECOMPILED-APPS/product/app/RokidSpriteLauncher/`

The launcher acts as an audio policy enforcement layer.

### Key Findings

- Does NOT directly instantiate AudioRecord
- Requests recording via CXR-S service (`startAudioStream`, `startAudioRecord`)
- Manages audio focus via `FocusAudioManager`
- Controls Bluetooth audio sink mute: `audioManager.setParameters("btsink_mute=true/false")`
- Manages system-wide mic mute: `audioManager.setMicrophoneMute(true/false)`
- Tracks audio recording status via `AudioRecordStatusManager`
- Coordinates audio scenes (only one exclusive scene at a time)

### Audio Control Commands (AssistServerCmd)

```
cmd_start_audio_stream  -- activates mic + streams to phone via CXR
cmd_start_audio_record  -- activates local recording (dictophone UI)
cmd_stop_audio_record   -- stops local recording
cmd_play_tts            -- text-to-speech playback
cmd_toggle_mute         -- toggle mic mute
```

### FocusAudioManager Sound Effects

60+ custom Rokid sound effects. Audio-related ones:
- FX_RKD_LAUNCH_AI (19), FX_RKD_AI_START (34), FX_RKD_AI_WAIT (35), FX_RKD_AI_END (26), FX_RKD_AI_EXIT (36)
- FX_RKD_RECORD_START (40), FX_RKD_RECORD_END (39)

## 4. CXR-M SDK (Phone/Mobile Side)

**Location:** `Recon/rokid-docs/cxr-m/`

### AudioStreamListener

```java
public interface AudioStreamListener {
    void onStartAudioStream(int streamId, int codecType, String streamType);
    void onAudioStream(int streamId, byte[] data, int offset, int length);
    void onAudioStreamFinish(int streamId);
}
```

Registration: `CxrApi.getInstance().setAudioStreamListener(listener)`

### openAudioRecord

```java
// Phone tells glasses to start streaming mic audio
CxrApi.getInstance().openAudioRecord(codecType, mode, streamName, denoiseMode)
// denoiseMode: 0=off, 1=lite, 2=standard (default)

CxrApi.getInstance().closeAudioRecord(streamName)
```

This triggers `startAudioStream()` on the glasses side (native JNI). Audio flows back via AudioStreamListener callbacks. This is the CXR-managed path -- unreliable after phone restart.

### setCommunicationDevice (BT SCO routing)

```java
// Android 12+:
AudioManager.setCommunicationDevice(btScoDevice)  // type=7 = BT SCO
// Android <12:
AudioManager.setBluetoothScoOn(true)
AudioManager.startBluetoothSco()
```

Routes phone mic input/output through BT SCO to glasses. Hijacks A2DP (kills music playback). NOT used in our implementation.

### Audio Scene Management

```java
CxrApi.getInstance().changeAudioSceneId(sceneId, callback)
```

Changes audio processing profile on glasses (normal, call, music recognition, echo-cancel).

## 5. CXR-S SDK (Glasses/Service Side)

**Location:** `Recon/rokid-docs/cxr-s/`

### Native Audio Streaming

```java
// In CXRServiceBridge:
public native int startAudioStream(int codecType, String streamName, Caps params);
public native void stopAudioStream(String streamName);
```

These are JNI calls into `libcxr-bridge-jni.so`. They activate the glasses hardware mic and stream encoded audio to the connected phone. The phone receives it via `AudioStreamListener`.

### Flora IPC

Flora is Rokid's pub/sub IPC framework. `startFloraService()` starts it. Glasses-side apps use Flora to communicate with the phone through CXRService.

### Caps Data Protocol

Bidirectional messaging uses Caps serialization:
- `write(String)` / `getString()` -- string fields
- `writeInt32()` / `writeUInt32()` -- integers
- `write(float)` -- floats
- `write(byte[])` -- binary blobs

Custom data channels use `bridge.sendMessage(channelName, caps)` on glasses, received via `CustomCmdListener.onCustomCmd(cmd, args)` on phone.

## 6. Clawsses (Third-Party Rokid App)

**Repository:** https://github.com/dweddepohl/clawsses

### Architecture

Clawsses does NOT capture audio on the glasses side. It uses phone-side AudioRecord with setCommunicationDevice (BT SCO routing).

Flow:
1. Glasses sends "start_voice" command to phone
2. Phone calls `RokidSdkManager.setCommunicationDevice()` -- enables BT SCO
3. Phone creates `AudioRecord(MIC, 24000Hz, CHANNEL_IN_MONO, PCM16)` -- captures glasses mic via SCO
4. Streams to OpenAI Realtime API (Base64 encoded, 960-byte frames ~20ms)
5. Fallback: Android `SpeechRecognizer` API

### Key Files

- `phone-app/.../voice/OpenAIRealtimeClient.kt` -- primary audio capture (24kHz)
- `phone-app/.../glasses/RokidSdkManager.kt` -- `setCommunicationDevice()` call
- `glasses-app/` -- NO RECORD_AUDIO permission, UI-only

### Not Applicable to Our Case

SCO routing hijacks A2DP/music, which the user explicitly forbade. Also requires phone-side capture which doesn't use the glasses mic array optimally.

## 7. Tuner (Third-Party Rokid App)

**Repository:** https://github.com/lvturner/tuner

### Architecture

Standalone glasses-only app. No phone component, no Bluetooth, no CXR SDK.

### Audio Configuration

```kotlin
AudioSource: MediaRecorder.AudioSource.MIC
Sample Rate: 44100 Hz
Channel Config: AudioFormat.CHANNEL_IN_MONO
Encoding: AudioFormat.ENCODING_PCM_16BIT
Buffer: getMinBufferSize() * 2
```

### Why It Works

At 44100Hz with CHANNEL_IN_MONO, Android routes to the **Built-In Back Mic** (which supports mono at 8kHz-48kHz) instead of the Built-In Mic (which only supports IN_8 at 16kHz). Different physical microphone, but functional.

### Key Files

- `app/.../audio/AudioRecorder.kt` -- AudioRecord creation and read loop
- `app/.../audio/AudioConfig.kt` -- constants (44100Hz, 4096 buffer, 16-bit)

## 8. Mixer Configuration

**Files:** `Recon/rokid-docs/yodaos/DECOMPILED/vendor/etc/mixer_paths_neo_*.xml`

Three variants: `neo_idp.xml`, `neo_qxr.xml`, `neo_idp_sg.xml`. These configure the codec-level audio signal paths:
- TX_MACRO controls for microphone input routing
- RX_MACRO controls for audio output paths
- Codec mixer controls for ADC/DAC configuration

## Summary: Mic Access Decision Tree

```
Want 16kHz voice audio from main mic array?
  -> Use channelConfig=60, extract channel 3 (bytes 6-7)
  -> This is what RokidSpriteAssistServer does

Want simple mono at any sample rate?
  -> Use CHANNEL_IN_MONO at 44100Hz (or other non-16kHz rate)
  -> Routes to Back Mic (different physical mic)
  -> Simpler code but different audio characteristics

Want phone to receive glasses audio via CXR SDK?
  -> Use openAudioRecord() + AudioStreamListener
  -> Unreliable after phone restart (native layer bug)
  -> Audio arrives encoded, codec type varies

Want phone to capture glasses mic via BT SCO?
  -> Use setCommunicationDevice() + phone AudioRecord
  -> Works but hijacks A2DP (kills music playback)
  -> Not recommended
```
