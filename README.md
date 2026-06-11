# client-glasses

Listener app for Rokid AR glasses (YodaOS / Android 12, arm64). Runs as a
privileged on-device service: captures mic audio, does on-device wake-word and
voice-activity detection, takes photos, draws an on-glasses overlay, and relays
messages/files to the companion phone over Bluetooth RFCOMM and Wi-Fi Direct.
It is the glasses half of a glasses + phone assistant; the phone app lives in a
separate repo.

Gradle multi-module project: `app` (main service + overlay, bundles the
wake-word/VAD models and native C++ via CMake/NDK), `bt-manager`, `capture`,
`filesync`, `glasses-tracing`. The `sthal/`, `*-daemon/` and `test/` trees are
research/native/test tooling, not part of the APK.

## Build

Build with JDK 17. JDK 21 breaks R8 on the minified debug variant. The debug
variant is the one we ship (minify + shrink enabled).

```
./gradlew :app:assembleDebug
```

Build everything (all modules):

```
./gradlew assembleDebug
```

You need the Android SDK and NDK installed (native code is built for
`arm64-v8a` only).

## Configuration

The APK itself needs no server endpoint or secrets -- all transport is local
and the peer is negotiated at runtime. The only build requirement is the SDK
path: copy `local.properties.example` to `local.properties` and set `sdk.dir`
(or set `ANDROID_HOME` / `ANDROID_SDK_ROOT`).

`.env.example` is only read by optional helper scripts under `sthal/`, `test/`,
and the native daemons (ADB serials, NDK path, QNN SDK for recompiling
wake-word graphs). Copy to `.env` if you use those; none are needed to build
the app.

## Dependencies

- JDK 17, Android SDK 34, NDK (CMake 3.22.1).
- Wake-word / VAD ONNX models (`silero_vad.onnx`, `sireneviy.onnx`,
  `melspectrogram.onnx`, `embedding_model.onnx`) are committed in
  `app/src/main/assets`.
- onnxruntime-android-qnn, ML Kit/TFLite (capture), and bundled native Opus +
  WebRTC AECM (built via CMake).
- Extra Maven repos (Rokid, alphacephei) are wired in `settings.gradle.kts`.

## Modules

### Gradle modules (ship in the APK / priv-app overlay)

These are the subprojects listed in `settings.gradle.kts`.

- **`app/`** -- The main glasses listener (`com.repository.glasses.listener`).
  Runs as two processes: a `:backend` `ListenerService` (Bluetooth, mic audio,
  camera, ReID face detection, screen recording, TTS playback, Rokid OS
  integration) and a default-process `MainActivity` UI with chat, ReID, todo,
  night vision, translation, map, and teleprompter tabs. Bundles the wake-word /
  VAD ONNX models and native C++ (Opus + AECM) built via CMake/NDK.
- **`bt-manager/`** -- A privileged system-app (`com.repository.glasses.btmanager`)
  that brokers Bluetooth for the listener. It wraps the hidden
  `BluetoothA2dpSink` / `BluetoothHeadsetClient` reflection APIs, RFCOMM sockets,
  and BLE advertising/wake, and runs a `ProfileAutoConnector` that brings HFP+A2DP
  up after pairing. The listener binds to it over `IBtManager.aidl`; it needs
  `BLUETOOTH_PRIVILEGED` from its `priv-permissions.xml`.
- **`capture/`** -- An out-of-process camera/HUD capture service
  (`com.repository.glasses.capture`) exposing `ICapture.aidl`. It isolates Camera2
  and MediaCodec from the listener process so a capture crash can't take the
  listener down, and also owns the privacy LED while the camera is open.
- **`filesync/`** -- A Wi-Fi P2P file and log sync server
  (`com.repository.glasses.filesync`). It hosts the persistent client log
  (`/sdcard/Download/glasses-client.log`) and captured files over HTTP on port
  8848 so they can be pulled from the phone/PC without USB.
- **`glasses-tracing/`** -- A small shared Android library exposing the `GT`
  Perfetto/systrace helpers (`GT.section`, `GT.counter`, async slices). It is
  consumed by `app`, `bt-manager`, `capture`, and `filesync` so every APK emits
  trace slices under a common `gt.` prefix.

### Native daemons (root binaries baked into the rooted firmware)

- **`glasses-power-daemon/`** -- A native arm64 C binary
  (`/system/bin/glasses-power-daemon`) that runs as root via an init service
  (`class core`, auto-respawn). It manages screen timeout, fold/take-off
  triggered suspend (s2idle), the battery-charge LED indicator, and unlatches the
  PSoC `enforce_psensor`/`enforce_hall` flags on boot so wear detection works.
- **`touchpad-daemon/`** -- A native arm64 C binary
  (`/system/bin/rokid-touchpad-daemon`) that `EVIOCGRAB`s the PSoC touchpad input
  device and re-emits filtered, velocity-scaled scroll keycodes on a uinput
  virtual keyboard. It also defers `KEY_PROG1` to suppress accidental Rokid AI
  triggers. The directory also holds `firmware-patch/`, a Python pipeline for
  editing the Cypress PSoC 4000R touchpad MCU's cyacd firmware images.

### Native audio libraries (C/C++ source compiled into `app`)

- **`WebRTC_AECM/`** -- The mobile WebRTC AECM acoustic-echo-cancellation port
  actually used by the listener's audio pipeline (driven from
  `audio/WebRtcAecm.kt` via JNI).
- **`KOTI_AEC/`** -- A reference AEC variant built on the WebRTC aec/aecm modules
  plus Speex 1.0/1.2. It is a research/reference implementation; the live path is
  `WebRTC_AECM/`.
- **`external/`** -- Vendored third-party dependencies for the audio modules
  (currently just `speexdsp`).

### Other components (HAL, assets, tooling)

- **`sthal/`** -- A custom SoundTrigger HAL (`sound_trigger.primary.neo.so`) that
  moves wake-word inference onto the Hexagon DSP via QNN so the ARM cluster isn't
  kept awake for the hot-word loop. It is deployed through the DIY overlay
  bind-mount (no `super.img` rebuild) and grants the listener `CAPTURE_AUDIO_HOTWORD`
  via its own `priv-permissions.xml`. Standalone NDK/CMake build, arm64-v8a only.
- **`nightvision-asset/`** -- The `nightvision_unet.onnx` model used by the app's
  Night Vision tab, plus `deploy-nightvision-asset.sh` to push it to the glasses.
  The model is kept out of the APK (it is ~31 MB) and deployed once per device to
  the app's external files dir.
- **`wake-word-training/`** -- A Python training pipeline for the "sireneviy" wake
  word. It produces the ONNX models the app ships (`sireneviy.onnx`,
  `embedding_model.onnx`, `melspectrogram.onnx`) and includes scripts to record,
  synthesize, augment, and evaluate samples (`train_pipeline.py`, `record_samples.py`,
  `test_live.py`, etc.).
- **`firmware/`** -- Rooting and flashing tooling for the Rokid AR Lite (Qualcomm
  "neo"). `fetch-os.sh` downloads and extracts stock OTA images into a gitignored
  cache; `root-firmware.sh` builds the rooted `super_4.img` (root,
  SELinux-permissive, the diy-overlay engine, patched touchpad driver, and the
  sinkconn/a2dpduck LD_PRELOAD hooks) and emits the QDL `rawprogram` flash lists.
  The large OS images are not committed.
- **`touchpad-test-app/`** -- A standalone diagnostic APK that visualizes incoming
  touchpad key events for debugging the touchpad daemon. Not deployed in
  production.
- **`led-cam-test/`** -- A standalone diagnostic APK for exercising the LED ring
  and camera HAL. Not deployed in production.
