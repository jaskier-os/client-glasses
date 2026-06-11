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
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * ML Kit face detection + crop/thumbnail pipeline for ReID. Frames are no longer self-owned via
 * Camera2 -- they are delivered as JPEG byte[] over AIDL from the capture process (see
 * CaptureBridge.captureReidFrame, delivered via Listener.onFrame). This class holds ONLY the
 * detection/encode logic; it never opens a camera.
 */
class ReidFrameConsumer {

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

    // Single worker thread for the heavy decode/crop/encode work AND the ML Kit detection.
    // ML Kit's Task is awaited synchronously on this thread (see processFrame), so a frame's
    // decoded bitmap stays alive for the entire detection + crop/encode and is recycled on the
    // SAME thread that produced and read it. This is the linchpin that closes the
    // "invalid/free'd bitmap" SIGABRT: there is never an outstanding async ML Kit task aliasing a
    // bitmap that another thread could recycle out from under it.
    private var worker: java.util.concurrent.ExecutorService? = null

    private var faceDetector: com.google.mlkit.vision.face.FaceDetector? = null

    private fun getOrCreateDetector(): com.google.mlkit.vision.face.FaceDetector? {
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

    fun start() {
        Log.d(TAG, "event=start")
        stop()
        frameCount = 0
        startTimeMs = SystemClock.elapsedRealtime()
        lastProcessingDoneMs = 0L
        processingFrame = false
        lastFaceDetected = false
        if (getOrCreateDetector() == null) {
            callback?.onStatusChanged("ERROR: ML Kit unavailable")
            return
        }
        worker = Executors.newSingleThreadExecutor()
        running = true
        callback?.onStatusChanged("SCANNING")
    }

    fun stop() {
        Log.d(TAG, "event=stop frames=$frameCount")
        // Flip running first so any in-flight worker body bails before touching the detector or
        // emitting callbacks. The worker recycles its own in-flight bitmap in its finally, so we
        // must let it drain before closing the detector -- closing the detector while it is still
        // reading an InputImage backed by a live bitmap is exactly the kind of cross-thread free
        // that triggers the native SIGABRT.
        running = false
        val w = worker
        worker = null
        if (w != null) {
            // shutdown (not shutdownNow): do NOT interrupt the worker mid-detection. Let the
            // current frame finish its synchronous Tasks.await + recycle on its own thread.
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
        // Worker is now drained -- no thread can be inside detector.process anymore.
        faceDetector?.close()
        faceDetector = null
        processingFrame = false
    }

    private fun log(msg: String) {
        remoteLog?.invoke("[$TAG] $msg")
    }

    /**
     * Entry point invoked per AIDL-delivered frame. Applies the same cadence gate
     * (1500ms with a face, 5000ms without) and processing guard the old Camera2 ImageReader
     * used, then runs the ML Kit pipeline on the worker thread. rotationDeg is the capture-side
     * sensor orientation -- never hardcoded.
     */
    fun onFrame(jpeg: ByteArray, width: Int, height: Int, rotationDeg: Int) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        // Single-frame-in-flight guard. NO time-based force-reset: the old escape hatch cleared
        // processingFrame WITHOUT recycling the in-flight bitmap or accounting for the outstanding
        // ML Kit task, letting a new frame decode while a stale detection still aliased the old
        // bitmap -> the "invalid/free'd bitmap" SIGABRT when the stale callback recycled it.
        // It cannot wedge: the frame source (CaptureBridge fan-out -> ReidController -> onFrame)
        // delivers ~1 frame/1.2s and we further self-throttle (1500ms with a face / 5000ms
        // without), while the worker processes each frame synchronously (Tasks.await) on a single
        // thread. The guard is cleared in the worker's finally for EVERY path, so the only way it
        // could stay set is ML Kit's process() never returning -- which we'd rather surface than
        // paper over by freeing a bitmap another thread may still read.
        if (processingFrame) return
        val interval = if (lastFaceDetected) CAPTURE_INTERVAL_FACE_MS else CAPTURE_INTERVAL_NO_FACE_MS
        if (now - lastProcessingDoneMs < interval) return

        processingFrame = true
        // Copy the buffer out of the binder transaction before handing to the worker.
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
     * One-shot detection on a single frame, independent of [start]/[stop], the cadence
     * gate, and the periodic loop. Used when a func-button photo is taken so that SAME
     * captured frame also drives exactly ONE ReID identify attempt, regardless of whether
     * ReID mode is enabled.
     *
     * Runs the full ML Kit detect + crop/encode pipeline SYNCHRONOUSLY on the calling
     * thread (caller must supply its own worker thread -- never the binder/main thread)
     * and routes any detected faces through the same [callback] the live loop uses, so the
     * identify path (onFacesDetected -> btSender.sendFace) is identical. Creates and closes
     * a throwaway detector so it cannot interfere with a running loop's detector/bitmap
     * lifecycle. Does NOT start the loop, touch [running], or change cadence state.
     */
    fun detectOnce(jpeg: ByteArray, rotationDeg: Int) {
        var bitmap: Bitmap? = null
        var detector: com.google.mlkit.vision.face.FaceDetector? = null
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

            val activeDetector = try {
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setMinFaceSize(0.1f)
                    .enableTracking()
                    .build()
                FaceDetection.getClient(options)
            } catch (e: Exception) {
                log("detectOnce: ML Kit init failed: ${e.message}")
                return
            }
            detector = activeDetector

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces: List<Face> = try {
                Tasks.await(activeDetector.process(inputImage))
            } catch (e: Exception) {
                log("detectOnce: face detection failed: ${e.message}")
                return
            }

            if (faces.isEmpty()) {
                log("detectOnce: no faces in photo frame")
                callback?.onNoFaces(0, 0.0)
                return
            }

            val detectedFaces = mutableListOf<DetectedFace>()
            for (face in faces) {
                val tid = face.trackingId ?: continue
                val box = face.boundingBox
                val area = box.width() * box.height()
                val webpB64 = cropAndCompressJpeg(bitmap, box) ?: continue
                val thumb = generateThumbnail(bitmap, box)
                detectedFaces.add(DetectedFace(
                    trackingId = tid,
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
            try { detector?.close() } catch (_: Exception) {}
        }
    }

    private fun finishProcessing(facesDetected: Boolean) {
        lastFaceDetected = facesDetected
        lastProcessingDoneMs = SystemClock.elapsedRealtime()
        processingFrame = false
    }

    /**
     * Runs on the single worker thread. Decodes the JPEG into the one in-flight bitmap, pre-rotates
     * it, runs ML Kit SYNCHRONOUSLY (Tasks.await) on this same thread so the bitmap is provably
     * alive for the entire detection, then crops/encodes and recycles the bitmap exactly once in a
     * finally -- all on this thread. Nothing else ever touches this bitmap, so it can never be
     * recycled while ML Kit or a crop op still references it.
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
            // Pre-rotate bitmap like rokid-reid does (ML Kit handles rotation=0 more reliably).
            bitmap = if (rotationDeg != 0) {
                val matrix = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                // decoded is a distinct, intermediate bitmap that ML Kit never sees; safe to recycle now.
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }

            val detector = getOrCreateDetector()
            if (detector == null) {
                log("Face detector unavailable (ML Kit init failed)")
                callback?.onStatusChanged("ERROR: ML Kit unavailable")
                return
            }
            if (!running) return

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            // Block this worker thread until ML Kit is completely done reading the InputImage.
            // After await returns, no ML Kit internals hold a reference to the bitmap, so the
            // recycle in the finally below cannot free a bitmap that is still being read.
            val faces: List<Face> = try {
                Tasks.await(detector.process(inputImage))
            } catch (e: Exception) {
                log("Face detection failed: ${e.message}")
                return
            }

            if (!running) return

            frameCount++
            val elapsed = (SystemClock.elapsedRealtime() - startTimeMs) / 1000.0
            val fps = if (elapsed > 0) frameCount / elapsed else 0.0

            if (faces.isEmpty()) {
                facesDetected = false
                callback?.onNoFaces(frameCount, fps)
                return
            }

            val detectedFaces = mutableListOf<DetectedFace>()
            for (face in faces) {
                val tid = face.trackingId ?: continue
                val box = face.boundingBox
                val area = box.width() * box.height()

                // cropAndCompressJpeg / generateThumbnail READ the in-flight bitmap; they run here,
                // before the finally recycles it.
                val webpB64 = cropAndCompressJpeg(bitmap, box) ?: continue
                val thumb = generateThumbnail(bitmap, box)

                detectedFaces.add(DetectedFace(
                    trackingId = tid,
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
            // Recycle exactly once, on this thread, after ML Kit and all crops are done with it.
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            finishProcessing(facesDetected)
        }
    }

    private fun cropAndCompressJpeg(bitmap: Bitmap, box: Rect): String? {
        // 100% padding gives SCRFD enough context to detect the face in the crop
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

        // No scaling -- send at natural resolution from the subsampled frame
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
