# sthal — custom SoundTrigger HAL for Rokid Neo (Phase 3)

Replacement `sound_trigger.primary.neo.so` that moves wake-word inference onto
the Hexagon DSP via QNN, so the ARM big cluster isn't kept awake for the
hot-word loop. Deployed through the existing DIY `/data/local/diy-overlay`
bind-mount — no `super.img` rebuild, no fastboot.

## Files

- `DESIGN.md` — research notes, AOSP + QNN reference, on-device audio-policy
  survey, privapp-permissions rationale, risks.
- `include/sound_trigger_hw.h` — minimal self-contained AOSP legacy HAL types.
- `src/sound_trigger_hw.cpp` — HAL implementation + `HAL_MODULE_INFO_SYM`.
- `src/qnn_runtime.{h,cpp}` — dlopen-based QNN wrapper with HTP + Null impls.
- `src/mic_reader.{h,cpp}` — stubbed low-power mic reader (F6 completes).
- `tools/compile_onnx_to_qnn.sh` — offline ONNX -> QNN context-binary pipeline.
- `tools/qnn_htp_config.json` — HTP backend tuning.
- `priv-permissions.xml` — grants `CAPTURE_AUDIO_HOTWORD` to the listener.
- `deploy.sh` — gated build + push + reboot of the HAL + QNN runtime +
  graph binaries + privapp XML. Does NOT install the APK as privileged.
- `deploy-privapp.sh` — separately gated install that drops the listener
  APK into the DIY overlay's `/system/priv-app/` so the permission grant
  from `priv-permissions.xml` actually takes effect.
- `CMakeLists.txt` — the real build path (NDK, arm64-v8a only).
- `Android.bp.DISABLED` — reference AOSP recipe (off by default).

## Build

Standalone NDK build, no Gradle:

```bash
export ANDROID_NDK=$HOME/Android/Sdk/ndk/27.1.12297006
export CMAKE=$HOME/Android/Sdk/cmake/3.22.1/bin/cmake
# Required -- QAIRT SDK providing QNN/*.h headers. We dlopen the backend .so
# libs at runtime from the device, so nothing from this SDK is link-linked;
# only its C headers are consumed at compile time.
export QNN_SDK_ROOT=$HOME/qairt/2.45.0.260326

cd AI/clients/glasses/sthal
rm -rf build && mkdir build
$CMAKE -S . -B build \
       -G "Unix Makefiles" \
       -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
       -DANDROID_ABI=arm64-v8a \
       -DANDROID_PLATFORM=android-31
$CMAKE --build build -j
```

`QNN_SDK_ROOT` can also be passed as a CMake cache entry: `-DQNN_SDK_ROOT=...`.
CMake hard-errors if the headers are not found -- the wake-word HAL has no
ARM-CPU fallback path in this .so.

Output: `build/sound_trigger.primary.neo.so`. Verify the HAL entry symbol:

```bash
$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm \
    build/sound_trigger.primary.neo.so | grep HMI
# expect:  ... D HMI
```

The build does NOT require the QNN SDK — QNN is `dlopen`ed at runtime. If the
device doesn't have `libQnnHtp.so`, the HAL falls back to the `NullQnnRuntime`
(no detection, HAL still loads cleanly).

## Compile the QNN graph

Requires Qualcomm QAIRT / QNN SDK on a host box. See `DESIGN.md` for download
links.

```bash
export QNN_SDK_ROOT=/opt/qairt/2.x.y.zzzzzz
bash tools/compile_onnx_to_qnn.sh
# writes models/wakeword.bin
```

Agent F5 owns the first successful run.

## Persistence model

Phase 3 content is deployed in two tiers so it survives a `/data` wipe
(factory reset / FBE rekey) while still letting us iterate without
reflashing super:

- **Tier 1 (baked-in)** — `root-firmware.sh` Step 3c writes the real
  HAL, QNN runtime, Hexagon skel, wake-word model graphs, and the
  `privapp-permissions-*.xml` directly into `system.img` at the correct
  paths. These live inside `super_4.img`, get covered by the regenerated
  AVB hashtree, and are live on first boot. Wiping `/data` does not
  affect them. The HAL is baked at
  `/system/lib64/hw/sound_trigger.primary.neo.so` and an `on init`
  bind-mount in `diy-overlay.rc` makes `/vendor/lib64/hw/...` point at
  it so the framework loads our HAL without any `/data` dependency.
- **Tier 2 (overlay)** — files pushed to `/data/local/diy-overlay/<abs
  path>` by `deploy.sh` / `deploy-privapp.sh` are bind-mounted on top of
  the Tier-1 versions at `on post-fs-data`. This is the fast-iteration
  path: swap a rebuilt HAL or retrained model into the overlay directory
  and reboot, no reflash needed. If the overlay source is missing, init
  silently skips its bind and the Tier-1 baked-in version stays live.

The **listener APK is deliberately NOT baked into Tier 1**. The debug
APK is ~176 MB which overflows the system partition; a 1-byte stub is
baked at `/system/priv-app/com.repository.glasses.listener/listener.apk`
and the real APK arrives via Tier 2 (`deploy-privapp.sh`). After a
`/data` wipe you MUST re-run `deploy-privapp.sh` to restore the privapp
APK; all other Phase-3 content works immediately.

To revert Tier 2 to Tier-1 defaults (drop all overlay files, fall back
to baked):

```bash
adb -s <GLASSES_SERIAL> shell 'rm -rf /data/local/diy-overlay/*'
adb -s <GLASSES_SERIAL> reboot
```

To update Tier 1 (new baked baseline): rebuild via `root-firmware.sh`
and re-flash super via EDL/qdl. Only done when shipping a new stable
baseline — day-to-day iteration stays in Tier 2.

## Deploy

The full deployment is a TWO-STEP process. Each step has its own env-var
gate because the risk profiles are different: step 1 can brick audio
(bad HAL); step 2 runs the listener APK with elevated privileges (bad
APK regression now has more blast radius). READ `DESIGN.md` risks first.

### Step 1 — HAL + QNN runtime + graphs

```bash
GLASSES_STHAL_DEPLOY=1 bash deploy.sh
```

This builds, pushes the .so + privapp XML + QNN runtime libs + Hexagon
aDSP skels + QNN graph binaries, and reboots the glasses. USB must stay
connected in case the HAL is buggy.

### Step 2 — privapp install (grants `CAPTURE_AUDIO_HOTWORD`)

```bash
GLASSES_PRIVAPP_INSTALL=1 bash deploy-privapp.sh
# then, when you are ready:
adb -s <GLASSES_SERIAL> reboot
```

This copies the already-built listener APK into
`/data/local/diy-overlay/system/priv-app/com.repository.glasses.listener/listener.apk`.
After reboot the DIY overlay bind-mounts it into place as
`/system/priv-app/com.repository.glasses.listener/listener.apk`, at which
point PackageManager treats the app as privileged and the pushed
`privapp-permissions-*.xml` becomes active, granting
`CAPTURE_AUDIO_HOTWORD`.

Expects the APK at
`app/build/outputs/apk/debug/app-debug.apk` (override with
`GLASSES_APK_PATH=...`). Build it first via the normal glasses deploy
pipeline (`bash Recon/scripts/deploy-to-glasses.sh` — if no device is
connected the script still builds and exits cleanly) or via
`./gradlew -p AI/clients/glasses :app:assembleDebug`.

Verification after reboot:

```bash
adb -s <GLASSES_SERIAL> shell \
    'dumpsys package com.repository.glasses.listener | grep -iE "CAPTURE_AUDIO_HOTWORD|privileged"'
# expect:  android.permission.CAPTURE_AUDIO_HOTWORD: granted=true
# and somewhere:  pkgFlags=... PRIVILEGED ...
```

### Follow-up rebuild workflow

Once the APK has been installed as privileged at least once, DO NOT use
`pm install` (or `deploy-to-glasses.sh`'s ADB install path) for subsequent
rebuilds of the listener package. The priv-app copy takes precedence and
re-installing the same package over the top via `pm install` can trigger
signing-conflict edge cases.

Correct post-initial-privapp workflow:

1. Rebuild the APK — gradle is fine; skip the `adb install`.
2. Re-run `GLASSES_PRIVAPP_INSTALL=1 bash deploy-privapp.sh`.
3. Reboot.

The overlay bind-mount re-materialises the priv-app layout fresh at every
post-fs-data, so the new APK becomes the privileged copy from the next
boot forward.

## What `deploy.sh` alone does NOT do

With only `deploy.sh` run (i.e. step 1 but not step 2), the HAL path is
fully deployed but DORMANT:

- **APK is NOT yet in `/system/priv-app/`.** Regular `pm install` does
  not promote the app to privileged. The `CAPTURE_AUDIO_HOTWORD` grant
  from the pushed `privapp-permissions-*.xml` is inert, and the framework
  denies the reflection call into
  `SoundTriggerManager.attachGenericSoundModel`. Step 2
  (`deploy-privapp.sh`) fixes this.
- Before Phase 3 F5 landed, the HAL also would not have the QNN SDK
  runtime libs or the QNN graph binary present. `deploy.sh` now pushes
  both of these, so this is a non-issue once step 1 completes
  successfully.

## Rollback

### HAL (step 1)

```bash
adb -s <GLASSES_SERIAL> shell \
    rm /data/local/diy-overlay/system/lib64/hw/sound_trigger.primary.neo.so
adb -s <GLASSES_SERIAL> reboot
```

On reboot the overlay bind-mount no longer supplies our file, so the stock
vendor HAL at `/vendor/lib64/hw/sound_trigger.primary.neo.so` is loaded
unchanged.

### Privapp install (step 2)

```bash
adb -s <GLASSES_SERIAL> shell \
    rm -rf /data/local/diy-overlay/system/priv-app/com.repository.glasses.listener
adb -s <GLASSES_SERIAL> reboot
```

After reboot, PackageManager no longer sees the APK under
`/system/priv-app`, so the user-installed copy (from `pm install` /
`deploy-to-glasses.sh`) is used instead and
`CAPTURE_AUDIO_HOTWORD` reverts to denied. The pushed
`privapp-permissions-*.xml` is harmless to leave in place — it only
grants the permission when a package with a matching id is actually
present in `/system/priv-app/`.

## Testing

1. `adb shell dumpsys media.sound_trigger_hw | head -40` — must show
   `implementor=Repository` and `description=Repository custom wake-word HAL`.
2. `adb shell logcat -d | grep -i sthal` — must show no errors.
3. `adb shell getprop init.svc.audioserver` — must be `running` (NOT
   `restarting`).
4. Load a sound model from Kotlin via `SoundTrigger.attachGenericSoundModel()`
   and confirm `load_sound_model` call reaches the HAL (logcat).

## Prereqs

| Tool   | Version               | Location                                |
|--------|-----------------------|-----------------------------------------|
| NDK    | 27.1.12297006 (tested)| `$HOME/Android/Sdk/ndk/27.1.12297006`   |
| cmake  | 3.22.1+               | `$HOME/Android/Sdk/cmake/3.22.1/bin`    |
| adb    | platform-tools 34+    | `$HOME/Android/Sdk/platform-tools`      |
| QNN    | QAIRT Community 2.x   | optional; only for graph compile        |

Install NDK + cmake via `sdkmanager`:

```bash
$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager \
    "ndk;27.1.12297006" "cmake;3.22.1"
```
