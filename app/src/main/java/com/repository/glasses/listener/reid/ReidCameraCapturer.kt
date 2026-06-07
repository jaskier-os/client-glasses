package com.repository.glasses.listener.reid

import android.Manifest
import android.os.SystemClock
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream

/**
 * Camera2 continuous capture + ML Kit face detection pipeline.
 * Ported from rokid-reid CameraStreamModule.kt, adapted for native Android (no React Native).
 */
class ReidCameraCapturer(private val context: Context) {

    companion object {
        private const val TAG = "App:CamReid"
        private const val LOG_EVERY_N_FRAMES = 10
        private const val CAPTURE_INTERVAL_FACE_MS = 1500L
        private const val CAPTURE_INTERVAL_NO_FACE_MS = 5000L
        private const val FACE_PADDING = 0.3f
        private const val WEBP_QUALITY = 50
        private const val MAX_RETRY_ATTEMPTS = 20
        private const val MAX_EXPOSURE_NS = 8_000_000L
        private const val FACE_CROP_HEIGHT = 480
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
    /** Wired by the owner (ListenerService) to setActiveSession("reid_streaming") for
     *  the duration the camera is open, so the RFCOMM idle watchdog can't tear down
     *  the connection while ReID frames are flowing. */
    var onActiveSessionEnter: (() -> Unit)? = null
    var onActiveSessionExit: (() -> Unit)? = null
    @Volatile private var sessionHeld = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraHandler: Handler? = null
    private var cameraThread: HandlerThread? = null

    private var isStreaming = false
    private var frameCount = 0
    private var startTimeMs = 0L
    private var lastProcessingDoneMs = 0L
    @Volatile private var processingFrame = false
    @Volatile private var lastFaceDetected = false
    private var sensorRotation = 0
    private var retryCount = 0
    private var imageWidth = 3840
    private var imageHeight = 2160
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var processingStartMs = 0L

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
        retryCount = 0
        if (!sessionHeld) {
            sessionHeld = true
            try { onActiveSessionEnter?.invoke() } catch (_: Throwable) {}
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            log("CAMERA permission not granted")
            callback?.onStatusChanged("NO CAMERA PERMISSION")
            return
        }

        if (getOrCreateDetector() == null) {
            callback?.onStatusChanged("ERROR: ML Kit unavailable")
            return
        }

        startCameraThread()
        openCamera()
    }

    fun stop() {
        Log.d(TAG, "event=stop frames=$frameCount")
        isStreaming = false
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        faceDetector?.close()
        faceDetector = null
        stopCameraThread()
        if (sessionHeld) {
            sessionHeld = false
            try { onActiveSessionExit?.invoke() } catch (_: Throwable) {}
        }
    }

    private fun log(msg: String) {
        remoteLog?.invoke("[$TAG] $msg")
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("ReidCameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join() } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }

    private fun getBestResolution(characteristics: CameraCharacteristics): Size {
        try {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val sizes = map.getOutputSizes(ImageFormat.JPEG)
                if (sizes != null && sizes.isNotEmpty()) {
                    val best = sizes.maxByOrNull { it.width * it.height }
                    if (best != null) {
                        log("Best JPEG resolution: ${best.width}x${best.height}")
                        return best
                    }
                }
            }
        } catch (e: Exception) {
            log("Failed to query resolutions: ${e.message}")
        }
        return Size(3840, 2160)
    }

    private fun openCamera() {
        log("Opening camera via Camera2 API")
        callback?.onStatusChanged("SCANNING")

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull()
        if (cameraId == null) {
            log("No cameras found")
            callback?.onStatusChanged("ERROR: No cameras found")
            return
        }

        val chars = manager.getCameraCharacteristics(cameraId)
        cameraCharacteristics = chars
        sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val bestSize = getBestResolution(chars)
        imageWidth = bestSize.width
        imageHeight = bestSize.height
        log("Camera: $cameraId, sensor=$sensorRotation, res=${imageWidth}x${imageHeight}")

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    log("Camera opened")
                    cameraDevice = camera
                    retryCount = 0
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    log("Camera disconnected, retrying...")
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                    retryCameraOpen()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    log("Camera error: $error, retrying...")
                    camera.close()
                    cameraDevice = null
                    isStreaming = false
                    retryCameraOpen()
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            log("Camera permission denied: ${e.message}")
            callback?.onStatusChanged("ERROR: Camera permission denied")
        }
    }

    private fun retryCameraOpen() {
        retryCount++
        if (retryCount > MAX_RETRY_ATTEMPTS) {
            log("Camera retry limit reached ($MAX_RETRY_ATTEMPTS)")
            callback?.onStatusChanged("ERROR: Camera failed after $MAX_RETRY_ATTEMPTS retries")
            return
        }

        stop()
        startCameraThread()

        val delayMs = (1000L * (1 shl (retryCount - 1))).coerceAtMost(5000L)
        log("Camera retrying in ${delayMs}ms (attempt $retryCount/$MAX_RETRY_ATTEMPTS)")
        callback?.onStatusChanged("RETRYING ($retryCount/$MAX_RETRY_ATTEMPTS)")

        cameraHandler?.postDelayed({
            if (retryCount > 0) openCamera()
        }, delayMs)
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return

        val swapDimensions = sensorRotation == 90 || sensorRotation == 270
        val readerWidth = if (swapDimensions) imageHeight else imageWidth
        val readerHeight = if (swapDimensions) imageWidth else imageHeight
        log("ImageReader: ${readerWidth}x${readerHeight}")
        imageReader = ImageReader.newInstance(readerWidth, readerHeight, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val now = SystemClock.elapsedRealtime()
                if (processingFrame) {
                    if (processingStartMs > 0 && now - processingStartMs > 15000) {
                        log("processingFrame stuck for 15s, force-resetting")
                        processingFrame = false
                    } else {
                        reader.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }
                }
                val interval = if (lastFaceDetected) CAPTURE_INTERVAL_FACE_MS else CAPTURE_INTERVAL_NO_FACE_MS
                if (now - lastProcessingDoneMs < interval) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processingFrame = true
                processingStartMs = now
                try {
                    val buffer = image.planes[0].buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    if (frameCount % LOG_EVERY_N_FRAMES == 0) {
                        Log.v(TAG, "event=reid_frame n=$frameCount bytes=${data.size} face=$lastFaceDetected")
                    }
                    processFrame(data)
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        val surface = imageReader!!.surface

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    log("Capture session configured")
                    captureSession = session
                    isStreaming = true
                    callback?.onStatusChanged("SCANNING")

                    val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, MAX_EXPOSURE_NS)
                        val isoRange = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                        val iso = isoRange?.upper?.coerceAtMost(1600) ?: 800
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }

                    session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                    log("Repeating capture started")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    log("Capture session configuration failed")
                    callback?.onStatusChanged("ERROR: Capture session failed")
                }
            },
            cameraHandler
        )
    }

    private fun finishProcessing(facesDetected: Boolean) {
        lastFaceDetected = facesDetected
        lastProcessingDoneMs = SystemClock.elapsedRealtime()
        processingFrame = false
    }

    private fun processFrame(jpegData: ByteArray) {
        if (!isStreaming) {
            finishProcessing(lastFaceDetected)
            return
        }
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            if (bitmap == null) {
                log("Failed to decode JPEG frame")
                finishProcessing(lastFaceDetected)
                return
            }
            log("Decoded: ${bitmap.width}x${bitmap.height}, sensor=$sensorRotation")
            // Pre-rotate bitmap like rokid-reid does (ML Kit handles rotation=0 more reliably)
            val oriented = if (sensorRotation != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(sensorRotation.toFloat()) }
                val r = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                r
            } else {
                bitmap
            }
            try {
                detectFaces(oriented)
            } finally {
                if (!oriented.isRecycled) oriented.recycle()
            }
        } catch (e: Throwable) {
            log("Frame processing failed: ${e.message}")
            finishProcessing(lastFaceDetected)
        }
    }

    private fun detectFaces(bitmap: Bitmap) {
        val detector = getOrCreateDetector()
        if (detector == null) {
            log("Face detector unavailable (ML Kit init failed)")
            callback?.onStatusChanged("ERROR: ML Kit unavailable")
            bitmap.recycle()
            return
        }
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val handler = cameraHandler
        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                log("ML Kit result: ${faces.size} faces")
                val work = Runnable {
                    try {
                        frameCount++
                        val elapsed = (SystemClock.elapsedRealtime() - startTimeMs) / 1000.0
                        val fps = if (elapsed > 0) frameCount / elapsed else 0.0

                        if (faces.isEmpty()) {
                            callback?.onNoFaces(frameCount, fps)
                            bitmap.recycle()
                            finishProcessing(false)
                            return@Runnable
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

                        callback?.onFacesDetected(detectedFaces, frameCount, fps)
                        bitmap.recycle()
                        finishProcessing(true)
                    } catch (e: Throwable) {
                        log("Face processing crashed: ${e.message}")
                        try { bitmap.recycle() } catch (_: Throwable) {}
                        finishProcessing(lastFaceDetected)
                    }
                }
                if (handler != null) handler.post(work) else work.run()
            }
            .addOnFailureListener { e ->
                log("Face detection failed: ${e.message}")
                try { bitmap.recycle() } catch (_: Throwable) {}
                finishProcessing(lastFaceDetected)
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
