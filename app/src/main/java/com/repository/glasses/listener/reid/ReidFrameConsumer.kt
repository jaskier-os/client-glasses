package com.repository.glasses.listener.reid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.repository.glasses.listener.capture.CaptureBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Face detection + crop/thumbnail pipeline for ReID. Frames arrive as JPEG byte[]
 * over AIDL from the capture process (CaptureBridge.captureReidFrame ->
 * Listener.onFrame); this class holds ONLY the detection/encode logic and never
 * opens a camera.
 *
 * ## Detector: SCRFD-10G on the Hexagon V73 NPU (default), ML Kit CPU fallback
 * The live detector is SCRFD-10G int8 running on the HTP NPU (~11 ms/frame),
 * which replaced the ML Kit ACCURATE CPU detector (~3200 ms/frame, ~289x slower).
 * Detection runs in the CAPTURE process (via [CaptureBridge.detectFaces] over
 * AIDL), NOT here: the listener is a privileged /system/priv-app whose linker
 * namespace cannot dlopen the vendor public lib libcdsprpc.so to reach the CDSP,
 * but the capture APK (a normal /data/app install) can -- same constraint that
 * put the SplitterNet QNN denoiser in the capture module. The capture side
 * returns face boxes in JPEG pixel coordinates; this class still decodes the same
 * JPEG to produce the crop bitmap, so the crop -> BT -> server recognition
 * contract (face-crop bitmap + bounding box semantics) is IDENTICAL to ML Kit.
 *
 * If the NPU is unavailable (capture binder unreachable, or HTP init failed in the
 * capture process) [CaptureBridge.detectFaces] returns null, and this class falls
 * back to the on-device ML Kit CPU detector so ReID never hard-fails.
 *
 * The whole decode + detect + crop/encode runs SYNCHRONOUSLY on the single worker
 * thread that decoded the in-flight bitmap (the AIDL detectFaces call blocks; ML
 * Kit via Tasks.await; both on this thread), so the decoded bitmap stays alive for
 * the entire detect + crop and is recycled on the SAME thread -- the linchpin that
 * closes the historical "invalid/free'd bitmap" SIGABRT.
 */
class ReidFrameConsumer(private val captureBridge: CaptureBridge? = null) {

    companion object {
        private const val TAG = "App:ReidFrames"
        private const val LOG_EVERY_N_FRAMES = 10
        private const val CAPTURE_INTERVAL_FACE_MS = 1500L
        private const val CAPTURE_INTERVAL_NO_FACE_MS = 5000L
        private const val THUMBNAIL_HEIGHT = 100
        private const val THUMBNAIL_QUALITY = 90
    }

    data class DetectedFace(
        val trackingId: Int,
        val webpBase64: String,
        val thumbnailBase64: String,
        val thumbnailWidth: Int,
        val faceArea: Int
    )

    interface Callback {
        fun onFacesDetected(faces: List<DetectedFace>, frameCount: Int, fps: Double)
        fun onNoFaces(frameCount: Int, fps: Double)
        fun onStatusChanged(status: String)
    }

    var callback: Callback? = null
    var remoteLog: ((String) -> Unit)? = null

    @Volatile private var running = false
    private var frameCount = 0
    private var startTimeMs = 0L
    private var lastProcessingDoneMs = 0L
    @Volatile private var processingFrame = false
    @Volatile private var lastFaceDetected = false

    private var worker: java.util.concurrent.ExecutorService? = null

    // ML Kit CPU fallback detector (only built + used if the NPU path is unavailable).
    private var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null
    // Latches once the NPU path has been observed unavailable, so we stop paying the
    // binder round-trip every frame and go straight to ML Kit for the session.
    @Volatile private var npuUnavailable = false

    private fun getOrCreateMlKitDetector(): com.google.mlkit.vision.face.FaceDetector? {
        faceDetector?.let { return it }
        return try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.1f)
                .enableTracking()
                .build()
            FaceDetection.getClient(options).also { faceDetector = it }
        } catch (e: Exception) {
            log("ML Kit FaceDetection init failed: ${e.message}")
            null
        }
    }

    /** A detected box + a tracking id (synthesized for the NPU path). */
    private data class DetBox(val trackingId: Int, val box: Rect)

    /** Stable-ish tracking id from box center, quantized to a 32px grid so a face
     *  that barely moves between frames keeps the same id (the downstream
     *  pending/verified maps key on trackingId; SCRFD has no tracker). */
    private fun synthTrackingId(box: Rect): Int {
        val cx = (box.centerX() / 32)
        val cy = (box.centerY() / 32)
        return (cx and 0xFFFF) or ((cy and 0xFFFF) shl 16)
    }

    /**
     * Detect faces. Prefers the SCRFD NPU path in the capture process (passing the
     * raw [jpeg]); falls back to ML Kit CPU on [bitmap] if the NPU is unavailable.
     * [bitmap] must be the decode of [jpeg] with the SAME orientation the capture
     * side sees (rotationDeg=0 for live ReID frames and the upright func-button
     * photo), so the JPEG-space boxes returned by the NPU align with [bitmap].
     * Returns boxes in [bitmap] pixel coordinates.
     */
    private fun detectFaces(jpeg: ByteArray, bitmap: Bitmap, rotationDeg: Int, logLatency: Boolean): List<DetBox> {
        // NPU path: only valid when the bitmap orientation matches the JPEG the
        // capture side detects on (rotationDeg == 0). For a rotated frame we cannot
        // map JPEG-space boxes onto the rotated bitmap, so use ML Kit there.
        if (!npuUnavailable && rotationDeg == 0 && captureBridge != null) {
            val t0 = SystemClock.elapsedRealtime()
            val flat = captureBridge.detectFaces(jpeg)
            val ms = SystemClock.elapsedRealtime() - t0
            if (flat != null) {
                val n = if (flat.isNotEmpty()) flat[0] else 0
                if (logLatency) log("SCRFD detect: $n face(s) in ${ms}ms (NPU, capture proc)")
                val boxes = ArrayList<DetBox>(n)
                for (i in 0 until n) {
                    val o = 1 + i * 4
                    if (o + 4 > flat.size) break
                    val r = Rect(flat[o], flat[o + 1], flat[o + 2], flat[o + 3])
                    boxes.add(DetBox(synthTrackingId(r), r))
                }
                return boxes
            }
            // null => capture binder unreachable / NPU init failed -> latch to ML Kit.
            npuUnavailable = true
            log("SCRFD NPU unavailable -> ML Kit CPU fallback for this session")
        }
        // ML Kit fallback.
        val detector = getOrCreateMlKitDetector() ?: return emptyList()
        val t0 = SystemClock.elapsedRealtime()
        val faces: List<Face> = try {
            Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            log("ML Kit detection failed: ${e.message}")
            return emptyList()
        }
        val ms = SystemClock.elapsedRealtime() - t0
        if (logLatency) log("ML Kit detect: ${faces.size} face(s) in ${ms}ms (CPU)")
        return faces.mapNotNull { f -> f.trackingId?.let { DetBox(it, f.boundingBox) } }
    }

    fun start() {
        Log.d(TAG, "event=start")
        stop()
        frameCount = 0
        startTimeMs = SystemClock.elapsedRealtime()
        lastProcessingDoneMs = 0L
        processingFrame = false
        lastFaceDetected = false
        npuUnavailable = false
        // The NPU detector lives in the capture process and inits lazily on the
        // first detectFaces call; nothing to init here. Pre-build ML Kit only as a
        // safety net (cheap; the client is created lazily anyway).
        if (captureBridge == null && getOrCreateMlKitDetector() == null) {
            callback?.onStatusChanged("ERROR: no face detector")
            return
        }
        worker = Executors.newSingleThreadExecutor()
        running = true
        callback?.onStatusChanged("SCANNING")
    }

    fun stop() {
        Log.d(TAG, "event=stop frames=$frameCount")
        running = false
        val w = worker
        worker = null
        if (w != null) {
            w.shutdown()
            try {
                if (!w.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log("worker did not drain within 5s on stop; forcing shutdown")
                    w.shutdownNow()
                    w.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (e: InterruptedException) {
                w.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        faceDetector?.close()
        faceDetector = null
        processingFrame = false
    }

    private fun log(msg: String) {
        remoteLog?.invoke("[$TAG] $msg")
    }

    fun onFrame(jpeg: ByteArray, width: Int, height: Int, rotationDeg: Int) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (processingFrame) return
        val interval = if (lastFaceDetected) CAPTURE_INTERVAL_FACE_MS else CAPTURE_INTERVAL_NO_FACE_MS
        if (now - lastProcessingDoneMs < interval) return

        processingFrame = true
        val data = jpeg.copyOf()
        val w = worker
        if (w == null || w.isShutdown) {
            processingFrame = false
            return
        }
        try {
            w.execute {
                if (frameCount % LOG_EVERY_N_FRAMES == 0) {
                    Log.v(TAG, "event=reid_frame n=$frameCount bytes=${data.size} face=$lastFaceDetected rot=$rotationDeg")
                }
                processFrame(data, rotationDeg)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            processingFrame = false
        }
    }

    /**
     * One-shot detection on a single frame, independent of [start]/[stop], the
     * cadence gate, and the periodic loop. Used when a func-button photo is taken
     * so that SAME captured frame drives exactly ONE ReID identify attempt.
     * Runs the full detect + crop/encode pipeline SYNCHRONOUSLY on the calling
     * thread. Uses the SCRFD NPU detector (or ML Kit fallback) -- identical output.
     */
    fun detectOnce(jpeg: ByteArray, rotationDeg: Int) {
        var bitmap: Bitmap? = null
        try {
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            if (decoded == null) {
                log("detectOnce: failed to decode JPEG frame")
                return
            }
            bitmap = if (rotationDeg != 0) {
                val matrix = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }

            val boxes = detectFaces(jpeg, bitmap, rotationDeg, logLatency = true)
            if (boxes.isEmpty()) {
                log("detectOnce: no faces in photo frame")
                callback?.onNoFaces(0, 0.0)
                return
            }

            val detectedFaces = mutableListOf<DetectedFace>()
            for (b in boxes) {
                val box = b.box
                val area = box.width() * box.height()
                val webpB64 = cropAndCompressJpeg(bitmap, box) ?: continue
                val thumb = generateThumbnail(bitmap, box)
                detectedFaces.add(DetectedFace(
                    trackingId = b.trackingId,
                    webpBase64 = webpB64,
                    thumbnailBase64 = thumb?.first ?: "",
                    thumbnailWidth = thumb?.second ?: 0,
                    faceArea = area
                ))
            }
            if (detectedFaces.isEmpty()) {
                callback?.onNoFaces(0, 0.0)
                return
            }
            log("detectOnce: ${detectedFaces.size} face(s) in photo frame -> identify")
            callback?.onFacesDetected(detectedFaces, 0, 0.0)
        } catch (e: Throwable) {
            log("detectOnce: processing failed: ${e.message}")
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun finishProcessing(facesDetected: Boolean) {
        lastFaceDetected = facesDetected
        lastProcessingDoneMs = SystemClock.elapsedRealtime()
        processingFrame = false
    }

    /**
     * Runs on the single worker thread. Decodes the JPEG into the one in-flight
     * bitmap, pre-rotates it, runs the detector SYNCHRONOUSLY on this same thread
     * so the bitmap is provably alive for the entire detection, then crops/encodes
     * and recycles the bitmap exactly once in a finally -- all on this thread.
     */
    private fun processFrame(jpegData: ByteArray, rotationDeg: Int) {
        if (!running) {
            finishProcessing(lastFaceDetected)
            return
        }
        var bitmap: Bitmap? = null
        var facesDetected = lastFaceDetected
        try {
            val decoded = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            if (decoded == null) {
                log("Failed to decode JPEG frame")
                return
            }
            bitmap = if (rotationDeg != 0) {
                val matrix = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }

            if (!running) return

            val logLatency = frameCount % LOG_EVERY_N_FRAMES == 0
            val boxes = detectFaces(jpegData, bitmap, rotationDeg, logLatency)

            if (!running) return

            frameCount++
            val elapsed = (SystemClock.elapsedRealtime() - startTimeMs) / 1000.0
            val fps = if (elapsed > 0) frameCount / elapsed else 0.0

            if (boxes.isEmpty()) {
                facesDetected = false
                callback?.onNoFaces(frameCount, fps)
                return
            }

            val detectedFaces = mutableListOf<DetectedFace>()
            for (b in boxes) {
                val box = b.box
                val area = box.width() * box.height()
                val webpB64 = cropAndCompressJpeg(bitmap, box) ?: continue
                val thumb = generateThumbnail(bitmap, box)
                detectedFaces.add(DetectedFace(
                    trackingId = b.trackingId,
                    webpBase64 = webpB64,
                    thumbnailBase64 = thumb?.first ?: "",
                    thumbnailWidth = thumb?.second ?: 0,
                    faceArea = area
                ))
            }

            facesDetected = true
            callback?.onFacesDetected(detectedFaces, frameCount, fps)
        } catch (e: Throwable) {
            log("Frame processing failed: ${e.message}")
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            finishProcessing(facesDetected)
        }
    }

    private fun cropAndCompressJpeg(bitmap: Bitmap, box: Rect): String? {
        // 100% padding gives the server-side recognizer enough context.
        val padX = (box.width() * 1.0f).toInt()
        val padY = (box.height() * 1.0f).toInt()
        val cropRect = Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(bitmap.width),
            (box.bottom + padY).coerceAtMost(bitmap.height)
        )
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return null

        val crop = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val stream = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        crop.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun generateThumbnail(bitmap: Bitmap, box: Rect): Pair<String, Int>? {
        val padX = (box.width() * 0.15f).toInt()
        val padY = (box.height() * 0.15f).toInt()
        val thumbRect = Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(bitmap.width),
            (box.bottom + padY).coerceAtMost(bitmap.height)
        )
        if (thumbRect.width() <= 0 || thumbRect.height() <= 0) return null
        val crop = Bitmap.createBitmap(bitmap, thumbRect.left, thumbRect.top, thumbRect.width(), thumbRect.height())
        val scale = THUMBNAIL_HEIGHT.toFloat() / crop.height
        val thumbW = (crop.width * scale).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(crop, thumbW, THUMBNAIL_HEIGHT, true)
        crop.recycle()
        val stream = ByteArrayOutputStream()
        thumb.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, stream)
        thumb.recycle()
        val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return Pair(b64, thumbW)
    }
}
