# CamProbe -- empirical camera-streaming findings (Rokid glasses 1901092544026001)

All numbers measured on the real device with `com.test.camprobe` (source in this dir,
APK = `camprobe.apk`). Raw logs in `logs/`. Each config ran one stream type for a fixed
duration; fps = HAL `onCaptureCompleted` count / (last-first frame ms); LED sampled from
`/sys/class/leds/white/brightness` every 1-2s; live-frame proof = mean luma of a center
40x40 ROI sampled across frames must vary (`roiVaries`).

## 1. Device camera capabilities (on-device CameraCharacteristics query)

- **INFO_SUPPORTED_HARDWARE_LEVEL = LIMITED** (confirmed)
- REQUEST_AVAILABLE_CAPABILITIES = 0,1,2,3,4,5,6,7 (BACKWARD_COMPAT, MANUAL_SENSOR,
  MANUAL_POST_PROCESSING, RAW, ZSL/private-reprocessing, ... )
- SENSOR_ORIENTATION = 270
- CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES = [15,15],[24,24],[14,30],[30,30],[14,60],[60,60]
- Output sizes (YUV_420_888 / PRIVATE / JPEG all advertise the same ladder), e.g.
  320x240, 640x360, 640x480, 854x480, 1024x768, 1280x720, 1920x1080 ... up to 4032x3024.
  - For 640x480 / 1280x720: minFrameDuration = 16.67ms (60fps ceiling). YUV/PRIVATE
    variant stallNs=0; the JPEG variant has stallNs (5990400ns @1280x720) so JPEG tops
    out lower.

## 2. Results table (one stream config at a time, screen kept on)

| config | size | template | sustained fps (over 30-35s) | continuous full duration? | LED (default prop) | bufferLost | live frames |
|---|---|---|---|---|---|---|---|
| PRIVATE ImageReader | 640x480 | TEMPLATE_PREVIEW | **30.03** | YES (1045 frames/34.8s) | 255 (on) | 0 | n/a (opaque) |
| YUV_420_888 ImageReader | 1280x720 | TEMPLATE_PREVIEW | **16.23** | YES (564/34.8s) | 255 (on) | 0 | YES (mean 3..42) |
| YUV_420_888 ImageReader | 640x480 | TEMPLATE_PREVIEW | **15.40** | YES (458/29.7s) | **0 (DARK, prop=0)** | 0 | YES (mean 4..46) |
| YUV + PRIVATE SurfaceTexture (2 streams) | 640x480 | TEMPLATE_PREVIEW | **15.63** | YES (464/29.7s) | 255 (on) | 0 | YES (mean 5..60) |
| JPEG ImageReader | 1280x720 | TEMPLATE_PREVIEW | **14.50** | YES (428/29.5s) | 255 (on) | 0 (1 unexpected buf-err notify, non-fatal) | YES |
| SurfaceTexture + YUV | 640x480 | TEMPLATE_RECORD (no MediaRecorder/file) | 30 while alive | yes once screen-on bug fixed | 255 | 0 | YES |

No ZSL-RAW-YUV buffer-error STORM occurred in ANY config (including the 2-stream one).
The only CamX "error" seen is a single non-fatal `Unexpected Buffer Error Notification`
on the JPEG path. The `cam_req_mgr_process_flush_req` lines are the normal end-of-run
teardown, not mid-stream failures.

## 3. The ~8s "stall" was a measurement artifact, not a HAL limit

Early runs showed every stream dying after ~3-8s with a pipeline flush. Root cause found
in logcat: `E/CameraService: finishCameraStreamingOps` fired right before the flush --
i.e. CameraService revoked the app's camera-streaming AppOp because **the activity lost
top-focus / the screen dimmed off** (device had no wakelock). Adding
`FLAG_KEEP_SCREEN_ON | FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON` to the window made
every stream run rock-steady for the full 30-35s with zero stalls. This DISPROVES the
prior "streamed preview is unsustainable / selects an unsustainable ZSL-RAW-YUV usecase"
hypothesis for the continuous-streaming case. (`RealTimeFeatureZSLPreviewRawRokidExt` is
just the active Rokid usecase NAME in resource-manager dumps, not an error.)

## 4. Privacy LED -- what actually drives it (evidence)

- With `vendor.rkd.camera.led.enable` at its DEFAULT (1): the white LED goes to 255 the
  entire time the camera streams, for EVERY config (YUV/PRIVATE/JPEG/2-stream/record).
  So the LED tracks "camera is open/streaming", independent of stream type, format,
  resolution, or whether a file is written. It is NOT tied to recording.
- With `vendor.rkd.camera.led.enable=0` set before open: the LED stayed **0 (dark) the
  entire 30s** of a live 15.4fps YUV stream. Frames were confirmed live (roiVaries).
  This is the clean silent-stream gate (same mechanism the production capture module's
  `LedController.setCameraLedEnabled(false)` uses).

## 5. Definitive answer for rPPG

**YES** -- a continuous >=15fps SILENT (no LED, no file) live frame stream is achievable
on this device. Exact recipe:

- Single `ImageReader` of `YUV_420_888` at 640x480 (gives ~15.4fps; 1280x720 gives ~16fps
  but heavier readback) as the only output.
- `CameraDevice.TEMPLATE_PREVIEW`, `setRepeatingRequest`, `CONTROL_AE_MODE_ON` (+ one
  precapture trigger so AE converges off the dark default).
- Set `vendor.rkd.camera.led.enable=0` before opening (restore to 1 afterwards) to keep
  the privacy LED dark.
- The host activity/service MUST hold the camera-streaming foreground state (keep screen
  on or run as a proper foreground camera service); otherwise CameraService calls
  `finishCameraStreamingOps` and tears the stream down within seconds. This is the single
  most important non-obvious constraint.
- PRIVATE ImageReader hits a full 30fps but its buffers are opaque (no CPU pixel access),
  so it is unusable for skin-color sampling -- use YUV.

rPPG cares about temporal color stability at >=15fps, which YUV 640x480 satisfies with
zero dropped buffers.
