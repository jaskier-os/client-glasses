package com.repository.glasses.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Per-frame driver for the silent rPPG (camera heart-rate) pipeline. Consumes the
 * YUV_420_888 frames delivered by [CameraSession.startRppgStream] (on the camera
 * session's dedicated rPPG worker thread, one frame in flight at a time) and, per
 * processed frame:
 *
 *   YUV_420_888 -> NV21 -> JPEG -> [ScrfdFaceDetector.detectFull]
 *     -> [FaceTracker.update] (stable ids)
 *     -> [RoiSampler.sampleForehead] over the same frame (JPEG decoded once to a
 *        Bitmap, read via getPixel) for each tracked face
 *     -> emit a [Sample] {trackingId, r, g, b, pixelCount, tMs} per kept face.
 *
 * Cadence: the YUV stream runs continuously, but SCRFD execution is gated to keep
 * the NPU/CPU cost bounded.
 *   IDLE   -> run SCRFD ~once/sec while no face is present.
 *   ACTIVE -> run SCRFD every frame once any face is seen; after the last face
 *             disappears stay ACTIVE for [ACTIVE_LINGER_MS] then fall back to IDLE.
 * Frames between cadence ticks still arrive (and are still closed) but skip SCRFD.
 *
 * The callback owns NO file IO; it just emits samples. The probe harness in
 * [CaptureService] collects them. The [onYuvFrame] callback MUST close the Image
 * it receives (this class always closes it in a finally).
 *
 * Single-threaded by construction: [CameraSession] guarantees one frame in flight,
 * so the tracker (which is not thread-safe) is only ever touched here.
 */
class RppgPipeline(
    private val context: Context,
    private val onSample: (Sample) -> Unit,
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

    private enum class State { IDLE, ACTIVE }
    private var state = State.IDLE
    private var lastScrfdMs = 0L
    private var lastFaceMs = 0L

    // Throwaway counters for the probe to log stream vs processed fps.
    @Volatile var framesSeen = 0L; private set
    @Volatile var framesProcessed = 0L; private set
    @Volatile var facesSeen = 0L; private set

    /** Reset counters + state for a fresh probe run. */
    fun reset() {
        framesSeen = 0L
        framesProcessed = 0L
        facesSeen = 0L
        state = State.IDLE
        lastScrfdMs = 0L
        lastFaceMs = 0L
    }

    /**
     * Process one YUV_420_888 frame. Always closes [image]. Safe to pass as the
     * [CameraSession.startRppgStream] callback.
     */
    fun onYuvFrame(image: Image) {
        framesSeen++
        val now = SystemClock.elapsedRealtime()
        try {
            if (!shouldRunScrfd(now)) return
            lastScrfdMs = now

            val w = image.width
            val h = image.height
            val jpeg = yuvToJpeg(image, w, h) ?: return
            framesProcessed++

            val det = ScrfdFaceDetector.shared(context) ?: return
            val faces = det.detectFull(jpeg)

            if (faces.isEmpty()) {
                // ACTIVE lingers for ACTIVE_LINGER_MS after the last face so a
                // brief miss does not drop back to the slow IDLE cadence.
                if (state == State.ACTIVE && now - lastFaceMs > ACTIVE_LINGER_MS) {
                    state = State.IDLE
                }
                return
            }

            state = State.ACTIVE
            lastFaceMs = now
            facesSeen += faces.size

            val boxes = faces.map { TrackBox(it.x0, it.y0, it.x1, it.y1) }
            val tracked = tracker.update(boxes)

            // detectFull and tracker.update both preserve input order, so tracked[i]
            // corresponds to faces[i] -- a direct index map, no IoU back-map needed.
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            try {
                val rgb = RoiSampler.RgbImage { x, y -> bmp.getPixel(x, y) and 0xFFFFFF }
                for (i in faces.indices) {
                    val sample = RoiSampler.sampleForehead(rgb, w, h, faces[i].kps) ?: continue
                    onSample(
                        Sample(
                            tMs = now,
                            trackingId = tracked[i].trackingId,
                            r = sample.r,
                            g = sample.g,
                            b = sample.b,
                            pixelCount = sample.pixelCount,
                        )
                    )
                }
            } finally {
                bmp.recycle()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "rppg frame processing threw: ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    /**
     * Cadence gate. ACTIVE runs SCRFD every frame; IDLE throttles to one run per
     * [IDLE_SCRFD_INTERVAL_MS].
     */
    private fun shouldRunScrfd(now: Long): Boolean = when (state) {
        State.ACTIVE -> true
        State.IDLE -> now - lastScrfdMs >= IDLE_SCRFD_INTERVAL_MS
    }

    /**
     * YUV_420_888 -> NV21 -> JPEG. NV21 is Y plane followed by interleaved V,U.
     * Handles arbitrary row/pixel strides from the HAL. Returns null on failure.
     */
    private fun yuvToJpeg(image: Image, w: Int, h: Int): ByteArray? {
        return try {
            val nv21 = yuv420ToNv21(image, w, h)
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
            out.toByteArray()
        } catch (e: Throwable) {
            Log.w(TAG, "yuvToJpeg failed: ${e.message}")
            null
        }
    }

    private fun yuv420ToNv21(image: Image, w: Int, h: Int): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val nv21 = ByteArray(w * h * 3 / 2)

        // Y plane (respect rowStride; pixelStride is 1 for Y).
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        if (yRowStride == w) {
            yBuf.get(nv21, 0, w * h)
            pos = w * h
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until h) {
                yBuf.position(r * yRowStride)
                yBuf.get(row, 0, minOf(yRowStride, row.size))
                System.arraycopy(row, 0, nv21, pos, w)
                pos += w
            }
        }

        // Interleaved VU for NV21. Chroma is w/2 x h/2.
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride
        val cw = w / 2
        val ch = h / 2
        for (r in 0 until ch) {
            var uIdx = r * uRowStride
            var vIdx = r * vRowStride
            for (c in 0 until cw) {
                nv21[pos++] = vBuf.get(vIdx)
                nv21[pos++] = uBuf.get(uIdx)
                uIdx += uPixStride
                vIdx += vPixStride
            }
        }
        return nv21
    }

    companion object {
        private const val TAG = "Cap:Rppg"
        private const val JPEG_QUALITY = 85
        private const val IDLE_SCRFD_INTERVAL_MS = 1000L
        private const val ACTIVE_LINGER_MS = 3000L
    }
}
