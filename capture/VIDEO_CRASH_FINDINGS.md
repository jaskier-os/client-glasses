# Glasses capture — video recording instability + stuck white LED (root-cause, 2026-06-15)

Device 1901092544026001, Camera 0, Qualcomm "neo" CamX HAL, Android 12 (API 32),
`INFO_SUPPORTED_HARDWARE_LEVEL = LIMITED`, `REQUEST_MAX_NUM_OUTPUT_STREAMS = [raw=1, proc=3, procStalling=2]`.
Investigation was READ-ONLY (adb / su reads / dumpsys / logcat / source). No code changed, no deploy.

---

## BUG 1 — recording instability / "crash" = recorder(PRIV) + snapshot(YUV) maps to an unsustainable ZSL usecase

### Evidence (live reproduction)

`am startservice ... ADB_START_VIDEO`, 8 s of logcat. The record session configured and started cleanly:

```
Cap:CamSession: openSnapshotReader YUV 1280x720
Cap:CamSession: setRecorderSurface holders=1
Cap:CamSession: repeating started template=RECORD recording=true outputs=2
Cap:Video: MediaRecorder.start (HEVC 1920x1080 30fps vBr=7000000 aBr=32000)
Cap:Video: started: .../VID_20260615_034726.mp4 recStartMs=16
```

`dumpsys media.camera` while recording — EXACTLY two configured streams:

```
Stream[0]: Output  Dims: 1920 x 1080, format 0x7fa30c06, dataspace 0x104   <- MediaRecorder surface (IMPLEMENTATION_DEFINED / PRIV, BT709 video)
Stream[1]: Output  Dims: 1280 x 720,  format 0x23,       dataspace 0x8c20000 <- snapshot ImageReader (YUV_420_888)
```

Then a CONTINUOUS buffer-error storm for the entire window (03:47:27.610 → 03:47:34.562):

```
112 "Reporting a buffer error" lines in ~8 s
637 occurrences of the usecase name "RealTimeFeatureZSLPreviewRawYUV"

E CamX camxsession.cpp:6497 InjectResult() Session 0x... [RealTimeFeatureZSLPreviewRawYUV]
   Reporting a buffer error to the framework for streamIndex 0 SeqId: 8 <-> ReqId: 9
E CamX ... [RealTimeFeatureZSLPreviewRawYUV] ... streamIndex 1 SeqId: 8 <-> ReqId: 9
```

Errors hit **BOTH** streamIndex 0 (the recorder PRIV) AND streamIndex 1 (the snapshot YUV), on
nearly every ReqId (SeqId/ReqId increment 8,9,10,…). Only ONE `stream AE frame=1` diagnostic
printed in the window (logs every 30th frame) → frame delivery is sporadic; most requests error
out. The mp4 still grew (8.8 MB / 39 s reported on stop) — the recorder limps along on the few
non-errored frames, which is exactly the user's "grows a little, then unstable".

### Root cause (definitive)

The snapshot YUV reader is the culprit. `CameraSession.reconfigure()` UNCONDITIONALLY adds the
snapshot YUV reader to the record session while recording (lines ~418–424):

```kotlin
recorderSurface?.let { outputs.add(it) }
if (recorderSurface != null) {
    snapshotReader?.surface?.let { outputs.add(it) }   // <-- YUV always added while recording
}
```

`openSnapshotReader()` is called unconditionally from `setRecorderSurface()` (line 182). So every
recording is recorder(PRIV 1920x1080) + snapshot(YUV 1280x720).

On this LIMITED HAL the CamX usecase selector maps PRIV-record + YUV-readout to the Rokid ZSL
usecase **`RealTimeFeatureZSLPreviewRawYUV`** — a ZSL pipeline that internally also stages a RAW
buffer. This is the same RAW+YUV concurrency limit documented in `HAL_RAW_STREAM_FINDINGS.md`
(LIMITED guarantees RAW+PRIV but NOT RAW+YUV; no RAW+YUV pipeline exists in
`chiusecaseselector.so`). The difference here is that recorder(PRIV)+YUV **does configure**
(unlike RAW+YUV which fails configure with -38) but the selected ZSL pipeline **cannot sustain
delivery** → it injects a buffer error on essentially every frame for both streams → the recorder
is starved/unstable. It is NOT a configure rejection and NOT a process crash — it is a runtime
buffer-error storm in the ZSL pipeline triggered by the YUV second stream.

### Is the process actually crashing?

**No.** Zero `FATAL` / `AndroidRuntime` / `Process … died` / `lowmemorykiller` for
`com.repository.glasses.capture`. PID 8916 stayed alive throughout the storm and through stop.
The recording is a HAL error-storm + a wedged-but-alive, frame-starved encoder — not a true
crash. The user's "recording crashes" = unstable footage + the inability to stop it from the UI
(Bug 2), not a process death.

---

## BUG 2 — stuck white LED / "can't stop the recording"

### The clean ADB stop path WORKS

`ADB_STOP_VIDEO` traced end-to-end:

```
Cap:Svc : ADB STOP_VIDEO entry recording=true
Cap:Video: stop entry recording=true paused=false
Cap:CamSession: clearRecorderSurface holders=0
Cap:CamSession: closeInternal holders=0
LightCtrlSVC: cancel event: type=2, id=2014, calling 1063   <- cameraserver's auto CAMERA_OPEN cancelled
LightCtrlSVC: cancel event: type=2, id=2014, calling 8916   <- app's assertCameraOpenEvent cancelled
LightCtrlSVC: turnOff: 8
Cap:Video: stopped: .../VID_...mp4 duration=39355ms size=8873166
```

`/sys/class/leds/white/brightness` → **0** after stop. So `clearRecorderSurface()` →
`onRecordingOutputChanged(false)` → `cancelCameraOpenEvent()` + `turnOffWhite()` all ran and the
LED went dark. The LED-off mechanism itself is correct.

On START two `send event 2,2014` fire — one from cameraserver (callPid=1063, uid 1047) auto-firing
CAMERA_OPEN on camera open, one from the app (callPid=8916) `assertCameraOpenEvent()` — then
`turnOn: 8`; LED reads 255. Both are cancelled symmetrically on the clean stop.

### Where it actually fails — STATE DESYNC, so stop never runs

The stuck LED is NOT a broken LED-off path; it is the LED-off path **never being reached** because
the stop decision is gated on a recording-state boolean that desyncs from the wedged HAL session.
Every stop entrypoint is guarded by `video.isRecording()` (the `VideoRecorder.recording` flag):

- `CaptureService.stopVideo()` / `ADB_STOP_VIDEO`: `if (!video.isRecording()) { return }`
- FN long-press (`FunctionButtonHandler.longPressRunnable`):
  `val rec = capture.isRecording(); if (rec) capture.stopVideo() else capture.startVideo()`
- `CaptureBridge.isRecording(): Boolean = try { api?.isRecording ?: false } catch { false }`
  — returns **false on ANY binder hiccup / wedge**.

Desync scenarios that strand the LED ON:

1. **FN long-press reads `isRecording()==false` while the camera/LED are actually up.** If the
   capture binder is momentarily wedged by the buffer-error storm (the HAL thread is hammering the
   binder), `CaptureBridge.isRecording()` swallows the RemoteException and returns `false`. FN
   long-press then calls `startVideo()` instead of `stopVideo()` — exactly the user's "held FN, LED
   came on, won't go off, couldn't stop". A second long-press, still reading `false`, starts again
   instead of stopping. The user can never reach the `stopVideo` branch, so
   `clearRecorderSurface` / `cancelCameraOpenEvent` / `turnOffWhite` never run.

2. **The start path can leave the LED on after a failed/aborted start.** `setRecorderSurface()`
   fires `onRecordingOutputChanged(true)` → `turnOnWhite()` + `assertCameraOpenEvent()` as soon as
   the surface is configured. The matching OFF only fires from `clearRecorderSurface()`. If a start
   half-completes or the session wedges such that `video.recording` is left `false` (e.g.
   `cleanupQuietly` after a throw, or a stall during start) while the camera was opened and the
   2014 event already asserted, every later stop is a no-op (`!isRecording()`), and the
   cameraserver-fired CAMERA_OPEN(2014) (priority 8000) keeps the LED lit with nothing ever
   cancelling it.

3. **Frame-stall during the storm.** The stall watchdog (`FRAME_STALL_MS=2000`) calls back into
   `VideoRecorder.stop()`, which runs `clearRecorderSurface()` → LED off. But the watchdog only
   fires when the recorder surface gets NO frame for 2 s. In the storm the recorder still gets
   occasional frames (mp4 grew), so the watchdog generally does NOT fire — the recording neither
   stops cleanly nor trips the watchdog; it just sits wedged with the LED on. The user's only
   recourse (UI stop / FN) is gated on the desynced `isRecording()` and fails.

Net: the LED-off code is correct; it is simply never invoked because every stop is gated on a
recording-state boolean that the buffer-error storm / binder wedge can desync to `false`.

---

## FN-button interaction

Handled in the **listener** app (`com.repository.glasses.listener`), not capture:
`input/FunctionButtonHandler.kt` (KEYCODE_CAMERA state machine) → `capture/CaptureBridge` AIDL →
capture's `CaptureService`. Long-press toggles start/stop **purely on `capture.isRecording()`**;
short-press toggles pause/photo. Because the toggle direction is decided by a single boolean that
`CaptureBridge.isRecording()` defaults to `false` on any binder exception, a wedged/desynced
capture process makes long-press repeatedly **start** (LED on) instead of **stop** — it can
double-start and never stops. No debounce against an already-open camera independent of the flag.

---

## FIX DIRECTION (not implemented) — capture-during-record MUST keep working

Constraint: photo + ReID during recording must keep working; do NOT drop snapshot-while-recording.

### Bug 1 — replace the YUV second stream with a HAL-blessed concurrent readout (ranked)

1. **(highest) Recorder(PRIV) + a SMALL JPEG/BLOB ImageReader as the snapshot stream.** On
   LIMITED, the guaranteed RECORD combos are `PRIV(record) + {PRIV|YUV|JPEG <= maxPreview/record}`,
   but on THIS HAL the YUV path is what selects the unsustainable ZSL-RAW-YUV usecase. A JPEG/BLOB
   reader (format 0x21) is a stalling stream the HAL already supports for stills and is far less
   likely to pull in the ZSL-RAW staging path than YUV. Test: configure recorder(PRIV 1080p) +
   JPEG ImageReader (e.g. 1280x720 or smaller from the BLOB map) and confirm the usecase name is
   NOT `…ZSLPreviewRawYUV` and no buffer-error storm. (Earlier code comments claim a JPEG reader in
   a repeating request "starves" — but that was JPEG-ONLY as the repeating target; here it is a
   secondary still target alongside the recorder, captured on-demand via `session.capture()`, not
   a repeating target.) If JPEG-on-demand works, drop the per-frame YUV cache entirely.

2. **Grab the snapshot from the recorder/encoder path itself (no second camera stream).** Feed the
   camera into a single PRIV stream that fans out to BOTH the MediaRecorder/MediaCodec input
   Surface AND a snapshot consumer — e.g. capture frames off the encoder (MediaCodec
   `getOutputImage` / surface tee), or drive the recorder through an intermediate SurfaceTexture
   you can also read. One PRIV stream = the known-good recorder-only config (no ZSL-RAW-YUV
   selection at all), and the snapshot is a copy of a frame already flowing to the encoder. Highest
   robustness; more plumbing.

3. **On-demand single readout instead of a persistent second stream.** Keep recorder-only
   (PRIV) as the steady record session; when a photo/ReID frame is requested while recording,
   briefly add a snapshot stream, grab one frame, and remove it. Risk: a mid-record reconfigure can
   hitch the encoder; only viable if the HAL tolerates a transient reconfigure on the record
   session (test before adopting). Lower preference than 1/2.

4. **Probe which exact second-stream format/size avoids the ZSL-RAW-YUV usecase.** Systematically
   try recorder(PRIV) + {PRIV-readable, JPEG, YUV@smaller sizes} and read the usecase name from
   `camxresourcemanager DumpState` for each; pick the first combo whose usecase is a plain
   preview/record usecase (no Raw/ZSL) and shows no buffer errors. Drives the choice in 1–3.

The essential change is: **the second (snapshot) stream must not be YUV_420_888**, because YUV is
what makes this LIMITED HAL select the unsustainable `RealTimeFeatureZSLPreviewRawYUV` pipeline.

### Bug 2 — make stop + LED-off unconditional and fix the FN toggle (ranked)

1. **(highest) Force LED off + cancel CAMERA_OPEN(2014) on EVERY stop attempt, regardless of
   `isRecording()`.** In `CaptureService.stopVideo()` / `ADB_STOP_VIDEO`, even when
   `!video.isRecording()`, still call `cameraSession.clearRecorderSurface()` (idempotent — it
   no-ops if already cleared) and unconditionally `LedController.cancelCameraOpenEvent()` +
   `turnOffWhite()`. This guarantees a user-driven stop always darkens the LED even when state is
   desynced. (`onDestroy` already does this belt-and-braces; do it on every stop too.)

2. **Make the FN long-press able to force-stop.** Don't trust `isRecording()==false` to mean
   "nothing to stop": if the camera is actually open (the LED/2014 event is asserted, or
   `cameraSession.isRecordingOutputActive()` is true), treat long-press as STOP. Expose a
   `forceStop()` on the bridge that calls `clearRecorderSurface()` + LED-off + `cleanupRecorder()`
   unconditionally, and have FN call it when the camera appears active regardless of the boolean.

3. **Drive the LED off the actual session state, not the recorder flag.** The LED is already wired
   to `onRecordingOutputChanged`, which is correct; additionally re-assert OFF whenever
   `recorderSurface == null` is observed (e.g. on `closeInternal`) so any path that tears the
   session down without a clean stop still darkens the LED.

4. **Tighten `CaptureBridge.isRecording()`** so a binder timeout/wedge does not silently report
   `false` and flip the FN toggle into a re-start; distinguish "known not recording" from "unknown
   / binder unhealthy" and, on unknown, prefer STOP semantics for the FN long-press.

Bug 1 (kill the buffer-error storm by changing the snapshot stream format) is the higher-impact fix
— it removes the wedge that causes the desync in the first place. Bug 2's unconditional-stop +
force-stop is the essential safety net so the user can ALWAYS turn the LED/recording off even if a
future HAL wedge recurs.

---

## HAL-compatible snapshot-during-record combo (2026-06-15, evidence-based)

### WHY recorder+YUV(streaming) error-storms

Two compounding causes:

1. **Usecase selection.** With recorder(PRIV 1080p) + YUV_420_888(720p) BOTH in the repeating
   request, the CamX selector on this Rokid LIMITED HAL maps the pair to
   `RealTimeFeatureZSLPreviewRawYUV` — a ZSL pipeline that internally also stages a RAW buffer
   (the same RAW+YUV-concurrency wall documented in `HAL_RAW_STREAM_FINDINGS.md`; LIMITED
   guarantees RAW+PRIV but NOT RAW+YUV). `dumpsys media.camera` during recording shows exactly
   `Stream[0] 1920x1080 format 0x7fa30c06` (recorder PRIV) + `Stream[1] 1280x720 format 0x23`
   (YUV) and the storm tags every frame with `[RealTimeFeatureZSLPreviewRawYUV]` (637 hits / 8 s).
2. **Continuous dual-stream.** The YUV reader is a TARGET of the 30 fps repeating request, so the
   ZSL pipeline must sustain RAW-stage + PRIV-encode + YUV-readout every frame. It can't:
   `InjectResult ... Reporting a buffer error` fires on BOTH streamIndex 0 and 1 on nearly every
   ReqId. The recorder is frame-starved; the mp4 limps on the few non-errored frames.

The Stream[1] YUV `DequeueBuffer latency histogram` confirms it: 22.44% of dequeues land in the
`inf` (>45 ms) bucket — the YUV consumer is the stalled stream dragging the pipeline.

### Leading candidate: recorder(PRIV) + JPEG(BLOB), on-demand one-shot capture — HAL-SUPPORTED

Strong evidence this AVOIDS the ZSL-RAW-YUV path:

- **The HAL ships dedicated live-shot usecases.** `strings` on
  `/vendor/lib64/com.qti.chiusecaseselector.so` lists the canonical Qualcomm "snapshot while
  recording" pipelines:
  `ChiStreamIntentVideoSnapshot`, `UsecaseJPEGEncodeLiveSnapshot`,
  `UsecaseJPEGEncodeLiveSnapshotGPU`, `JPEGEncodeLiveSnapshotPreview`, `JPEGEncodeLiveSnapshotGPU`,
  plus the plain video usecases `PreviewVideo` / `RealTimePreviewVideoHFR`. These are EXACTLY the
  blessed PRIV-record + BLOB-snapshot ("live shot") path — a separate pipeline family from the
  `…ZSLPreviewRaw*` ones. A JPEG snapshot target selects a `*LiveSnapshot*` usecase, not
  `ZSLPreviewRawYUV`.
- **JPEG (BLOB, format 33) is available at the FULL size ladder, including record sizes.**
  `availableStreamConfigurations` lists `33 1920 1080 OUTPUT`, `33 1280 720 OUTPUT`,
  `33 640 480 OUTPUT`, up to `33 4032 3024 OUTPUT`. (Correction to `HAL_RAW_STREAM_FINDINGS.md`,
  which wrongly said "JPEG only small, 640x480 and below" — JPEG has the complete ladder. So a
  720p/1080p JPEG snapshot is a legal size.)
- **Stalling-stream budget fits.** `maxNumOutputStreams = [raw=1, proc=3, procStalling=2]`.
  Recorder PRIV is non-stalling; JPEG is one stalling stream → 1 of 2 procStalling used. Within
  budget with room to spare.
- **On-demand, NOT a repeating target — this is the key change.** The current YUV reader streams
  at 30 fps as a repeating-request target (the continuous dual-stream that storms). A JPEG live-shot
  reader sits IDLE (no repeating target); you fire a single high-priority `session.capture()` only
  when a photo/ReID frame is needed. The recorder repeating request keeps running on a single PRIV
  stream (the known-good recorder-only config), and the JPEG stream only produces a buffer on the
  rare one-shot — so there is no continuous concurrency for the HAL to choke on.

### Recommended design for snapshot-during-record (preserves the feature)

- Record session outputs = **recorder PRIV surface** (repeating request, `TEMPLATE_RECORD`,
  `CONTROL_CAPTURE_INTENT = VIDEO_RECORD`) + a **JPEG `ImageReader`** (e.g. 1920x1080 or 1280x720
  from the BLOB ladder) that is **NOT added to the repeating request**.
- To snapshot while recording: build a one-shot request from `TEMPLATE_STILL_CAPTURE` (or
  `TEMPLATE_VIDEO_SNAPSHOT` if available) targeting ONLY the JPEG reader, set
  `CONTROL_CAPTURE_INTENT = VIDEO_SNAPSHOT`, and `session.capture(...)` it once. Read JPEG bytes
  from the reader's `onImageAvailable`.
- ReID-during-record fires these one-shots periodically (every N seconds) instead of caching a
  per-frame YUV. This drops the 30 fps YUV listener entirely.
- Stream count = 2 outputs (PRIV + JPEG): within proc=3 / procStalling=2. Selects a
  `*LiveSnapshot* / PreviewVideo` usecase, NOT `RealTimeFeatureZSLPreviewRawYUV`.

Verification step before adopting (no code change required to probe): configure recorder(PRIV) +
JPEG and confirm via `adb shell dumpsys media.camera` that Stream[1] format is `0x21` (BLOB) and
that logcat (`-b all | grep -E 'Usecase|usecase'`) shows a `LiveSnapshot`/`PreviewVideo` usecase
with NO `Reporting a buffer error` storm. (Could not run this live: the existing app's intents only
exercise the RAW-still photo path and the YUV recording path; there is no app intent that configures
a JPEG snapshot reader, and code may not be modified.)

### Fallback ranking if JPEG live-shot also storms

1. **PRIV record + a second PRIV/SurfaceTexture readout, on-demand.** PRIV+PRIV is the other
   LIMITED-guaranteed combo and won't pull the RAW/YUV ZSL path; copy a frame off the second PRIV
   surface (SurfaceTexture→GL→readPixels) only when a snapshot is needed.
2. **Tee the encoder input (no second camera stream).** Single PRIV stream fanned to the
   MediaCodec input surface AND a SurfaceTexture you read for snapshots — exactly the known-good
   recorder-only config, snapshot is a copy of a frame already going to the encoder. Most robust,
   most plumbing.
3. **Brief transient reconfigure:** recorder-only steady state, add a snapshot stream for one grab
   then remove it. Risk: mid-record reconfigure can hitch the encoder; only if the HAL tolerates it.

### Constraints / sizes

- JPEG max size `android.jpeg.maxSize = 18457096` bytes; full-res JPEG 4032x3024 legal but heavy.
  Use 1920x1080 or 1280x720 JPEG for fast live-shot (well under the stall budget).
- JPEG is a stalling format — keep it off the repeating request so its stall never paces the
  recorder.
- A `VIDEO_SNAPSHOT` capture intent on the one-shot is the correct hint to steer CamX onto the
  live-shot pipeline; `STILL_CAPTURE` also works but `VIDEO_SNAPSHOT` is the precise match.
