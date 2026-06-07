package com.repository.glasses.listener.nightvision

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class RawFrameCapturer(private val context: Context) {

    companion object {
        private const val TAG = "App:CamRaw"
    }

    var remoteLog: ((String) -> Unit)? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    @Volatile
    private var capturing = false
    @Volatile
    private var captureTimedOut = false

    fun capture(
        numFrames: Int,
        exposureNs: Long,   // 0 = use max
        boost: Int,          // 100-3199, 0 = use max
        onComplete: (success: Boolean, paths: List<String>, error: String?) -> Unit
    ) {
        Log.d(TAG, "event=capture frames=$numFrames exp_ns=$exposureNs boost=$boost")
        // Force cleanup any stale state from previous capture
        if (capturing) {
            remoteLog?.invoke("RAW: Force-cleaning stale capture state")
            cleanup()
        }
        capturing = true
        captureTimedOut = false

        cameraThread = HandlerThread("RawCapture").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        // Watchdog: force-complete after timeout (frames * 2s + 30s buffer)
        val timeoutMs = (numFrames * 2000L + 30000L).coerceAtMost(120000L)
        cameraHandler?.postDelayed({
            if (capturing) {
                captureTimedOut = true
                remoteLog?.invoke("RAW: Watchdog timeout after ${timeoutMs/1000}s")
                cleanup()
                onComplete(false, emptyList(), "Capture timed out")
            }
        }, timeoutMs)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager)
        if (cameraId == null) {
            cleanup()
            onComplete(false, emptyList(), "No camera found")
            return
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)

        // Check RAW support
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val hasRaw = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        if (!hasRaw) {
            cleanup()
            onComplete(false, emptyList(), "Camera does not support RAW capture")
            return
        }

        // Get RAW size
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (rawSizes.isNullOrEmpty()) {
            cleanup()
            onComplete(false, emptyList(), "No RAW output sizes available")
            return
        }
        val rawSize = rawSizes[0] // Largest available
        val rawWidth = rawSize.width
        val rawHeight = rawSize.height

        // Exposure and boost ranges
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val boostRange = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

        val actualExposure = if (exposureNs <= 0) exposureRange?.upper ?: 100_000_000L else exposureNs
        val actualBoost = if (boost <= 0) boostRange?.upper ?: 100 else boost
        val maxIso = isoRange?.upper ?: 1600

        remoteLog?.invoke("RAW: ${rawWidth}x${rawHeight} frames=$numFrames exposure=${actualExposure / 1_000_000}ms iso=$maxIso boost=$actualBoost")

        // Save to /sdcard/Download/ -- served by Rokid HTTP server (port 8848)
        val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "nightvision_raw")
        outDir.mkdirs()

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startRawCapture(camera, characteristics, rawWidth, rawHeight,
                        actualExposure, maxIso, actualBoost, numFrames, outDir, onComplete)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    remoteLog?.invoke("RAW: Camera disconnected")
                    camera.close()
                    cleanup()
                    onComplete(false, emptyList(), "Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    remoteLog?.invoke("RAW: Camera error $error")
                    camera.close()
                    cleanup()
                    onComplete(false, emptyList(), "Camera error: $error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            cleanup()
            onComplete(false, emptyList(), "Camera permission denied: ${e.message}")
        } catch (e: Exception) {
            cleanup()
            onComplete(false, emptyList(), "Failed to open camera: ${e.message}")
        }
    }

    private fun startRawCapture(
        camera: CameraDevice,
        characteristics: CameraCharacteristics,
        width: Int,
        height: Int,
        exposureNs: Long,
        iso: Int,
        boost: Int,
        numFrames: Int,
        outDir: File,
        onComplete: (Boolean, List<String>, String?) -> Unit
    ) {
        // Buffer enough images to handle DNG save latency (image arrives before capture result)
        imageReader = ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, 8)

        val savedPaths = mutableListOf<String>()
        var frameCount = 0
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val expLabel = "${exposureNs / 1_000_000}ms"

        // Buffer images and results separately, match by timestamp, save as DNG
        val pendingResults = mutableMapOf<Long, TotalCaptureResult>()
        val pendingImages = mutableMapOf<Long, android.media.Image>()

        fun trySaveDng(frameTs: Long) {
            val image = pendingImages[frameTs] ?: return
            val result = pendingResults[frameTs] ?: return
            pendingImages.remove(frameTs)
            pendingResults.remove(frameTs)
            try {
                if (frameCount >= numFrames || captureTimedOut || !capturing) {
                    image.close()
                    return
                }
                val filename = "raw-${timestamp}-${expLabel}-b${boost}-f${String.format("%03d", frameCount)}.dng"
                val file = File(outDir, filename)
                FileOutputStream(file).use { fos ->
                    val dngCreator = DngCreator(characteristics, result)
                    dngCreator.writeImage(fos, image)
                    dngCreator.close()
                }
                savedPaths.add(file.absolutePath)
                Log.d(TAG, "event=raw_frame_saved n=$frameCount size_kb=${file.length() / 1024}")
                remoteLog?.invoke("RAW: Frame $frameCount saved: ${file.name} (${file.length() / 1024}KB)")
                frameCount++
                image.close()
                checkDone(frameCount, numFrames, savedPaths, onComplete)
            } catch (e: Exception) {
                remoteLog?.invoke("RAW: Frame save error: ${e.message}")
                image.close()
                frameCount++
                checkDone(frameCount, numFrames, savedPaths, onComplete)
            }
        }

        imageReader!!.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                if (frameCount >= numFrames || captureTimedOut || !capturing) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                val frameTs = image.timestamp
                pendingImages[frameTs] = image
                trySaveDng(frameTs)
            } catch (e: Exception) {
                remoteLog?.invoke("RAW: Image listener error: ${e.message}")
            }
        }, cameraHandler)

        val surface = imageReader!!.surface

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session

                    // Try multiple ISO configurations to find what the HAL actually accepts
                    // Config 1: STILL_CAPTURE + AE_OFF (standard manual)
                    // Config 2: PREVIEW template (some HALs behave differently)
                    // Config 3: CONTROL_MODE_AUTO + AE_OFF (hybrid)
                    // We try config based on boost param as a hack selector:
                    //   boost < 200 -> config 1 (standard)
                    //   boost 200-999 -> config 2 (preview template)
                    //   boost >= 1000 -> config 3 (auto+manual AE)

                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                        set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
                    }

                    remoteLog?.invoke("RAW: REQ exp=${exposureNs/1_000_000}ms iso=$iso boost=$boost")

                    var loggedResult = false
                    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            if (!loggedResult) {
                                val aIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                                val aExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                                val aBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
                                val aCtrl = result.get(CaptureResult.CONTROL_MODE)
                                remoteLog?.invoke("RAW: ACT iso=$aIso exp=${(aExp ?: 0) / 1_000_000}ms boost=$aBoost ctrl=$aCtrl")
                                loggedResult = true
                            }
                            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP)
                            if (ts != null) {
                                pendingResults[ts] = result
                                trySaveDng(ts)
                            }
                        }
                    }

                    remoteLog?.invoke("RAW: Starting $numFrames captures...")
                    for (i in 0 until numFrames) {
                        session.capture(builder.build(), captureCallback, cameraHandler)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    remoteLog?.invoke("RAW: Session config failed")
                    cleanup()
                    onComplete(false, emptyList(), "Capture session config failed")
                }
            },
            cameraHandler
        )
    }

    private fun checkDone(
        frameCount: Int,
        numFrames: Int,
        savedPaths: List<String>,
        onComplete: (Boolean, List<String>, String?) -> Unit
    ) {
        if (frameCount >= numFrames && capturing && !captureTimedOut) {
            remoteLog?.invoke("RAW: Capture complete. ${savedPaths.size}/$numFrames frames saved.")
            cleanup()
            onComplete(savedPaths.isNotEmpty(), savedPaths, null)
        }
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

    private fun cleanup() {
        capturing = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
