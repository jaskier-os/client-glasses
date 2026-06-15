# Rokid glasses camera HAL — RAW + readable-stream combination findings (2026-06-15)

Device 1901092544026001, Camera 0, Qualcomm "neo" CamX HAL, Android 12.

## Exact rejection reason (RAW 4032x3024 + YUV_420_888 640x480)
```
Camera3-OutputStream: configureConsumerQueueLocked: Camera HAL requested max_buffer count: 0, requires at least 1
Camera3-Stream:       finishConfiguration: Unable to configure stream 0 queue: Function not implemented (-38)
Camera3-Device:       configureStreamsLocked: Can't finish configuring output stream 0: Function not implemented (-38)
CameraDeviceClient:   endConfigure: Camera 0: Unsupported set of inputs/outputs provided
CamX camxhal3.cpp:1130 configure_streams() max_buffers : 0
CamX camxhal3.cpp:1171 configure_streams() HalOp: End CONFIG failed
```
The CamX usecase selector picks the Rokid usecase `RealTimeFeatureZSLPreviewRawRokid` (seen in
camxresourcemanager DumpState). That usecase has NO pipeline that emits RAW + a processed YUV
concurrently, so the HAL returns `max_buffers=0` for the RAW stream → framework rejects with -38
"Unsupported set of inputs/outputs". It is a **usecase-match / pipeline failure**, baked into
`/vendor/lib64/com.qti.chiusecaseselector.so` (not editable XML; `/vendor/etc/camera/` has no
override txt, only `usecaseKvManager.xml` which has no RAW/YUV stream entries).

## Hardware capabilities (dumpsys media.camera)
- `INFO_SUPPORTED_HARDWARE_LEVEL = LIMITED`
- `REQUEST_MAX_NUM_OUTPUT_STREAMS = [raw=1, proc=3, procStalling=2]`
- Capabilities: BACKWARD_COMPATIBLE, **RAW**, YUV_REPROCESSING, PRIVATE_REPROCESSING,
  READ_SENSOR_SETTINGS, MANUAL_SENSOR, BURST_CAPTURE, MANUAL_POST_PROCESSING.

On **LIMITED** the Android-guaranteed combos with RAW are only `RAW + {PRIV<=preview}` and
`RAW + {YUV<=preview}` are NOT guaranteed (YUV+RAW concurrency is a FULL/LEVEL_3 guarantee).
This HAL follows the LIMITED rule: it does NOT provide a RAW+YUV pipeline.

## Stream config map (format codes: 34=PRIV, 35=YUV_420_888, 33=BLOB/JPEG, 54=RAW16)
- RAW (54) OUTPUT sizes: full + **binned/small** entries down to 176x144 — e.g.
  4032x3024, 2016x1512, 1280x720, 800x600, 640x480, 320x240. **A small RAW stream is legal.**
- YUV (35): 1920x1080 … 640x480 … 176x144 (640x480 IS in the map — so the failure is NOT
  size-validity; it is the missing RAW+YUV pipeline).
- PRIV (34): 800x600 … 640x480 … 176x144.
- JPEG/BLOB (33): only small, 640x480 … 176x144.
- JPEG is stalling (stallDuration ~79 ms at 4032x3024). RAW is stalling. YUV is non-stalling.

## What works vs not (ground truth)
- **RAW + PRIVATE (SurfaceTexture)** — WORKS (the original code; the HAL's
  ZSLPreviewRaw usecase = RAW still + a PRIVATE preview surface for AE warmup). NOT CPU-readable.
- **RAW + YUV_420_888 (any size)** — **REJECTED.** Not size-specific; format/pipeline-specific.
  No RAW+YUV usecase exists in this HAL.
- **RAW + JPEG** — expected to fail the same way (no RAW+BLOB Rokid pipeline; two stalling
  streams also stresses procStalling=2 but the real blocker is the usecase match, same as YUV).
- **RAW alone (full OR binned)** — ALWAYS configures (single-stream ZSL RAW usecase).

## RECOMMENDED ground-truth approach: option (b) — meter off a single RAW frame, RAW-only session
Since RAW-only always configures and the CFA is **RGGB** (already verified via rawpy and used
throughout `RawDemosaic.kt`), compute scene luma directly from one RAW frame — **no second
stream**, sidestepping the combo limit entirely.

Mechanism (zero extra streams):
1. Single-stream session: `ImageReader(RAW_SENSOR, w, h, N+1)`. Optionally use a **binned RAW
   size** (e.g. 2016x1512 or 1280x720 from the RAW map) for a faster warmup-meter frame, then
   reconfigure/continue for the full burst — or just meter off the first full-res RAW frame.
2. AE warmup repeating request targets the RAW reader (TEMPLATE_PREVIEW, AE_MODE_ON,
   precapture trigger) until `aeState==CONVERGED` — identical to today minus the YUV target.
3. On the first converged RAW frame, compute mean luma from the **green Bayer samples**:
   - RGGB layout: index `i = y*w + x`. Green = pixels where `(x+y)` is odd
     (positions (even row,odd col) and (odd row,even col)). Equivalently the two greens of each
     2x2 quad at (x+1,y) and (x,y+1).
   - `lin = (raw[i] & 0xFFFF) - blackLevel`; `meanLuma01 = mean(lin) / (whiteLevel - blackLevel)`.
     `blackLevel≈64`, `whiteLevel=1023` from SENSOR_BLACK_LEVEL_PATTERN / SENSOR_INFO_WHITE_LEVEL.
   - Highlight-clip fraction = fraction of green samples with `raw[i] >= whiteLevel*0.98`.
   Subsample on a coarse grid (e.g. every 8th green) for speed.
4. This is ALREADY built: `RawDemosaic.fastPreviewToBitmap` computes a per-block 8-green sum AND
   a 256-bin histogram in one pass — reuse that pass to emit `(meanGreen, clipFrac)` cheaply.
   Drop `meterReader`/`METER_W/H`/`REID_METER_W/H` and the YUV listener entirely from both
   `captureBurst` and the reid path.

Fallback if a converged RAW is wanted at metered-then-corrected exposure: option (d) — capture one
RAW, compute luma, and only re-capture with EV correction if mis-exposed (always-valid single
stream; adds latency only when needed).

Avoid: RAW+YUV and RAW+JPEG (no HAL pipeline). RAW+PRIVATE works but yields no CPU luma, so it
only helps combined with (b), which already removes the need for any second stream.

## Could not fully determine
- The precise binary rule inside chiusecaseselector.so (usecase names are runtime-concatenated;
  no editable config). Settled enough: on-device test proves RAW+YUV fails and RAW-only succeeds.
- Whether a binned-RAW warmup + full-RAW burst needs a session reconfigure or can share one
  reader at one size — implementer should meter off the first full-res RAW frame to stay on one
  config (simplest, guaranteed).
