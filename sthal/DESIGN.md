# sthal — Custom SoundTrigger HAL for Rokid Neo (Phase 3)

Replacement `sound_trigger.primary.neo.so` that runs our wake-word pipeline on
Hexagon HTP via QNN, delivered through the existing DIY `/data/local/diy-overlay`
bind-mount (no `super.img` rebuild, no fastboot).

Target device: Rokid AR glasses, `ro.board.platform=neo`, `ro.hardware=qcom`,
SDK 32, arm64-v8a. Stock HAL: `/vendor/lib64/hw/sound_trigger.primary.neo.so`
(95 KB, to be overridden).

## Why

Phase 2 verified end-to-end wake-word on glasses via ORT QNN EP, but ORT silently
partitioned the graph to CPU (see `verification_report.md`). The HAL path cuts
the ARM big-cluster out of the steady-state hot-word loop entirely: mic PCM
flows DSP → HTP graph → `recognition_callback` only on detection.

## AOSP legacy HAL reference

Source: `android.googlesource.com/platform/hardware/libhardware`, Apache-2.0.
Relevant headers (reproduced under our project in `include/` to keep our build
self-contained):

- `hardware/libhardware/include_all/hardware/hardware.h` — `hw_module_t`,
  `hw_module_methods_t`, `hw_device_t`, `HAL_MODULE_INFO_SYM` (= `HMI`),
  `HARDWARE_MODULE_TAG`, `HARDWARE_DEVICE_TAG`.
- `hardware/libhardware/include_all/hardware/sound_trigger.h` —
  `sound_trigger_hw_device`, API version macros
  (`SOUND_TRIGGER_DEVICE_API_VERSION_1_3`), `SOUND_TRIGGER_HARDWARE_INTERFACE`
  (`"sound_trigger_hw_if"`), `SOUND_TRIGGER_HARDWARE_MODULE_ID_PRIMARY`
  (`"primary"`).
- `system/media/audio/include/system/sound_trigger.h` — `sound_trigger_properties`,
  `sound_trigger_sound_model` (+ `_generic_` subtype), `sound_trigger_recognition_event`
  (+ `_generic_recognition_event`), `sound_trigger_recognition_config`,
  `sound_model_handle_t`, `sound_trigger_uuid_t`, recognition-mode and status
  flags, SOUND_MODEL_TYPE_GENERIC.

### Module entry point

The AOSP loader `dlopen`s the `.so` and looks up the exported symbol
`HAL_MODULE_INFO_SYM` (expanded to `HMI`). That symbol is an
`hw_module_t` whose `.methods->open(module, SOUND_TRIGGER_HARDWARE_INTERFACE,
hw_device_t**)` must return a `sound_trigger_hw_device*`.

### Function-pointer table (version 1.3 — `sound_trigger_hw_device`)

- `int get_properties(dev, sound_trigger_properties*)` — fills
  `implementor`, `description`, `version`, `max_sound_models`,
  `max_key_phrases`, `max_users`, `recognition_modes`, `capture_transition`,
  `max_buffer_ms`, `concurrent_capture`, `trigger_in_event`, `power_consumption_mw`.
- `int load_sound_model(dev, sound_trigger_sound_model*, sound_model_callback_t,
  void* cookie, sound_model_handle_t*)` — opaque model bytes at
  `((char*)model) + data_offset`, length `data_size`. Our convention: opaque
  payload is a short ASCII token identifying which pre-compiled QNN context
  binary to load from `/vendor/etc/sthal/models/` or
  `/data/local/diy-overlay/system/etc/sthal/models/`.
- `int unload_sound_model(dev, sound_model_handle_t)` — tear down graph.
- `int start_recognition(dev, handle, sound_trigger_recognition_config*,
  recognition_callback_t, void* cookie)` — kick off mic read + HTP inference.
- `int stop_recognition(dev, handle)`.
- `int stop_all_recognitions(dev)` — 1.1+.
- `common.close(hw_device_t*)` from `hw_device_t` base.

Callbacks:

```
typedef void (*sound_model_callback_t)(sound_trigger_model_event*, void* cookie);
typedef void (*recognition_callback_t)(sound_trigger_recognition_event*, void* cookie);
```

On detection we allocate a `sound_trigger_generic_recognition_event`, set
`common.status = RECOGNITION_STATUS_SUCCESS`, `common.capture_available = true`,
`common.capture_delay_ms = 0` (or lab-fill delay), and include our LAB PCM at
`data_offset` if the client requested capture — otherwise upstream reads the
audio via `AudioRecord(AudioSource.HOTWORD)` against `capture_session`.

### Look-Ahead Buffer (LAB)

A HAL-owned ring buffer of the most recent ~2 s of 16 kHz mono int16 PCM
(64 KB). While recognition is active the mic reader appends frames; on detection
the latest N frames (configurable via recognition config opaque data) are
attached to the event. This lets the upstream app process the full utterance
that triggered wake without losing the leading edge.

## Qualcomm QNN public API (shape only; call through dlopen)

Public refs:

- `docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/` — QNN SDK
  tutorials (backend create, context-from-binary).
- `github.com/quic/qidk` — sample integrations.
- `softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community` —
  QAIRT / QNN Runtime downloads (provides `libQnnHtp.so`, `libQnnSystem.so`,
  `qnn-context-binary-generator`, `qnn-onnx-converter`).

### Minimal runtime flow

```
QnnInterface_getProviders(...)      // from libQnnSystem.so
QnnBackend_create(logger, cfg, &backend)
QnnDevice_create(logger, cfg, &device)
QnnContext_createFromBinary(backend, device, NULL,
                            buffer, size, &ctx, NULL)
// Enumerate graphs from ctx, retrieve tensor descriptors
QnnGraph_execute(graph, inputs, num_in, outputs, num_out, NULL, NULL)
// On shutdown:
QnnContext_free, QnnDevice_free, QnnBackend_free
```

A `.bin` context binary is produced offline by
`qnn-context-binary-generator` against an HTP backend model library (from
`qnn-model-lib-generator` + `qnn-onnx-converter`).

### Runtime library names (per QNN 2.x)

- `libQnnSystem.so` — interface-provider entry point.
- `libQnnHtp.so` — HTP backend (the one we want; aDSP residency via HTP).
- Secondary: `libQnnHtpV79Stub.so` / `V75` / `V73` per chip-specific HTP
  version. The stub is loaded by `libQnnHtp.so` via `qnn-htp-config` JSON.
  These ship with the SDK; we bundle the relevant stub for Neo into
  `/data/local/diy-overlay/vendor/lib64/` and add an `LD_LIBRARY_PATH` hint
  in a small shim.
- `libcdsprpc.so` / `libadsprpc.so` — FastRPC to the DSP; present in `/vendor/lib64`
  on the device (confirmed: `/dev/adsprpc-smd` exists on Neo; `/dev/cdsprpc-smd`
  does NOT — this board routes everything through the aDSP, not a separate CDSP).

### Build-time implication

The HAL `.so` must NOT hard-link any `libQnn*.so` — SDK binaries may not match
the device's exact version. We `dlopen("libQnnHtp.so", RTLD_NOW)` at
`start_recognition` time; on failure, log `ENOTSUP` and return error so the
Android framework falls back to the stock HAL (or our Kotlin ONNX-on-ARM
path if the overlay file gets renamed).

## On-device audio policy survey

Checked `/vendor/etc/audio/sku_neo_qssi/audio_policy_configuration.xml`,
`/vendor/etc/audio/sku_neo/audio_policy_configuration.xml`, and
`/vendor/etc/audio_policy_configuration.xml`. None mention
`hotword`, `voice_recognition`, `sound_trigger`, or `AUDIO_SOURCE_HOTWORD`.
This is consistent with: "no sound trigger service running on Neo today"
(confirmed by `dumpsys media.sound_trigger_hw` reporting
`Can't find service: media.sound_trigger_hw`).

The audio HAL itself (`audio.primary.neo.so`, 402 KB) is Qualcomm-derived,
exposes the standard mix-port / device-port schema, but no explicit
HOTWORD route. Two practical options for the mic read path:

1. **Open `AudioSource.VOICE_RECOGNITION` via `tinyalsa` / `libaudiohal_api`
   directly from inside the HAL** (requires linking against platform audio
   HAL headers — not available outside AOSP tree; would require SSH-level
   integration we don't have).
2. **Use `AudioRecord(AudioSource.HOTWORD)` from Kotlin on detection only**
   and do the continuous low-power mic read inside the HAL using a
   minimal wrapper around `/dev/snd/pcmC0D*c` `ioctl`s or via the
   standalone `tinyalsa` library (`pcm_open`, `pcm_readi`). `tinyalsa`
   is already present on the device (`/vendor/lib64/libtinyalsa.so`).

**Decision:** Phase 3 HAL uses option 2 — link statically to NDK-built
`tinyalsa` or `dlopen` the vendor one. This keeps us platform-portable.
Implementation in `mic_reader.cpp` is a TODO-heavy stub for now; agent F6
picks it up.

## privapp-permissions

The Android permission `android.permission.CAPTURE_AUDIO_HOTWORD` is
`signature|privileged`. Even with permissive SELinux, `PackageManager`
checks signature. For our in-firmware app we drop a
`privapp-permissions-com.repository.glasses.listener.xml` file via overlay
at `/data/local/diy-overlay/system/etc/permissions/`. The XML lists the
single permission under `<privapp-permissions package=...>`.

The listener app APK must also be treated as privileged — placed under
`/system/priv-app/<name>/` via overlay. That is out of scope for this
worktree (a later agent handles overlay install); here we only author
the XML.

## Deploy mechanism

Mount confirmed via existing firmware patches:

```
/system/etc/init/diy-overlay.rc  (bind-mounts /data/local/diy-overlay/system
                                   over /system at post-fs-data)
```

Deployment steps for our HAL (automated in `deploy.sh`, gated behind
`GLASSES_STHAL_DEPLOY=1`):

```
adb -s <GLASSES_SERIAL> push build/sound_trigger.primary.neo.so \
    /data/local/diy-overlay/system/lib64/hw/sound_trigger.primary.neo.so
adb -s <GLASSES_SERIAL> push priv-permissions.xml \
    /data/local/diy-overlay/system/etc/permissions/privapp-permissions-com.repository.glasses.listener.xml
adb -s <GLASSES_SERIAL> reboot
# wait, then:
adb -s <GLASSES_SERIAL> shell 'dumpsys media.sound_trigger_hw'
```

Rollback: `adb shell rm /data/local/diy-overlay/system/lib64/hw/sound_trigger.primary.neo.so && adb reboot`.

## Risks

- **HAL crash = audioserver down.** First deploy must be user-authorized
  with USB cable plugged in for recovery.
- **QNN SDK version drift.** HTP backend stub must match the aDSP firmware
  version on Neo. Unknown until we try on-device; plan is to start from
  the lowest-HTPv that QNN SDK still ships and bisect.
- **Mic double-open.** If the Kotlin side still has an `AudioRecord` open,
  the HAL mic read may fail. `WakeWordPipeline` must release its mic
  before enabling HAL mode (handled by agent F4).
- **aDSP residency verification.** There's no per-process power meter;
  proxy via `top -d 10` ARM idle % after 5 min and `lsof /dev/adsprpc-smd`
  from the audio-HAL daemon must show our graph loaded.

## Known TODOs

- F4 (Kotlin): add `useNativeHal` branch in `WakeWordPipeline.kt`, register
  `AlwaysOnHotwordDetector` against SOUND_MODEL_TYPE_GENERIC with our opaque
  model-id token.
- F5 (QNN graph compile): run `compile_onnx_to_qnn.sh` once QNN SDK is
  installed on a dev machine; drop `.bin` artifacts into `sthal/models/`
  and bake into overlay.
- F6 (mic reader): fill out `mic_reader.cpp` — `tinyalsa` pcm_open, 16 kHz
  mono s16le, 20 ms frames, feed into LAB and QnnRuntime.

## Non-goals for this worktree

- No AIDL HAL port (Android 13+ service-based HAL). Legacy HAL is sufficient
  for SDK 32 and avoids the `.hal`/AIDL toolchain dependency.
- No SELinux policy changes (firmware already permissive).
- No kernel module or sefcontext changes.
- No device-side QNN SDK installation — that's done offline on a dev box.
