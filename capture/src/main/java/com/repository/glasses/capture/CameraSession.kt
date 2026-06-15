package com.repository.glasses.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.repository.glasses.tracing.GT
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * THE single owner of Camera2 device 0 for the capture process.
 *
 * Caller-agnostic: it knows nothing about ReID, video, or photos as features --
 * only about camera *holders* and *requests*. Every `CameraManager.openCamera`
 * call in the whole capture process happens here, on ONE dedicated
 * HandlerThread. Because open/close and every output-set change are serialized
 * onto that single thread, two camera opens can never race -- the no-double-open
 * guarantee is structural, not a runtime gate.
 *
 * Holders (camera stays open while >= 1 holder exists):
 *  - recorder surface: an active video recording (`setRecorderSurface`/
 *    `clearRecorderSurface`).
 *
 * Requests (transient, serviced one at a time on the same thread):
 *  - still capture (`requestStill`): a one-shot JPEG via a transient JPEG
 *    ImageReader (used by the func-button photo path). Queued behind any
 *    in-flight work, so it can never open a second device concurrently with
 *    the recorder.
 *  - exclusive device borrow (`borrowDeviceExclusive`): RAW one-shots (the
 *    func-button photo and the ReID still) that need their own session config.
 */
@SuppressLint("MissingPermission")
class CameraSession(private val context: Context) {

    companion object {
        private const val TAG = "Cap:CamSession"
        private const val FRAME_RATE = 30
        // Bound the streamed JPEG so a frame fits comfortably under the binder
        // transaction limit (~1MB). 1280x960 JPEG is ~80-200KB.
        private const val FRAME_MAX_W = 1280
        private const val FRAME_MAX_H = 960
        // Live-shot (snapshot-while-recording) JPEG size. The reader is part of
        // the record session's output set but is NOT a target of the
        // TEMPLATE_RECORD repeating request -- it sits idle until a snapshot is
        // requested via a one-shot session.capture() with
        // CONTROL_CAPTURE_INTENT = VIDEO_SNAPSHOT. This is the HAL's blessed
        // "video live shot" path (selects a *LiveSnapshot* / PreviewVideo
        // usecase, NOT the unsustainable RealTimeFeatureZSLPreviewRawYUV that a
        // continuously-streamed YUV second target selected). 1280x720 BLOB is on
        // the advertised JPEG size ladder and well within the procStalling
        // budget (recorder PRIV non-stalling + 1 JPEG stalling = 1/2). Used by
        // both the func-button photo and ReID while a recording is active.
        private const val SNAPSHOT_W = 1280
        private const val SNAPSHOT_H = 720
        // Force-stop recording if the repeating request produces no frame in
        // this window (mirrors the old VideoRecorder stall watchdog).
        private const val FRAME_STALL_MS = 2000L
        private const val STALL_CHECK_MS = 1000L
        private const val OPEN_TIMEOUT_S = 3L
        private const val SESSION_TIMEOUT_S = 3L
        private const val OP_TIMEOUT_S = OPEN_TIMEOUT_S + SESSION_TIMEOUT_S + 2
        // Log AE diagnostics (state/exposure/ISO) for the stream every Nth frame
        // so the on-device log shows whether AE converges and what exposure it
        // lands on, without spamming a line per frame.
        private const val AE_LOG_EVERY_N = 30L
    }

    /** Sink for fatal camera errors. Implemented by CaptureService. */
    interface FrameEmitter {
        fun onCameraError(msg: String)
    }

    /** Notified when the live-stream output set transitions presence, so the
     *  owner of LED/state can react (e.g. a recording starts/stops). */
    interface StateListener {
        fun onRecordingOutputChanged(active: Boolean)
    }

    var emitter: FrameEmitter? = null
    var stateListener: StateListener? = null

    private val handlerThread = HandlerThread("CamSession").apply { start() }
    private val handler = Handler(handlerThread.looper)
    // camera2 framework session-state callbacks.
    private val cbExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "CamSession-cb") }
    // USER-supplied callbacks (emitter.onFrame/onCameraError, stateListener,
    // requestStill onJpeg/onError, onStall). Dispatched here -- NEVER on the
    // camera `handler` thread -- so a callback body that synchronously calls
    // back into a blocking CameraSession method (which posts to `handler` and
    // awaits a latch) cannot self-deadlock against the busy handler. Kept
    // separate from cbExecutor so framework callbacks and user callbacks do not
    // head-of-line-block each other.
    private val userCbExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "CamSession-usercb") }

    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var frameReader: ImageReader? = null

    // Live-shot reader: a JPEG (BLOB) ImageReader configured into the
    // TEMPLATE_RECORD session as a SECOND output alongside the MediaRecorder
    // surface, so callers can pull a video-grade still WHILE recording (the RAW
    // borrow path is unavailable then). It is NOT a target of the repeating
    // request -- it stays idle until captureVideoSnapshot() fires a single
    // high-priority one-shot session.capture() with
    // CONTROL_CAPTURE_INTENT = VIDEO_SNAPSHOT against it. The HAL encodes the
    // JPEG itself (no demosaic on our side). Created when the recorder surface
    // is added, closed when it is cleared. While recording this is the ONLY
    // frame source for both the func-button photo and ReID (the reid frameReader
    // is NOT also added -- the record session stays at exactly two outputs:
    // recorder + live-shot JPEG).
    @Volatile private var snapshotReader: ImageReader? = null
    private var snapshotSize = Size(SNAPSHOT_W, SNAPSHOT_H)
    // One live-shot in flight at a time. A new request while one is pending is
    // rejected with onError (the ReID driver tolerates retry-next-tick).
    @Volatile private var liveShotInFlight = false
    // Delivery callbacks for the pending live-shot, set on the handler thread.
    private var liveShotOnJpeg: ((ByteArray, Int, Int, Int) -> Unit)? = null
    private var liveShotOnError: ((Throwable) -> Unit)? = null

    // Holders.
    @Volatile private var recorderSurface: Surface? = null

    // Surfaces currently configured into the live session (for change detection).
    private var configuredSurfaces: List<Surface> = emptyList()

    private var cameraId: String = "0"
    private var sensorOrientation = 0
    private var frameSize = Size(FRAME_MAX_W, FRAME_MAX_H)
    // AE target-fps ranges advertised by the device, captured at open. Used to
    // pick a low-min range for the stream/preview path so AE can lengthen
    // exposure in dim light. Null/empty => omit the lock entirely.
    private var availableFpsRanges: Array<Range<Int>>? = null

    @Volatile private var lastCaptureFrameMs = 0L
    // Cheap AE diagnostics for the stream's repeating request. Counts completed
    // frames so the per-frame CaptureCallback can log AE state / exposure / ISO
    // periodically (every AE_LOG_EVERY_N frames) instead of every frame. Reset
    // whenever a new repeating request starts. All reads/writes are off the hot
    // path's allocation -- just an int increment + an occasional Log.i.
    private val aeFrameCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private var stallWatchdog: Runnable? = null
    private var onStall: (() -> Unit)? = null

    // Pending one-shot still request, satisfied from the next stream frame.
    private var pendingStill: ((ByteArray, Int, Int, Int) -> Unit)? = null

    fun isOpen(): Boolean = device != null

    private fun holderCount(): Int = if (recorderSurface != null) 1 else 0

    // ---- Recorder surface (video) ----

    /** Add the MediaRecorder input Surface and start feeding it. Blocks until the
     *  session is configured so the caller can safely start MediaRecorder. */
    fun setRecorderSurface(surface: Surface, onStall: () -> Unit): Boolean {
        var ok = false
        runOnHandlerBlocking("setRecorderSurface") {
            this.onStall = onStall
            recorderSurface = surface
            openSnapshotReader()
            Log.i(TAG, "setRecorderSurface holders=${holderCount()}")
            reconfigure()
            ok = session != null && configuredSurfaces.contains(surface)
        }
        if (ok) {
            val sl = stateListener
            if (sl != null) userCbExecutor.execute { sl.onRecordingOutputChanged(true) }
        }
        return ok
    }

    /** Remove the MediaRecorder Surface. Rebuilds to the remaining output set or
     *  closes the camera if no holders remain.
     *
     *  Returns true iff the recorder surface was actually detached and the
     *  session reconfigured/closed ON the handler thread (i.e. the posted op
     *  ran to completion). Returns false if the op timed out in
     *  runOnHandlerBlocking before finishing -- in that case the recorder
     *  surface may STILL be attached, so the caller must NOT assume frames have
     *  stopped reaching the encoder. The success flag is set at the very END of
     *  the on-handler body, so a true return guarantees the detach + reconfigure
     *  completed before this method returned. */
    fun clearRecorderSurface(): Boolean {
        var detached = false
        var changed = false
        runOnHandlerBlocking("clearRecorderSurface") {
            if (recorderSurface == null) {
                // Already cleared: nothing attached, so the post-condition
                // (surface detached) holds.
                detached = true
                return@runOnHandlerBlocking
            }
            recorderSurface = null
            onStall = null
            cancelStallWatchdog()
            closeSnapshotReader()
            changed = true
            Log.i(TAG, "clearRecorderSurface holders=${holderCount()}")
            if (holderCount() == 0) closeInternal() else reconfigure()
            // Set LAST: a true return means the full detach + reconfigure ran.
            detached = true
        }
        if (changed) {
            val sl = stateListener
            if (sl != null) userCbExecutor.execute { sl.onRecordingOutputChanged(false) }
        }
        return detached
    }

    /** True while a recorder surface is part of the live session. */
    fun isRecordingOutputActive(): Boolean = recorderSurface != null

    // ---- Still capture (queued one-shot) ----

    /**
     * Request a single JPEG still. Serviced on the owner's single thread, so it
     * is queued behind any in-flight open/reconfigure and can never open a
     * second camera concurrently.
     *
     * If the camera is already open (stream/recording), the still is the next
     * stream frame. If the camera is closed, the owner opens a transient
     * stream, grabs one frame, then closes (when no other holders exist).
     */
    fun requestStill(onJpeg: (ByteArray, Int, Int, Int) -> Unit, onError: (Throwable) -> Unit) {
        handler.post {
            try {
                if (pendingStill != null) {
                    val err = IllegalStateException("still already in progress")
                    userCbExecutor.execute { onError(err) }
                    return@post
                }
                pendingStill = onJpeg
                if (holderCount() == 0) {
                    // Open a transient stream just for this still; closed when the
                    // frame is delivered (see deliverFrame).
                    transientStillOpen = true
                }
                reconfigure()
            } catch (e: Throwable) {
                pendingStill = null
                transientStillOpen = false
                userCbExecutor.execute { onError(e) }
            }
        }
    }

    @Volatile private var transientStillOpen = false

    // ---- Exclusive device borrow (RAW / incompatible-format one-shots) ----

    /**
     * Borrow the open CameraDevice for an exclusive one-shot capture that needs
     * its OWN session config (e.g. RAW_SENSOR, which cannot share the JPEG
     * stream session). Runs on the owner's single thread: the current stream
     * session is torn down, the device is ensured open, [body] builds + runs +
     * closes ITS OWN session synchronously on the device, then the prior stream
     * session is rebuilt (or the device closed if no holders remain).
     *
     * Because this executes on the same serialized thread as every other open,
     * no second CameraDevice is ever opened concurrently -- the no-double-open
     * guarantee holds for RAW captures too.
     *
     * Not supported while a video recording is active (the recorder session
     * cannot be torn down mid-record): returns false without running [body].
     * [body] must NOT close the CameraDevice; it owns only its own session.
     */
    fun borrowDeviceExclusive(timeoutMs: Long, body: (CameraDevice) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        handler.post {
            try {
                if (recorderSurface != null) {
                    Log.w(TAG, "borrowDeviceExclusive refused: recording active")
                    return@post
                }
                // Tear down the shared stream session + reader so the borrower
                // has exclusive control of the device's outputs.
                try { session?.stopRepeating() } catch (_: Exception) {}
                try { session?.close() } catch (_: Exception) {}
                try { frameReader?.close() } catch (_: Exception) {}
                session = null
                frameReader = null
                configuredSurfaces = emptyList()

                ensureDeviceOpen()
                val cam = device ?: throw IllegalStateException("device null for borrow")
                body(cam)
                ok[0] = true
            } catch (e: Throwable) {
                Log.e(TAG, "borrowDeviceExclusive failed: ${e.message}")
                val em = emitter
                val msg = "borrowDeviceExclusive: ${e.message}"
                if (em != null) userCbExecutor.execute { em.onCameraError(msg) }
            } finally {
                // Restore the shared stream for remaining holders, or close.
                try {
                    if (holderCount() == 0 && pendingStill == null) closeInternal()
                    else reconfigure()
                } catch (e: Throwable) {
                    Log.e(TAG, "borrow restore failed: ${e.message}")
                }
                latch.countDown()
            }
        }
        val completed = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) { false }
        if (!completed) {
            // The body is still running on `handler`. We do NOT spin up a second
            // thread or open a second device -- everything (this borrow body and
            // the next caller's op) is serialized on the single `handler`
            // thread, so the next op simply queues BEHIND the still-running body
            // and no double-open can occur. The only hazard is the CALLER
            // proceeding as if it failed; callers (RawStillCapturer/
            // LowLightCapturer) must size timeoutMs to exceed their worst-case
            // body time. ok[0] stays false until the body completes normally.
            Log.w(TAG, "borrow body exceeded ${timeoutMs}ms -- still serialized on handler; caller should increase timeout")
        }
        return ok[0]
    }

    // ---- Internal: device open + session (re)configuration ----

    private inline fun runOnHandlerBlocking(tag: String, crossinline body: () -> Unit) {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                body()
            } catch (e: Throwable) {
                Log.e(TAG, "$tag failed: ${e.message}")
                val em = emitter
                val msg = "$tag: ${e.message}"
                if (em != null) userCbExecutor.execute { em.onCameraError(msg) }
            } finally {
                latch.countDown()
            }
        }
        try { latch.await(OP_TIMEOUT_S, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    private fun ensureDeviceOpen() {
        if (device != null) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = manager.cameraIdList.firstOrNull()
            ?: throw IllegalStateException("no camera")
        val chars = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        frameSize = chooseFrameSize(chars)
        availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        Log.i(TAG, "aeFpsRanges=${availableFpsRanges?.joinToString { "[${it.lower},${it.upper}]" } ?: "null"}")
        Log.i(TAG, "openCamera id=$cameraId sensor=$sensorOrientation frame=${frameSize.width}x${frameSize.height}")

        val latch = CountDownLatch(1)
        val opened = arrayOfNulls<CameraDevice>(1)
        val err = arrayOfNulls<Throwable>(1)
        manager.openCamera(cameraId, cbExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { opened[0] = d; latch.countDown() }
            override fun onDisconnected(d: CameraDevice) {
                err[0] = IllegalStateException("camera disconnected"); d.close(); latch.countDown()
            }
            override fun onError(d: CameraDevice, error: Int) {
                err[0] = IllegalStateException("camera open error $error"); d.close(); latch.countDown()
            }
        })
        if (!latch.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)) throw IllegalStateException("camera open timeout")
        err[0]?.let { throw it }
        device = opened[0] ?: throw IllegalStateException("camera null after open")
    }

    /** True when the JPEG reader must exist: a pending still that needs a frame
     *  to grab (the func-button photo path). */
    private fun needFrameReader(): Boolean = pendingStill != null

    /** Recompute the output set and rebuild the capture session if it changed.
     *  Opens the device first if needed. Runs on the handler thread. */
    private fun reconfigure() {
        ensureDeviceOpen()
        val cam = device ?: return

        if (needFrameReader() && frameReader == null) {
            frameReader = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader -> onFrameAvailable(reader) }, handler)
            }
        } else if (!needFrameReader() && frameReader != null) {
            frameReader?.close()
            frameReader = null
        }

        // Output set (what is CONFIGURED into the session):
        //  - RECORDING: recorder surface + live-shot JPEG reader (exactly two).
        //    Both are configured so the JPEG reader can receive a one-shot
        //    capture, but only the recorder is added to the repeating request
        //    (see startRepeating). The reid frameReader is NOT added while
        //    recording; ReID fires a live-shot one-shot instead, so the record
        //    session never exceeds two outputs.
        //  - NOT RECORDING: the func-button-photo frameReader (when a still is
        //    pending), as before.
        val outputs = mutableListOf<Surface>()
        recorderSurface?.let { outputs.add(it) }
        if (recorderSurface != null) {
            snapshotReader?.surface?.let { outputs.add(it) }
        } else {
            frameReader?.surface?.let { outputs.add(it) }
        }

        if (outputs.isEmpty()) return
        if (outputs == configuredSurfaces && session != null) return

        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null

        val sessLatch = CountDownLatch(1)
        val created = arrayOfNulls<CameraCaptureSession>(1)
        val sessErr = arrayOfNulls<Throwable>(1)
        val stateCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { created[0] = s; sessLatch.countDown() }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                sessErr[0] = IllegalStateException("session configure failed"); sessLatch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs.map { OutputConfiguration(it) },
                    cbExecutor,
                    stateCb
                )
            )
        } else {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(outputs, stateCb, handler)
        }
        if (!sessLatch.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS)) throw IllegalStateException("session configure timeout")
        sessErr[0]?.let { throw it }
        session = created[0]
        configuredSurfaces = outputs.toList()

        startRepeating(outputs)
    }

    private fun startRepeating(outputs: List<Surface>) {
        val cam = device ?: return
        val s = session ?: return
        val recording = recorderSurface != null
        // Template per output type: RECORD when a MediaRecorder surface is
        // present (constant-rate video), PREVIEW for the JPEG ImageReader stream.
        // TEMPLATE_RECORD with ONLY a JPEG ImageReader target stops delivering
        // frames on this Rokid HAL (verified on-device: zero onImageAvailable /
        // onCaptureCompleted), so the stream MUST use TEMPLATE_PREVIEW.
        val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        // Repeating targets: while RECORDING, ONLY the recorder surface. The
        // live-shot JPEG reader is configured into the session but must NOT be a
        // repeating target -- a continuously-streamed second readout is exactly
        // what selected the unsustainable RealTimeFeatureZSLPreviewRawYUV usecase
        // and storms the HAL with buffer errors. It receives a buffer only on the
        // on-demand one-shot capture in captureVideoSnapshot(). When NOT
        // recording, every output (the func-button JPEG reader) is a target.
        val repeatingTargets = if (recording) {
            outputs.filter { it === recorderSurface }
        } else {
            outputs
        }
        val builder = cam.createCaptureRequest(template).apply {
            for (out in repeatingTargets) addTarget(out)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // Match the func-button photo path (PhotoCapturer warmup): let AWB
            // run automatically so colour/exposure converge like a normal photo.
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            // Fixed-focus AR camera: continuous AF wastes HAL CPU on a lens that
            // does not move. Lock to infinity (matches the old VideoRecorder).
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            if (recording) {
                // RECORDING: lock to a constant 30fps so video stays
                // constant-rate. A fixed (30,30) range pins frame duration to
                // ~33ms; that is the correct behaviour for a recorder surface
                // and must NOT regress.
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FRAME_RATE, FRAME_RATE))
            } else {
                // STREAM (TEMPLATE_RECORD, the ReID 1280x960 JPEG frame path):
                // do NOT lock to (30,30). Locking the min to 30
                // caps frame duration at ~33ms, so in dim indoor light AE
                // cannot lengthen exposure and frames come out almost black.
                // Pick a range with a LOW lower bound (upper 30) so AE is free
                // to extend exposure, matching how the func-button photo lets
                // AE choose. If no suitable range is advertised, omit the lock
                // entirely and let the HAL/AE fully control exposure.
                val streamRange = pickStreamFpsRange()
                if (streamRange != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, streamRange)
                    Log.i(TAG, "stream AE fps range=[${streamRange.lower},${streamRange.upper}]")
                } else {
                    Log.i(TAG, "stream AE fps range: omitted (AE free to expose)")
                }
            }
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
        lastCaptureFrameMs = SystemClock.elapsedRealtime()
        aeFrameCounter.set(0L)
        val frameCb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s2: CameraCaptureSession,
                req: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult
            ) {
                lastCaptureFrameMs = SystemClock.elapsedRealtime()
                // AE diagnostics: periodically log what AE is doing so the next
                // on-device run shows whether AE converges and the exposure/ISO
                // it lands on. Mirrors RawStillCapturer's warmup logging (AE
                // state + SENSOR_EXPOSURE_TIME + SENSOR_SENSITIVITY) so the
                // stream and the known-good photo path report the same fields.
                // Kept cheap + never-throwing: an atomic increment plus, every
                // Nth frame, a few result.get() reads and one Log.i.
                val n = aeFrameCounter.incrementAndGet()
                if (n % AE_LOG_EVERY_N == 1L) {
                    try {
                        val aeState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                        val expNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                        val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                        val fps = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                        val expMs = expNs?.let { it / 1_000_000.0 }
                        Log.i(
                            TAG,
                            "stream AE frame=$n aeState=$aeState exp=${expMs}ms iso=$iso " +
                                "fps=${fps?.let { "[${it.lower},${it.upper}]" } ?: "n/a"}"
                        )
                    } catch (e: Throwable) {
                        // Diagnostics must never disturb the camera handler thread.
                        Log.w(TAG, "stream AE log failed: ${e.message}")
                    }
                }
            }
        }
        s.setRepeatingRequest(builder.build(), frameCb, handler)
        Log.i(TAG, "repeating started template=${if (recording) "RECORD" else "PREVIEW"} recording=$recording outputs=${outputs.size}")

        // Kick AE into metering. A bare AE_MODE_ON repeating request leaves AE at
        // the sensor's near-zero default exposure on this Rokid HAL until a
        // precapture trigger fires -> black frames. The func-button photo path
        // (RawStillCapturer warmup) fires CONTROL_AE_PRECAPTURE_TRIGGER_START and
        // waits for CONTROL_AE_STATE_CONVERGED for exactly this reason. For the
        // continuous stream we fire the trigger ONCE as a one-shot capture; the
        // repeating request then keeps AE metering every frame so exposure
        // converges across the next frames (no blocking wait on the camera
        // thread). Recording uses constant-rate AE and does not need this.
        if (!recording) {
            try {
                val trig = cam.createCaptureRequest(template).apply {
                    for (out in outputs) addTarget(out)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                    )
                }
                s.capture(trig.build(), frameCb, handler)
                Log.i(TAG, "stream AE precapture trigger fired")
            } catch (e: Throwable) {
                Log.w(TAG, "stream AE precapture trigger failed: ${e.message}")
            }
        }

        if (recording) startStallWatchdog() else cancelStallWatchdog()
    }

    /**
     * Choose an AE target-fps range for the stream/preview repeating request.
     * Prefer a range whose upper bound is the stream's nominal 30fps and whose
     * LOWER bound is small (<= 15), so AE may stretch frame duration in dim
     * light. Among candidates, prefer the smallest lower bound (the most
     * exposure headroom). Returns null when nothing suitable is advertised --
     * the caller then omits the lock so the HAL/AE fully controls exposure.
     */
    private fun pickStreamFpsRange(): Range<Int>? {
        val ranges = availableFpsRanges ?: return null
        if (ranges.isEmpty()) return null
        return ranges
            .filter { it.upper == FRAME_RATE && it.lower < FRAME_RATE && it.lower <= 15 }
            .minByOrNull { it.lower }
    }

    private fun onFrameAvailable(reader: ImageReader) {
        // The JPEG reader only exists to satisfy a pending still (func-button
        // photo). Drop any frame that arrives with no still outstanding.
        if (pendingStill == null) {
            reader.acquireLatestImage()?.close()
            return
        }
        val image = reader.acquireLatestImage() ?: return
        val data: ByteArray
        val w: Int
        val h: Int
        try {
            val buffer = image.planes[0].buffer
            data = ByteArray(buffer.remaining())
            buffer.get(data)
            w = image.width
            h = image.height
        } catch (e: Throwable) {
            Log.w(TAG, "onFrameAvailable read failed: ${e.message}")
            image.close()
            return
        }
        image.close()

        // Satisfy a queued still from this frame, then (if it was a transient
        // open with no other holders) close the camera.
        // `data` is a fresh copy taken from the Image BEFORE image.close() above,
        // so it is safe to hand to userCbExecutor without holding the Image.
        val rot = sensorOrientation
        val still = pendingStill
        if (still != null) {
            pendingStill = null
            // Dispatch the user onJpeg OFF the handler thread so a callback body
            // that re-enters a blocking CameraSession method cannot deadlock.
            userCbExecutor.execute {
                try { still(data, w, h, rot) } catch (e: Throwable) {
                    Log.w(TAG, "still callback threw: ${e.message}")
                }
            }
            if (transientStillOpen) {
                transientStillOpen = false
                if (holderCount() == 0) closeInternal() else reconfigure()
            }
        }
    }

    /** Create the live-shot JPEG (BLOB) reader for the recording session. It is
     *  configured into the record session as a second output but is NEVER a
     *  target of the repeating request; captureVideoSnapshot fires a one-shot
     *  capture against it. This is the HAL-blessed video live-shot path and does
     *  NOT select the ZSL-RAW-YUV usecase a streamed YUV second target did. Runs
     *  on the handler thread (from setRecorderSurface). Idempotent. */
    private fun openSnapshotReader() {
        if (snapshotReader != null) return
        snapshotSize = chooseSnapshotSize()
        snapshotReader = ImageReader.newInstance(
            snapshotSize.width, snapshotSize.height, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ r -> onLiveShotAvailable(r) }, handler)
        }
        Log.i(TAG, "openSnapshotReader JPEG ${snapshotSize.width}x${snapshotSize.height}")
    }

    /** Close the live-shot reader and fail any pending request. Handler thread. */
    private fun closeSnapshotReader() {
        try { snapshotReader?.close() } catch (_: Exception) {}
        snapshotReader = null
        val err = liveShotOnError
        liveShotOnJpeg = null
        liveShotOnError = null
        liveShotInFlight = false
        if (err != null) userCbExecutor.execute {
            try { err(IllegalStateException("live-shot reader closed")) } catch (_: Throwable) {}
        }
    }

    /** A one-shot live-shot JPEG landed: read the already-encoded bytes and
     *  deliver them to the pending request's callback. Runs on the handler
     *  thread. The reader produces a buffer ONLY in response to the one-shot
     *  capture fired by captureVideoSnapshot, so any frame here belongs to the
     *  in-flight request. */
    private fun onLiveShotAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage()
        if (image == null) return
        val onJpeg = liveShotOnJpeg
        if (onJpeg == null) {
            // No request outstanding (e.g. raced a close): drop the buffer.
            image.close()
            return
        }
        val data: ByteArray
        val w: Int
        val h: Int
        try {
            // BLOB: planes[0] is the complete, already-encoded JPEG stream.
            val buffer = image.planes[0].buffer
            data = ByteArray(buffer.remaining())
            buffer.get(data)
            w = image.width
            h = image.height
        } catch (e: Throwable) {
            Log.w(TAG, "onLiveShotAvailable read failed: ${e.message}")
            image.close()
            val onError = liveShotOnError
            liveShotOnJpeg = null
            liveShotOnError = null
            liveShotInFlight = false
            if (onError != null) userCbExecutor.execute {
                try { onError(IllegalStateException("live-shot read failed: ${e.message}")) } catch (_: Throwable) {}
            }
            return
        }
        image.close()
        // Deliver sensorOrientation as rotationDeg (the HAL JPEG is
        // sensor-oriented); consumers rotate, matching the old contract.
        val rot = sensorOrientation
        liveShotOnJpeg = null
        liveShotOnError = null
        liveShotInFlight = false
        userCbExecutor.execute {
            try { onJpeg(data, w, h, rot) } catch (e: Throwable) {
                Log.w(TAG, "live-shot callback threw: ${e.message}")
            }
        }
    }

    /**
     * Fire a single high-priority video live-shot JPEG WHILE RECORDING and
     * deliver the encoded bytes (recording-only). The live-shot JPEG reader is
     * already configured into the live record session (idle); this builds a
     * one-shot request from TEMPLATE_STILL_CAPTURE targeting ONLY the JPEG
     * reader, sets CONTROL_CAPTURE_INTENT = VIDEO_SNAPSHOT (steers CamX onto the
     * live-snapshot pipeline, not ZSL-RAW-YUV), keeps AE/AWB auto, and fires it
     * once against the SAME live session (no reconfigure). The recorder repeating
     * request keeps running on the single PRIV stream throughout. [onJpeg]
     * receives (jpegBytes, width, height, rotationDeg) where rotationDeg =
     * sensorOrientation; the caller rotates (matching the old contract).
     *
     * One live-shot in flight at a time: a request arriving while one is pending
     * fails fast with [onError] (the ReID driver tolerates retry-next-tick).
     * Only valid while a recorder surface is active; otherwise [onError] fires
     * and callers must use the RAW path instead.
     */
    fun captureVideoSnapshot(
        onJpeg: (ByteArray, Int, Int, Int) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        handler.post {
            val s = session
            val reader = snapshotReader
            val cam = device
            if (recorderSurface == null || reader == null || s == null || cam == null) {
                userCbExecutor.execute {
                    onError(IllegalStateException("captureVideoSnapshot: not recording"))
                }
                return@post
            }
            if (liveShotInFlight) {
                userCbExecutor.execute {
                    onError(IllegalStateException("captureVideoSnapshot: live-shot already in flight"))
                }
                return@post
            }
            try {
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(
                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                        CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT
                    )
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }
                liveShotOnJpeg = onJpeg
                liveShotOnError = onError
                liveShotInFlight = true
                s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        s2: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        Log.w(TAG, "live-shot capture failed reason=${failure.reason}")
                        val cb = liveShotOnError
                        liveShotOnJpeg = null
                        liveShotOnError = null
                        liveShotInFlight = false
                        if (cb != null) userCbExecutor.execute {
                            try { cb(IllegalStateException("live-shot capture failed reason=${failure.reason}")) } catch (_: Throwable) {}
                        }
                    }
                }, handler)
                Log.i(TAG, "live-shot one-shot fired ${snapshotSize.width}x${snapshotSize.height}")
            } catch (e: Throwable) {
                liveShotOnJpeg = null
                liveShotOnError = null
                liveShotInFlight = false
                Log.w(TAG, "captureVideoSnapshot failed: ${e.message}")
                userCbExecutor.execute {
                    onError(IllegalStateException("captureVideoSnapshot failed: ${e.message}"))
                }
            }
        }
    }

    private fun chooseSnapshotSize(): Size {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes.isNullOrEmpty()) Size(SNAPSHOT_W, SNAPSHOT_H)
            else {
                // Prefer the known-good 1280x720 if the HAL advertises it for
                // JPEG. Otherwise fall back to the largest advertised JPEG size
                // not exceeding the cap (keeps the live-shot fast + small).
                val exact = sizes.firstOrNull { it.width == SNAPSHOT_W && it.height == SNAPSHOT_H }
                if (exact != null) exact
                else {
                    val cap = SNAPSHOT_W * SNAPSHOT_H
                    sizes.filter { it.width * it.height <= cap }
                        .maxByOrNull { it.width * it.height }
                        ?: sizes.minByOrNull { it.width * it.height }
                        ?: Size(SNAPSHOT_W, SNAPSHOT_H)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "chooseSnapshotSize failed: ${e.message}")
            Size(SNAPSHOT_W, SNAPSHOT_H)
        }
    }

    private fun startStallWatchdog() {
        cancelStallWatchdog()
        val w = object : Runnable {
            override fun run() {
                // Recording ended (surface cleared): stop watching, do not
                // reschedule. This is the ONLY legitimate reason to stop.
                if (recorderSurface == null) return
                try {
                    val gap = SystemClock.elapsedRealtime() - lastCaptureFrameMs
                    if (gap > FRAME_STALL_MS) {
                        Log.e(TAG, "frameStall: no frames for ${gap}ms while recording - signalling stop")
                        GT.counter("cap.session.stall", 1)
                        // Dispatch onStall OFF the handler thread. This is what
                        // un-blocks the stall->stop path: VideoRecorder.stop()'s
                        // clearRecorderSurface() posts to THIS handler, and the
                        // handler is no longer pinned running this watchdog
                        // callback, so the clear can actually be serviced and
                        // the recorder surface detached before recorder.stop().
                        val cb = onStall
                        if (cb != null) userCbExecutor.execute {
                            try { cb() } catch (e: Throwable) {
                                Log.e(TAG, "onStall threw: ${e.message}")
                            }
                        }
                        // The stop path will clear recorderSurface + cancel this
                        // watchdog. Do not reschedule from here.
                        return
                    }
                } catch (e: Throwable) {
                    // Never die silently on a transient error -- log and keep
                    // watching so a later real stall is still detected.
                    Log.e(TAG, "stall watchdog tick failed: ${e.message}")
                }
                handler.postDelayed(this, STALL_CHECK_MS)
            }
        }
        stallWatchdog = w
        handler.postDelayed(w, STALL_CHECK_MS)
    }

    private fun cancelStallWatchdog() {
        stallWatchdog?.let { handler.removeCallbacks(it) }
        stallWatchdog = null
    }

    private fun closeInternal() {
        Log.i(TAG, "closeInternal holders=${holderCount()}")
        cancelStallWatchdog()
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { frameReader?.close() } catch (_: Exception) {}
        closeSnapshotReader()
        try { device?.close() } catch (_: Exception) {}
        session = null
        frameReader = null
        device = null
        configuredSurfaces = emptyList()
    }

    fun shutdown() {
        handler.post { closeInternal() }
        handlerThread.quitSafely()
        cbExecutor.shutdown()
        userCbExecutor.shutdown()
    }

    private fun chooseFrameSize(chars: CameraCharacteristics): Size {
        return try {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes.isNullOrEmpty()) Size(FRAME_MAX_W, FRAME_MAX_H)
            else {
                val cap = FRAME_MAX_W * FRAME_MAX_H
                sizes.filter { it.width * it.height <= cap }
                    .maxByOrNull { it.width * it.height }
                    ?: sizes.minByOrNull { it.width * it.height }
                    ?: Size(FRAME_MAX_W, FRAME_MAX_H)
            }
        } catch (e: Exception) {
            Log.w(TAG, "chooseFrameSize failed: ${e.message}")
            Size(FRAME_MAX_W, FRAME_MAX_H)
        }
    }
}
