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
) {
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
    private val tracker = FaceTracker()

    /**
     * Latest SCRFD box+kps for each currently-active tracking id, published as an
     * immutable map behind a @Volatile reference. Writer: detect thread (swaps a
     * fresh map). Reader: frame thread (iterates the snapshot it reads once).
     */
    @Volatile private var trackKps: Map<Long, FloatArray> = emptyMap()

    private enum class State { IDLE, ACTIVE }
    // state/lastScrfdMs/lastFaceMs are written by the detect thread and read by the
    // frame thread (cheap gate decisions); @Volatile keeps reads fresh. A stale read
    // at worst fires one extra/fewer detect request, which is harmless.
    @Volatile private var state = State.IDLE
    @Volatile private var lastScrfdMs = 0L
    @Volatile private var lastFaceMs = 0L

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

            // --- Cheap per-frame ROI sampling (EVERY frame) ---------------------
            // Read the published kps snapshot once; iterate it against THIS frame's
            // plane snapshot. Never blocked by / waiting on detection.
            val kpsSnap = trackKps
            if (state == State.ACTIVE && kpsSnap.isNotEmpty()) {
                roiAttempts++
                val src = RoiSampler.RgbImage { x, y -> rgbAtSnapshot(x, y, w) }
                val batch = ArrayList<Sample>(kpsSnap.size)
                for ((id, kps) in kpsSnap) {
                    val sample = RoiSampler.sampleForehead(src, w, h, kps)
                    if (sample == null) {
                        if (framesSeen % 15L == 0L) {
                            // Diagnostic: why is a tracked frontal face rejected?
                            val rex = kps[0]; val rey = kps[1]; val lex = kps[2]; val ley = kps[3]
                            val nx = kps[4]; val ny = kps[5]
                            val d = Math.hypot((lex - rex).toDouble(), (ley - rey).toDouble())
                            val ex = (rex + lex) / 2f; val ey = (rey + ley) / 2f
                            val raxX = if (d > 0) (lex - rex) / d.toFloat() else 0f
                            val raxY = if (d > 0) (ley - rey) / d.toFloat() else 0f
                            val noseProjR = (nx - ex) * raxX + (ny - ey) * raxY
                            val cx = (ex + (ey - ny) * 0.0f).toInt().coerceIn(0, w - 1)
                            val cy = ey.toInt().coerceIn(0, h - 1)
                            val px = rgbAtSnapshot(cx, cy, w)
                            Log.i(TAG, "ROI-reject d=${"%.0f".format(d)} noseProjR=${"%.1f".format(noseProjR)} " +
                                "(yawGate=${"%.1f".format(0.25 * d)}) eyeC=($ex,$ey) nose=($nx,$ny) w=$w h=$h " +
                                "centerRGB=${(px shr 16) and 0xFF},${(px shr 8) and 0xFF},${px and 0xFF}")
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
            val fresh = HashMap<Long, FloatArray>(faces.size * 2)
            for (i in faces.indices) {
                val srcKps = faces[i].kps
                val scaled = FloatArray(srcKps.size)
                for (k in srcKps.indices) scaled[k] = srcKps[k] * DET_DS
                fresh[tracked[i].trackingId] = scaled
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
    }

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
        // Detection runs on the full-resolution stream frame (DET_DS=1). Detection
        // is now offloaded to its own thread (see detectExecutor), so the
        // conversion + setPixels cost no longer competes with per-frame ROI
        // sampling -- there is no reason to downscale and lose detection range.
        // (DET_DS>1 starved SCRFD: at 213x160 a normally-distanced face is too
        // small to detect, collapsing facesSeen to ~1/30s.)
        private const val DET_DS = 1
        private const val IDLE_SCRFD_INTERVAL_MS = 1000L
        // ACTIVE re-detect cadence. Detection is now ASYNC on its own thread, so
        // this is a pure box-freshness interval -- it does NOT trade off against the
        // ROI sample rate any more (a detect in flight does not block frame
        // delivery or ROI sampling). ~200ms refreshes the box ~5x/s, plenty for a
        // slowly-moving head while ROI samples run at the full ~15Hz stream rate.
        private const val ACTIVE_SCRFD_INTERVAL_MS = 200L
        private const val ACTIVE_LINGER_MS = 3000L
    }
}
