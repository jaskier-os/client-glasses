package com.repository.glasses.capture

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.SystemClock
import android.util.Log

/**
 * Per-frame driver for the silent rPPG (camera heart-rate) pipeline. Consumes the
 * YUV_420_888 frames delivered by [CameraSession.startRppgStream] (on the camera
 * session's dedicated rPPG worker thread, one frame in flight at a time).
 *
 * Per processed frame the work is split into a CHEAP path that runs EVERY frame
 * and an EXPENSIVE path (SCRFD) that runs at a throttled cadence:
 *
 *   YUV_420_888 -> single ARGB int[] (one NV21->ARGB conversion, no JPEG)
 *     -> [maybe, ~5fps] SCRFD on the same buffer (via a reusable Bitmap) ->
 *        [FaceTracker.update] -> store latest box+kps per track
 *     -> [EVERY frame] for each active track, [RoiSampler.sampleForehead] over the
 *        int[] buffer using the track's latest kps -> emit a [Sample].
 *
 * This decouples detection rate (slow, NPU-bound) from color-sampling rate (fast,
 * a few thousand int reads), so PPG samples come at the full ~15fps stream rate
 * while SCRFD only fires ~5x/s when a face is present.
 *
 * Cadence state machine:
 *   IDLE   -> run SCRFD ~once/sec while no face is present; no ROI samples.
 *   ACTIVE -> run SCRFD every [ACTIVE_SCRFD_INTERVAL_MS]; ROI-sample EVERY frame
 *             for each active track. After the last track ages out, linger
 *             [ACTIVE_LINGER_MS] then fall back to IDLE.
 *
 * The callback owns NO file IO; it just emits samples. The [onYuvFrame] callback
 * MUST close the Image it receives (this class always closes it in a finally).
 *
 * Single-threaded by construction: [CameraSession] guarantees one frame in flight,
 * so the tracker + per-track box cache (not thread-safe) are only touched here.
 *
 * Two emission callbacks, both fired from [onYuvFrame] on the same thread:
 *   - [onSample] fires ONCE PER FACE (per-sample). The ADB probe uses this to append
 *     each forehead reading to a CSV.
 *   - [onFrameSamples] fires ONCE PER PROCESSED FRAME with the full list of that
 *     frame's samples (one per active face; empty list frames are skipped -- the
 *     callback is only invoked when the list is non-empty). The AIDL stream path
 *     uses this to ship one batched onRppgSamples per frame. All samples in a batch
 *     share the same frame tMs.
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

    private val tracker = FaceTracker()

    /** Latest SCRFD box+kps for each currently-active tracking id. */
    private val trackKps = HashMap<Long, FloatArray>()

    private enum class State { IDLE, ACTIVE }
    private var state = State.IDLE
    private var lastScrfdMs = 0L
    private var lastFaceMs = 0L

    // Reusable ARGB buffer + Bitmap, sized to the stream resolution. Avoids a
    // per-frame allocation of the (640*480) int[] and the Bitmap handed to SCRFD.
    private var rgbBuf: IntArray? = null
    private var bmp: Bitmap? = null
    private var bufW = 0
    private var bufH = 0

    // Reusable plane scratch (bulk-read the DirectByteBuffers into JVM byte[] once
    // per frame, then index those -- a per-pixel ByteBuffer.get() is a bounds-
    // checked JNI call and was the ~2.4s/frame regression). Stride metadata is
    // cached alongside so both the full-frame and on-demand paths can index.
    private var yScratch = ByteArray(0)
    private var uScratch = ByteArray(0)
    private var vScratch = ByteArray(0)
    private var pYRowStride = 0
    private var pURowStride = 0
    private var pVRowStride = 0
    private var pUPixStride = 0
    private var pVPixStride = 0

    // Throwaway counters for the probe to log stream vs processed fps.
    @Volatile var framesSeen = 0L; private set
    @Volatile var framesProcessed = 0L; private set   // frames converted YUV->RGB
    @Volatile var scrfdProcessed = 0L; private set     // frames that ran SCRFD
    @Volatile var facesSeen = 0L; private set

    /** Reset counters + state for a fresh probe run. */
    fun reset() {
        framesSeen = 0L
        framesProcessed = 0L
        scrfdProcessed = 0L
        facesSeen = 0L
        state = State.IDLE
        lastScrfdMs = 0L
        lastFaceMs = 0L
        trackKps.clear()
    }

    /**
     * Process one YUV_420_888 frame. Always closes [image]. Safe to pass as the
     * [CameraSession.startRppgStream] callback.
     */
    fun onYuvFrame(image: Image) {
        framesSeen++
        val now = SystemClock.elapsedRealtime()
        try {
            val w = image.width
            val h = image.height
            // Snapshot the plane bytes once (cheap bulk reads). Both the
            // full-frame conversion (detection) and the on-demand ROI accessor
            // index these, so we never touch the slow DirectByteBuffer per pixel.
            snapshotPlanes(image)

            val runScrfd = shouldRunScrfd(now)
            var scrfdMs = 0L
            var convMs = 0L
            if (runScrfd) {
                // Detection frame: convert a DOWNSCALED (1/DET_DS) frame for SCRFD
                // (which resizes its input internally anyway), cutting the
                // conversion + setPixels cost by DET_DS^2. Keypoints come back in
                // downscaled pixels and are scaled up by DET_DS before tracking +
                // ROI placement. Runs at the throttled detection cadence only.
                val dw = w / DET_DS
                val dh = h / DET_DS
                val rgb = ensureBuffers(dw, dh)
                val tC0 = SystemClock.elapsedRealtime()
                argbFromSnapshotScaled(dw, dh, DET_DS, rgb)
                convMs = SystemClock.elapsedRealtime() - tC0
                framesProcessed++
                lastScrfdMs = now
                val tS0 = SystemClock.elapsedRealtime()
                runDetection(now, dw, dh, rgb)
                scrfdMs = SystemClock.elapsedRealtime() - tS0
            }
            if (framesSeen <= 6L || framesSeen % 15L == 0L) {
                Log.i(TAG, "frame#${framesSeen} convMs=$convMs scrfdMs=$scrfdMs ran=$runScrfd")
            }

            // EVERY frame: ROI-sample each active track. The RoiSampler scans only
            // the small forehead bbox (a few thousand pixels), converting each
            // requested pixel on demand from the plane snapshot -- no full-frame
            // conversion on non-detection frames.
            if (state == State.ACTIVE && trackKps.isNotEmpty()) {
                val src = RoiSampler.RgbImage { x, y -> rgbAtSnapshot(x, y, w) }
                val batch = ArrayList<Sample>(trackKps.size)
                for ((id, kps) in trackKps) {
                    val sample = RoiSampler.sampleForehead(src, w, h, kps) ?: continue
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
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    /**
     * Run SCRFD on the current frame's ARGB Bitmap, update the tracker, and
     * refresh the per-track latest kps cache. Maintains the IDLE/ACTIVE state.
     */
    private fun runDetection(now: Long, dw: Int, dh: Int, rgb: IntArray) {
        val det = ScrfdFaceDetector.shared(context) ?: return
        val b = bmp ?: return
        b.setPixels(rgb, 0, dw, 0, 0, dw, dh)
        val faces = det.detectFullBitmap(b)
        scrfdProcessed++

        if (faces.isEmpty()) {
            // ACTIVE lingers after the last face so a brief miss does not drop
            // back to the slow IDLE cadence. Tracker still ages on empty frames
            // via update(emptyList()) so stale tracks expire.
            val tracked = tracker.update(emptyList())
            pruneTrackKps(tracked)
            if (state == State.ACTIVE && now - lastFaceMs > ACTIVE_LINGER_MS) {
                state = State.IDLE
                trackKps.clear()
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
        for (i in faces.indices) {
            val src = faces[i].kps
            val scaled = FloatArray(src.size)
            for (k in src.indices) scaled[k] = src[k] * DET_DS
            trackKps[tracked[i].trackingId] = scaled
        }
        pruneTrackKps(tracked)
    }

    /** Drop cached kps for any track id no longer present in this frame's result. */
    private fun pruneTrackKps(tracked: List<TrackedBox>) {
        if (trackKps.isEmpty()) return
        val live = HashSet<Long>(tracked.size)
        for (t in tracked) live.add(t.trackingId)
        val it = trackKps.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in live) it.remove()
        }
    }

    /**
     * Cadence gate. ACTIVE runs SCRFD every [ACTIVE_SCRFD_INTERVAL_MS]; IDLE
     * throttles to one run per [IDLE_SCRFD_INTERVAL_MS].
     */
    private fun shouldRunScrfd(now: Long): Boolean = when (state) {
        State.ACTIVE -> now - lastScrfdMs >= ACTIVE_SCRFD_INTERVAL_MS
        State.IDLE -> now - lastScrfdMs >= IDLE_SCRFD_INTERVAL_MS
    }

    /** Allocate (or reuse) the ARGB int buffer + Bitmap for a w x h frame. */
    private fun ensureBuffers(w: Int, h: Int): IntArray {
        var buf = rgbBuf
        if (buf == null || bufW != w || bufH != h) {
            buf = IntArray(w * h)
            rgbBuf = buf
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bufW = w
            bufH = h
        }
        return buf
    }

    /**
     * Bulk-copy the three YUV planes into JVM byte[] (one bounds-checked JNI call
     * per plane, not per pixel) and cache their strides. Cheap (~1-2ms). The
     * subsequent conversion/sampling indexes only these arrays.
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

    /** Single-pixel YUV->packed 0xRRGGBB from the current plane snapshot. */
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
     * Only run on detection frames at the throttled cadence -- SCRFD resizes its
     * input internally, so a 1/ds frame is sufficient and ds^2 cheaper to build.
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
        // Detection downscale factor: SCRFD runs on a 1/DET_DS frame (it resizes
        // internally), so conversion + setPixels cost drops DET_DS^2. Boxes +
        // keypoints are scaled back up by DET_DS for full-res ROI placement.
        private const val DET_DS = 2
        private const val IDLE_SCRFD_INTERVAL_MS = 1000L
        private const val ACTIVE_SCRFD_INTERVAL_MS = 500L  // ~2fps detection;
        // each detection frame costs ~350ms (full-frame YUV->ARGB + setPixels +
        // SCRFD), and blocks the single in-flight slot. Spacing detection out lets
        // the cheap ROI-only frames (~5-10ms each) flow between for a high sample
        // rate. Faces move slowly relative to the sample cadence, so ~2.5fps box
        // refresh is plenty to keep the forehead ROI placed.
        private const val ACTIVE_LINGER_MS = 3000L
    }
}
