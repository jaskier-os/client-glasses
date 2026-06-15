# CLAUDE.md -- Capture module (`com.repository.glasses.capture`)

Out-of-process camera/photo/video service for the Rokid AR glasses, exposed over
`ICapture.aidl`. Isolates Camera2 + MediaCodec + ML denoise from the listener
process so a capture crash cannot take the listener down. Built as a **priv-app
APK** and installed via the `/system/priv-app` overlay slot by
`scripts/deploy-to-glasses.sh` (NEVER `adb install` -- see repo + glasses
CLAUDE.md).

## Source map

```
capture/src/main/java/com/repository/glasses/capture/
  CaptureService.kt    -- Service + ICapture.Stub. AIDL takePhoto/startVideo/stopVideo/
                          captureReidFrame + ADB test hooks (ACTION_ADB_*). forceTeardown().
  CameraSession.kt     -- SINGLE camera owner. Serializes all opens through one queue;
                          owns the CameraDevice + capture session + repeating request +
                          frame-stall watchdog. captureVideoSnapshot (JPEG live-shot while
                          recording). isRecordingOutputActive() = authoritative record state.
  VideoRecorder.kt     -- MediaRecorder lifecycle (HEVC 1080p30). Does NOT own the camera.
                          pause/resume, forceStop().
  RawStillCapturer.kt  -- RAW_SENSOR burst -> own demosaic -> SplitterNet denoise -> JPEG.
                          Disk-backed crash-resume queue (.pending/ sidecars). Two-phase:
                          fast preview JPEG then full-res denoised. skip_denoise prop.
  RawDemosaic.kt       -- Bilinear demosaic + WB + CCM + local tone mapping (Reinhard).
  SplitterDenoiser.kt  -- SplitterNet TFLite denoiser, tiled 256x256. SEE "NPU" BELOW.
  LowLightCapturer.kt  -- DNG capture path (ADB_TAKE_RAW), no ML.
  PhotoCapturer.kt     -- YUV preview-tap photo path (non-recording).
  LedController.kt     -- White privacy LED; vendor.rkd.camera.led.enable source gate.
  FileNamer / Sha256 / boot/BootReceiver.
```

## Camera architecture: single owner

`CameraSession` is the ONE owner of the CameraDevice. Photo, video, and ReID all
go through it; opens are serialized through a single queue. Do NOT open a second
CameraDevice anywhere. Capture-during-recording (photo + ReID while video records)
is a HARD product requirement and works via `captureVideoSnapshot` (a one-shot JPEG
BLOB live-shot off the active record session) -- never remove it. The LIMITED
Qualcomm HAL will NOT do RAW+YUV concurrently; a YUV snapshot reader during
recording triggers a ZSLPreviewRawYUV buffer-error storm that wedges the device,
which is why the snapshot path is JPEG, not YUV.

## Debug / test hooks (ADB)

`CaptureService` accepts `am startservice` actions (bypass AIDL) for testing:
`ADB_TAKE_PHOTO` (--es id X), `ADB_START_VIDEO`, `ADB_STOP_VIDEO`, `ADB_TAKE_RAW`
(--ei iso/exposure_ms). Results land in
`/sdcard/Android/data/com.repository.glasses.capture/files/adb_results/<id>.json`.
Reboot-scoped props (NON-persist, reset on reboot):
`debug.glasses.capture.skip_denoise` (1 = skip the ~67-130s denoise, ship the
demosaiced JPEG -- fast exposure iteration),
`debug.glasses.capture.denoise_interp` / `_threads` (CPU parallelism sweep),
`debug.glasses.capture.denoise_gpu` (TFLite GPU delegate toggle -- see below, does
not work on this model).

## Denoise: SplitterNet + NPU acceleration

`SplitterDenoiser.kt` runs SplitterNet (`assets/ml/splitternet.tflite`), a
fully-conv U-Net, tiled at 256x256 with 16px overlap over the ~1504x2016 photo
(63 tiles). Fixed input `[1,256,256,3]` float32 RGB [0,1]. ~1.6 GMACs/tile.

### Current production path: CPU
1 TFLite interpreter x 4 intra-op threads. The SoC has only 4 Cortex-A55 cores, so
4 threads saturate them -- the denoise is compute-bound. The ~236MB tensor arena is
created+closed per `denoise()` call (NEVER held idle: a permanent arena was the
dominant cause of the capture-FGS OOM on this 1.8GB device). Cost: ~67-105s/photo,
all 4 cores pinned the whole time (battery-heavy).

**Do NOT try these to speed it up (all measured-dead 2026-06-15):**
- More interpreters (2x2 / 4x1): zero speedup (cores already saturated) and 2x2
  doubles native heap to ~473MB -> OOM-kills the FGS under a photo burst.
- TFLite GPU delegate (Adreno 621): builds (needs explicit
  `org.tensorflow:tensorflow-lite-gpu-api:2.14.0` dep) but FAILS to apply --
  SplitterNet's SHAPE/PACK/REDUCE_MAX/dynamic-RESHAPE ops are unsupported by the
  Adreno GLES backend.
- NNAPI: no `libneuralnetworks.so` on this SDK-32 build.

### THE fast path: Hexagon V73 NPU via Qualcomm QNN (validated, not yet integrated)
The SoC (codename SSG2_AURORA, `/sys/devices/soc0/soc_id` = **579** =
**SSG2125P "AR1 Gen1 Luna2"**, QNN soc_model enum **58**) has a real Hexagon **V73**
NPU. Using the QAIRT SDK (Qualcomm AI Engine Direct -- requires a Qualcomm dev
account; the SDK zip lives in the user's Downloads), SplitterNet was converted +
int8-quantized and runs CORRECTLY on the NPU:

| path | per 256x256 tile | full 63-tile photo |
|---|---|---|
| QNN CPU backend | 265 ms | ~16.7 s |
| **V73 NPU (int8 w8a8)** | **70-74 ms** | **~4.6 s** |

~3.7x faster, and the NPU draws ~0.5-1W vs 4-core CPU saturation ~2.5-3.5W, so
**energy/photo drops ~5-10x** -- the real battery win. Output is visually lossless
(42-44 dB PSNR vs fp32).

**Conversion recipe (host, QAIRT 2.47):**
```
splitternet.tflite
 -> tf2onnx (opset 17)                         # constant-folds the dynamic
 -> onnxsim (static 256x256)                   #   SHAPE/PACK cluster away
 -> swap 78 reflect-pads to constant(zero) pad # fixes HTP flat_from_vtcm op fail
 -> qairt-converter                  -> float DLC (splitternet_cp.dlc)
 -> qairt-quantizer --act_bitwidth 8 --weights_bitwidth 8 --bias_bitwidth 8
       --use_per_channel_quantization -l <48-tile calib list>  -> w8a8 DLC
 -> qnn-context-binary-generator (run the AARCH64 generator ON THE DEVICE)
       --backend libQnnHtp.so --model libQnnModelDlc.so --dlc_path <w8a8.dlc>
       (config: soc_model 58, dsp_arch v73, pd_session unsigned)
 -> cached .bin  (load ~570ms; device prepare ~12.6s one-time)
```
Gotchas root-caused:
- **AR1 HTP has NO fp16 path** ("SocModel doesn't support FP16") -> int8 (or w8a16)
  is MANDATORY, not optional.
- The **x86** context-binary generator bakes a 4MB VTCM feature request the AR1
  silicon rejects ("Request feature vtcm size 4194304 unsupported", err 0x138d).
  The `vtcm_mb` graph knob does NOT change it. FIX: generate the `.bin` ON-DEVICE
  (aarch64 generator queries real hardware VTCM). So the cached `.bin` is
  device-VTCM-specific -> build it at first app launch, or prebuild on this exact
  SoC.
- Unsigned-PD signing is NOT a blocker (even non-rooted): the chip exposes
  `fastrpc_shell_unsigned_3`; the unsigned v73 skel loads on the standard PD.
  `ADSP_LIBRARY_PATH` must include the dir with `libQnnHtpV73Skel.so` (+ /vendor/lib/rfsa/adsp).

**APK must ship** (several already bundled via `onnxruntime-android-qnn`):
`libQnnHtp.so`, `libQnnHtpV73Stub.so`, `libQnnHtpV73Skel.so` (hexagon-v73/**unsigned**),
`libQnnSystem.so`, plus either (`libQnnModelDlc.so` + the w8a8 DLC + `libQnnHtpPrepare.so`
for on-device prepare) OR just the prebuilt cached `.bin`.

**Caveats:** (a) const-pad replaces the 78 reflect-pads with zero-pad -> faint dark
seam at tile borders; the 16px overlap masks it but verify on a full stitched photo.
(b) if int8 smears flat low-light regions, bump to w8a16 (chip supports it, same
on-device prepare path).

Validated artifacts (off-repo, on this build host): `/tmp/qnnbuild/FINAL/`
(`splitternet_cp_w8a8.dlc`, `splitternet_ondevice.bin`, `htp_cfg58_q.json`,
`ext_cfg58_q.json`); 48-tile calibration set `/tmp/qnncalib/`. Device test dir
`/data/local/tmp/qnnrun/`.

## See also
- `HAL_RAW_STREAM_FINDINGS.md` -- Qualcomm LIMITED HAL stream-combo constraints.
- `VIDEO_CRASH_FINDINGS.md` -- the ZSLPreviewRawYUV record+snapshot crash + JPEG fix.
