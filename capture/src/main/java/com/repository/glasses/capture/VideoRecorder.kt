package com.repository.glasses.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Camera2 + MediaRecorder video recorder with pause/resume.
 * One session per recording.  stop() closes everything.
 */
@SuppressLint("MissingPermission")
class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "Cap:Video"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        // 1080p30 HEVC at 7 Mbps. 60 fps doubled the camera-HAL CPU and
        // wedged the device under sustained recording on the 4-core A55;
        // 30 fps is the standard for POV/wearable footage anyway.
        private const val VIDEO_BITRATE = 7_000_000
        private const val AUDIO_BITRATE = 32_000
        private const val FRAME_RATE = 30
        // Force-stop the recording if no camera frame arrived in this window.
        // 2s is ~120 frames at 60fps; far above any plausible jitter.
        private const val FRAME_STALL_MS = 2000L
        private const val STALL_CHECK_MS = 1000L
    }

    private val handlerThread = HandlerThread("VideoRec").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "VideoRec-exec") }
    private val cbExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "VideoRec-cb") }

    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var file: File? = null
    @Volatile private var startedAtMs: Long = 0
    @Volatile private var recording = false
    @Volatile private var paused = false
    @Volatile private var lastFrameAtMs: Long = 0
    private var stallWatchdog: Runnable? = null

    fun isRecording(): Boolean = recording
    fun isPaused(): Boolean = paused
    fun currentFile(): File? = file

    fun start(onResult: (File?, Throwable?) -> Unit) = GT.section("cap.video.start") {
        Log.i(TAG, "start entry recording=$recording")
        if (recording) {
            onResult(file, null)
            return@section
        }
        // doStart blocks on CountDownLatch.await() for openCamera/sessionConfig. Camera2
        // callbacks arrive on `handler`; running doStart on `executor` keeps the two threads
        // separate and prevents the same deadlock PhotoCapturer had.
        executor.execute {
            val t0 = SystemClock.elapsedRealtime()
            try {
                doStart()
                Log.i(TAG, "start exit doStartMs=${SystemClock.elapsedRealtime() - t0}")
                onResult(file, null)
            } catch (e: Throwable) {
                Log.e(TAG, "start failed: ${e.message}")
                cleanupQuietly()
                onResult(null, e)
            }
        }
    }

    fun togglePause(onResult: (Boolean) -> Unit) {
        Log.i(TAG, "togglePause entry recording=$recording paused=$paused")
        handler.post {
            val rec = recorder
            if (!recording || rec == null) {
                onResult(false)
                return@post
            }
            try {
                if (paused) {
                    rec.resume()
                    paused = false
                    Log.i(TAG, "resumed")
                } else {
                    rec.pause()
                    paused = true
                    Log.i(TAG, "paused")
                }
                onResult(paused)
            } catch (e: Throwable) {
                Log.e(TAG, "togglePause failed: ${e.message}")
                onResult(paused)
            }
        }
    }

    fun stop(onResult: (File?, Long, Long, Throwable?) -> Unit) = GT.section("cap.video.stop") {
        Log.i(TAG, "stop entry recording=$recording paused=$paused")
        handler.post {
            val f = file
            if (!recording || f == null) {
                onResult(null, 0, 0, null)
                return@post
            }
            val duration = SystemClock.elapsedRealtime() - startedAtMs
            try {
                // MediaRecorder.stop() from paused state is undefined on some OEM stacks and can
                // leave the mp4 without a finalized moov atom. Resume first so stop() runs from
                // RECORDING and the container is closed properly.
                if (paused) {
                    try {
                        recorder?.resume()
                        paused = false
                    } catch (e: Exception) {
                        Log.w(TAG, "recorder.resume before stop failed: ${e.message}")
                    }
                }
                try { session?.stopRepeating() } catch (e: Exception) { Log.w(TAG, "session.stopRepeating: ${e.message}") }
                try { session?.abortCaptures() } catch (e: Exception) { Log.w(TAG, "session.abortCaptures: ${e.message}") }
                try { session?.close() } catch (e: Exception) { Log.w(TAG, "session.close: ${e.message}") }
                try { recorder?.stop() } catch (e: Exception) { Log.w(TAG, "recorder.stop: ${e.message}") }
                cleanupQuietly()
                val size = if (f.exists()) f.length() else 0L
                Log.i(TAG, "stopped: ${f.absolutePath} duration=${duration}ms size=$size")
                onResult(f, duration, size, null)
            } catch (e: Throwable) {
                cleanupQuietly()
                onResult(f, duration, f.length(), e)
            }
        }
    }

    private fun doStart() {
        val out = FileNamer.videoFile()
        file = out

        // Probe the camera first so we can derive the orientation hint from
        // SENSOR_ORIENTATION instead of hardcoding the device's current value.
        //
        // Capture's MediaRecorder pipeline encodes setOrientationHint into a
        // different displaymatrix than the listener-side ArVideoRecorder for
        // the same hint value (capture uses Camera2 + OutputConfiguration ->
        // MediaRecorder; ArVideoRecorder is plain MediaRecorder.SURFACE).
        // Empirically the capture pipeline needs the hint set to the raw
        // SENSOR_ORIENTATION value (270 on this device) -- experimental test
        // matrix on the Rokid hardware:
        //     hint=0    : starting reference, 270 deg off
        //     hint=180  : 90 deg CCW from upright (still wrong)
        //     hint=90   : upside down (180 off)
        //     hint=270  : <-- this should be upright (matches sensor)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull()
            ?: throw IllegalStateException("no camera")
        val sensorOrientation = try {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "SENSOR_ORIENTATION read failed: ${e.message}; defaulting to 0")
            0
        }
        val orientationHint = sensorOrientation % 360

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(out.absolutePath)
        rec.setVideoEncodingBitRate(VIDEO_BITRATE)
        rec.setVideoFrameRate(FRAME_RATE)
        rec.setVideoSize(WIDTH, HEIGHT)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(AUDIO_BITRATE)
        rec.setOrientationHint(orientationHint)
        Log.i(TAG, "orientation: sensor=$sensorOrientation hint=$orientationHint")
        rec.prepare()
        recorder = rec

        val openLatch = CountDownLatch(1)
        val openedErr = arrayOfNulls<Throwable>(1)
        val opened = arrayOfNulls<CameraDevice>(1)
        val tOpen = SystemClock.elapsedRealtime()
        Log.i(TAG, "openCamera request cameraId=$cameraId")
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.i(TAG, "camera onOpened cb durMs=${SystemClock.elapsedRealtime() - tOpen}")
                opened[0] = device; openLatch.countDown()
            }
            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "camera onDisconnected cb")
                openedErr[0] = IllegalStateException("camera disconnected")
                device.close(); openLatch.countDown()
            }
            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "camera onError cb error=$error")
                openedErr[0] = IllegalStateException("camera open error $error")
                device.close(); openLatch.countDown()
            }
        }, handler)
        if (!openLatch.await(3, TimeUnit.SECONDS))
            throw IllegalStateException("camera open timeout")
        openedErr[0]?.let { throw it }
        camera = opened[0] ?: throw IllegalStateException("camera null")

        val recSurface = rec.surface

        val sessionLatch = CountDownLatch(1)
        val sessionErr = arrayOfNulls<Throwable>(1)
        val createdSession = arrayOfNulls<CameraCaptureSession>(1)
        val tSess = SystemClock.elapsedRealtime()
        val stateCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                Log.i(TAG, "session onConfigured cb durMs=${SystemClock.elapsedRealtime() - tSess}")
                createdSession[0] = s; sessionLatch.countDown()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "session onConfigureFailed cb")
                sessionErr[0] = IllegalStateException("session configure failed"); sessionLatch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            camera!!.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(recSurface)),
                    cbExecutor,
                    stateCb
                )
            )
        } else {
            @Suppress("DEPRECATION")
            camera!!.createCaptureSession(listOf(recSurface), stateCb, handler)
        }
        if (!sessionLatch.await(3, TimeUnit.SECONDS))
            throw IllegalStateException("session configure timeout")
        sessionErr[0]?.let { throw it }
        session = createdSession[0]

        val reqBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(recSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // Fixed-focus AR camera; continuous AF wastes ~5% of camera HAL CPU
            // refocusing a lens that doesn't move. Lock to infinity.
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(FRAME_RATE, FRAME_RATE))
            // Standard EIS adds ~10-15% camera HAL CPU. Head-mounted POV
            // doesn't benefit from in-camera stabilization the same way
            // hand-held footage does -- viewers expect to see head motion.
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
        val req = reqBuilder.build()
        val frameCounter = java.util.concurrent.atomic.AtomicLong(0L)
        val lastLogAt = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
        val frameCb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                req: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult
            ) {
                lastFrameAtMs = SystemClock.elapsedRealtime()
                val n = frameCounter.incrementAndGet()
                if (n % 300L == 0L) {
                    val now = lastFrameAtMs
                    val prev = lastLogAt.getAndSet(now)
                    val windowMs = now - prev
                    val fps = if (windowMs > 0) 300.0 * 1000.0 / windowMs else 0.0
                    val expected = (windowMs * FRAME_RATE / 1000L).coerceAtLeast(1)
                    val dropped = (expected - 300).coerceAtLeast(0)
                    GT.counter("cap.video.fps", fps.toLong())
                    GT.counter("cap.video.dropped", dropped)
                    Log.i(TAG, "frameStream n=$n windowMs=$windowMs fps=${"%.2f".format(fps)} dropped=$dropped paused=$paused")
                }
            }
        }
        lastFrameAtMs = SystemClock.elapsedRealtime()
        startStallWatchdog()
        Log.i(TAG, "setRepeatingRequest ${FRAME_RATE}fps (frame-stream logs every 300 frames)")
        session!!.setRepeatingRequest(req, frameCb, handler)

        Log.i(TAG, "MediaRecorder.start (H264 ${WIDTH}x${HEIGHT} ${FRAME_RATE}fps vBr=$VIDEO_BITRATE aBr=$AUDIO_BITRATE)")
        val tRec = SystemClock.elapsedRealtime()
        rec.start()
        startedAtMs = SystemClock.elapsedRealtime()
        recording = true
        paused = false
        Log.i(TAG, "started: ${out.absolutePath} recStartMs=${startedAtMs - tRec}")
    }

    /**
     * Force-stop on camera frame stall. Without this, MediaRecorder silently
     * lets the AAC track run past the last H264 frame, producing an mp4 with
     * audio longer than video that decodes wrong on every player.
     */
    private fun startStallWatchdog() {
        cancelStallWatchdog()
        val w = object : Runnable {
            override fun run() {
                if (!recording) return
                if (!paused) {
                    val gap = SystemClock.elapsedRealtime() - lastFrameAtMs
                    if (gap > FRAME_STALL_MS) {
                        Log.e(TAG, "frameStall: no frames for ${gap}ms - force stopping")
                        GT.counter("cap.video.stall", 1)
                        try { session?.stopRepeating() } catch (_: Exception) {}
                        try { session?.abortCaptures() } catch (_: Exception) {}
                        try { recorder?.stop() } catch (_: Exception) {}
                        cleanupQuietly()
                        return
                    }
                }
                handler.postDelayed(this, STALL_CHECK_MS)
            }
        }
        stallWatchdog = w
        handler.postDelayed(w, STALL_CHECK_MS)
    }

    private fun cancelStallWatchdog() {
        stallWatchdog?.let { handler.removeCallbacks(it) }
        stallWatchdog = null
    }

    private fun cleanupQuietly() {
        Log.i(TAG, "cleanupQuietly entry")
        val t0 = SystemClock.elapsedRealtime()
        cancelStallWatchdog()
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        session = null
        camera = null
        recorder = null
        recording = false
        paused = false
        Log.i(TAG, "cleanupQuietly exit durMs=${SystemClock.elapsedRealtime() - t0}")
    }

    fun shutdown() {
        handler.post { cleanupQuietly() }
        handlerThread.quitSafely()
        executor.shutdown()
        cbExecutor.shutdown()
    }
}
