package com.repository.glasses.capture

import android.annotation.SuppressLint
import android.content.Context
import com.repository.glasses.tracing.GT
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Replacement for the HAL-JPEG still path. Captures a burst of RAW_SENSOR
 * frames at manual ISO + exposure (which the Qualcomm HAL honors on the
 * RAW path, unlike the JPEG path), averages them in RAW space for
 * denoising, runs our own bilinear demosaic + WB + sRGB gamma, then
 * encodes a JPEG.
 *
 * Output JPEG is written to [FileNamer.photoFile]. The on-disk JPEG is
 * full sensor resolution (4032x3024) so downstream phone sync has the
 * same filename convention the HAL path produced.
 */
@SuppressLint("MissingPermission")
class RawStillCapturer(private val context: Context) {

    companion object {
        private const val TAG = "Cap:RawStill"

        /** Hard upper bound on AE convergence wait. The actual capture fires as
         *  soon as CONTROL_AE_STATE reaches CONVERGED, so this only kicks in
         *  for scenes where the HAL never reports convergence. */
        private const val AE_WARMUP_MS = 1500L

        /** Exposure compensation in 1/N EV steps applied ONLY to the still-
         *  capture request. Warmup runs at 0 EV so AE converges to the
         *  scene's base brightness; the still capture then re-targets a
         *  brighter point. Glasses use indoor / dim scenes most of the
         *  time and the SplitterDenoiser eats the extra noise from the
         *  longer exposure / higher ISO that this bias forces.
         *  Most Qualcomm HALs use 1/3 EV step, so +6 = +2 EV. */
        private const val STILL_AE_COMPENSATION = 6

        /** Number of RAW frames averaged per capture. sqrt(N) noise reduction. */
        private const val BURST_N = 3

        /** JPEG output quality. */
        private const val JPEG_QUALITY = 95

        /** Long edge of the first-pass preview JPEG. Matches the listener overlay's
         *  TARGET_LONG_EDGE_PX so the overlay doesn't even need to downsample. Keeps
         *  the preview encode well under 200 ms on this SoC. Full-res denoised JPEG
         *  overwrites the file at the same path later, so sync still gets full fidelity. */
        private const val PREVIEW_LONG_EDGE_PX = 960

        /** Lower quality is fine for preview -- the denoised full-res takes over soon
         *  and the overlay renders at a tiny rendered size anyway. */
        private const val PREVIEW_JPEG_QUALITY = 80

        /** Camera white-balance gains (from DNG metadata). Applied channelwise to raw Bayer. */
        private const val WB_R = 1.88f
        private const val WB_G = 1.00f
        private const val WB_B = 1.83f
    }

    // Camera callbacks must not share a thread with the executor (doCapture
    // blocks on latches; callback handler deadlocks if shared).
    private val handlerThread = HandlerThread("RawStill-cb").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "RawStill-exec") }
    // Separate thread for SplitterNet so preview + sync notifier stay
    // responsive while the ~60s denoise runs.
    private val denoiseExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "RawStill-denoise") }

    /** Dedicated thread for the fast-preview path so it doesn't contend with the
     *  camera callback thread or the denoise worker. Keeps preview latency low
     *  even when a previous denoise is still running. */
    private val previewExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "RawStill-preview") }
    private val busy = AtomicBoolean(false)

    /**
     * Two-phase capture:
     *   [onPreview] fires once the undenoised JPEG is on disk (a few
     *       seconds after button press). Caller should kick off the local
     *       preview animation here — the file is viewable even before
     *       SplitterNet runs.
     *   [onFinal]   fires after SplitterNet denoising finishes and the
     *       JPEG has been overwritten in place with the cleaner output.
     *       Caller should sync to the phone here, NOT on [onPreview].
     *
     * On error, [onPreview] fires with (null, err) and [onFinal] is not
     * called. If denoise fails, [onFinal] fires with the preview file +
     * the throwable so callers can still sync the undenoised version if
     * they want.
     */
    fun takePhoto(
        onPreview: (File?, Throwable?) -> Unit,
        onFinal: (File, Throwable?) -> Unit = { _, _ -> },
    ) = GT.section("cap.raw.capture") {
        Log.i(TAG, "takePhoto entry busy=${busy.get()}")
        executor.execute {
            if (!busy.compareAndSet(false, true)) {
                onPreview(null, IllegalStateException("raw still busy"))
                return@execute
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val file: File
            val binned: Bitmap
            // Wrap onPreview so it fires exactly once -- either from the fast-path
            // (frame 0 arrives) inside capturePreview, or from here if the whole
            // pipeline fails before the fast path had a chance to run.
            val previewFired = java.util.concurrent.atomic.AtomicBoolean(false)
            val onPreviewOnce: (File?, Throwable?) -> Unit = { f, e ->
                if (previewFired.compareAndSet(false, true)) onPreview(f, e)
            }
            try {
                val pair = capturePreview(onPreviewOnce, t0)
                file = pair.first
                binned = pair.second
                // onPreview may or may not have fired yet; if not, fire now with the
                // post-demosaic file (legacy slow path, shouldn't normally happen).
                onPreviewOnce(file, null)
            } catch (e: Throwable) {
                Log.e(TAG, "takePhoto failed: ${e.message}")
                onPreviewOnce(null, e)
                busy.set(false)
                return@execute
            }
            // Release busy now that the camera session is done. Denoise tails off on
            // its own executor and queues if a new capture is also denoising.
            busy.set(false)
            denoiseExecutor.execute {
                GT.section("cap.raw.denoise") {
                    try {
                        val tD = android.os.SystemClock.elapsedRealtime()
                        val denoised = SplitterDenoiser.get(context).denoise(binned)
                        FileOutputStream(file).use { denoised.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                        denoised.recycle()
                        stampExifOrientationNormal(file)
                        Log.i(TAG, "takePhoto denoise done ${file.absolutePath} denoiseMs=${android.os.SystemClock.elapsedRealtime() - tD} totalMs=${android.os.SystemClock.elapsedRealtime() - t0}")
                        onFinal(file, null)
                    } catch (e: Throwable) {
                        Log.e(TAG, "takePhoto denoise failed: ${e.message}")
                        onFinal(file, e)
                    }
                    // busy was released right after preview; nothing to do here.
                }
            }
        }
    }

    /**
     * Run the RAW burst, demosaic, and write an undenoised JPEG to disk.
     * Returns the on-disk file plus the in-memory binned Bitmap so the
     * caller can hand it off to SplitterNet without re-reading the JPEG.
     */
    private fun capturePreview(
        onEarlyPreview: (File?, Throwable?) -> Unit = { _, _ -> },
        takePhotoStartMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): Pair<File, Bitmap> = GT.section("cap.raw.preview") {
        val out = FileNamer.photoFile()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findRawCapableCamera(manager) ?: throw IllegalStateException("no RAW camera")
        val chars = manager.getCameraCharacteristics(cameraId)
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("no stream config")
        val rawSize = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.firstOrNull()
            ?: throw IllegalStateException("no RAW sizes")
        val w = rawSize.width
        val h = rawSize.height
        val blackLevel = (chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.getOffsetForIndex(0, 0) ?: 64).toFloat()
        val whiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        Log.i(TAG, "doCapture ${w}x${h} burstN=$BURST_N black=$blackLevel white=$whiteLevel (AE_ON warmup then LOCK)")

        // Accumulator for burst averaging. Each sample <= whiteLevel (~1023),
        // BURST_N=3 -> sum <= 3069, fits comfortably in int32.
        val acc = IntArray(w * h)
        val received = java.util.concurrent.atomic.AtomicInteger(0)
        val imageLatch = CountDownLatch(1)
        val frameErr = arrayOfNulls<Throwable>(1)

        val reader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, BURST_N)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: run {
                Log.w(TAG, "acquireNextImage null")
                return@setOnImageAvailableListener
            }
            try {
                val buf = img.planes[0].buffer
                val shorts = ShortArray(buf.remaining() / 2)
                buf.asShortBuffer().get(shorts)
                // Accumulate in int. We require the buffer to be exactly w*h.
                if (shorts.size < w * h) throw IllegalStateException("raw plane size ${shorts.size} < ${w*h}")
                // FAST PREVIEW: as soon as frame 0 is in memory, spawn a worker that
                // does a coarse grayscale demosaic + scale + encode from this single
                // frame and fires onEarlyPreview. Total preview latency drops from
                // ~4 s to well under 1 s. The remaining burst frames continue to
                // accumulate for the full-fidelity denoised result.
                val isFirst = received.get() == 0
                if (isFirst) {
                    val frame0 = shorts.copyOf()
                    previewExecutor.execute {
                        try {
                            val tPrev = android.os.SystemClock.elapsedRealtime()
                            val gray = RawDemosaic.fastPreviewToBitmap(
                                frame0, w, h, blackLevel, whiteLevel,
                            )
                            val rotM = android.graphics.Matrix().apply { postRotate(-90f) }
                            val rotated = Bitmap.createBitmap(gray, 0, 0, gray.width, gray.height, rotM, false)
                            if (rotated !== gray) gray.recycle()
                            val longE = maxOf(rotated.width, rotated.height)
                            val thumb: Bitmap = if (longE > PREVIEW_LONG_EDGE_PX) {
                                val scale = PREVIEW_LONG_EDGE_PX.toFloat() / longE.toFloat()
                                val tw = (rotated.width * scale).toInt().coerceAtLeast(1)
                                val th = (rotated.height * scale).toInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(rotated, tw, th, true)
                            } else rotated
                            FileOutputStream(out).use { thumb.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, it) }
                            if (thumb !== rotated && !thumb.isRecycled) thumb.recycle()
                            rotated.recycle()
                            stampExifOrientationNormal(out)
                            Log.i(TAG, "fastPreview done ${out.absolutePath} bytes=${out.length()} previewMs=${android.os.SystemClock.elapsedRealtime() - takePhotoStartMs} pathMs=${android.os.SystemClock.elapsedRealtime() - tPrev}")
                            onEarlyPreview(out, null)
                        } catch (e: Throwable) {
                            Log.e(TAG, "fastPreview failed: ${e.message}")
                            // Do NOT fire onEarlyPreview on failure; the legacy slow
                            // path at the end of capturePreview will still deliver
                            // the preview from the full-burst demosaic.
                        }
                    }
                }
                for (i in 0 until w * h) {
                    acc[i] += (shorts[i].toInt() and 0xFFFF)
                }
                val done = received.incrementAndGet()
                Log.i(TAG, "burst RAW $done/$BURST_N accumulated")
                if (done == BURST_N) imageLatch.countDown()
            } catch (e: Throwable) {
                frameErr[0] = e
                imageLatch.countDown()
            } finally {
                img.close()
            }
        }, handler)

        // Preview surface: SurfaceTexture-backed Surface so the HAL has
        // somewhere to send the warmup frames while AE converges. Tiny
        // resolution (640x480) -- we never read from it.
        val previewTex = SurfaceTexture(0).apply {
            setDefaultBufferSize(640, 480)
            detachFromGLContext()
        }
        val previewSurface = Surface(previewTex)

        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val openLatch = CountDownLatch(1)
            val opened = arrayOfNulls<CameraDevice>(1)
            val openErr = arrayOfNulls<Throwable>(1)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { opened[0] = device; openLatch.countDown() }
                override fun onDisconnected(device: CameraDevice) {
                    openErr[0] = IllegalStateException("camera disconnected")
                    device.close(); openLatch.countDown()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    openErr[0] = IllegalStateException("camera open error $error")
                    device.close(); openLatch.countDown()
                }
            }, handler)
            if (!openLatch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("camera open timeout")
            openErr[0]?.let { throw it }
            camera = opened[0]!!

            val sessLatch = CountDownLatch(1)
            val sessOut = arrayOfNulls<CameraCaptureSession>(1)
            val sessErr = arrayOfNulls<Throwable>(1)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface, previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { sessOut[0] = s; sessLatch.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    sessErr[0] = IllegalStateException("session configure failed"); sessLatch.countDown()
                }
            }, handler)
            if (!sessLatch.await(3, TimeUnit.SECONDS)) throw IllegalStateException("session configure timeout")
            sessErr[0]?.let { throw it }
            session = sessOut[0]!!

            // Hand exposure off to the HAL's standard AE. SplitterDenoiser
            // cleans up whatever noise the chosen ISO leaves behind, so the
            // hand-tuned manual ISO/shutter pairing is no longer worth its
            // failure modes (under/overexposure when the scene differs from
            // the indoor "sweet spot" the constants were tuned to).
            //
            // Run a preview repeating request so AE has frames to meter, then
            // wait for CONTROL_AE_STATE to reach CONVERGED (or fall back to
            // AE_WARMUP_MS hard cap). Without convergence the HAL hasn't
            // picked an exposure yet and the still burst lands at the
            // sensor's default near-zero values -> dark images.
            run {
                val warmupBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
                val converged = CountDownLatch(1)
                val lastExp = java.util.concurrent.atomic.AtomicLong(0L)
                val lastIso = java.util.concurrent.atomic.AtomicInteger(0)
                val lastAeState = java.util.concurrent.atomic.AtomicInteger(-1)
                session.setRepeatingRequest(warmupBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        req: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult,
                    ) {
                        result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp.set(it) }
                        result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso.set(it) }
                        val state = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                        if (state != null) lastAeState.set(state)
                        if (state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                            converged.countDown()
                        }
                    }
                }, handler)
                val convergedOk = converged.await(AE_WARMUP_MS, TimeUnit.MILLISECONDS)
                Log.i(TAG, "AE warmup: converged=$convergedOk aeState=${lastAeState.get()} exp=${lastExp.get() / 1_000_000.0}ms iso=${lastIso.get()}")
                session.stopRepeating()
            }
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, STILL_AE_COMPENSATION)
            }
            Log.i(TAG, "initial capture: AE_MODE_ON (HAL-managed, ${AE_WARMUP_MS}ms warmup)")
            val burstRequests = List(BURST_N) { builder.build() }
            val captureLatch = CountDownLatch(BURST_N)
            val captureErr = arrayOfNulls<Throwable>(1)
            val tBurst = android.os.SystemClock.elapsedRealtime()
            session.captureBurst(burstRequests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    req: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    val isoAct = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    Log.i(TAG, "RAW frame completed exp=${exp?.let { it / 1_000_000.0 }}ms iso=$isoAct remaining=${captureLatch.count - 1}")
                    captureLatch.countDown()
                }
                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    req: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    captureErr[0] = IllegalStateException("capture failed reason=${failure.reason}")
                    captureLatch.countDown()
                }
            }, handler)
            if (!captureLatch.await(10, TimeUnit.SECONDS))
                throw IllegalStateException("burst capture timeout (remaining=${captureLatch.count})")
            captureErr[0]?.let { throw it }
            Log.i(TAG, "burst captured durMs=${android.os.SystemClock.elapsedRealtime() - tBurst}")
            if (!imageLatch.await(30, TimeUnit.SECONDS))
                throw IllegalStateException("raw burst merge timeout (received=${received.get()}/$BURST_N)")
            frameErr[0]?.let { throw it }
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { previewSurface.release() } catch (_: Exception) {}
            try { previewTex.release() } catch (_: Exception) {}
        }

        // Average into a ShortArray (same semantic as a single frame), then demosaic.
        val avg = ShortArray(w * h)
        for (i in 0 until w * h) {
            avg[i] = (acc[i] / BURST_N).toShort()
        }
        val tProc = android.os.SystemClock.elapsedRealtime()
        val raw = RawDemosaic.binToBitmap(
            avg, w, h, blackLevel, whiteLevel, WB_R, WB_G, WB_B,
        )
        val tDemosaic = android.os.SystemClock.elapsedRealtime()
        // Physically rotate 90° CCW. EXIF-only rotation is unreliable once
        // we overwrite the file after denoise, so bake the rotation into
        // the pixels and stamp ORIENTATION_NORMAL below.
        val rotMatrix = android.graphics.Matrix().apply { postRotate(-90f) }
        val binned = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotMatrix, false)
        if (binned !== raw) raw.recycle()
        val tRot = android.os.SystemClock.elapsedRealtime()

        // Preview file was already written by fastPreviewToBitmap from frame 0.
        // Denoise will overwrite `out` later with the full-res denoised JPEG.
        Log.i(TAG, "full-burst demosaic done source=${binned.width}x${binned.height} demosaicMs=${tDemosaic - tProc} rotMs=${tRot - tDemosaic} totalProcMs=${tRot - tProc}")
        out to binned
    }

    private fun findRawCapableCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return id
        }
        return null
    }

    /**
     * Pixels are already rotated 90° CCW in [capturePreview]; mark EXIF
     * as NORMAL so viewers and downstream encoders don't re-rotate.
     */
    private fun stampExifOrientationNormal(file: File) {
        try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "EXIF stamp failed: ${e.message}")
        }
    }

    fun shutdown() {
        handlerThread.quitSafely()
        executor.shutdown()
        denoiseExecutor.shutdown()
    }
}
