package com.repository.glasses.listener.reid

import android.os.SystemClock
import com.repository.glasses.listener.reid.rppg.RppgEngine

/**
 * Core face recognition state machine.
 * Manages the full pipeline: camera -> face detection -> BT send -> result matching -> UI.
 */
class ReidController {

    companion object {
        private const val TAG = "ReidController"
        private const val RESEND_AREA_RATIO = 1.5f
        private const val PENDING_TIMEOUT_MS = 600_000L
        private const val STALE_FRAME_THRESHOLD = 20
        private const val MAX_VERIFIED_FACES = 50
        /** Minimum gap between the END of one ReID still's delivery and the START of
         *  the next capture. The capture itself takes ~1-2s (AE warmup + RAW frame +
         *  demosaic), so the effective cadence is capture-time + this floor. Keeps the
         *  camera from being hammered while ReID frames are flowing. */
        private const val CAPTURE_MIN_INTERVAL_MS = 1500L
        /** Cadence of the rPPG recompute tick. compute() runs the full window each call;
         *  ~1 Hz matches TrackBuffer's intended drive rate and keeps CPU low. */
        private const val RPPG_TICK_INTERVAL_MS = 1000L
    }

    data class PendingFace(
        val trackingId: Int,
        var thumbnailBase64: String,
        var thumbnailWidth: Int,
        var lastSentArea: Int,
        var bestArea: Int,
        val sentAtMs: Long,
        var lastSeenFrame: Int
    )

    data class VerifiedFace(
        val personUid: String,
        val displayName: String,
        var trackingId: Int,
        var thumbnailBase64: String,
        var thumbnailWidth: Int,
        var score: Float
    )

    interface BtSender {
        fun sendFace(trackingId: Int, webpBase64: String)
    }

    interface UiCallback {
        fun onFacesUpdated(verified: List<VerifiedFace>, pendingCount: Int)
        fun onStatsUpdated(frames: Int, faces: Int, fps: Double, pending: Int, verified: Int)
        fun onStatusChanged(status: String)
    }

    var btSender: BtSender? = null
    var uiCallback: UiCallback? = null
    var remoteLog: ((String) -> Unit)? = null
    /** Bridge to the capture process. ReID subscribes to AIDL-delivered JPEG frames through
     *  this instead of opening Camera2 itself. Set by ListenerService before start(). */
    var captureBridge: com.repository.glasses.listener.capture.CaptureBridge? = null
    /** Wired by the owner so the streaming lifecycle holds a bt-manager active session
     *  ("reid_streaming"), preventing the RFCOMM idle watchdog from tearing the connection down
     *  while ReID frames are flowing. */
    var onActiveSessionEnter: (() -> Unit)? = null
    var onActiveSessionExit: (() -> Unit)? = null
    @Volatile private var sessionHeld = false

    private val pendingFaces = mutableListOf<PendingFace>()
    private val verifiedFaces = mutableListOf<VerifiedFace>()
    // Merges that arrived before the face was added (source -> target)
    private val pendingMerges = mutableMapOf<String, String>()
    private var frameConsumer: ReidFrameConsumer? = null
    @Volatile var isRunning = false
        private set

    // Periodic capture driver. ReID no longer subscribes to a continuous stream;
    // it requests one exposed still at a time and fires the next only AFTER the
    // previous one's detection completes (self-throttling -- no pile-up).
    private var captureThread: android.os.HandlerThread? = null
    private var captureHandler: android.os.Handler? = null
    @Volatile private var captureInFlight = false

    /** Live BPM per face track. Fed from the AIDL onRppgSamples batch on the binder
     *  thread; recomputed about once per second on [captureHandler]. UI reads via
     *  [bpmFor]. The trackingId<->verified-uid link is a later task; this only keeps
     *  BPM keyed by trackingId. */
    val rppgEngine = RppgEngine()

    private val rppgListener = object : com.repository.glasses.listener.capture.CaptureBridge.Listener {
        override fun onRppgSamples(trackingIds: LongArray, rgb: FloatArray, tMs: Long) {
            rppgEngine.onSamples(trackingIds, rgb, tMs)
        }
    }

    private val rppgTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try { rppgEngine.tick(SystemClock.elapsedRealtime()) } catch (e: Throwable) {
                log("rppg tick threw: ${e.message}")
            }
            captureHandler?.postDelayed(this, RPPG_TICK_INTERVAL_MS)
        }
    }

    /** Latest smoothed BPM for a face track, or null while measuring / unknown / dropped. */
    fun bpmFor(trackingId: Long): Float? = rppgEngine.bpmFor(trackingId)

    private val frameListener = object : com.repository.glasses.listener.capture.CaptureBridge.Listener {
        override fun onFrame(jpeg: ByteArray, width: Int, height: Int, rotationDeg: Int, frameId: Long) {
            // rotationDeg is 0: the capture side bakes the upright rotation into the
            // pixels, so the consumer must NOT rotate again.
            try {
                frameConsumer?.onFrame(jpeg, width, height, rotationDeg)
            } finally {
                scheduleNextCapture()
            }
        }
        override fun onCaptureError(code: Int, msg: String) {
            // Capture failed (e.g. camera busy with video). Back off and retry on cadence.
            log("ReID capture error code=$code $msg")
            scheduleNextCapture()
        }
    }

    /** Fire the next ReID still after CAPTURE_MIN_INTERVAL_MS, unless stopped or a
     *  capture is already queued. Called after each frame's detection / each error. */
    private fun scheduleNextCapture() {
        if (!isRunning) return
        captureInFlight = false
        captureHandler?.postDelayed({ triggerCapture() }, CAPTURE_MIN_INTERVAL_MS)
    }

    private fun triggerCapture() {
        if (!isRunning) return
        if (captureInFlight) return
        captureInFlight = true
        try {
            captureBridge?.captureReidFrame()
        } catch (e: Throwable) {
            log("triggerCapture threw: ${e.message}")
            scheduleNextCapture()
        }
    }

    fun start(context: android.content.Context) {
        if (isRunning) return
        isRunning = true
        pendingFaces.clear()
        pendingMerges.clear()

        log("Starting reid controller")
        uiCallback?.onStatusChanged("SCANNING")

        if (!sessionHeld) {
            sessionHeld = true
            try { onActiveSessionEnter?.invoke() } catch (_: Throwable) {}
        }

        frameConsumer = ReidFrameConsumer(captureBridge).apply {
            remoteLog = this@ReidController.remoteLog
            callback = frameCallback
        }
        frameConsumer?.start()

        val bridge = captureBridge
        if (bridge == null) {
            log("captureBridge is null -- ReID cannot receive frames")
            uiCallback?.onStatusChanged("ERROR: no capture bridge")
        } else {
            bridge.addListener(frameListener)
            val thread = android.os.HandlerThread("ReidCaptureDriver").apply { start() }
            captureThread = thread
            captureHandler = android.os.Handler(thread.looper)
            // Kick the first capture immediately; subsequent ones are scheduled
            // after each frame's detection completes (self-throttling loop).
            captureHandler?.post { triggerCapture() }

            // rPPG: subscribe to the silent skin-color sample stream and start it,
            // then drive the per-track BPM recompute at ~1 Hz on the same thread.
            bridge.addListener(rppgListener)
            bridge.startRppg()
            captureHandler?.postDelayed(rppgTick, RPPG_TICK_INTERVAL_MS)
        }
    }

    /**
     * One-shot ReID identify on a func-button photo frame, independent of whether ReID
     * mode is on. Runs exactly ONE detection pass on the supplied JPEG and routes any
     * detected faces through the SAME [frameCallback] the live loop uses (so the
     * onFacesDetected -> btSender.sendFace identify path is identical).
     *
     * Does NOT start the controller, the periodic capture loop, or change the LED -- it
     * borrows nothing from the camera (the frame is already captured) and uses a throwaway
     * ReidFrameConsumer so a running loop's detector/bitmap lifecycle is untouched. Detection
     * runs on a short-lived background thread; the caller may invoke this from any thread.
     *
     * [rotationDeg] must match the frame's orientation. The func-button photo file is stored
     * upright (pixels physically rotated, EXIF NORMAL), so callers decoding that file pass 0.
     */
    fun detectPhotoOneShot(jpeg: ByteArray, rotationDeg: Int) {
        val consumer = ReidFrameConsumer(captureBridge).apply {
            remoteLog = this@ReidController.remoteLog
            callback = frameCallback
        }
        Thread({
            try {
                consumer.detectOnce(jpeg, rotationDeg)
            } catch (e: Throwable) {
                log("detectPhotoOneShot threw: ${e.message}")
            }
        }, "ReidPhotoOneShot").start()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        log("Stopping reid controller")

        captureHandler?.removeCallbacksAndMessages(null)
        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null
        captureInFlight = false

        val bridge = captureBridge
        if (bridge != null) {
            bridge.removeListener(frameListener)
            bridge.removeListener(rppgListener)
            bridge.stopRppg()
        }
        rppgEngine.reset()
        frameConsumer?.stop()
        frameConsumer = null

        if (sessionHeld) {
            sessionHeld = false
            try { onActiveSessionExit?.invoke() } catch (_: Throwable) {}
        }
        uiCallback?.onStatusChanged("STOPPED")
    }

    fun onReidResult(trackingIdStr: String, recognized: Boolean, personUid: String, displayName: String, score: Float) {
        val trackingId = trackingIdStr.toIntOrNull() ?: return
        log("Reid result: tid=$trackingId recognized=$recognized uid=$personUid name=$displayName score=$score")

        if (!recognized) {
            // Keep in pending so the same trackingId isn't re-sent next frame
            emitUi()
            return
        }

        val pending = synchronized(pendingFaces) {
            pendingFaces.find { it.trackingId == trackingId }
        }
        val thumbB64 = pending?.thumbnailBase64 ?: ""
        val thumbW = pending?.thumbnailWidth ?: 0

        synchronized(pendingFaces) {
            pendingFaces.removeAll { it.trackingId == trackingId }
        }

        synchronized(verifiedFaces) {
            // Check if this UID was already merged before arrival
            val mergeTarget = pendingMerges.remove(personUid)
            val effectiveUid = mergeTarget ?: personUid
            if (mergeTarget != null) {
                log("Reid result: applying pending merge $personUid -> $effectiveUid")
            }

            val existing = verifiedFaces.find { it.personUid == effectiveUid }
            if (existing != null) {
                if (score > existing.score) {
                    if (thumbB64.isNotEmpty()) {
                        existing.thumbnailBase64 = thumbB64
                        existing.thumbnailWidth = thumbW
                    }
                    existing.score = score
                    existing.trackingId = trackingId
                }
            } else {
                if (verifiedFaces.size >= MAX_VERIFIED_FACES) {
                    verifiedFaces.removeAt(0)
                }
                verifiedFaces.add(VerifiedFace(effectiveUid, displayName, trackingId, thumbB64, thumbW, score))
            }
            Unit
        }

        emitUi()
    }

    fun onReidMerge(sourcePersonId: String, targetPersonId: String, targetDisplayName: String) {
        log("Reid merge: $sourcePersonId -> $targetPersonId ($targetDisplayName)")
        synchronized(verifiedFaces) {
            val source = verifiedFaces.find { it.personUid == sourcePersonId }
            val target = verifiedFaces.find { it.personUid == targetPersonId }
            if (source != null) {
                if (target != null) {
                    // Both exist -- keep best score, remove source
                    if (source.score > target.score) {
                        target.thumbnailBase64 = source.thumbnailBase64
                        target.thumbnailWidth = source.thumbnailWidth
                        target.score = source.score
                    }
                    verifiedFaces.remove(source)
                } else {
                    // Only source exists -- relabel it as target
                    verifiedFaces.remove(source)
                    verifiedFaces.add(VerifiedFace(
                        targetPersonId,
                        targetDisplayName,
                        source.trackingId,
                        source.thumbnailBase64,
                        source.thumbnailWidth,
                        source.score
                    ))
                }
            }
            if (source == null) {
                // Merge arrived before the face -- remember it
                if (pendingMerges.size >= 100) pendingMerges.clear()
                pendingMerges[sourcePersonId] = targetPersonId
                log("Reid merge: deferred (source not in face list yet)")
            }
        }
        emitUi()
    }

    private val frameCallback = object : ReidFrameConsumer.Callback {
        override fun onFacesDetected(faces: List<ReidFrameConsumer.DetectedFace>, frameCount: Int, fps: Double) {
            val now = SystemClock.elapsedRealtime()

            for (face in faces) {
                val tid = face.trackingId
                val area = face.faceArea

                // Check if this is a verified face
                val verified = synchronized(verifiedFaces) {
                    verifiedFaces.find { it.trackingId == tid }
                }

                if (verified != null) {
                    // Already verified -- resend if area improved significantly
                    val pending = synchronized(pendingFaces) {
                        pendingFaces.find { it.trackingId == tid }
                    }
                    val lastSent = pending?.lastSentArea ?: area
                    if (area > (lastSent * RESEND_AREA_RATIO).toInt()) {
                        log("REID_SEND tid=$tid area=$area imgLen=${face.webpBase64.length} resend=verified")
                        btSender?.sendFace(tid, face.webpBase64)
                        synchronized(verifiedFaces) {
                            verified.thumbnailBase64 = face.thumbnailBase64
                            verified.thumbnailWidth = face.thumbnailWidth
                        }
                        synchronized(pendingFaces) {
                            if (pending != null) {
                                pending.lastSentArea = area
                                pending.bestArea = area
                                pending.lastSeenFrame = frameCount
                                pending.thumbnailBase64 = face.thumbnailBase64
                                pending.thumbnailWidth = face.thumbnailWidth
                            } else {
                                pendingFaces.add(PendingFace(tid, face.thumbnailBase64, face.thumbnailWidth, area, area, now, frameCount))
                            }
                        }
                    } else {
                        synchronized(pendingFaces) { pending?.lastSeenFrame = frameCount }
                    }
                    continue
                }

                // Check if in pending
                val pending = synchronized(pendingFaces) {
                    pendingFaces.find { it.trackingId == tid }
                }

                if (pending != null) {
                    synchronized(pendingFaces) {
                        pending.lastSeenFrame = frameCount
                        if (area > pending.bestArea) {
                            pending.thumbnailBase64 = face.thumbnailBase64
                            pending.thumbnailWidth = face.thumbnailWidth
                            pending.bestArea = area
                        }
                        // Re-send if area improved significantly
                        if (area > (pending.lastSentArea * RESEND_AREA_RATIO).toInt()) {
                            log("REID_SEND tid=$tid area=$area imgLen=${face.webpBase64.length} resend=pending")
                            btSender?.sendFace(tid, face.webpBase64)
                            pending.lastSentArea = area
                        }
                    }
                } else {
                    // New face -- send via BT and add to pending
                    log("REID_SEND tid=$tid area=$area imgLen=${face.webpBase64.length} new=true")
                    btSender?.sendFace(tid, face.webpBase64)
                    synchronized(pendingFaces) {
                        pendingFaces.add(PendingFace(tid, face.thumbnailBase64, face.thumbnailWidth, area, area, now, frameCount))
                    }
                }
            }

            // Clean stale pending faces
            synchronized(pendingFaces) {
                pendingFaces.removeAll { p ->
                    val timedOut = (now - p.sentAtMs) > PENDING_TIMEOUT_MS
                    val notSeen = (frameCount - p.lastSeenFrame) > STALE_FRAME_THRESHOLD
                    timedOut || notSeen
                }
            }

            val pendingCount = synchronized(pendingFaces) { pendingFaces.size }
            val verifiedCount = synchronized(verifiedFaces) { verifiedFaces.size }
            uiCallback?.onStatsUpdated(frameCount, faces.size, fps, pendingCount, verifiedCount)
            emitUi()
        }

        override fun onNoFaces(frameCount: Int, fps: Double) {
            val pendingCount = synchronized(pendingFaces) { pendingFaces.size }
            val verifiedCount = synchronized(verifiedFaces) { verifiedFaces.size }
            uiCallback?.onStatsUpdated(frameCount, 0, fps, pendingCount, verifiedCount)
        }

        override fun onStatusChanged(status: String) {
            uiCallback?.onStatusChanged(status)
        }
    }

    private fun emitUi() {
        val verified = synchronized(verifiedFaces) { verifiedFaces.toList() }
        val pendingCount = synchronized(pendingFaces) { pendingFaces.size }
        uiCallback?.onFacesUpdated(verified, pendingCount)
    }

    private fun log(msg: String) {
        remoteLog?.invoke("[$TAG] $msg")
    }
}
