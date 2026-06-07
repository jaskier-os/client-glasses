package com.repository.glasses.listener.nightvision

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.widget.ImageView
import kotlin.math.max

class NightVisionPreview(private val context: Context) {

    var remoteLog: ((String) -> Unit)? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var sensorRotation = 0
    @Volatile
    var isRunning = false
        private set

    private val captureWidth = 640
    private val captureHeight = 480
    private val pixelCount = captureWidth * captureHeight

    // EMA accumulation -- proven to work on this sensor.
    // alpha=0.04 -> ~25 frame effective window -> sqrt(25)=5x SNR improvement
    // No black level subtraction -- in extreme dark the signal IS the low values
    private val alphaFixed = 10       // 0.04 * 256 = ~10
    private val oneMinusAlpha = 246   // 256 - 10

    // Gamma tone mapping LUT: replaces linear gain.
    // Gamma < 1.0 lifts dark values while preserving contrast across the full range.
    // Linear 20x gain clips everything above 12 to white, killing contrast.
    // Gamma 0.25 on 0-255: input 5->83, 10->103, 20->127, 50->166, 100->205, 200->244
    private val gamma = 0.25
    private val toneLut = IntArray(256).also { lut ->
        for (i in 0..255) {
            lut[i] = (255.0 * Math.pow(i / 255.0, gamma)).toInt().coerceIn(0, 255)
        }
    }

    private var accumBuffer: IntArray? = null
    private var outputPixels: IntArray? = null
    private var outputBitmap: Bitmap? = null
    @Volatile
    private var firstFrame = true

    // Rotation
    private var effectiveRotation = 0
    private var outWidth = 0
    private var outHeight = 0

    fun start(imageView: ImageView, onReady: () -> Unit) {
        if (isRunning) {
            remoteLog?.invoke("NightVision: Already running")
            onReady()
            return
        }

        cameraThread = HandlerThread("NightVisionPreview").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        openCamera(imageView, onReady)
    }

    private fun openCamera(imageView: ImageView, onReady: () -> Unit) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager)
        if (cameraId == null) {
            remoteLog?.invoke("NightVision: No camera found")
            onReady()
            return
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        sensorRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Log camera capabilities for RAW/ML feasibility check
        val hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val hwLevelStr = when (hwLevel) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($hwLevel)"
        }
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasRaw = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        val hasManual = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val capsList = caps?.joinToString(",") { it.toString() } ?: "none"
        val outputFormats = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.outputFormats?.joinToString(",") { it.toString() } ?: "none"
        val blStr = try {
            val blp = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            if (blp != null) "${blp.getOffsetForIndex(0, 0)},${blp.getOffsetForIndex(0, 1)},${blp.getOffsetForIndex(1, 0)},${blp.getOffsetForIndex(1, 1)}" else "null"
        } catch (_: Exception) { "error" }
        remoteLog?.invoke("NightVision: hwLevel=$hwLevelStr RAW=$hasRaw manual=$hasManual")
        remoteLog?.invoke("NightVision: caps=[$capsList] formats=[$outputFormats] blackLevel=[$blStr]")

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    remoteLog?.invoke("NightVision: Camera opened: $cameraId sensor=$sensorRotation")
                    cameraDevice = camera
                    startCapture(camera, imageView, characteristics, onReady)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    remoteLog?.invoke("NightVision: Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    remoteLog?.invoke("NightVision: Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                    onReady()
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            remoteLog?.invoke("NightVision: Camera permission denied: ${e.message}")
            onReady()
        } catch (e: Exception) {
            remoteLog?.invoke("NightVision: Failed to open camera: ${e.message}")
            onReady()
        }
    }

    private fun startCapture(
        camera: CameraDevice,
        imageView: ImageView,
        characteristics: CameraCharacteristics,
        onReady: () -> Unit
    ) {
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val maxFrameDuration = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)

        val maxExposure = exposureRange?.upper ?: 100_000_000L
        val maxIso = isoRange?.upper ?: 1600

        remoteLog?.invoke("NightVision: Exposure=${maxExposure / 1_000_000}ms ISO=$maxIso maxFrameDur=${(maxFrameDuration ?: 0) / 1_000_000}ms")
        remoteLog?.invoke("NightVision: EMA alpha=${alphaFixed}/256 (~${2 * 256 / alphaFixed} frame window)")

        // Rotation
        val displayRotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        effectiveRotation = (sensorRotation - displayDegrees + 180 + 360) % 360
        val swap = effectiveRotation == 90 || effectiveRotation == 270
        outWidth = if (swap) captureHeight else captureWidth
        outHeight = if (swap) captureWidth else captureHeight

        remoteLog?.invoke("NightVision: Output ${outWidth}x${outHeight} rotation=$effectiveRotation")

        // Allocate buffers
        accumBuffer = IntArray(pixelCount)
        outputPixels = IntArray(pixelCount)
        outputBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        firstFrame = true

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                if (!isRunning) {
                    reader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val accum = accumBuffer ?: return@setOnImageAvailableListener
                    val outPx = outputPixels ?: return@setOnImageAvailableListener
                    val bmp = outputBitmap ?: return@setOnImageAvailableListener

                    val yPlane = image.planes[0]
                    val yBuf = yPlane.buffer
                    val rowStride = yPlane.rowStride

                    val seed = firstFrame
                    if (seed) firstFrame = false

                    for (row in 0 until captureHeight) {
                        val rowOffset = row * rowStride
                        for (col in 0 until captureWidth) {
                            val i = row * captureWidth + col
                            val yVal = yBuf.get(rowOffset + col).toInt() and 0xFF

                            // EMA: no black level subtraction, keep all signal
                            if (seed) {
                                accum[i] = yVal
                            } else {
                                accum[i] = (alphaFixed * yVal + oneMinusAlpha * accum[i]) shr 8
                            }

                            // Rotation-aware output index
                            val outIdx = when (effectiveRotation) {
                                90 -> (captureWidth - 1 - col) * captureHeight + row
                                180 -> (captureHeight - 1 - row) * captureWidth + (captureWidth - 1 - col)
                                270 -> col * captureHeight + (captureHeight - 1 - row)
                                else -> i
                            }

                            // Gamma tone map: lifts darks while preserving contrast
                            val bright = toneLut[accum[i].coerceIn(0, 255)]
                            outPx[outIdx] = 0xFF000000.toInt() or (bright shl 8)
                        }
                    }

                    bmp.setPixels(outPx, 0, outWidth, 0, 0, outWidth, outHeight)

                    imageView.post {
                        if (isRunning) {
                            imageView.setImageBitmap(bmp)
                        }
                    }
                } catch (e: Exception) {
                    remoteLog?.invoke("NightVision: Frame error: ${e.message}")
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
                    captureSession = session
                    // Log all available modes and boost range
                    val availableAeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    val availableCtrlModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES)
                    remoteLog?.invoke("NightVision: availCtrl=${availableCtrlModes?.toList()} availAE=${availableAeModes?.toList()}")
                    val boostRange = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
                    val isoRangeFull = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val evStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    remoteLog?.invoke("NightVision: isoRange=$isoRangeFull boostRange=$boostRange evRange=$evRange evStep=$evStep")

                    // Try max post-RAW sensitivity boost (digital gain in ISP)
                    val maxBoost = boostRange?.upper ?: 100

                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, maxExposure)
                        set(CaptureRequest.SENSOR_SENSITIVITY, maxIso)
                        // Post-RAW digital gain boost -- bypasses analog gain limitation
                        set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, maxBoost)
                    }

                    // Log what we're actually requesting
                    remoteLog?.invoke("NightVision: REQUEST ctrlMode=${builder.build().get(CaptureRequest.CONTROL_MODE)} aeMode=${builder.build().get(CaptureRequest.CONTROL_AE_MODE)} exposure=${builder.build().get(CaptureRequest.SENSOR_EXPOSURE_TIME)} iso=${builder.build().get(CaptureRequest.SENSOR_SENSITIVITY)}")

                    var logCount = 0
                    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            if (logCount < 10) {
                                val actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                                val actualBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
                                val actualAe = result.get(CaptureResult.CONTROL_AE_MODE)
                                remoteLog?.invoke("NightVision: ACTUAL[$logCount] ae=$actualAe exposure=${(actualExposure ?: 0) / 1_000_000}ms iso=$actualIso boost=$actualBoost")
                                logCount++
                            }
                        }
                    }

                    session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
                    isRunning = true
                    remoteLog?.invoke("NightVision: EMA capture started")
                    onReady()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    remoteLog?.invoke("NightVision: Capture session config failed")
                    isRunning = false
                    onReady()
                }
            },
            cameraHandler
        )
    }

    private fun findCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK ||
                facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    fun stop() {
        isRunning = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        try { outputBitmap?.takeIf { !it.isRecycled }?.recycle() } catch (_: Throwable) {}
        outputBitmap = null
        accumBuffer = null
        outputPixels = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        remoteLog?.invoke("NightVision: Stopped")
    }
}
