package com.repository.glasses.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-frame driver for the silent rPPG (camera heart-rate) pipeline. Consumes the
 * YUV_420_888 frames delivered by [CameraSession.startRppgStream] and splits the
 * work across TWO DECOUPLED paths so that slow NPU face detection can never starve
 * the fast color-sampling the pulse signal needs:
 *
 *   1. CHEAP per-frame path (runs on EVERY delivered frame, on the camera worker
 *      thread). Snapshot the YUV planes, close the Image promptly, then -- if a
 *      face box/kps is currently cached -- run [RoiSampler.sampleForehead] over the
 *      forehead bbox and emit the sample. ~8-15ms; never waits for detection.
 *
 *   2. SCRFD DETECTION path (runs on a SEPARATE single-thread executor). When a
 *      re-detect is due (throttle) AND no detection is already in flight, the frame
 *      thread builds a DOWNSCALED RGB COPY of the latest frame and hands that copy
 *      to the detect executor, which runs SCRFD (~150ms) + updates the tracker +
 *      refreshes the cached box/kps. While that runs, the frame path keeps
 *      delivering frames and ROI-sampling against the PREVIOUS cached box.
 *
 * Sharing: the cached per-track kps map is published as an immutable @Volatile
 * reference. The detection thread builds a fresh map and swaps the reference; the
 * frame thread reads the reference and iterates the snapshot. No lock needed --
 * the only writer is the detect thread, the only structural reader is the frame
 * thread, and the swap is atomic.
 *
 * Linger: a detection cycle that finds no face does NOT clear the cached kps. The
 * last known box is kept (and keeps being ROI-sampled) until [ACTIVE_LINGER_MS]
 * elapses with no successful detection. A few hundred ms of missed detection must
 * not blank the PPG signal.
 *
 * The frame path owns NO file IO; it just emits samples. [onYuvFrame] always
 * closes the Image it receives.
 *
 * Two emission callbacks, both fired from [onYuvFrame] on the frame thread:
 *   - [onSample] fires ONCE PER FACE (per-sample). The ADB probe appends each
 *     forehead reading to a CSV.
 *   - [onFrameSamples] fires ONCE PER PROCESSED FRAME with the full list of that
 *     frame's samples (empty-list frames are skipped). The AIDL stream path ships
 *     one batched onRppgSamples per frame; all samples in a batch share the frame tMs.
 */
class RppgPipeline(
    private val context: Context,
    private val onSample: (Sample) -> Unit = {},
    private val onFrameSamples: (List<Sample>) -> Unit = {},
    // Optional debug sink: when set, EVERY ACTIVE frame hands over the full-res
    // YUV->RGB pixels + the per-track forehead geometry so a caller can render a
    // face-masked annotated frame (ROI rect + 5 keypoints) for visual debugging.
    // Heavy (full-frame conversion + callback per frame) -- only wire it for the
    // debug-video probe, never the live stream.
    private val onDebugFrame: ((DebugFrame) -> Unit)? = null,
) {
    /** Per-frame debug payload: DOWNSCALED RGB (0xFFRRGGBB, [width]x[height]) plus
     *  the scale factor [ds] relating it to full-res keypoints (full = down * ds).
     *  Downscaled via the fast bulk converter so the per-frame debug render does not
     *  starve the sample stream. */
    class DebugFrame(
        val rgb: IntArray,
        val width: Int,
        val height: Int,
        val ds: Int,
        val tMs: Long,
        /** Per track: the 5 keypoints (10 floats, FULL-RES) used this frame. */
        val tracks: List<TrackDebug>,
    )
    class TrackDebug(
        val trackingId: Long, val kps: FloatArray,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val sampled: Boolean, val bpmHintR: Float, val bpmHintG: Float, val bpmHintB: Float,
    )
    /** One forehead skin-color reading for one tracked face on one frame. */
    data class Sample(
        val tMs: Long,
        val trackingId: Long,
        val r: Float,
        val g: Float,
        val b: Float,
        val pixelCount: Int,
    )

    // Tracker lives on the DETECT thread only (FaceTracker is not thread-safe).
    // maxAgeFrames here counts DETECTION cycles (not stream frames): detection runs
    // ~4fps, so 20 cycles ~= 5s of tolerated misses before a track id is dropped and
    // re-acquired with a NEW id. Generous tolerance keeps the trackingId STABLE across
    // brief SCRFD misses -- id churn was resetting the listener's 10s rPPG buffer
    // before it could ever become ready (BPM stuck at null).
    private val tracker = FaceTracker(maxAgeFrames = 20)

    /** Latest face geometry for an active track: full-res box + 5 keypoints. */
    class TrackGeom(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val kps: FloatArray)

    /**
     * Latest SCRFD box+kps for each currently-active tracking id, published as an
     * immutable map behind a @Volatile reference. Writer: detect thread (swaps a
     * fresh map). Reader: frame thread (iterates the snapshot it reads once).
     */
    @Volatile private var trackKps: Map<Long, TrackGeom> = emptyMap()

    private enum class State { IDLE, ACTIVE }
    // state/lastScrfdMs/lastFaceMs are written by the detect thread and read by the
    // frame thread (cheap gate decisions); @Volatile keeps reads fresh. A stale read
    // at worst fires one extra/fewer detect request, which is harmless.
    @Volatile private var state = State.IDLE
    @Volatile private var lastScrfdMs = 0L
    @Volatile private var lastFaceMs = 0L
    // Frame-thread only: last time a ROI sample batch was taken (sample-rate throttle).
    private var lastSampleMs = 0L

    // Detection offload. ONE detection at a time; re-detect requests are dropped
    // while one is in flight (detectInFlight). Cleared in the detect task's finally.
    // Daemon-threaded so a pipeline that is dropped without an explicit shutdown()
    // (CaptureService recreates the pipeline per probe/stream) never pins JVM exit.
    private val detectExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Rppg-detect").apply { isDaemon = true }
    }
    private val detectInFlight = AtomicBoolean(false)

    // Reusable detection ARGB buffer + Bitmap (DETECT thread only). The frame
    // thread builds a per-request COPY of the downscaled RGB and hands it over;
    // the detect thread copies it into [detBmp] via setPixels.
    private var detBmp: Bitmap? = null
    private var detBmpW = 0
    private var detBmpH = 0

    // Per-frame plane scratch (FRAME thread only). Bulk-read the DirectByteBuffers
    // into JVM byte[] once per frame, then index those (a per-pixel ByteBuffer.get()
    // is a bounds-checked JNI call). Stride metadata cached alongside.
    private var yScratch = ByteArray(0)
    private var uScratch = ByteArray(0)
    private var vScratch = ByteArray(0)
    private var pYRowStride = 0
    private var pURowStride = 0
    private var pVRowStride = 0
    private var pUPixStride = 0
    private var pVPixStride = 0
    // Current frame dims (FRAME thread); copied into the snapshot under snapLock.
    private var curFrameW = 0
    private var curFrameH = 0

    // --- Latest-frame JPEG snapshot (for serving ReID recognition off the live
    // rPPG stream instead of a camera-stealing RAW still) -----------------------
    // The frame thread publishes a COPY of each frame's planes + dims under
    // [snapLock]; [latestFrameJpeg] (any thread) reads that copy under the same
    // lock and converts it to an upright JPEG. A per-frame plane COPY is the price
    // of decoupling the snapshot from the frame thread's reused scratch; it is a
    // few hundred KB memcpy at ~8fps, far cheaper than the 1280x960 RAW burst it
    // replaces. snapW<=0 means no frame captured yet.
    private val snapLock = Any()
    private var snapY = ByteArray(0)
    private var snapU = ByteArray(0)
    private var snapV = ByteArray(0)
    private var snapYRowStride = 0
    private var snapURowStride = 0
    private var snapVRowStride = 0
    private var snapUPixStride = 0
    private var snapVPixStride = 0
    private var snapW = 0
    private var snapH = 0

    // Throwaway counters for the probe.
    @Volatile var framesSeen = 0L; private set
    @Volatile var framesProcessed = 0L; private set    // detection-frame RGB copies built
    @Volatile var scrfdProcessed = 0L; private set      // frames that ran SCRFD
    @Volatile var facesSeen = 0L; private set
    @Volatile var roiAttempts = 0L; private set         // frames that ran the ROI block

    /** Reset counters + state for a fresh probe run. */
    fun reset() {
        framesSeen = 0L
        framesProcessed = 0L
        scrfdProcessed = 0L
        facesSeen = 0L
        roiAttempts = 0L
        state = State.IDLE
        lastScrfdMs = 0L
        lastFaceMs = 0L
        lastSampleMs = 0L
        trackKps = emptyMap()
        detectInFlight.set(false)
    }

    /** Shut down the detection executor. Idempotent. Call from stopRppgStream. */
    fun shutdown() {
        detectExecutor.shutdown()
    }

    /**
     * Process one YUV_420_888 frame on the camera worker thread. ALWAYS closes
     * [image]. Cheap by construction: snapshot + (maybe) hand a COPY to detection +
     * ROI-sample the cached box. Detection itself runs off-thread.
     */
    fun onYuvFrame(image: Image) {
        framesSeen++
        val now = SystemClock.elapsedRealtime()
        val w = image.width
        val h = image.height
        curFrameW = w
        curFrameH = h
        try {
            // Snapshot the plane bytes once (cheap bulk reads) so we never touch the
            // slow DirectByteBuffer per pixel -- and so we can close the Image before
            // any further work (the async detection works off a COPY, not the Image).
            snapshotPlanes(image)
        } catch (e: Throwable) {
            Log.w(TAG, "snapshotPlanes threw: ${e.message}")
            try { image.close() } catch (_: Exception) {}
            return
        }
        // Close the Image PROMPTLY -- everything below works off the JVM snapshot.
        try { image.close() } catch (_: Exception) {}

        try {
            // --- Detection offload (non-blocking) -------------------------------
            // If a re-detect is due and none is in flight, build the downscaled RGB
            // COPY here (on the frame thread, off the snapshot) and dispatch it.
            if (shouldRunScrfd(now) && detectInFlight.compareAndSet(false, true)) {
                val dw = w / DET_DS
                val dh = h / DET_DS
                val rgbCopy = IntArray(dw * dh)
                argbFromSnapshotScaled(dw, dh, DET_DS, rgbCopy)
                framesProcessed++
                lastScrfdMs = now
                try {
                    detectExecutor.execute { runDetection(SystemClock.elapsedRealtime(), dw, dh, rgbCopy) }
                } catch (e: Throwable) {
                    // Executor rejected (shutting down): release the guard.
                    detectInFlight.set(false)
                }
            }

            if (framesSeen <= 6L || framesSeen % 15L == 0L) {
                Log.i(TAG, "frame#${framesSeen} state=$state roiAttempts=$roiAttempts " +
                    "scrfd=$scrfdProcessed inFlight=${detectInFlight.get()} tracks=${trackKps.size}")
            }

            // --- Throttled ROI sampling (~SAMPLE_INTERVAL_MS) -------------------
            // Sample at ~10Hz, NOT every stream frame: a head rate maxes ~4Hz so
            // 10Hz is ample (Nyquist 5Hz), and matching the sample rate closer to
            // the detection rate keeps every sample on a FRESH box -- sampling at the
            // full ~24fps put samples on boxes up to ~7 frames stale, raising the
            // ROI wobble and dropping SNR (measured: 24fps SNR ~0.2 vs matched-rate
            // SNR ~0.7 at the same BPM). Read the published kps snapshot once.
            val kpsSnap = trackKps
            val dueToSample = now - lastSampleMs >= SAMPLE_INTERVAL_MS
            if (state == State.ACTIVE && kpsSnap.isNotEmpty() && dueToSample) {
                lastSampleMs = now
                roiAttempts++
                val src = RoiSampler.RgbImage { x, y -> rgbAtSnapshot(x, y, w) }
                val batch = ArrayList<Sample>(kpsSnap.size)
                val dbgSink = onDebugFrame
                val dbgTracks = if (dbgSink != null) ArrayList<TrackDebug>(kpsSnap.size) else null
                for ((id, geom) in kpsSnap) {
                    // Full-FACE skin sampling (rotation-robust): average all skin-mask
                    // pixels in the face box, not a fixed forehead patch.
                    val sample = RoiSampler.sampleFaceSkin(src, w, h, geom.x0, geom.y0, geom.x1, geom.y1)
                    if (dbgTracks != null) {
                        dbgTracks.add(
                            TrackDebug(
                                id, geom.kps.copyOf(), geom.x0, geom.y0, geom.x1, geom.y1, sample != null,
                                sample?.r ?: 0f, sample?.g ?: 0f, sample?.b ?: 0f,
                            )
                        )
                    }
                    if (sample == null) {
                        if (framesSeen % 30L == 0L) {
                            Log.i(TAG, "face-skin reject box=(${geom.x0},${geom.y0},${geom.x1},${geom.y1}) w=$w h=$h")
                        }
                        continue
                    }
                    val s = Sample(
                        tMs = now,
                        trackingId = id,
                        r = sample.r,
                        g = sample.g,
                        b = sample.b,
                        pixelCount = sample.pixelCount,
                    )
                    batch.add(s)
                    onSample(s)
                }
                if (batch.isNotEmpty()) onFrameSamples(batch)
                // Debug: hand over a DOWNSCALED frame (fast bulk converter) + geometry
                // so the caller can render a face-masked annotated frame WITHOUT the
                // slow full-res per-pixel conversion that starved the stream. Only
                // when a debug sink is wired.
                if (dbgSink != null && dbgTracks != null) {
                    val ds = DBG_DS
                    val dw = w / ds; val dh = h / ds
                    val small = IntArray(dw * dh)
                    argbFromSnapshotScaled(dw, dh, ds, small)
                    try { dbgSink(DebugFrame(small, dw, dh, ds, now, dbgTracks)) }
                    catch (e: Throwable) { Log.w(TAG, "debug sink threw: ${e.message}") }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "rppg frame processing threw: ${e.message}")
        }
    }

    /**
     * Run SCRFD on the handed-over downscaled RGB COPY, update the tracker, and
     * publish a fresh per-track kps map. Runs on the DETECT thread; the only writer
     * of [tracker], [trackKps], [state], [lastFaceMs]. Always releases
     * [detectInFlight] in its finally so the next re-detect can dispatch.
     */
    private fun runDetection(now: Long, dw: Int, dh: Int, rgb: IntArray) {
        try {
            val det = ScrfdFaceDetector.shared(context) ?: return
            val b = ensureDetBitmap(dw, dh)
            b.setPixels(rgb, 0, dw, 0, 0, dw, dh)
            val faces = det.detectFullBitmap(b)
            scrfdProcessed++

            if (faces.isEmpty()) {
                // Age the tracker so stale tracks eventually expire, but DO NOT clear
                // the published kps on a brief miss -- keep ROI sampling the last box
                // until the full linger elapses. Only after ACTIVE_LINGER_MS of no
                // face do we drop to IDLE and blank the cache.
                tracker.update(emptyList())
                if (state == State.ACTIVE && now - lastFaceMs > ACTIVE_LINGER_MS) {
                    state = State.IDLE
                    trackKps = emptyMap()
                }
                return
            }

            state = State.ACTIVE
            lastFaceMs = now
            facesSeen += faces.size

            // SCRFD ran on the downscaled frame -> scale boxes + keypoints back to
            // full-res coords so the tracker and the full-res ROI sampler agree.
            val boxes = faces.map {
                TrackBox(it.x0 * DET_DS, it.y0 * DET_DS, it.x1 * DET_DS, it.y1 * DET_DS)
            }
            val tracked = tracker.update(boxes)
            // detectFullBitmap and tracker.update both preserve input order, so
            // tracked[i] corresponds to faces[i] -- a direct index map.
            // Keypoint EMA per track: SCRFD's 5 keypoints jitter several px between
            // detections, which moves/resizes the forehead ROI every frame and was
            // the dominant rPPG noise (pixelCount swinging +/-33% -> AC/DC ~15% while
            // exposure was provably constant). Blend each new keypoint with the prior
            // smoothed value: kps_smooth = a*kps_new + (1-a)*kps_prev. This pins the
            // ROI onto the same physical skin patch frame-to-frame.
            val prev = trackKps
            val fresh = HashMap<Long, TrackGeom>(faces.size * 2)
            for (i in faces.indices) {
                val id = tracked[i].trackingId
                val srcKps = faces[i].kps
                val scaled = FloatArray(srcKps.size)
                for (k in srcKps.indices) scaled[k] = srcKps[k] * DET_DS
                val tb = tracked[i].box  // full-res box (FaceTracker boxes are already *DET_DS)
                var bx0 = tb.x0; var by0 = tb.y0; var bx1 = tb.x1; var by1 = tb.y1
                val prior = prev[id]
                if (prior != null && prior.kps.size == scaled.size) {
                    // EMA-smooth keypoints AND the box so the sampling region stops
                    // jittering between detections (the dominant rPPG noise source).
                    for (k in scaled.indices) {
                        scaled[k] = KPS_EMA_ALPHA * scaled[k] + (1f - KPS_EMA_ALPHA) * prior.kps[k]
                    }
                    bx0 = (KPS_EMA_ALPHA * bx0 + (1f - KPS_EMA_ALPHA) * prior.x0).toInt()
                    by0 = (KPS_EMA_ALPHA * by0 + (1f - KPS_EMA_ALPHA) * prior.y0).toInt()
                    bx1 = (KPS_EMA_ALPHA * bx1 + (1f - KPS_EMA_ALPHA) * prior.x1).toInt()
                    by1 = (KPS_EMA_ALPHA * by1 + (1f - KPS_EMA_ALPHA) * prior.y1).toInt()
                }
                fresh[id] = TrackGeom(bx0, by0, bx1, by1, scaled)
            }
            // Publish atomically. Replaces (not mutates) so the frame thread always
            // sees a consistent map. Stale ids simply drop out -- the linger that
            // matters is time-based, and on a real miss we keep the prior map above.
            trackKps = fresh
        } catch (e: Throwable) {
            Log.w(TAG, "rppg detection threw: ${e.message}")
        } finally {
            detectInFlight.set(false)
        }
    }

    /**
     * Cadence gate (read on the frame thread). ACTIVE re-detects every
     * [ACTIVE_SCRFD_INTERVAL_MS]; IDLE throttles to one run per
     * [IDLE_SCRFD_INTERVAL_MS]. Because detection is now async, this interval is a
     * pure freshness knob -- it no longer competes with ROI sampling for a slot.
     */
    private fun shouldRunScrfd(now: Long): Boolean = when (state) {
        State.ACTIVE -> now - lastScrfdMs >= ACTIVE_SCRFD_INTERVAL_MS
        State.IDLE -> now - lastScrfdMs >= IDLE_SCRFD_INTERVAL_MS
    }

    /** Allocate (or reuse) the detection Bitmap for a dw x dh frame. Detect thread. */
    private fun ensureDetBitmap(w: Int, h: Int): Bitmap {
        var b = detBmp
        if (b == null || detBmpW != w || detBmpH != h) {
            b?.recycle()
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            detBmp = b
            detBmpW = w
            detBmpH = h
        }
        return b
    }

    /**
     * Bulk-copy the three YUV planes into JVM byte[] (one bounds-checked JNI call
     * per plane, not per pixel) and cache their strides. Cheap (~1-2ms). Frame
     * thread. After this returns the Image can be closed; subsequent indexing only
     * touches these arrays.
     */
    private fun snapshotPlanes(image: Image) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        pYRowStride = yPlane.rowStride
        pURowStride = uPlane.rowStride
        pVRowStride = vPlane.rowStride
        pUPixStride = uPlane.pixelStride
        pVPixStride = vPlane.pixelStride
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yN = yBuf.remaining()
        val uN = uBuf.remaining()
        val vN = vBuf.remaining()
        if (yScratch.size < yN) yScratch = ByteArray(yN)
        if (uScratch.size < uN) uScratch = ByteArray(uN)
        if (vScratch.size < vN) vScratch = ByteArray(vN)
        yBuf.get(yScratch, 0, yN)
        uBuf.get(uScratch, 0, uN)
        vBuf.get(vScratch, 0, vN)

        // Publish a copy for on-demand recognition snapshots (off-thread readers).
        synchronized(snapLock) {
            if (snapY.size < yN) snapY = ByteArray(yN)
            if (snapU.size < uN) snapU = ByteArray(uN)
            if (snapV.size < vN) snapV = ByteArray(vN)
            System.arraycopy(yScratch, 0, snapY, 0, yN)
            System.arraycopy(uScratch, 0, snapU, 0, uN)
            System.arraycopy(vScratch, 0, snapV, 0, vN)
            snapYRowStride = pYRowStride
            snapURowStride = pURowStride
            snapVRowStride = pVRowStride
            snapUPixStride = pUPixStride
            snapVPixStride = pVPixStride
            snapW = curFrameW
            snapH = curFrameH
        }
    }

    /**
     * Latest live-stream frame as an UPRIGHT JPEG, for serving ReID recognition off
     * the running rPPG stream instead of a camera-stealing RAW still. Converts the
     * most-recently-published YUV snapshot to ARGB, rotates -90 (matching the RAW
     * still path so the consumer passes rotationDeg=0), and JPEG-encodes.
     *
     * Returns null if no frame has been captured yet. Callable from any thread; reads
     * the published snapshot under [snapLock] so it never races the frame thread.
     */
    fun latestFrameJpeg(quality: Int = 90): JpegFrame? {
        val w: Int; val h: Int
        val yA: ByteArray; val uA: ByteArray; val vA: ByteArray
        val yRow: Int; val uRow: Int; val vRow: Int; val uPix: Int; val vPix: Int
        synchronized(snapLock) {
            if (snapW <= 0 || snapH <= 0 || snapY.isEmpty()) return null
            w = snapW; h = snapH
            // Copy out under the lock so the conversion below runs lock-free.
            yA = snapY.copyOf(); uA = snapU.copyOf(); vA = snapV.copyOf()
            yRow = snapYRowStride; uRow = snapURowStride; vRow = snapVRowStride
            uPix = snapUPixStride; vPix = snapVPixStride
        }
        val argb = IntArray(w * h)
        var o = 0
        for (y in 0 until h) {
            val yBase = y * yRow
            val cRow = y shr 1
            val uBase = cRow * uRow
            val vBase = cRow * vRow
            for (x in 0 until w) {
                val yy = (yA[yBase + x].toInt() and 0xFF)
                val cIdx = x shr 1
                val u = (uA[uBase + cIdx * uPix].toInt() and 0xFF) - 128
                val v = (vA[vBase + cIdx * vPix].toInt() and 0xFF) - 128
                val y1192 = 1192 * (yy - 16)
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                argb[o++] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        val raw = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        // Rotate -90 so the delivered pixels are upright (rotationDeg=0 downstream),
        // identical to RawStillCapturer's ReID frame orientation.
        val m = android.graphics.Matrix().apply { postRotate(-90f) }
        val upright = Bitmap.createBitmap(raw, 0, 0, w, h, m, true)
        if (upright !== raw) raw.recycle()
        val bos = java.io.ByteArrayOutputStream(upright.width * upright.height / 4)
        upright.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        val outW = upright.width; val outH = upright.height
        upright.recycle()
        return JpegFrame(bos.toByteArray(), outW, outH)
    }

    /** An encoded upright JPEG plus its dimensions. rotationDeg is implicitly 0. */
    class JpegFrame(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Single-pixel YUV->packed 0xRRGGBB from the current plane snapshot. Frame thread. */
    private fun rgbAtSnapshot(x: Int, y: Int, @Suppress("UNUSED_PARAMETER") w: Int): Int {
        val yy = (yScratch[y * pYRowStride + x].toInt() and 0xFF)
        val cIdx = x shr 1
        val cRow = y shr 1
        val u = (uScratch[cRow * pURowStride + cIdx * pUPixStride].toInt() and 0xFF) - 128
        val v = (vScratch[cRow * pVRowStride + cIdx * pVPixStride].toInt() and 0xFF) - 128
        val y1192 = 1192 * (yy - 16)
        var r = (y1192 + 1634 * v) shr 10
        var g = (y1192 - 833 * v - 400 * u) shr 10
        var b = (y1192 + 2066 * u) shr 10
        if (r < 0) r = 0 else if (r > 255) r = 255
        if (g < 0) g = 0 else if (g > 255) g = 255
        if (b < 0) b = 0 else if (b > 255) b = 255
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * Downscaled YUV->packed ARGB int[] (0xFFRRGGBB) from the plane snapshot, by
     * nearest-neighbor (sample every [ds]-th source pixel). Output is [dw]x[dh].
     * Built on the FRAME thread into a per-request copy that is handed to the
     * detect thread; SCRFD resizes its input internally so a 1/ds frame suffices.
     * BT.601 integer approximation.
     */
    private fun argbFromSnapshotScaled(dw: Int, dh: Int, ds: Int, out: IntArray) {
        val yA = yScratch
        val uA = uScratch
        val vA = vScratch
        val yRowStride = pYRowStride
        val uRowStride = pURowStride
        val vRowStride = pVRowStride
        val uPixStride = pUPixStride
        val vPixStride = pVPixStride
        var oRow = 0
        for (dy in 0 until dh) {
            val sy = dy * ds
            val yRow = sy * yRowStride
            val cRow = sy shr 1
            val uRowBase = cRow * uRowStride
            val vRowBase = cRow * vRowStride
            var o = oRow
            var dx = 0
            while (dx < dw) {
                val sx = dx * ds
                val yy = (yA[yRow + sx].toInt() and 0xFF)
                val cIdx = sx shr 1
                val u = (uA[uRowBase + cIdx * uPixStride].toInt() and 0xFF) - 128
                val v = (vA[vRowBase + cIdx * vPixStride].toInt() and 0xFF) - 128
                val y1192 = 1192 * (yy - 16)
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[o++] = -0x1000000 or (r shl 16) or (g shl 8) or b
                dx++
            }
            oRow += dw
        }
    }

    companion object {
        private const val TAG = "Cap:Rppg"
        // Detection downscale. The stream is now 1280x960, so DET_DS=2 feeds SCRFD
        // a 640x480 frame -- a normally-distanced face is ~40px inter-ocular on the
        // 1280 stream, so ~20px at half-scale, which SCRFD still detects. Halving
        // each axis cuts the full-frame YUV->RGB conversion ~4x, so detection
        // actually keeps its ~5fps cadence instead of collapsing to ~1fps (which it
        // did at DET_DS=1 on 1280x960: the ~250ms full-res conversion throttled it).
        // Box/keypoint coords are scaled back up by DET_DS for full-res ROI placement.
        private const val DET_DS = 3
        // Keypoint EMA smoothing factor (detect thread). Lower = smoother/stickier ROI
        // but slower to follow real head motion. 0.4 keeps the ROI on the same skin
        // patch across SCRFD's per-detection keypoint jitter while still tracking.
        private const val KPS_EMA_ALPHA = 0.6f
        private const val IDLE_SCRFD_INTERVAL_MS = 1000L
        // ACTIVE re-detect cadence. Detection is now ASYNC on its own thread, so
        // this is a pure box-freshness interval -- it does NOT trade off against the
        // ROI sample rate any more (a detect in flight does not block frame
        // delivery or ROI sampling). ~200ms refreshes the box ~5x/s, plenty for a
        // slowly-moving head while ROI samples run at the full ~15Hz stream rate.
        private const val ACTIVE_SCRFD_INTERVAL_MS = 200L
        // ROI sampling cadence (~10Hz). Heart rate maxes ~4Hz so 10Hz is ample, and
        // keeping it near the detection rate keeps each sample on a fresh (non-stale)
        // box -- the key to a clean signal (full-frame-rate sampling on a stale box
        // wobbled the ROI and tanked SNR).
        private const val SAMPLE_INTERVAL_MS = 100L
        private const val ACTIVE_LINGER_MS = 3000L
        // Debug-render downscale: the masked debug frame is rendered at 1/DBG_DS so
        // the JPEG encode + canvas work keeps up with the stream (full-res 1280x960
        // per-frame render was ~2s/frame). Keypoints are full-res; the renderer
        // divides them by DebugFrame.ds.
        private const val DBG_DS = 2
    }
}
