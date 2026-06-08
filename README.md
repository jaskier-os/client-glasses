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
