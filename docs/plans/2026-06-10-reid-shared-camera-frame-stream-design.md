# ReID Shared-Camera Frame Stream Design

Date: 2026-06-10
Status: PLAN (no code written yet)
Scope: glasses client (`AI/clients/glasses`), modules `capture/` and `app/`.

## 1. Problem statement and confirmed root cause

The glasses client runs as two independent Android processes that BOTH open
Camera2 device 0:

- Capture APK (`com.repository.glasses.capture`, `capture/` module): owns the
  camera for always-on video recording (`VideoRecorder.kt`) and stills
  (`PhotoCapturer.kt`). Exposed over AIDL via `ICapture`.
- Listener `:backend` process (`com.repository.glasses.listener`, `app/`
  module): its ReID face-recognition feature opens Camera2 DIRECTLY in
  `reid/ReidCameraCapturer.kt`, driven by `reid/ReidController.kt`.

When ReID starts while always-on video recording is active, the two processes
fight over camera 0. Live logcat showed device-0 eviction ping-pong: the
capture PID is evicted by the listener PID, then vice versa. ML Kit then fails
with "Internal error has occurred when executing ML Kit tasks", and the
listener `:backend` process takes a native SIGABRT. Net user-visible effect:
the ReID "start" button vanishes (the crash kills the pipeline) and "scanning"
disappears with zero faces found.

Camera2 on this Rokid HAL allows exactly one open client for device 0. The two
processes cannot both hold it. The fix is to make the capture module the SINGLE
camera owner and have ReID consume frames from it over AIDL.

## 2. Design intent (fixed; not relitigated here)

- The capture service is the SINGLE camera owner.
- Its camera session serves frames to ANY subscriber.
- The camera stays open while there is AT LEAST ONE listener (video recording
  OR a ReID frame subscription). With zero listeners it closes.
- Video recording is one kind of listener; ReID frame-subscription is another.
  Both share ONE camera session.
- `ReidCameraCapturer.kt`'s direct Camera2 open is REMOVED entirely. ReID keeps
  its ML Kit detection + crop/thumbnail logic, fed from AIDL-delivered frames.

## 3. Architecture decisions (one per critical question)

### Q1. Frame transport over AIDL/Binder

Decision: deliver a small JPEG `byte[]` per frame through a new oneway AIDL
callback `ICaptureCallback.onFrame(byte[] jpeg, int width, int height,
int rotationDeg, long frameId)`. The capture side adds a dedicated
`ImageReader` (ImageFormat.JPEG) target at 1280x960 to the shared session and
encodes at the camera HAL (hardware JPEG), then fans the bytes out to
subscribers. The capture side also rate-limits emission to the slowest
subscriber's cadence (see Q3) so it does not push 30 fps of JPEGs across
binder.

Why JPEG byte[] over the alternatives:

- Binder transaction limit (~1 MB shared per process, ~512 KB practical for a
  single transaction). A 1280x960 JPEG at the HAL's default quality is ~80-200
  KB, comfortably under the limit. ReID only needs ~1 frame / 1.5 s, so even
  the per-frame cost is negligible. A raw 1280x960 YUV_420_888 frame is ~1.8 MB
  and would NOT fit; a raw 1080p frame is ~3 MB. Rejected.
- ParcelFileDescriptor / Ashmem / MemoryFile shared buffer: would dodge the
  1 MB limit, but adds a manual SHM lifecycle (alloc, mmap, ref-count, unmap,
  cross-process tear-down on binder death) for a payload that already fits in a
  normal transaction. YAGNI for a 1.5 s cadence. Rejected.
- Surface handoff (listener creates an ImageReader, passes its Surface into the
  capture session): clean and zero-copy, BUT it means the listener process owns
  an ImageReader whose Surface is consumed by the capture session in another
  process. The producer (camera) writes directly into the listener's
  BufferQueue. This is technically supported (Surface is Parcelable across
  binder) and is the most "correct" long-term design, but: (a) it couples the
  capture session's output-set rebuild to the liveness of a cross-process
  Surface, complicating the session-rebuild logic in Q2; (b) ML Kit on this
  device historically wanted a pre-rotated bitmap from a decoded JPEG (existing
  `ReidCameraCapturer` decodes JPEG then `postRotate`), so we would still
  decode; (c) binder-death handling of a dangling remote Surface is fiddlier
  than dropping a callback. Rejected in favor of the simpler JPEG byte[] path
  which matches the existing ReID decode pipeline exactly (it already did
  `BitmapFactory.decodeByteArray(jpegData)` on HAL JPEGs).

Existing-pattern alignment: `ReidCameraCapturer` already used an
`ImageFormat.JPEG` ImageReader and decoded `byte[]` -> Bitmap -> `postRotate`.
We are moving the JPEG SOURCE from a listener-owned ImageReader to a
capture-owned one delivered over AIDL; the consumer code is nearly unchanged.

Frame thinning: the capture side delivers AT MOST one JPEG per
`frameMinIntervalMs` (default 1200 ms, just under ReID's 1500 ms with-face
cadence) and additionally drops a frame if the previous `onFrame` to that
subscriber has not returned (oneway, so we track an in-flight flag per
subscriber via a returned `boolean` ack is impossible on oneway -- instead we
gate purely on the 1200 ms timer; ReID already self-throttles to 1500/5000 ms
and ignores extra frames). This keeps binder traffic to <1 small JPEG/sec.

### Q2. Camera session topology

Decision: extract a single long-lived `CameraSession` owner ("frame hub") in
the capture process, refactored OUT of `VideoRecorder`. New class
`capture/.../CameraSession.kt`. It owns the one `CameraDevice` + one
`CameraCaptureSession`, and rebuilds the session whenever the SET of output
targets changes. Targets are:

- the ReID frame `ImageReader` (JPEG 1280x960) -- present whenever >=1 frame
  subscriber exists.
- the `MediaRecorder` input Surface -- present only while video recording.

`VideoRecorder` no longer opens the camera. It becomes a thin owner of the
`MediaRecorder` lifecycle (prepare/start/pause/resume/stop, orientation hint,
stall watchdog) and ASKS `CameraSession` to add/remove the recorder Surface as
a session output. `PhotoCapturer` stays a separate path for now (see Q4) but is
guarded so it cannot evict the shared session.

Session-rebuild rules (all on `CameraSession`'s single HandlerThread):

- Outputs are computed as the union of: {frame reader surface if subscriberCount
  > 0} and {recorder surface if recording}. When this union changes,
  `CameraSession` tears down `setRepeatingRequest`, closes the old
  `CameraCaptureSession` (NOT the `CameraDevice`), and creates a new
  `CameraCaptureSession` with the new `OutputConfiguration` list, then issues a
  new repeating request.
- Camera device stays OPEN across session rebuilds; only the capture session is
  recreated. This avoids cold-open latency on video start/stop while ReID is
  subscribed.
- TEMPLATE choice: use `TEMPLATE_RECORD` for the repeating request whenever the
  recorder surface is present (preserves current video behavior:
  AE_MODE_ON, AF_OFF + LENS_FOCUS_DISTANCE 0, fixed 30 fps range, EIS off). When
  ReID-only (no recorder), use `TEMPLATE_PREVIEW` targeting only the frame
  reader. The repeating request always targets ALL current outputs (recorder
  surface and/or frame reader surface).
- Orientation: keep `SENSOR_ORIENTATION` (270 on this device) read once at open.
  Video keeps its `setOrientationHint(270)`. ReID frames carry
  `rotationDeg = sensorOrientation` in the `onFrame` callback so the listener
  applies the SAME pre-rotate it does today.
- Stall watchdog: relocate the frame-stall watchdog into `CameraSession` (it
  watches the repeating-request `onCaptureCompleted` heartbeat). On stall while
  recording, it force-stops the recorder (current behavior) but does NOT close
  the camera if ReID is still subscribed -- it rebuilds the session to the
  ReID-only output set.

Why extract a hub vs "add a second target to VideoRecorder": the camera must be
openable for ReID even when NOT recording. If the open/session lived inside
`VideoRecorder`, ReID-only streaming would have to fake a recording or
VideoRecorder would have to grow a "no recorder" mode -- both are uglier than a
dedicated owner. The hub also gives one clear place for the ref-count and the
open/close transitions (Q3). The repo rule (remove redundant code) is satisfied
because the camera-open/session code is MOVED out of VideoRecorder, not
duplicated.

### Q3. Listener ref-counting (">=1 listener keeps camera open" gate)

Decision: `CameraSession` maintains an integer subscriber model with two
distinct kinds of holders:

- Video recording: counts as exactly one holder while a recording is active
  (added in `startVideo`, removed in `stopVideo`/stall/stop).
- ReID frame subscription: one holder per AIDL `subscribeFrames` callback,
  tracked in the existing `RemoteCallbackList<ICaptureCallback>` plus a
  `subscribedFrameCallbacks` set keyed by the callback's `IBinder`.

Open/close transitions:

- First holder (count 0 -> 1): `CameraSession.ensureOpen()` opens the
  `CameraDevice` and configures the session for the current output set.
- Output-set change without crossing zero (e.g. video starts while ReID
  subscribed): rebuild capture session only (Q2), device stays open.
- Last holder removed (count 1 -> 0): `CameraSession.closeAll()` stops
  repeating, closes session, closes device. LED off (Q5).

New AIDL surface (minimal, YAGNI):

- `ICapture.subscribeFrames(in ICaptureCallback cb, int maxWidth, int maxHeight)`
  -- registers `cb` as a frame subscriber, bumps the ref-count, ensures the
  camera is open and the frame reader is in the session. Idempotent per binder.
- `ICapture.unsubscribeFrames(in ICaptureCallback cb)` -- removes the
  subscriber, decrements the ref-count, rebuilds/closes as needed.
- Callback delivery: `ICaptureCallback.onFrame(in byte[] jpeg, int width,
  int height, int rotationDeg, long frameId)` (oneway).

`maxWidth/maxHeight` are advisory; the capture side clamps to the nearest
supported JPEG size (>= request, <= 1280x960 cap to bound binder size). ReID
passes 1280x960.

Binder death: the existing `CaptureService` already uses
`RemoteCallbackList<ICaptureCallback>`, which auto-unregisters dead callbacks
and invokes `onCallbackDied`. We override `onCallbackDied(cb)` to ALSO drop the
frame subscription for that binder and re-evaluate the ref-count. So if the
listener `:backend` dies, its frame hold is released and (if it was the only
holder) the camera closes. On the listener side, `CaptureBridge`'s existing
DeathRecipient + `scheduleBind` re-binds and ReID re-subscribes on reconnect
(see Q-edge-cases).

### Q4. Photo vs frame-stream coexistence

Decision: `takePhoto` is gated, NOT routed through the shared stream, to keep
the change minimal and avoid touching the denoise/low-light still pipeline. The
gate:

- `CaptureService.takePhoto()` already no-ops while recording. Extend the gate:
  takePhoto is also a no-op (returns `onCaptureError(ERR_BUSY)`) while the
  ReID frame subscriber count > 0, OR -- preferred -- it routes through the
  shared session by grabbing the next ReID frame JPEG and writing it as the
  photo when the camera is already owned by the hub. To keep YAGNI and not
  regress still quality, the chosen rule is:
  - If `CameraSession` is OPEN (any holder, including ReID), `takePhoto`
    captures a still from the SHARED session (add a one-shot full-res JPEG
    `ImageReader` target to the current session, fire a single
    `TEMPLATE_STILL_CAPTURE`/`capture()`, then remove the target). This never
    opens a second `CameraDevice`, so it cannot evict the shared session.
  - If `CameraSession` is CLOSED (no holders), `takePhoto` uses the existing
    `PhotoCapturer` warm-pool path unchanged (it opens, shoots, idles closed).
- Crucially, `PhotoCapturer` must NEVER open the camera while `CameraSession`
  holds it. Add a hard guard: `CaptureService` checks `cameraSession.isOpen()`
  before delegating to `PhotoCapturer`. This is the rule that prevents
  re-introducing the exact bug (a second open evicting the shared session).

This keeps the still denoise pipeline intact for the common case (photo with
nothing else running) while guaranteeing no second open during recording/ReID.
The one-shot-target still path reuses the open device, adding only a transient
output to the session.

### Q5. LED + bt-manager session holds

LED ownership rules under shared camera:

- Capture's `LedController` is the SINGLE LED owner for camera activity. The
  rule becomes: solid white LED is lit whenever VIDEO recording is active
  (unchanged). For ReID-only frame streaming (no video), the LED stays OFF --
  matching today's behavior where the listener disabled the camera LED for ReID
  via the `vendor.rkd.camera.led.enable` property.
- Move the LED gate into the capture process: `CameraSession` knows whether a
  recorder surface is present. When the only holder is ReID frames, it suppresses
  the Rokid CAMERA_OPEN auto-event (call
  `LedController.cancelCameraOpenEvent()` on ReID-only open) and keeps the white
  LED off. When recording is active, normal solid-white behavior applies.
- REMOVE the listener-side `setCameraLedEnabled(false/true)` property toggling
  from `startReidWithLed`/`stopReidWithLed`. The listener no longer opens the
  camera, so it has no business setting the camera-LED property. `reidLedDisabled`
  field and `setCameraLedEnabled` calls in the reid path are deleted. (Keep
  `setCameraLedEnabled` only if it is used elsewhere -- grep shows it is used
  for phone-driven AR recording around line 5377/5464; leave those, delete only
  the reid usage. Confirm during implementation; if reid was the only caller,
  delete the function too.)

bt-manager active-session hold ("reid_streaming"): stays on the LISTENER side,
moved to ReidController's subscribe/unsubscribe lifecycle rather than the old
camera-open lifecycle. `ReidController.onActiveSessionEnter` is invoked when it
calls `captureBridge.subscribeReidFrames(...)`; `onActiveSessionExit` when it
calls `unsubscribeReidFrames(...)`. The wiring in `ListenerService` (line ~2472:
`setBtSession("reid_streaming")` / `clearBtSession("reid_streaming")`) is
unchanged -- only what triggers the enter/exit moves from
ReidCameraCapturer.start/stop to the subscribe/unsubscribe calls.

### Q6. Dead code removal

Deleted:

- `app/.../reid/ReidCameraCapturer.kt`: ALL Camera2 code -- `openCamera`,
  `retryCameraOpen`, `createCaptureSession`, the `ImageReader` +
  `OnImageAvailableListener`, `startCameraThread`/`stopCameraThread`,
  `getBestResolution`, `cameraDevice`/`captureSession`/`imageReader` fields,
  `CameraDevice.StateCallback`, the `MAX_RETRY_ATTEMPTS`/`MAX_EXPOSURE_NS`/
  `imageWidth`/`imageHeight`/`sensorRotation` machinery. The
  `onActiveSessionEnter/Exit` hooks move to the subscribe lifecycle.
- Listener-side reid camera-LED property gating: `reidLedDisabled` field and the
  `setCameraLedEnabled(false/true)` calls inside `startReidWithLed`/
  `stopReidWithLed` (and the function itself if reid was its only caller).

Retained / relocated:

- ML Kit detection (`FaceDetectorOptions` ACCURATE + tracking, `getOrCreateDetector`),
  `cropAndCompressJpeg` (100% padding, JPEG q85, base64), `generateThumbnail`
  (THUMBNAIL_HEIGHT=100, q90), pre-rotate-by-sensorOrientation, the
  `DetectedFace` data class, the `Callback` interface
  (`onFacesDetected`/`onNoFaces`/`onStatusChanged`), and the 1500/5000 ms
  cadence -- all relocated into the new frame-consumer class (see step list).

## 4. Implementation task list (ordered, file-by-file)

### Phase A -- capture AIDL surface

A1. `capture/src/main/aidl/.../ICaptureCallback.aidl`: add
`oneway void onFrame(in byte[] jpeg, int width, int height, int rotationDeg,
long frameId);`
(Mark the WHOLE callback interface `oneway`? No -- only `onFrame` needs oneway.
AIDL allows per-method `oneway`.)

A2. `capture/src/main/aidl/.../ICapture.aidl`: add
`void subscribeFrames(in ICaptureCallback cb, int maxWidth, int maxHeight);`
and `void unsubscribeFrames(in ICaptureCallback cb);`

### Phase B -- capture-side shared session

B1. New `capture/.../CameraSession.kt`: owns one `CameraDevice` + one
`CameraCaptureSession` on a dedicated HandlerThread. API:
`ensureOpen()`, `closeAll()`, `addRecorderSurface(surface)`/
`removeRecorderSurface()`, `setReidSubscribed(active: Boolean)`,
`isOpen(): Boolean`, `captureStillToFile(file, onResult)` (one-shot target),
plus an emitter that calls back into `CaptureService` with each JPEG frame.
Implements: union-output computation, session rebuild on output-set change,
TEMPLATE selection, orientation read, stall watchdog (relocated from
VideoRecorder), and the 1200 ms frame-thinning timer for `onFrame` emission.

B2. Refactor `capture/.../VideoRecorder.kt`: remove `openCamera`,
`createCaptureSession`, `CameraDevice`/`CameraCaptureSession` fields, the
session config + repeating request, and the stall watchdog (moved to
CameraSession). Keep `MediaRecorder` prepare/start/pause/resume/stop,
orientation hint, file naming. `start()` now: builds the MediaRecorder, hands
its input Surface to `CameraSession.addRecorderSurface(...)`, then
`rec.start()`. `stop()` calls `rec.stop()` then
`CameraSession.removeRecorderSurface()`. VideoRecorder takes a `CameraSession`
reference (constructor injected from CaptureService).

B3. `capture/.../CaptureService.kt`:
- Construct one `CameraSession` in `onCreate`; pass it to `VideoRecorder`.
- Implement `subscribeFrames`/`unsubscribeFrames` in the `ICapture.Stub`:
  register/unregister the callback, maintain a `subscribedFrameCallbacks`
  set keyed by `cb.asBinder()`, call `cameraSession.setReidSubscribed(...)` to
  bump/drop the ReID holder, and `ensureOpen()`/re-evaluate close.
- Add `onCallbackDied(cb)` override on the `RemoteCallbackList` to drop the
  frame subscription for that binder and re-evaluate the ref-count.
- Wire `CameraSession`'s frame emitter to fan out `onFrame(...)` to all
  subscribed callbacks via a guarded broadcast (reuse the existing `broadcast`
  helper pattern, but only to subscribed binders).
- LED rule (Q5): when the session is ReID-only (no recorder),
  `cancelCameraOpenEvent()` + keep LED off; when recording, solid white as
  today.
- `takePhoto`: if `cameraSession.isOpen()`, route to
  `cameraSession.captureStillToFile(...)`; else delegate to `PhotoCapturer` as
  today. Hard-guard `PhotoCapturer`/`warmUp` so they never open while the
  session is open.

### Phase C -- listener-side frame subscription + ReID consumer

C1. `app/.../capture/CaptureBridge.kt`:
- Add `subscribeReidFrames(maxW, maxH)` / `unsubscribeReidFrames()` forwarding
  to `api?.subscribeFrames(...)` / `unsubscribeFrames(...)`.
- Extend the `Listener` interface with
  `onFrame(jpeg: ByteArray, width: Int, height: Int, rotationDeg: Int,
  frameId: Long) {}` and fan it out from the `ICaptureCallback.Stub.onFrame`
  override to registered listeners.

C2. New `app/.../reid/ReidFrameConsumer.kt` (replaces ReidCameraCapturer's
role): holds the ML Kit detector + `getOrCreateDetector`, `processFrame`
(decode JPEG, pre-rotate by `rotationDeg`), `detectFaces`, `cropAndCompressJpeg`,
`generateThumbnail`, the `DetectedFace` data class, the `Callback` interface,
and the 1500/5000 ms cadence guard. It does NOT touch Camera2. It receives
frames via a method `onFrame(jpeg, w, h, rotationDeg)` invoked by the
CaptureBridge listener fan-out. `onActiveSessionEnter/Exit` are invoked on
subscribe/unsubscribe.

C3. `app/.../reid/ReidController.kt`: replace `ReidCameraCapturer` usage with
`ReidFrameConsumer`. `start(context)` now: register a CaptureBridge listener
that forwards frames to the consumer, call `captureBridge.subscribeReidFrames`,
invoke `onActiveSessionEnter`. `stop()`: `captureBridge.unsubscribeReidFrames`,
unregister the listener, `onActiveSessionExit`. ReidController needs a reference
to `CaptureBridge` (injected from ListenerService). The `cameraCallback` /
detection-result handling is unchanged.

C4. Delete `app/.../reid/ReidCameraCapturer.kt`.

### Phase D -- listener service wiring + LED cleanup

D1. `app/.../service/ListenerService.kt`:
- Inject `captureBridge` into `reidController` (construction ~line 2470).
- `startReidWithLed`/`stopReidWithLed`: remove `reidLedDisabled` +
  `setCameraLedEnabled` reid usage (Q5/Q6). They now just call
  `reidController.start/stop`.
- `flushMemoryForCapture` (~line 3815): the `stopReidWithLed("flushForCap")`
  call is NO LONGER NECESSARY for camera-contention reasons (the shared session
  means video + ReID coexist). CALL-OUT: keep stopping ReID here ONLY if memory
  pressure still warrants it; otherwise remove it so video start does not kill
  ReID. Recommended: REMOVE the reid-stop from flushForCap, since the whole
  point of this change is simultaneous video + ReID. Keep the GC hints.

### Phase E -- build + verify (Section 7).

## 5. Data-flow diagram (text)

```
[Camera HAL device 0]
   | (single open, owned by capture process)
   v
[CameraSession (capture proc)]
   |  repeating request, outputs = union{ JPEG ImageReader 1280x960 (if reid),
   |                                       MediaRecorder Surface (if recording) }
   |
   |-- recorder surface --> [MediaRecorder] --> mp4 file (video path, unchanged)
   |
   |-- JPEG ImageReader --> onImageAvailable --> byte[] jpeg
        | (frame-thinned to <=1 per 1200ms)
        v
   [CaptureService.broadcast onFrame() to subscribed ICaptureCallback binders]
        | AIDL oneway, byte[] ~80-200KB (< binder 1MB)
        v
   [CaptureBridge (listener :backend proc) ICaptureCallback.onFrame]
        | fan-out to registered Listener
        v
   [ReidFrameConsumer.onFrame(jpeg,w,h,rotationDeg)]
        | BitmapFactory.decodeByteArray -> postRotate(rotationDeg)
        | cadence gate (1500ms with-face / 5000ms no-face)
        v
   [ML Kit FaceDetector.process(InputImage)]
        | faces -> cropAndCompressJpeg(q85) + generateThumbnail(q90)
        v
   [ReidController.Callback.onFacesDetected]
        | btSender.sendFace(trackingId, webpBase64) over BT
        v
   [reid backend match -> onReidResult -> verified faces -> UI]
```

## 6. Edge cases

- Binder death of capture process: `CaptureBridge` DeathRecipient fires,
  `scheduleBind` re-binds. On reconnect, ReidController must RE-subscribe frames
  (re-call `subscribeReidFrames`) -- add a re-subscribe hook in
  `onServiceConnected` if ReID `isRunning`. Camera reopens in capture proc on
  first subscriber.
- Binder death of listener process: capture's `RemoteCallbackList.onCallbackDied`
  drops the ReID frame hold; if no video recording, the camera closes (count
  -> 0). If recording, camera stays open with recorder as the sole holder.
- Video start while ReID subscribed: `CameraSession` adds the recorder surface,
  rebuilds the capture session (device stays open), switches repeating request
  to TEMPLATE_RECORD, lights solid-white LED. ReID frames keep flowing from the
  same session.
- Video stop while ReID subscribed: remove recorder surface, rebuild session to
  ReID-only (TEMPLATE_PREVIEW + frame reader), LED off. ReID uninterrupted.
- ReID start while recording: `setReidSubscribed(true)` adds the frame reader,
  rebuilds session keeping recorder; both outputs live. NO second open, NO
  eviction.
- ReID stop while recording: remove frame reader, rebuild to recorder-only.
  Recording uninterrupted.
- Zero-listener close: count 1 -> 0 closes session + device, LED off,
  cancelCameraOpenEvent.
- Camera open failure / retry: `CameraSession.ensureOpen` handles open
  error/disconnect with a bounded retry (relocate the bounded backoff idea from
  ReidCameraCapturer.retryCameraOpen, but ONCE in the hub). On permanent
  failure it reports `onCaptureError(ERR_CAMERA)` to subscribers and clears
  holders.
- Stall while recording + ReID subscribed: watchdog force-stops the recorder,
  rebuilds session to ReID-only instead of closing the camera.
- takePhoto while ReID/recording: routed through shared session one-shot target;
  if session closed, warm-pool PhotoCapturer path. Never a second device open.
- Process restart (capture killed mid-record): existing
  `onCaptureKilledDuringRecording` path still fires; on rebind ReID re-subscribes.

## 7. Verification plan

Build + deploy (NEVER `adb install`):

```bash
bash /media/varingait/Lobotomite/Repository/AI/clients/glasses/scripts/deploy-to-glasses.sh
```

Wait for boot, then verify `getprop sys.boot_completed` == 1 before testing.

Tests over USB (glasses serial 1901092544026001), watching logcat:

1. ReID alone (no video):
   `adb -s 1901092544026001 shell am broadcast -a com.repository.glasses.listener.REID_START -p com.repository.glasses.listener`
   - Confirm in logcat: capture opens camera ONCE; NO listener-side Camera2
     open; NO "evicted"/eviction lines on camera 0; NO "Internal error has
     occurred when executing ML Kit tasks"; NO SIGABRT / `:backend` native
     crash; `onFrame` flowing; ML Kit emits faces; status "SCANNING".
   - Hold a face in view; confirm `REID_SEND` lines and verified faces.

2. Simultaneous video + ReID (the bug scenario):
   - Start always-on video (FN long-press or
     `am broadcast`/`am startservice` ADB_START_VIDEO), then REID_START.
   - Confirm a SINGLE camera-device open across both processes, session REBUILD
     (not reopen) on video start, both the recorder mp4 grows AND ReID faces
     are detected, NO eviction, NO SIGABRT.
   - Stop video; confirm ReID keeps scanning (session rebuilt to ReID-only),
     LED goes off.

3. REID_STOP then confirm camera closes if no recording; verify LED off.

4. Stress: start/stop video repeatedly while ReID runs; confirm no eviction and
   stable mp4 + face flow.

Pull the persistent log if needed:
`bash AI/clients/phone/test/adb/pull_glasses_log.sh`.

## 8. Dead-code-removed checklist

- [ ] `app/.../reid/ReidCameraCapturer.kt` file DELETED.
- [ ] No remaining Camera2 imports (`CameraManager`, `CameraDevice`,
      `CameraCaptureSession`, `ImageReader`, `CaptureRequest`) anywhere under
      `app/.../reid/`.
- [ ] `VideoRecorder.kt` no longer references `CameraManager`/`CameraDevice`/
      `CameraCaptureSession`/`openCamera`/`createCaptureSession` (all moved to
      `CameraSession`).
- [ ] Stall watchdog exists in exactly one place (`CameraSession`), removed from
      `VideoRecorder`.
- [ ] Listener `reidLedDisabled` field + reid-path `setCameraLedEnabled` calls
      removed; `setCameraLedEnabled` deleted if reid was its only caller.
- [ ] `flushMemoryForCapture` reid-stop removed (or explicitly justified for
      memory only).
- [ ] No "unified"/"consolidated" naming; no emojis in any new code/logs.
- [ ] No backwards-compat shim for the old direct-open ReID path.
```
