package com.repository.glasses.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Low-light photo path. Captures a single RAW_SENSOR frame at long exposure +
 * max ISO, runs the SID-style U-Net (same model the NightVision preview uses)
 * and writes an RGB JPEG.
 *
 * Model: `nightvision_unet.onnx` in the capture APK's external files dir
 * (`/sdcard/Android/data/com.repository.glasses.capture/files/`). On Android
 * 12+ the listener APK's copy is not readable across packages, so deployment
 * must copy the model into both dirs.
 *
 * Model contract (SID U-Net, dynamic H/W re-export of the NightVisionML checkpoint):
 *   input:  float32 [1, 4, H/2, W/2]   packed Bayer (R, G1, B, G2)
 *   output: float32 [1, 3, H,   W]     RGB, range [0, 1]
 *
 * We feed the full sensor RAW (4032x3024) as 2016x1504 packed Bayer so the
 * model outputs at ~native resolution (4032x3008). Input dims must both be
 * multiples of 32 for the five MaxPool(2x2) levels -- 1504 loses the last 8
 * sensor rows vs. 1512, which is imperceptible.
 */
@SuppressLint("MissingPermission")
class LowLightCapturer(private val context: Context, private val cameraSession: CameraSession) {

    companion object {
        private const val TAG = "Cap:LowLight"
        private const val TAG_ONNX = "Cap:ONNX"
        private const val TAG_BAYER = "Cap:Bayer"
        private const val MODEL_FILENAME = "nightvision_unet.onnx"

        // Model input/output dimensions. The U-Net is fully-convolutional with a
        // fixed 2x upscale (ConvTranspose at the last block). Both input dims
        // must be multiples of 32 (five 2x2 MaxPool levels). Feeding the full
        // sensor means raw 4032x3024 -> packed Bayer 2016x1512, but 1512 is
        // not a multiple of 32; we clamp to 1504 and drop the final 8 sensor
        // rows. Output is 4032x3008 -- effectively native resolution.
        private const val MODEL_IN_H = 1504  // 1504/32=47
        private const val MODEL_IN_W = 2016  // 2016/32=63
        private const val MODEL_OUT_H = MODEL_IN_H * 2  // 3008
        private const val MODEL_OUT_W = MODEL_IN_W * 2  // 4032
        private const val PACKED_SIZE = 4 * MODEL_IN_H * MODEL_IN_W

        // Sensor calibration constants (Rokid AR Lite Bayer sensor).
        private const val BLACK_LEVEL = 64f
        private const val WHITE_LEVEL = 1023f

        // Capture parameters: max exposure within single-frame budget, high
        // analog+digital gain. The sensor supports ISO up to 12680 and
        // exposure up to ~313 ms.
        private const val EXPOSURE_MS = 313L
        private const val TARGET_ISO = 6400 // avoid the very top of the gain range to limit noise banding
        // SID U-Net amplification factor. Calibrated for the LOW_LIGHT_LUMA_THRESHOLD
        // of 50 in PhotoCapturer -- these captures are always dark-scene shots.
        // Higher values pull detail out of deeper shadows at the cost of clipping
        // brighter regions. Tune in tandem with the luma threshold.
        private const val AMPLIFICATION = 270f
    }

    // ImageReader + capture callbacks must not share a thread with [executor] --
    // doCapture blocks on CountDownLatch.await(), and if the callback handler were
    // the same thread the RAW frame would never be delivered. This thread services
    // only the ImageReader / CameraCaptureSession callbacks; the CameraDevice itself
    // is owned and opened by CameraSession on its own thread.
    private val handlerThread = HandlerThread("LowLight-cb").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LowLight-exec") }

    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var modelReady: Boolean = false
    @Volatile private var modelInitAttempted: Boolean = false

    // Model checked on-demand, not at init, to keep capture-APK memory small
    // until a low-light scene actually fires. The model file is ~30 MB and
    // the loaded graph is bigger -- pre-loading is the difference between
    // surviving LMK and getting killed on an OOM burst during a capture.
    fun isReady(): Boolean {
        if (closed.get()) return false
        ensureModelLoaded()
        val ready = modelReady && !busy.get()
        Log.i(TAG, "isReady=$ready (modelReady=$modelReady busy=${busy.get()})")
        return ready
    }

    @Synchronized
    private fun ensureModelLoaded() {
        if (modelInitAttempted) return
        modelInitAttempted = true
        initModel()
    }

    private fun initModel() {
        Log.i(TAG, "initModel entry")
        val tInit = android.os.SystemClock.elapsedRealtime()
        try {
            val modelFile = File(context.getExternalFilesDir(null), MODEL_FILENAME)
            if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
                Log.i(TAG, "model not found at ${modelFile.absolutePath} -- low-light path disabled")
                return
            }
            val tRead = android.os.SystemClock.elapsedRealtime()
            val bytes = modelFile.readBytes()
            Log.i(TAG, "initModel file read bytes=${bytes.size} readMs=${android.os.SystemClock.elapsedRealtime() - tRead}")
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try {
                    addQnn(mapOf(
                        "backend_path" to "libQnnHtp.so",
                        "htp_performance_mode" to "high_performance"
                    ))
                    Log.i(TAG, "QNN provider enabled")
                } catch (e: Throwable) {
                    Log.w(TAG, "QNN not available, CPU only: ${e.message}")
                }
            }
            val tSess = android.os.SystemClock.elapsedRealtime()
            session = env!!.createSession(bytes, opts)
            modelReady = true
            Log.i(TAG, "initModel exit bytes=${bytes.size} createSessionMs=${android.os.SystemClock.elapsedRealtime() - tSess} totalMs=${android.os.SystemClock.elapsedRealtime() - tInit}")
        } catch (e: Throwable) {
            Log.e(TAG, "model init failed: ${e.message}")
            try { session?.close() } catch (_: Exception) {}
            session = null
            modelReady = false
        }
    }

    /**
     * Capture and enhance one low-light photo. Calls [onResult] with the saved
     * file or (null, throwable) on failure.
     */
    /**
     * Capture a single RAW_SENSOR frame and save it as a standards-compliant
     * DNG (via android.hardware.camera2.DngCreator). Does NOT run inference
     * and does NOT load the ML model -- intended for off-device RAW
     * processing (e.g. feeding SID with different amplifications in Python).
     *
     * The DNG encodes the CFA pattern, black/white levels, colour matrices
     * and orientation so desktop tools (rawpy, tifffile, darktable) handle
     * it natively.
     */
    fun captureDng(onResult: (File?, Throwable?) -> Unit) = captureDng(0, 0L, onResult)

    /**
     * @param isoOverride set to >0 to request a specific SENSOR_SENSITIVITY
     *   (clamped to sensor's supported range). 0 = use TARGET_ISO.
     * @param exposureNsOverride set to >0 to request a specific
     *   SENSOR_EXPOSURE_TIME in ns. 0 = use EXPOSURE_MS*1e6.
     */
    fun captureDng(isoOverride: Int, exposureNsOverride: Long, onResult: (File?, Throwable?) -> Unit) {
        Log.i(TAG, "captureDng entry busy=${busy.get()} isoOverride=$isoOverride expNsOverride=$exposureNsOverride")
        if (closed.get()) {
            onResult(null, IllegalStateException("LowLightCapturer is closed"))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onResult(null, IllegalStateException("low-light capture busy"))
            return
        }
        executor.execute {
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                val file = doCaptureDng(isoOverride, exposureNsOverride)
                Log.i(TAG, "captureDng exit durMs=${android.os.SystemClock.elapsedRealtime() - t0} file=${file.absolutePath}")
                onResult(file, null)
            } catch (e: Throwable) {
                Log.e(TAG, "captureDng failed: ${e.message}")
                onResult(null, e)
                // On a session-configure timeout the borrow lambda's CameraCaptureSession
                // callback never fired, which can leave the shared HandlerThread
                // "LowLight-cb" wedged. Reclaim the whole capturer so the thread is
                // released; a fresh LowLightCapturer will be constructed on the next
                // request.
                val msg = e.message ?: ""
                if (msg.contains("session configure timeout")) {
                    Log.w(TAG, "session configure timeout -- closing capturer to reclaim HandlerThread")
                    try { close() } catch (ce: Throwable) { Log.e(TAG, "close() during timeout recovery failed: ${ce.message}") }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun doCaptureDng(isoOverride: Int = 0, exposureNsOverride: Long = 0L): File {
        val out = java.io.File(FileNamer.rootDir, "RAW_${System.currentTimeMillis()}.dng")
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRawCapableCamera(manager)
            ?: throw IllegalStateException("no RAW-capable camera")
        val chars = manager.getCameraCharacteristics(cameraId)
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream configuration map")
        val rawSizes = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?: throw IllegalStateException("no RAW sizes")
        if (rawSizes.isEmpty()) throw IllegalStateException("RAW sizes empty")
        val rawSize = rawSizes[0]
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minIso = isoRange?.lower ?: 50
        val maxIso = isoRange?.upper ?: TARGET_ISO
        val isoRequested = if (isoOverride > 0) isoOverride else TARGET_ISO
        val iso = isoRequested.coerceIn(minIso, maxIso)
        val exposureNs = if (exposureNsOverride > 0L) exposureNsOverride else EXPOSURE_MS * 1_000_000L
        Log.i(TAG, "captureDng at ${rawSize.width}x${rawSize.height} exposureNs=$exposureNs ISO=$iso (clamped from $isoRequested, range [$minIso..$maxIso])")

        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1)
        val imageLatch = java.util.concurrent.CountDownLatch(1)
        var captured: android.media.Image? = null
        reader.setOnImageAvailableListener({ r ->
            captured = r.acquireLatestImage()
            imageLatch.countDown()
        }, handler)

        val resultHolder = arrayOfNulls<android.hardware.camera2.TotalCaptureResult>(1)
        val ran = cameraSession.borrowDeviceExclusive(60_000L) { device ->
            var session: android.hardware.camera2.CameraCaptureSession? = null
            try {
                val sessLatch = java.util.concurrent.CountDownLatch(1)
                val sessErr = arrayOfNulls<Throwable>(1)
                val sessOut = arrayOfNulls<android.hardware.camera2.CameraCaptureSession>(1)
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(reader.surface), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) { sessOut[0] = s; sessLatch.countDown() }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                        sessErr[0] = IllegalStateException("session configure failed"); sessLatch.countDown()
                    }
                }, handler)
                if (!sessLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    throw IllegalStateException("session configure timeout")
                sessErr[0]?.let { throw it }
                session = sessOut[0]!!

                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                }

                val resultLatch = java.util.concurrent.CountDownLatch(1)
                session!!.capture(builder.build(), object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: android.hardware.camera2.CameraCaptureSession,
                        req: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        val actIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                        val actExp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                        Log.i(TAG, "DNG capture result: requested iso=$iso exp=${exposureNs/1_000_000.0}ms -> actual iso=$actIso exp=${actExp?.let { it/1_000_000.0 }}ms")
                        resultHolder[0] = result; resultLatch.countDown()
                    }
                }, handler)
                if (!imageLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    throw IllegalStateException("raw frame timeout")
                if (!resultLatch.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    throw IllegalStateException("capture result timeout")
            } finally {
                try { session?.close() } catch (_: Exception) {}
            }
        }
        if (!ran) {
            try { reader.close() } catch (_: Exception) {}
            throw IllegalStateException("camera busy")
        }

        val img = captured ?: throw IllegalStateException("raw image null")
        val totalResult = resultHolder[0]!!
        try {
            val dng = android.hardware.camera2.DngCreator(chars, totalResult)
            dng.setOrientation(android.media.ExifInterface.ORIENTATION_ROTATE_270)
            java.io.FileOutputStream(out).use { dng.writeImage(it, img) }
            Log.i(TAG, "DNG saved: ${out.absolutePath} bytes=${out.length()}")
        } finally {
            try { img.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
        }
        return out
    }

    fun capture(onResult: (File?, Throwable?) -> Unit) {
        Log.i(TAG, "capture entry modelReady=$modelReady busy=${busy.get()}")
        if (closed.get()) {
            onResult(null, IllegalStateException("LowLightCapturer is closed"))
            return
        }
        if (!modelReady) {
            onResult(null, IllegalStateException("low-light model not loaded"))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onResult(null, IllegalStateException("low-light capture busy"))
            return
        }
        executor.execute {
            val tCap = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "doCapture entry")
            try {
                val file = doCapture()
                Log.i(TAG, "doCapture exit durMs=${android.os.SystemClock.elapsedRealtime() - tCap} file=${file.absolutePath}")
                onResult(file, null)
            } catch (e: Throwable) {
                Log.e(TAG, "low-light capture failed: ${e.message}")
                onResult(null, e)
                // On a session-configure timeout the borrow lambda's CameraCaptureSession
                // callback never fired, which can leave the shared HandlerThread
                // "LowLight-cb" wedged. Reclaim the whole capturer so the thread is
                // released; a fresh LowLightCapturer will be constructed on the next
                // request.
                val msg = e.message ?: ""
                if (msg.contains("session configure timeout")) {
                    Log.w(TAG, "session configure timeout -- closing capturer to reclaim HandlerThread")
                    try { close() } catch (ce: Throwable) { Log.e(TAG, "close() during timeout recovery failed: ${ce.message}") }
                }
            } finally {
                // Unload the 30 MB model + ORT graph so it doesn't hold memory
                // between captures. Next low-light capture will re-initialize
                // (takes ~1 s for the model file read + QNN graph compile).
                unloadModel()
                busy.set(false)
            }
        }
    }

    @Synchronized
    private fun unloadModel() {
        Log.i(TAG, "unloadModel entry")
        val t0 = android.os.SystemClock.elapsedRealtime()
        try { session?.close() } catch (_: Exception) {}
        session = null
        modelReady = false
        modelInitAttempted = false
        Log.i(TAG, "unloadModel exit durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
    }

    private fun doCapture(): File {
        val out = FileNamer.photoFile()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRawCapableCamera(manager)
            ?: throw IllegalStateException("no RAW-capable camera")
        val chars = manager.getCameraCharacteristics(cameraId)

        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream configuration map")
        val rawSizes = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?: throw IllegalStateException("no RAW sizes available")
        if (rawSizes.isEmpty()) throw IllegalStateException("RAW sizes empty")
        val rawSize = rawSizes[0]
        val rawW = rawSize.width
        val rawH = rawSize.height

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val maxIso = isoRange?.upper ?: TARGET_ISO
        val iso = min(TARGET_ISO, maxIso)

        Log.i(TAG, "capture at ${rawW}x${rawH}, ${EXPOSURE_MS}ms, ISO=$iso (max=$maxIso)")

        val reader = ImageReader.newInstance(rawW, rawH, ImageFormat.RAW_SENSOR, 1)
        val imageLatch = CountDownLatch(1)
        var rawBuffer: ShortArray? = null
        reader.setOnImageAvailableListener({ r ->
            Log.i(TAG, "RAW onImageAvailable entry")
            val tIA = android.os.SystemClock.elapsedRealtime()
            val img = r.acquireLatestImage() ?: run {
                Log.w(TAG, "RAW onImageAvailable acquireLatestImage null")
                return@setOnImageAvailableListener
            }
            try {
                val plane = img.planes[0]
                val bb = plane.buffer
                val shorts = ShortArray(bb.remaining() / 2)
                bb.asShortBuffer().get(shorts)
                rawBuffer = shorts
                Log.i(TAG, "RAW onImageAvailable got shorts=${shorts.size} durMs=${android.os.SystemClock.elapsedRealtime() - tIA}")
            } finally {
                img.close()
                imageLatch.countDown()
            }
        }, handler)

        val ran = cameraSession.borrowDeviceExclusive(60_000L) { device ->
            var session: CameraCaptureSession? = null
            try {
                val sessionLatch = CountDownLatch(1)
                val createdSession = arrayOfNulls<CameraCaptureSession>(1)
                val sessionErr = arrayOfNulls<Throwable>(1)
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { createdSession[0] = s; sessionLatch.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessionErr[0] = IllegalStateException("session configure failed"); sessionLatch.countDown()
                    }
                }, handler)
                if (!sessionLatch.await(3, TimeUnit.SECONDS))
                    throw IllegalStateException("session configure timeout")
                sessionErr[0]?.let { throw it }
                session = createdSession[0]!!

                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_MS * 1_000_000L)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                }
                session!!.capture(builder.build(), null, handler)

                // Exposure is up to 313 ms; give plenty of headroom.
                if (!imageLatch.await(2, TimeUnit.SECONDS))
                    throw IllegalStateException("raw frame timeout")
            } finally {
                try { session?.close() } catch (_: Exception) {}
            }
        }
        try { reader.close() } catch (_: Exception) {}
        if (!ran) throw IllegalStateException("camera busy")

        val raw = rawBuffer ?: throw IllegalStateException("no raw buffer received")

        // Bin + pack + infer.
        val packed = FloatArray(PACKED_SIZE)
        val tBP = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG_BAYER, "binAndPack entry rawW=$rawW rawH=$rawH rawShorts=${raw.size} packedSize=${packed.size}")
        binAndPack(raw, rawW, rawH, packed)
        Log.i(TAG_BAYER, "binAndPack exit durMs=${android.os.SystemClock.elapsedRealtime() - tBP}")
        val rgb = runInference(packed)

        // Encode JPEG. Apply same sensor-orientation rotation the normal path uses
        // (270 deg clockwise) so the image is upright for the wearer.
        val bmp = rgbToBitmap(rgb)
        val tRot = android.os.SystemClock.elapsedRealtime()
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(270f) }, false)
        Log.i(TAG, "bitmap rotated durMs=${android.os.SystemClock.elapsedRealtime() - tRot}")
        val tJpeg = android.os.SystemClock.elapsedRealtime()
        FileOutputStream(out).use { fos ->
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }
        val jpegMs = android.os.SystemClock.elapsedRealtime() - tJpeg
        rotated.recycle()
        bmp.recycle()
        Log.i(TAG, "low-light photo saved: ${out.absolutePath} bytes=${out.length()} jpegCompressMs=$jpegMs")
        return out
    }

    private fun runInference(packed: FloatArray): FloatArray {
        Log.i(TAG_ONNX, "runInference entry inShape=[1,4,$MODEL_IN_H,$MODEL_IN_W] inFloats=${packed.size}")
        val tInfer = android.os.SystemClock.elapsedRealtime()
        val e = env ?: throw IllegalStateException("ORT env null")
        val s = session ?: throw IllegalStateException("ORT session null")
        val shape = longArrayOf(1, 4, MODEL_IN_H.toLong(), MODEL_IN_W.toLong())
        val input = OnnxTensor.createTensor(e, FloatBuffer.wrap(packed), shape)
        try {
            val tRun = android.os.SystemClock.elapsedRealtime()
            val results = s.run(mapOf("input" to input))
            val runMs = android.os.SystemClock.elapsedRealtime() - tRun
            try {
                val outTensor = results[0] as OnnxTensor
                val buf = outTensor.floatBuffer
                val out = FloatArray(3 * MODEL_OUT_H * MODEL_OUT_W)
                buf.get(out)
                Log.i(TAG_ONNX, "runInference exit outShape=[1,3,$MODEL_OUT_H,$MODEL_OUT_W] outFloats=${out.size} sessionRunMs=$runMs totalMs=${android.os.SystemClock.elapsedRealtime() - tInfer}")
                return out
            } finally {
                try { results.close() } catch (_: Exception) {}
            }
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
    }

    /** Convert [1,3,H,W] float buffer to a Bitmap. */
    private fun rgbToBitmap(rgb: FloatArray): Bitmap {
        val w = MODEL_OUT_W
        val h = MODEL_OUT_H
        val total = w * h
        Log.i(TAG, "rgbToBitmap entry w=$w h=$h totalPx=$total rgbFloats=${rgb.size}")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val px = IntArray(total)
        val chSize = w * h
        var processed = 0L
        var nextLog = 500_000L
        for (row in 0 until h) {
            for (col in 0 until w) {
                val i = row * w + col
                val r = (rgb[0 * chSize + i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val g = (rgb[1 * chSize + i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val b = (rgb[2 * chSize + i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            processed += w
            if (processed >= nextLog) {
                Log.i(TAG, "rgbToBitmap progress processed=$processed/$total")
                nextLog += 500_000L
            }
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "rgbToBitmap exit totalPx=$total durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
        return bmp
    }

    /**
     * Bin full-res Bayer to model input size and pack as 4 channels (R, G1, B, G2)
     * matching the SID training convention. Ported from NightVisionML.binAndPack.
     */
    private fun binAndPack(raw: ShortArray, rawW: Int, rawH: Int, output: FloatArray) {
        output.fill(0f)
        val strideX = rawW / 2 / MODEL_IN_W
        val strideY = rawH / 2 / MODEL_IN_H
        val stride = maxOf(strideX, strideY, 1)

        val packedH = min(rawH / (2 * stride), MODEL_IN_H)
        val packedW = min(rawW / (2 * stride), MODEL_IN_W)
        val range = WHITE_LEVEL - BLACK_LEVEL
        val chSize = MODEL_IN_H * MODEL_IN_W

        for (py in 0 until packedH) {
            for (px in 0 until packedW) {
                val bayerY = py * stride * 2
                val bayerX = px * stride * 2
                if (bayerY + 1 >= rawH || bayerX + 1 >= rawW) continue

                val r  = (raw[bayerY * rawW + bayerX].toInt() and 0xFFFF).toFloat()
                val g1 = (raw[bayerY * rawW + bayerX + 1].toInt() and 0xFFFF).toFloat()
                val g2 = (raw[(bayerY + 1) * rawW + bayerX].toInt() and 0xFFFF).toFloat()
                val b  = (raw[(bayerY + 1) * rawW + bayerX + 1].toInt() and 0xFFFF).toFloat()

                output[0 * chSize + py * MODEL_IN_W + px] = min(((r - BLACK_LEVEL).coerceAtLeast(0f) / range) * AMPLIFICATION, 1f)
                output[1 * chSize + py * MODEL_IN_W + px] = min(((g1 - BLACK_LEVEL).coerceAtLeast(0f) / range) * AMPLIFICATION, 1f)
                output[2 * chSize + py * MODEL_IN_W + px] = min(((b - BLACK_LEVEL).coerceAtLeast(0f) / range) * AMPLIFICATION, 1f)
                output[3 * chSize + py * MODEL_IN_W + px] = min(((g2 - BLACK_LEVEL).coerceAtLeast(0f) / range) * AMPLIFICATION, 1f)
            }
        }
    }

    private fun findRawCapableCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                return id
            }
        }
        return null
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            Log.i(TAG, "close() already invoked -- ignoring")
            return
        }
        Log.i(TAG, "close() entry")
        try { session?.close() } catch (_: Exception) {}
        session = null
        env = null
        try { handlerThread.quitSafely() } catch (_: Throwable) {}
        try { executor.shutdown() } catch (_: Throwable) {}
        Log.i(TAG, "close() exit")
    }
}
