package com.repository.glasses.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.SurfaceTexture
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import com.repository.glasses.tracing.GT
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Takes a single JPEG via Camera2 + ImageReader, optionally runs a post-capture
 * byte transform (ML denoise), then saves to disk and invokes callback.
 *
 * Resolution is driven by [requestedWidth]/[requestedHeight] (settable from the
 * app via [CaptureService.setPhotoSize]) and clamped against the camera's
 * supported JPEG sizes.
 *
 * Warm pool: the camera + capture session + ImageReader stay alive for
 * [WARM_IDLE_MS] after each successful capture. Subsequent shots inside that
 * window skip openCamera, createCaptureSession, AND the 600 ms 3A warmup --
 * each typically costs 800-1000 ms cold, so back-to-back shots feel near
 * instant. After idle, the pool auto-closes to release the camera (low-light
 * RAW path needs it, and idle camera draws ~120 mW).
 */
@SuppressLint("MissingPermission")
class PhotoCapturer(
    private val context: Context,
    /**
     * Optional low-light path. When the captured JPEG is darker than
     * [LOW_LIGHT_LUMA_THRESHOLD] and this is non-null, the JPEG is discarded
     * and [LowLightCapturer] takes an amplified RAW capture via the SID U-Net.
     */
    private val lowLight: LowLightCapturer? = null
) {

    companion object {
        private const val TAG = "Cap:Photo"
        // Resolution tuned for the user-facing preview latency budget (<3s
        // shutter-to-overlay). Full sensor (4032x3024) takes the Rokid HAL
        // ~4s just to encode the JPEG, plus ~3s to decode for the overlay.
        // 1280x960 fits in a single 33ms HAL frame and decodes near-instant.
        // Phone sync still gets a full image, just at this lower res -- the
        // glasses preview overlay is 504x378 anyway so 1280x960 has more
        // resolution than the on-glasses display can use.
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 960
        /** Quality tuned for size + latency, not archival. */
        private const val JPEG_QUALITY: Byte = 85

        /**
         * Route through the RAW + SID U-Net path when the JPEG is dim. The
         * old value (3 / 255) only fired in pitch-black -- a normal indoor
         * room at evening with overhead lights off measures ~30-60. A
         * threshold of 50 catches "dim room" without triggering on properly
         * exposed daylight scenes (which sit at 100+). Value is mean Y out
         * of 255.
         */
        private const val LOW_LIGHT_LUMA_THRESHOLD = 50f

        /**
         * Idle window during which the camera + session stay open after a
         * capture. Tuned at 5 s: long enough to absorb any human-paced burst
         * (the FN button can be re-pressed in well under that), short enough
         * to release camera before the user moves to a different feature
         * (low-light RAW capture, video, or another app needing the sensor).
         */
        // Short warm window: keeping the camera open is expensive on this
        // device. (a) Rokid's libcameraservice auto-fires the CAMERA_OPEN
        // lights_ctrl event for the whole duration the camera is held, so
        // the white LED stays lit -- visible to the wearer and to anyone
        // looking at them. (b) The YUV preview stream's per-frame NV21
        // rotation work keeps the A55 cluster active at ~30fps, heating
        // the SoC. 5s covers a fast double-shot but releases the camera
        // quickly when idle. The cold-reopen latency hit (~3s) is
        // acceptable when the wearer isn't burst-shooting.
        private const val WARM_IDLE_MS = 5_000L

        /**
         * Hard cap on the AE-convergence wait. The capture path actually
         * waits for CONTROL_AE_STATE = CONVERGED (or FLASH_REQUIRED) on the
         * preview repeating request and proceeds the moment the HAL reports
         * it -- typically <500ms in normal lighting, longer in dim scenes.
         * If the HAL never reports convergence within this cap we proceed
         * anyway so the user isn't stuck staring at a placeholder, but
         * frames may be under-exposed in that case.
         */
        private const val WARMUP_MAX_MS = 2500L
    }

    // Photo resolution is hardcoded to the sensor's native full resolution.
    // pickJpegSize() will clamp to the nearest actually-supported output size.
    private val requestedWidth: Int = DEFAULT_WIDTH
    private val requestedHeight: Int = DEFAULT_HEIGHT

    private val handlerThread = HandlerThread("PhotoCap").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotoCap-exec") }
    private val cbExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PhotoCap-cb") }
    // Background thread for denoise + filesync push. Decoupled from the
    // shutter path so the preview overlay fires the moment the JPEG bytes
    // are on disk; denoise happens silently afterwards and overwrites
    // the same file in place. Single-threaded so back-to-back shots
    // serialize their denoise instead of fighting for CPU.
    private val denoiseExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhotoCap-denoise").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    init {
        // Heal any leaked LED state from a previous crashed PhotoCapturer
        // instance: restore vendor.rkd.camera.led.enable=1 and cancel any
        // lingering CAMERA_OPEN(2014) WARN event in lights_ctrl. Cheap
        // and idempotent. Covers the SIGKILL gap that the shutdown hook
        // can't.
        try { ledRelease() } catch (e: Throwable) { Log.w(TAG, "LED heal on init failed: ${e.message}") }
        // Best-effort cleanup on JVM shutdown (System.exit, normal Service
        // teardown). SIGKILL bypasses this -- next process create's init
        // block above is the safety net.
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try { ledRelease() } catch (_: Throwable) {}
            })
        } catch (_: Throwable) {}
    }

    // [isBusy] is only advisory (tells whether we're actively taking a shot
    // right now). All [takePhoto] calls are queued -- rapid-fire button
    // presses never get rejected; they just wait their turn on [executor].
    private val busy = AtomicBoolean(false)
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    fun isBusy(): Boolean = busy.get()
    fun queuedCount(): Int = pending.get()

    // ---------------------------- warm pool ----------------------------
    // All access guarded by warmLock. Mutated from doCapture (executor
    // thread), the auto-close runnable (handler thread), and shutdown calls.

    private val warmLock = Any()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewTexture: SurfaceTexture? = null
    // YUV preview reader. The HAL feeds this at 30fps via setRepeatingRequest.
    // On shutter we grab the latest frame here and encode JPEG ourselves --
    // ~10x faster than firing TEMPLATE_STILL_CAPTURE which costs ~3s of HAL
    // pipeline overhead even for tiny resolutions.
    private var yuvReader: ImageReader? = null
    // Latest preview frame stashed as RAW (un-rotated) NV21 bytes plus its
    // source dims. The YUV listener does only the cheap YUV->NV21 plane
    // copy on every frame; the 90 deg pixel rotation AND the JPEG encode
    // are deferred to shutter time. This was previously rotating per-frame
    // at ~30fps which kept the A55 cluster active continuously while the
    // warm pool was open. Idle CPU now drops to just the plane-copy cost
    // (~3-5ms/frame at 1280x720).
    private class RawNv21(val bytes: ByteArray, val width: Int, val height: Int)
    @Volatile private var latestPreviewNv21: RawNv21? = null
    // Cached so a warm capture can build its STILL request without
    // re-querying CameraCharacteristics on every shot.
    private var warmSensorOrientation: Int = 0
    private var warmJpegWidth: Int = 0
    private var warmJpegHeight: Int = 0
    // Pending auto-close runnable. Cancelled and re-scheduled on each
    // capture; fires once after WARM_IDLE_MS of no captures.
    private var warmCloseRunnable: Runnable? = null

    /**
     * Pre-open camera + run 3A warmup so the next [takePhoto] skips cold open.
     * Resets the warm-pool auto-close timer. Cheap when pool is already hot.
     */
    fun warmUp() {
        executor.execute {
            try {
                synchronized(warmLock) { cancelWarmCloseLocked() }
                ensureWarmPool()
                synchronized(warmLock) { scheduleWarmCloseLocked() }
                Log.i(TAG, "warmUp done -- pool hot for ${WARM_IDLE_MS}ms")
            } catch (e: Throwable) {
                Log.w(TAG, "warmUp failed: ${e.message}")
            }
        }
    }

    /**
     * [onPreview] fires the moment the un-denoised JPEG hits disk (~500ms
     * warm). It drives the on-glasses preview overlay and the AIDL
     * onPhotoTaken broadcast.
     *
     * [onDenoised] fires after a background SplitterDenoiser pass overwrites
     * the same file with cleaner bytes. Use it to push the denoised version
     * to the phone via filesync. Default no-op so callers that don't care
     * about denoise can ignore it.
     */
    fun takePhoto(
        onPreview: (File?, Throwable?) -> Unit,
        onDenoised: (File) -> Unit = {},
    ) {
        val q = pending.incrementAndGet()
        Log.i(TAG, "takePhoto enqueued (queued=$q)")
        executor.execute {
            busy.set(true)
            val tCap = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "doCapture entry queued=$q")
            var capturedFile: File? = null
            try {
                capturedFile = doCapture(onPreview)
            } finally {
                busy.set(false)
                pending.decrementAndGet()
                Log.i(TAG, "doCapture exit totalMs=${android.os.SystemClock.elapsedRealtime() - tCap}")
            }
            val file = capturedFile ?: return@execute
            denoiseExecutor.execute {
                // Keep the warm camera pool ALIVE during denoise. Closing it
                // here would free ~30MB of YUV reader buffers, but on the
                // Rokid HAL the subsequent cold reopen reliably hangs at
                // "no preview frame after 2s" -- the HAL never delivers
                // frames to the freshly-reopened YUV reader. Reliability
                // beats RAM. If denoise OOM-kills the process, drop
                // SplitterDenoiser to 1 interpreter.
                val tD = android.os.SystemClock.elapsedRealtime()
                try {
                    val src = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (src == null) {
                        Log.w(TAG, "denoise: decode null for ${file.absolutePath}")
                        onDenoised(file)
                        return@execute
                    }
                    val denoised = SplitterDenoiser.get(context).denoise(src)
                    FileOutputStream(file).use {
                        denoised.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY.toInt() and 0xFF, it)
                    }
                    denoised.recycle()
                    Log.i(TAG, "denoise done ${file.name} bytes=${file.length()} durMs=${android.os.SystemClock.elapsedRealtime() - tD}")
                    onDenoised(file)
                } catch (e: Throwable) {
                    Log.e(TAG, "denoise failed: ${e.message} -- pushing un-denoised JPEG so phone still gets photo")
                    onDenoised(file)
                }
            }
        }
    }

    private fun doCapture(onPreview: (File?, Throwable?) -> Unit): File? {
        val out = FileNamer.photoFile()
        return try {
            synchronized(warmLock) { cancelWarmCloseLocked() }
            ensureWarmPool()

            // Wait up to 4s for a YUV frame. The first frame after a fresh
            // warm pool can take ~1.5s on this HAL (especially when warmUp
            // ran AND we're racing immediately on the same executor thread
            // that just finished warmup). Polling at 20ms keeps latency
            // tight when frames are flowing.
            val tStart = android.os.SystemClock.elapsedRealtime()
            var raw: RawNv21? = latestPreviewNv21
            while (raw == null && (android.os.SystemClock.elapsedRealtime() - tStart) < 4000L) {
                Thread.sleep(20)
                raw = latestPreviewNv21
            }
            if (raw == null) throw IllegalStateException("no preview frame after 4s")

            // Rotate the raw NV21 90 deg into portrait, then JPEG-encode.
            // Both steps run only on shutter, not per preview frame.
            val tRot = android.os.SystemClock.elapsedRealtime()
            val rotated = rotateNv21Cw(raw)
            val tEnc = android.os.SystemClock.elapsedRealtime()
            val yuv = android.graphics.YuvImage(
                rotated.bytes, android.graphics.ImageFormat.NV21, rotated.width, rotated.height, null,
            )
            val jpegBytes = java.io.ByteArrayOutputStream(rotated.width * rotated.height / 4).use { bos ->
                yuv.compressToJpeg(
                    android.graphics.Rect(0, 0, rotated.width, rotated.height),
                    JPEG_QUALITY.toInt() and 0xFF,
                    bos,
                )
                bos.toByteArray()
            }
            val tWrite = android.os.SystemClock.elapsedRealtime()
            FileOutputStream(out).use { it.write(jpegBytes) }
            Log.i(TAG, "photo saved: ${out.absolutePath} bytes=${jpegBytes.size} rotMs=${tEnc - tRot} encMs=${tWrite - tEnc} writeMs=${android.os.SystemClock.elapsedRealtime() - tWrite}")

            synchronized(warmLock) { scheduleWarmCloseLocked() }
            onPreview(out, null)
            out
        } catch (e: Throwable) {
            Log.e(TAG, "photo capture failed: ${e.message}")
            closeWarmPool("error: ${e.message}")
            if (out.exists() && out.length() == 0L) out.delete()
            onPreview(null, e)
            null
        }
    }

    /**
     * Cold-opens the camera + capture session + ImageReader if the warm
     * pool isn't populated, and runs the 600 ms 3A warmup. Idempotent: a
     * second call while warm is a no-op (logs once).
     */
    private fun ensureWarmPool() {
        synchronized(warmLock) {
            if (camera != null && session != null && reader != null) {
                Log.i(TAG, "reusing warm camera+session (skip openCamera + warmup)")
                return
            }
        }

        // Heavy work outside the lock: openCamera and createCaptureSession
        // both block on async callbacks. Re-take the lock at the end to
        // assign warm fields atomically.

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull()
            ?: throw IllegalStateException("no camera")

        val chars = manager.getCameraCharacteristics(cameraId)
        val (width, height) = pickJpegSize(chars, requestedWidth, requestedHeight)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        Log.i(TAG, "cold-open photo capture at ${width}x${height} (requested ${requestedWidth}x${requestedHeight}) sensorOrientation=$sensorOrientation")

        val newReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)

        // Preview target: YUV ImageReader. Sized 1280x960 -- the largest
        // YUV preview size the Rokid HAL reliably delivers. Larger sizes
        // (1440x1080, full sensor) are only supported on the JPEG path;
        // setRepeatingRequest with a YUV ImageReader at those sizes
        // configures successfully but never delivers frames, so the
        // shutter starves on "no preview frame after 2s". Pick the
        // largest supported YUV preview size from the SCALER map at
        // ~720p-1080p and don't use the cold-open JPEG-clamped size.
        val (yw, yh) = pickYuvPreviewSize(chars)
        Log.i(TAG, "yuv preview size ${yw}x${yh}")
        val newYuvReader = ImageReader.newInstance(yw, yh, ImageFormat.YUV_420_888, 3)
        newYuvReader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val raw = yuvImageToRawNv21(img)
                if (raw != null) latestPreviewNv21 = raw
            } finally {
                img.close()
            }
        }, handler)
        val newPreviewSurface = newYuvReader.surface

        // Suppress the white CAMERA_OPEN LED for the lifetime of the warm
        // pool. ledSuppress() sets the property AND starts a heartbeat
        // that periodically re-cancels the cameraserver-fired
        // CAMERA_OPEN event (the property alone isn't honored on this
        // firmware). ledRelease() in closeWarmPool restores defaults.
        // The on-shutter pulseWhite() in CaptureService still gives the
        // wearer brief shutter feedback (independent transact).
        ledSuppress()

        val openLatch = java.util.concurrent.CountDownLatch(1)
        val opened = arrayOfNulls<CameraDevice>(1)
        val openedErr = arrayOfNulls<Throwable>(1)
        val tOpen = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "openCamera request cameraId=$cameraId")
        GT.section("cap.photo.openCamera") {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.i(TAG, "camera onOpened cb durMs=${android.os.SystemClock.elapsedRealtime() - tOpen}")
                    // Immediate cancel of the CAMERA_OPEN event cameraserver
                    // fires here; the heartbeat in ledSuppress will keep
                    // re-cancelling if cameraserver re-fires later.
                    try { LedController.cancelCameraOpenEvent() } catch (_: Throwable) {}
                    opened[0] = device; openLatch.countDown()
                }
                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "camera onDisconnected cb")
                    openedErr[0] = IllegalStateException("camera disconnected")
                    device.close(); openLatch.countDown()
                    // Force the warm pool to re-cold-open next time.
                    closeWarmPool("disconnected")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "camera onError cb error=$error")
                    openedErr[0] = IllegalStateException("camera open error $error")
                    device.close(); openLatch.countDown()
                    closeWarmPool("error $error")
                }
            }, handler)
        }
        if (!openLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            throw IllegalStateException("camera open timeout")
        openedErr[0]?.let { throw it }
        val newCamera = opened[0] ?: throw IllegalStateException("camera null")

        val sessionLatch = java.util.concurrent.CountDownLatch(1)
        val sessionErr = arrayOfNulls<Throwable>(1)
        val createdSession = arrayOfNulls<CameraCaptureSession>(1)
        val tSess = android.os.SystemClock.elapsedRealtime()
        val stateCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                Log.i(TAG, "session onConfigured cb durMs=${android.os.SystemClock.elapsedRealtime() - tSess}")
                createdSession[0] = s; sessionLatch.countDown()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "session onConfigureFailed cb")
                sessionErr[0] = IllegalStateException("session configure failed"); sessionLatch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            newCamera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(
                        OutputConfiguration(newReader.surface),
                        OutputConfiguration(newPreviewSurface)
                    ),
                    cbExecutor,
                    stateCb
                )
            )
        } else {
            @Suppress("DEPRECATION")
            newCamera.createCaptureSession(listOf(newReader.surface, newPreviewSurface), stateCb, handler)
        }
        if (!sessionLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            throw IllegalStateException("session configure timeout")
        sessionErr[0]?.let { throw it }
        val newSession = createdSession[0]!!

        // 3A warmup: required by the Rokid HAL even after openCamera so
        // that AE / AWB converge before the still capture. We pay this
        // exactly once per cold-open; warm captures skip it entirely.
        // TEMPLATE_RECORD instead of TEMPLATE_PREVIEW: the Rokid HAL routes
        // PREVIEW frames through a low-bandwidth pipeline that silently drops
        // anything above ~1280x720, while RECORD goes through the video
        // pipeline that supports up to 1920x1080. We want the higher YUV
        // size for ~2K photos so we ride the RECORD pipeline even though
        // we're "just previewing".
        GT.section("cap.photo.ae_warmup") {
            val previewRequest = newCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(newPreviewSurface)
                set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO)
            }.build()
            // Wait for the HAL to report AE_STATE_CONVERGED (or FLASH_REQUIRED)
            // instead of a blind sleep. The previous fixed 600ms wait left
            // pre-converged dark frames in the YUV slot when the scene took
            // longer to meter (mean luma=1 observed). The metering callback
            // fires on every preview frame; we count down once AE is locked
            // in, then leave the repeating request running so 3A stays
            // converged for the warm window. WARMUP_MAX_MS is a safety cap
            // for scenes the HAL never reports convergence on (it's still
            // dark in those cases, but at least we don't hang forever).
            val converged = java.util.concurrent.CountDownLatch(1)
            val tWarmup = android.os.SystemClock.elapsedRealtime()
            val meteringCb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    req: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult,
                ) {
                    val state = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                        ?: return
                    if (state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        state == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        if (converged.count > 0) {
                            val exp = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                            val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                            Log.i(TAG, "preview 3A converged exp=${exp?.let { it / 1_000_000.0 }}ms iso=$iso aeState=$state durMs=${android.os.SystemClock.elapsedRealtime() - tWarmup}")
                            converged.countDown()
                        }
                    }
                }
            }
            Log.i(TAG, "preview 3A warmup start (waiting for AE_STATE_CONVERGED, cap ${WARMUP_MAX_MS}ms)")
            newSession.setRepeatingRequest(previewRequest, meteringCb, handler)
            val convergedOk = converged.await(WARMUP_MAX_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!convergedOk) {
                Log.w(TAG, "AE never converged within ${WARMUP_MAX_MS}ms -- proceeding anyway (frames may be dark)")
            }
            // Keep preview repeating with the metering callback so AE stays
            // locked in across the warm window; subsequent shots reuse the
            // same converged exposure.
        }

        synchronized(warmLock) {
            // If a concurrent close ran while we were opening, abandon our
            // newly-opened pool to avoid leaking. (Shouldn't happen since
            // doCapture is serialized on executor, but guard anyway.)
            if (camera != null) {
                Log.w(TAG, "concurrent warm pool present; closing our new instance")
                try { newSession.close() } catch (_: Exception) {}
                try { newCamera.close() } catch (_: Exception) {}
                try { newReader.close() } catch (_: Exception) {}
                try { newYuvReader.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
                try { newYuvReader.close() } catch (_: Exception) {}
                return
            }
            camera = newCamera
            session = newSession
            reader = newReader
            previewSurface = newPreviewSurface
            yuvReader = newYuvReader
            warmSensorOrientation = sensorOrientation
            warmJpegWidth = width
            warmJpegHeight = height
        }
    }

    /**
     * Extract NV21 (Y plane + interleaved VU) from a YUV_420_888 Image,
     * honoring rowStride / pixelStride padding. No rotation -- callers
     * who need pixel rotation invoke [rotateNv21Cw] separately at the
     * moment they actually consume the buffer (i.e. shutter time).
     */
    private fun yuvImageToRawNv21(img: android.media.Image): RawNv21? {
        return try {
            val sw = img.width
            val sh = img.height
            val yPlane = img.planes[0]
            val uPlane = img.planes[1]
            val vPlane = img.planes[2]

            val ySize = sw * sh
            val uvSize = sw * sh / 2
            val nv21 = ByteArray(ySize + uvSize)
            val yBuf = yPlane.buffer
            val yRowStride = yPlane.rowStride
            if (yRowStride == sw) {
                yBuf.get(nv21, 0, ySize)
            } else {
                var dst = 0
                for (row in 0 until sh) {
                    yBuf.position(row * yRowStride)
                    yBuf.get(nv21, dst, sw)
                    dst += sw
                }
            }
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride
            var dst = ySize
            val uvHeightSrc = sh / 2
            val uvWidthSrc = sw / 2
            for (row in 0 until uvHeightSrc) {
                val uRow = row * uRowStride
                val vRow = row * vRowStride
                for (col in 0 until uvWidthSrc) {
                    nv21[dst++] = vBuf.get(vRow + col * vPixelStride)
                    nv21[dst++] = uBuf.get(uRow + col * uPixelStride)
                }
            }
            RawNv21(nv21, sw, sh)
        } catch (e: Throwable) {
            Log.w(TAG, "yuvImageToRawNv21 failed: ${e.message}")
            null
        }
    }

    /**
     * Rotate an NV21 buffer 90 deg into portrait orientation. Empirically
     * matches the Rokid waveguide's upright display (the pure-math "CCW"
     * came out upside-down on this device, so the formula here is
     * output(x', y') = source(sw-1-y', x') for the Y plane and the
     * analogous (uvWidthSrc-1-y', x') for the interleaved VU plane).
     */
    private fun rotateNv21Cw(raw: RawNv21): RawNv21 {
        val sw = raw.width
        val sh = raw.height
        val src = raw.bytes
        val ySize = sw * sh
        val ow = sh
        val oh = sw
        val outNv21 = ByteArray(ow * oh + ow * oh / 2)
        var oIdx = 0
        for (y2 in 0 until oh) {
            val srcCol = sw - 1 - y2
            for (x2 in 0 until ow) {
                val srcRow = x2
                outNv21[oIdx++] = src[srcRow * sw + srcCol]
            }
        }
        val uvWidthSrc = sw / 2
        val uvHeightSrc = sh / 2
        val uvWidthOut = ow / 2
        val uvHeightOut = oh / 2
        val uvOffset = ow * oh
        val srcVuOffset = ySize
        val srcVuRowBytes = uvWidthSrc * 2
        var uvIdx = uvOffset
        for (y2 in 0 until uvHeightOut) {
            val srcCol = uvWidthSrc - 1 - y2
            for (x2 in 0 until uvWidthOut) {
                val srcRow = x2
                val srcOff = srcVuOffset + srcRow * srcVuRowBytes + srcCol * 2
                outNv21[uvIdx++] = src[srcOff]      // V
                outNv21[uvIdx++] = src[srcOff + 1]  // U
            }
        }
        return RawNv21(outNv21, ow, oh)
    }

    /** Caller must hold warmLock. */
    private fun cancelWarmCloseLocked() {
        warmCloseRunnable?.let {
            handler.removeCallbacks(it)
            warmCloseRunnable = null
        }
    }

    /** Caller must hold warmLock. */
    private fun scheduleWarmCloseLocked() {
        cancelWarmCloseLocked()
        if (camera == null) return  // pool already gone (e.g. low-light closed it)
        val r = Runnable { closeWarmPool("idle-${WARM_IDLE_MS}ms") }
        warmCloseRunnable = r
        handler.postDelayed(r, WARM_IDLE_MS)
        Log.i(TAG, "warm pool close scheduled in ${WARM_IDLE_MS}ms")
    }

    /**
     * Permanent teardown -- called from CaptureService.onDestroy. Closes
     * the warm pool and stops the executor / handler threads. Any in-flight
     * capture is left to complete on its own (the executor's shutdown is
     * orderly, not aggressive).
     */
    fun shutdown() {
        closeWarmPool("shutdown")
        executor.shutdown()
        cbExecutor.shutdown()
        handlerThread.quitSafely()
    }

    /**
     * Tear down the warm pool right now. Safe to call from any thread; safe
     * to call when nothing is open. The next [doCapture] will cold-open.
     */
    fun closeWarmPool(reason: String) {
        synchronized(warmLock) {
            cancelWarmCloseLocked()
            if (camera == null && session == null && reader == null) return
            Log.i(TAG, "closeWarmPool reason=$reason")
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            try { yuvReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
            try { yuvReader?.close() } catch (_: Exception) {}
            try { previewSurface?.release() } catch (_: Exception) {}
            try { previewTexture?.release() } catch (_: Exception) {}
            session = null
            camera = null
            reader = null
            yuvReader = null
            previewSurface = null
            previewTexture = null
            latestPreviewNv21 = null
        }
        // Stop suppressing: cancel heartbeat, restore property, one final
        // cancel of the CAMERA_OPEN event so any straggler is killed.
        ledRelease()
    }

    // Single source of truth for the camera-related LED state. The Rokid
    // firmware's three independent LED inputs (vendor.rkd.camera.led.enable
    // property, cameraserver's auto-fired CAMERA_OPEN(2014) WARN event,
    // and our shutter pulseWhite) all need coordinated handling, otherwise
    // the LED ends up stuck on. cameraserver will sometimes RE-fire the
    // CAMERA_OPEN event after onOpened (e.g. on session config or new
    // capture request), so we run a 500ms heartbeat that re-cancels the
    // event for the lifetime of the warm pool. Idempotent: starting an
    // already-suppressed state is a no-op, releasing an already-released
    // state is a no-op.
    @Volatile private var ledSuppressing = false
    private val ledHeartbeat = object : Runnable {
        override fun run() {
            if (!ledSuppressing) return
            try { LedController.cancelCameraOpenEvent() } catch (_: Throwable) {}
            handler.postDelayed(this, 500)
        }
    }

    /**
     * Begin suppressing the camera LED while we own the camera. Must be
     * called from a thread holding warmLock so the heartbeat schedule
     * doesn't race with [ledRelease]. Sets the property AND aggressively
     * cancels the active CAMERA_OPEN event AND starts a periodic
     * re-cancel until [ledRelease] is called.
     */
    private fun ledSuppress() {
        if (ledSuppressing) return
        ledSuppressing = true
        setLedProperty("0")
        try { LedController.cancelCameraOpenEvent() } catch (_: Throwable) {}
        handler.postDelayed(ledHeartbeat, 500)
    }

    /**
     * Stop suppressing. Called on every clean closeWarmPool path AND
     * unconditionally on process onCreate (heal stale state from a
     * previous crashed instance) and on JVM shutdown hook (best-effort
     * for ordinary process exits; SIGKILL still bypasses this -- the
     * onCreate heal is what covers that case).
     */
    private fun ledRelease() {
        if (!ledSuppressing) {
            // Restore the property even if we weren't actively suppressing,
            // for the heal-on-create case.
            setLedProperty("1")
            try { LedController.cancelCameraOpenEvent() } catch (_: Throwable) {}
            return
        }
        ledSuppressing = false
        handler.removeCallbacks(ledHeartbeat)
        setLedProperty("1")
        try { LedController.cancelCameraOpenEvent() } catch (_: Throwable) {}
    }

    private fun setLedProperty(v: String) {
        val key = "vendor.rkd.camera.led.enable"
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val set = sp.getMethod("set", String::class.java, String::class.java)
            set.invoke(null, key, v)
            return
        } catch (_: Throwable) {}
        try {
            Runtime.getRuntime().exec(arrayOf("setprop", key, v)).waitFor()
        } catch (e: Throwable) {
            Log.w(TAG, "setprop $key=$v failed: ${e.message}")
        }
    }

    /**
     * If a low-light path is wired and the captured JPEG is dark, discard the
     * JPEG and do a RAW+SID capture instead. Returns the file that should be
     * reported back to the client.
     */
    private fun maybeRouteLowLight(jpegFile: File): File {
        val ll = lowLight
        if (ll == null) {
            Log.i(TAG, "low-light routing skipped: lowLight=null (not wired into PhotoCapturer)")
            return jpegFile
        }
        if (!ll.isReady()) {
            Log.i(TAG, "low-light routing skipped: LowLightCapturer not ready")
            return jpegFile
        }
        val luma = meanLuma(jpegFile) ?: return jpegFile
        Log.i(TAG, "scene mean luma=${"%.1f".format(luma)} (threshold=$LOW_LIGHT_LUMA_THRESHOLD)")
        if (luma >= LOW_LIGHT_LUMA_THRESHOLD) return jpegFile

        // Low-light RAW path needs the camera; release the warm pool first
        // so the two don't fight over it. The next normal capture pays the
        // cold-open cost, which is fine because low-light captures are slow
        // anyway (RAW + SID U-Net + JPEG encode).
        closeWarmPool("low-light path needs camera")
        Log.i(TAG, "routing to low-light RAW path")
        // Keep a sibling copy of the original JPEG for debugging.
        try {
            val debugCopy = File(jpegFile.parent, jpegFile.nameWithoutExtension + ".original.jpg")
            jpegFile.copyTo(debugCopy, overwrite = true)
            Log.i(TAG, "original JPEG preserved at ${debugCopy.absolutePath}")
        } catch (e: Throwable) {
            Log.w(TAG, "failed to preserve original JPEG: ${e.message}")
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = arrayOfNulls<File>(1)
        val err = arrayOfNulls<Throwable>(1)
        ll.capture { f, e ->
            result[0] = f; err[0] = e; latch.countDown()
        }
        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "low-light capture timed out -- keeping original JPEG")
            return jpegFile
        }
        val llFile = result[0]
        if (llFile == null) {
            Log.w(TAG, "low-light capture failed (${err[0]?.message}) -- keeping original JPEG")
            return jpegFile
        }
        try {
            if (llFile.absolutePath != jpegFile.absolutePath) {
                jpegFile.delete()
                if (!llFile.renameTo(jpegFile)) {
                    return llFile
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "swap failed: ${e.message}")
            return llFile
        }
        return jpegFile
    }

    /** Mean luma of the decoded JPEG, downsampled to ~64 px. Null on decode failure. */
    private fun meanLuma(file: File): Float? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 32 } // aggressive downsample
            val bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: run {
                Log.w(TAG, "meanLuma: decodeFile returned null for ${file.absolutePath} (exists=${file.exists()}, size=${file.length()})")
                return null
            }
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            var sum = 0.0
            var minR = 255; var maxR = 0
            var minG = 255; var maxG = 0
            var minB = 255; var maxB = 0
            for (c in px) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < minR) minR = r; if (r > maxR) maxR = r
                if (g < minG) minG = g; if (g > maxG) maxG = g
                if (b < minB) minB = b; if (b > maxB) maxB = b
                // Standard Rec. 709 luma weights.
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            }
            val mean = (sum / px.size).toFloat()
            val firstPx = if (px.isNotEmpty()) String.format("%08x", px[0]) else "n/a"
            Log.i(TAG, "meanLuma: size=${w}x${h} sampled=${px.size} meanY=${"%.1f".format(mean)} R[${minR}..${maxR}] G[${minG}..${maxG}] B[${minB}..${maxB}] first=${firstPx}")
            mean
        } catch (e: Throwable) {
            Log.w(TAG, "meanLuma failed: ${e.message}")
            null
        }
    }

    /**
     * Stamp EXIF TAG_ORIENTATION so consumers (preview overlay, phone file-sync
     * viewer) render the JPEG upright without re-encoding pixels. The Camera2
     * HAL on this device emits raw sensor-oriented bytes with no EXIF.
     */
    private fun stampExifOrientation(file: File, sensorOrientation: Int) {
        try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            val orient = when (sensorOrientation) {
                90  -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
                180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
                270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
                else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            }
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                orient.toString(),
            )
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "EXIF stamp failed: ${e.message}")
        }
    }

    /**
     * Pick the supported JPEG size closest to [reqW]x[reqH]. Prefers matching
     * aspect ratio, falls back to nearest-area.
     */
    private fun pickJpegSize(chars: CameraCharacteristics, reqW: Int, reqH: Int): Pair<Int, Int> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes: Array<Size>? = map?.getOutputSizes(ImageFormat.JPEG)
        if (sizes.isNullOrEmpty()) return reqW to reqH
        val reqAr = reqW.toFloat() / reqH.toFloat()
        val reqArea = reqW.toLong() * reqH.toLong()
        val best = sizes.minByOrNull { s ->
            val ar = s.width.toFloat() / s.height.toFloat()
            val arDelta = abs(ar - reqAr)
            val areaDelta = abs(s.width.toLong() * s.height.toLong() - reqArea)
            arDelta * 1_000_000f + areaDelta / 1_000f
        }!!
        return best.width to best.height
    }

    /**
     * Largest YUV_420_888 preview output the HAL actually delivers frames
     * for. Picked from the SCALER map's YUV size list (different from JPEG
     * sizes), constrained to <=1280 long edge so the per-frame inline JPEG
     * encode in the YUV listener stays cheap on the A55 quad-core.
     * Falls back to 640x480 if the map is empty.
     */
    private fun pickYuvPreviewSize(chars: CameraCharacteristics): Pair<Int, Int> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes: Array<Size>? = map?.getOutputSizes(ImageFormat.YUV_420_888)
        if (sizes.isNullOrEmpty()) return 640 to 480
        // Log every supported YUV output so we can tell what the HAL accepts.
        Log.i(TAG, "yuv supported sizes: " + sizes.joinToString(",") { "${it.width}x${it.height}" })
        // 1280-long-edge is the largest YUV preview size the Rokid HAL
        // delivers RELIABLY across cold reopens. 1920x1080 sometimes
        // delivers, sometimes silently drops -- the second shot in a
        // burst pattern (cold reopen after denoise's closeWarmPool) was
        // observed to time out at "no preview frame after 2s" repeatedly
        // at 1920. Stay at 1280 until a workaround is found (probably
        // needs to keep camera warm during denoise or use a larger
        // ImageReader maxImages count).
        val cap = 1280
        val candidates = sizes.filter { it.width <= cap && it.height <= cap }
            .ifEmpty { sizes.toList() }
        val best = candidates.maxByOrNull { it.width.toLong() * it.height.toLong() }!!
        return best.width to best.height
    }

    private data class Quad(
        val a: CameraDevice?,
        val b: CameraCaptureSession?,
        val c: ImageReader?,
        val d: Int,
    )
}
