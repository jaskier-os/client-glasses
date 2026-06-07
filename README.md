# client-glasses

Listener app for Rokid AR glasses (YodaOS / Android 12, arm64). It runs as a
privileged on-device service that captures microphone audio, performs on-device
wake-word detection and voice-activity detection, takes photos, renders an
on-glasses overlay UI, and relays messages/files to a companion phone over
Bluetooth RFCOMM and Wi-Fi Direct. It is the glasses half of a glasses + phone
assistant system; the phone app is a separate project.

The production build has no network server endpoint and no secrets: all
transport is local (Bluetooth RFCOMM for messaging, Wi-Fi Direct for file sync),
with the peer address negotiated at runtime. There is therefore no host/port or
API key to configure for the app itself.

## Repository layout

Gradle multi-module Android project. Modules wired in `settings.gradle.kts`:

- `app/` -- main listener service + overlay UI (`com.repository.glasses.listener`).
  Bundles the wake-word / VAD ONNX models and native C++ (Opus codec, WebRTC AECM
  echo cancellation) built via CMake/NDK.
- `bt-manager/` -- Bluetooth bridge/manager helper.
- `capture/` -- camera/photo capture module (ships an ML Kit / TFLite asset).
- `filesync/` -- Wi-Fi Direct file sync host/client.
- `glasses-tracing/` -- shared tracing utility library used by the other modules.

Supporting (non-Gradle) directories, used for research, native daemons and
on-device test tooling, not part of the APK build:

- `sthal/` -- experimental SoundTrigger HAL + ACD probe + QNN model compilation
  tooling (native, built with the NDK / QAIRT SDK).
- `glasses-power-daemon/`, `touchpad-daemon/` -- native daemons (NDK).
- `touchpad-test-app/`, `led-cam-test/` -- standalone test apps.
- `KOTI_AEC/`, `WebRTC_AECM/`, `external/speexdsp/` -- vendored DSP source.
- `nightvision-asset/` -- night-vision UNet model + deploy helper.
- `test/` -- on-device (adb / uiautomator2) test harness scripts.
- `docs/` -- design notes.

## Prerequisites

- JDK 17 (required by Android Gradle Plugin 8.2.2).
- Android SDK with platform 34 and build-tools; set `sdk.dir` (see Setup) or the
  `ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variable.
- Android NDK + CMake 3.22.1 (the `app` module builds native code; AGP installs a
  compatible NDK automatically, or set `ANDROID_NDK`).
- Internet access on first build to fetch Gradle 8.5 and dependencies from
  Google, Maven Central, the AlphaCephei Maven repo and the Rokid Maven repo
  (all declared in `settings.gradle.kts`).
- Optional, only to re-compile wake-word models: Qualcomm QAIRT (QNN) SDK.
- Optional, only for the on-device test scripts: `adb`, Python 3 with
  `uiautomator2` and `pytest`, `ffmpeg`.

## Setup

1. Copy the SDK config example and point it at your Android SDK:

   ```bash
   cp local.properties.example local.properties
   # then edit sdk.dir, or skip this and export ANDROID_HOME instead
   ```

   `local.properties` is gitignored and machine-specific; never commit it.

2. (Optional) Copy the environment example for the developer/deploy/test scripts:

   ```bash
   cp .env.example .env
   ```

   Every variable is optional and documented inline in `.env.example`. None are
   needed to build the APK; they only target devices and toolchains for the
   helper scripts. `.env` is gitignored.

## Build

From the repository root:

```bash
./gradlew assembleDebug          # all module debug APKs
./gradlew :app:assembleDebug     # just the main listener app
./gradlew :app:assembleRelease   # release variant
```

The debug variant of `app` is the one normally deployed to glasses (it enables
debuggable mode, verbose logging and StrictMode, and applies R8 minification +
resource shrinking).

No signing keystore is committed. The debug build is signed with the standard
Android debug key generated locally by the SDK. To produce a signed release,
supply your own keystore and add a `signingConfigs` block referencing values
from a gitignored `keystore.properties` (do not commit the keystore or its
passwords).

## Run / deploy / test

This app must run on Rokid AR glasses hardware (it depends on privileged
SoundTrigger, hidden-API and Rokid-specific behaviour); it does not run on a
generic emulator.

- Deploy and on-device tests are driven by the shell scripts under `test/`,
  `capture/test/`, `sthal/`, and the `*-daemon/` directories. They locate the
  target via `ADB_SERIAL` / `GLASSES_SERIAL` / `PHONE_SERIAL` (set in `.env` or
  the environment); if unset they fall back to the only attached adb device.
- Example UI test run: `GLASSES_SERIAL=<serial> bash test/run_tests.sh`.

Note: as a privileged app, full functionality (pairing, scan mode, SoundTrigger,
Wi-Fi config) requires installation into the device's priv-app slot with the
accompanying `privapp-permissions` XML; a plain `adb install` will not grant the
privileged permissions. See `sthal/deploy-privapp.sh` and the design notes in
`docs/` for the priv-app overlay procedure.

## Connectivity, TLS and VPN

The app uses only local transports (Bluetooth RFCOMM and Wi-Fi Direct); it does
not open TLS sockets, does not require a VPN, and contains no hardcoded IPs,
hostnames or certificates. There is consequently nothing to configure for
secure transport. If a future build adds a networked endpoint, read its host
from an env/`BuildConfig` value and treat any certificate path as an optional
variable that falls back to a plain connection when unset.

## Model weights

All models needed to build and run are committed as in-tree assets (they are
small, 1-31 MB each):

- `app/src/main/assets/sireneviy.onnx`, `embedding_model.onnx` -- in-house
  wake-word / speaker-embedding models. Committed.
- `app/src/main/assets/silero_vad.onnx`, `melspectrogram.onnx` -- supporting
  VAD / feature models. Committed for build self-containment.
- `nightvision-asset/nightvision_unet.onnx` -- in-house night-vision UNet.
  Committed.
- `capture/src/main/assets/ml/splitternet.tflite` -- capture-pipeline model.
  Committed.
- `sthal/models/*.bin` -- QNN/HTP-compiled forms of the wake-word pipeline.
  Committed; can be regenerated from the `.onnx` sources with
  `sthal/tools/compile_onnx_to_qnn.sh` (requires the QAIRT SDK; set
  `QNN_SDK_ROOT`).
