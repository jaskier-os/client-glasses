package com.repository.glasses.listener.nightvision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.ImageView
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * ML-based night vision using "Learning to See in the Dark" U-Net.
 *
 * Capture-infer-display loop:
 * 1. Single RAW_SENSOR capture at user-selected exposure
 * 2. Bin Bayer to model input size, pack 4 channels, normalize with user-selected amplification
 * 3. ONNX Runtime inference -> RGB output
 * 4. Convert to green-channel bitmap for waveguide display
 * 5. Repeat (no frame queuing, no concurrent inference)
 */
class NightVisionML(private val context: Context) {

    companion object {
        private const val TAG = "App:CamNight"
        private const val LOG_EVERY_N_FRAMES = 5
    }

    var remoteLog: ((String) -> Unit)? = null
    private var nvFrameCount = 0L

    @Volatile
    var isRunning = false
        private set

    // User-adjustable parameters
    var exposureMs = 313       // 50ms steps, range 50-313
    var amplification = 300f   // 100x steps, range 100-1000

    // ONNX Runtime
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Camera
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null

    // Display
    private var imageView: ImageView? = null
    private var sensorRotation = 0
    private var effectiveRotation = 0

    // Flow control
    @Volatile
    private var inferencing = false

    // Pre-allocated buffers
    private val MODEL_IN_H = 192
    private val MODEL_IN_W = 256
    private val MODEL_OUT_H = 384
    private val MODEL_OUT_W = 512
    private val PACKED_SIZE = 4 * MODEL_IN_H * MODEL_IN_W
    private val OUTPUT_SIZE = 3 * MODEL_OUT_H * MODEL_OUT_W

    private var packedBuffer = FloatArray(PACKED_SIZE)
    private var outputPixels: IntArray? = null
    private var outputBitmap: Bitmap? = null

    // Sensor constants
    private val BLACK_LEVEL = 64f
    private val WHITE_LEVEL = 1023f

    fun start(imageView: ImageView, modelBytes: ByteArray, onReady: () -> Unit) {
        Log.d(TAG, "event=start running=$isRunning")
        if (isRunning) {
            remoteLog?.invoke("NightVisionML: Already running")
            onReady()
            return
        }

        this.imageView = imageView

        // Init ONNX Runtime
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnv!!.createSession(modelBytes, opts)
            remoteLog?.invoke("NightVisionML: ONNX model loaded")
        } catch (e: Exception) {
            remoteLog?.invoke("NightVisionML: Failed to load model: ${e.message}")
            onReady()
            return
        }

        // Init camera thread
        cameraThread = HandlerThread("NightVisionML").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        // Find camera
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = findCamera(manager)
        if (cameraId == null) {
            remoteLog?.invoke("NightVisionML: No camera found")
            onReady()
            return
        }
        characteristics = manager.getCameraCharacteristics(cameraId!!)
        sensorRotation = characteristics!!.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Rotation for display
        val displayRotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        effectiveRotation = (sensorRotation - displayDegrees + 180 + 360) % 360

        // Output bitmap
        val swap = effectiveRotation == 90 || effectiveRotation == 270
        val outW = if (swap) MODEL_OUT_H else MODEL_OUT_W
        val outH = if (swap) MODEL_OUT_W else MODEL_OUT_H
        outputPixels = IntArray(MODEL_OUT_H * MODEL_OUT_W)
        outputBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        remoteLog?.invoke("NightVisionML: Started (rotation=$effectiveRotation, output=${outW}x${outH})")
        isRunning = true
        onReady()

        // Start first capture
        captureOneFrame()
    }

    fun stop() {
        Log.d(TAG, "event=stop frames=$nvFrameCount")
        isRunning = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        ortSession?.close()
        ortSession = null
        // OrtEnvironment is a singleton, don't close it
        ortEnv = null
        outputBitmap = null
        outputPixels = null
        imageView = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        remoteLog?.invoke("NightVisionML: Stopped")
    }

    fun adjustExposure(delta: Int) {
        exposureMs = (exposureMs + delta).coerceIn(50, 313)
        remoteLog?.invoke("NightVisionML: Exposure=${exposureMs}ms")
    }

    fun adjustAmplification(delta: Float) {
        amplification = (amplification + delta).coerceIn(100f, 1000f)
        remoteLog?.invoke("NightVisionML: Amplification=x${amplification.toInt()}")
    }

    private fun captureOneFrame() {
        if (!isRunning) return
        val handler = cameraHandler ?: return
        val camId = cameraId ?: return
        val chars = characteristics ?: return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Get RAW size
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (rawSizes.isNullOrEmpty()) {
            remoteLog?.invoke("NightVisionML: No RAW sizes available")
            return
        }
        val rawSize = rawSizes[0]
        val rawW = rawSize.width
        val rawH = rawSize.height

        // Create ImageReader for single frame
        imageReader?.close()
        imageReader = ImageReader.newInstance(rawW, rawH, ImageFormat.RAW_SENSOR, 1).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!isRunning || inferencing) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    inferencing = true
                    nvFrameCount++
                    val tInferStart = SystemClock.elapsedRealtime()

                    // Close camera while we process
                    try { captureSession?.close() } catch (_: Exception) {}
                    captureSession = null

                    processAndDisplay(image, rawW, rawH)
                    if (nvFrameCount % LOG_EVERY_N_FRAMES == 0L) {
                        Log.v(TAG, "event=nv_frame n=$nvFrameCount dt_ms=${SystemClock.elapsedRealtime() - tInferStart}")
                    }
                } catch (e: Exception) {
                    remoteLog?.invoke("NightVisionML: Frame error: ${e.message}")
                } finally {
                    image.close()
                    inferencing = false
                    // Next frame
                    if (isRunning) {
                        handler.post { captureOneFrame() }
                    }
                }
            }, handler)
        }

        try {
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isRunning) { camera.close(); return }
                    cameraDevice = camera
                    val surface = imageReader!!.surface

                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!isRunning) { session.close(); return }
                            captureSession = session
                            val exposureNs = exposureMs.toLong() * 1_000_000L
                            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                            val maxIso = isoRange?.upper ?: 1600

                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                                set(CaptureRequest.SENSOR_SENSITIVITY, maxIso)
                            }
                            // Single capture, not repeating
                            session.capture(builder.build(), null, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            remoteLog?.invoke("NightVisionML: Session config failed")
                        }
                    }, handler)
                }

                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    remoteLog?.invoke("NightVisionML: Camera error $error")
                    camera.close(); cameraDevice = null
                }
            }, handler)
        } catch (e: SecurityException) {
            remoteLog?.invoke("NightVisionML: Camera permission denied")
        } catch (e: Exception) {
            remoteLog?.invoke("NightVisionML: Camera open failed: ${e.message}")
        }
    }

    private fun processAndDisplay(image: android.media.Image, rawW: Int, rawH: Int) {
        val session = ortSession ?: return
        val env = ortEnv ?: return
        val iv = imageView ?: return
        val bmp = outputBitmap ?: return
        val outPx = outputPixels ?: return

        // Read raw 16-bit Bayer data
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rawBytes = ShortArray(buffer.remaining() / 2)
        buffer.asShortBuffer().get(rawBytes)

        // Bin Bayer and pack to model input
        binAndPack(rawBytes, rawW, rawH, packedBuffer)

        // ONNX inference
        val inputShape = longArrayOf(1, 4, MODEL_IN_H.toLong(), MODEL_IN_W.toLong())
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(packedBuffer), inputShape)
        val results = session.run(mapOf("input" to inputTensor))
        val outputTensor = results[0] as OnnxTensor
        val outputData = outputTensor.floatBuffer

        // Convert RGB output to green-channel bitmap with rotation
        for (row in 0 until MODEL_OUT_H) {
            for (col in 0 until MODEL_OUT_W) {
                val rIdx = 0 * MODEL_OUT_H * MODEL_OUT_W + row * MODEL_OUT_W + col
                val gIdx = 1 * MODEL_OUT_H * MODEL_OUT_W + row * MODEL_OUT_W + col
                val bIdx = 2 * MODEL_OUT_H * MODEL_OUT_W + row * MODEL_OUT_W + col

                val r = (outputData.get(rIdx).coerceIn(0f, 1f) * 255).toInt()
                val g = (outputData.get(gIdx).coerceIn(0f, 1f) * 255).toInt()
                val b = (outputData.get(bIdx).coerceIn(0f, 1f) * 255).toInt()

                // Convert to green-only for waveguide (luminance-weighted)
                val lum = ((0.299f * r + 0.587f * g + 0.114f * b)).toInt().coerceIn(0, 255)

                // Rotation-aware output index
                val outIdx = when (effectiveRotation) {
                    90 -> (MODEL_OUT_W - 1 - col) * MODEL_OUT_H + row
                    180 -> (MODEL_OUT_H - 1 - row) * MODEL_OUT_W + (MODEL_OUT_W - 1 - col)
                    270 -> col * MODEL_OUT_H + (MODEL_OUT_H - 1 - row)
                    else -> row * MODEL_OUT_W + col
                }

                outPx[outIdx] = 0xFF000000.toInt() or (lum shl 8)  // Green channel only
            }
        }

        bmp.setPixels(outPx, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        // Clean up tensors
        outputTensor.close()
        inputTensor.close()
        results.close()

        iv.post {
            if (isRunning) {
                iv.setImageBitmap(bmp)
            }
        }
    }

    /**
     * Bin full-res Bayer to model input size and pack as 4 channels.
     * Bayer pattern RGGB, pack order R, G1, B, G2 (matching Sony SID paper).
     *
     * Stride-based binning: sample every Nth 2x2 Bayer superpixel.
     * 4032x3024 -> stride 8 on superpixels -> 252x189 packed.
     * Pad to 256x192 for model.
     */
    private fun binAndPack(raw: ShortArray, rawW: Int, rawH: Int, output: FloatArray) {
        output.fill(0f)

        // Superpixel stride: how many 2x2 blocks to skip
        val strideX = rawW / 2 / MODEL_IN_W   // 4032/2/256 = ~7.87 -> use 8
        val strideY = rawH / 2 / MODEL_IN_H   // 3024/2/192 = ~7.87 -> use 8
        val stride = maxOf(strideX, strideY, 1)

        val packedH = min(rawH / (2 * stride), MODEL_IN_H)
        val packedW = min(rawW / (2 * stride), MODEL_IN_W)
        val range = WHITE_LEVEL - BLACK_LEVEL
        val amp = amplification

        for (py in 0 until packedH) {
            for (px in 0 until packedW) {
                val bayerY = py * stride * 2
                val bayerX = px * stride * 2

                if (bayerY + 1 >= rawH || bayerX + 1 >= rawW) continue

                val r  = (raw[bayerY * rawW + bayerX].toInt() and 0xFFFF).toFloat()
                val g1 = (raw[bayerY * rawW + bayerX + 1].toInt() and 0xFFFF).toFloat()
                val g2 = (raw[(bayerY + 1) * rawW + bayerX].toInt() and 0xFFFF).toFloat()
                val b  = (raw[(bayerY + 1) * rawW + bayerX + 1].toInt() and 0xFFFF).toFloat()

                // Normalize and amplify: (pixel - BL) / (WL - BL) * amp, clamped to 1.0
                val chSize = MODEL_IN_H * MODEL_IN_W
                output[0 * chSize + py * MODEL_IN_W + px] = min(((r - BLACK_LEVEL).coerceAtLeast(0f) / range) * amp, 1f)   // R
                output[1 * chSize + py * MODEL_IN_W + px] = min(((g1 - BLACK_LEVEL).coerceAtLeast(0f) / range) * amp, 1f)  // G1
                output[2 * chSize + py * MODEL_IN_W + px] = min(((b - BLACK_LEVEL).coerceAtLeast(0f) / range) * amp, 1f)   // B (SID order)
                output[3 * chSize + py * MODEL_IN_W + px] = min(((g2 - BLACK_LEVEL).coerceAtLeast(0f) / range) * amp, 1f)  // G2
            }
        }
    }

    private fun findCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasRaw = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            if (!hasRaw) continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK ||
                facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }
}
