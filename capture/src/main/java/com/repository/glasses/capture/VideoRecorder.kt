package com.repository.glasses.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import java.io.File
import java.util.concurrent.Executors

/**
 * MediaRecorder video recorder with pause/resume.
 *
 * Does NOT own the camera. The shared [CameraSession] owns the single
 * CameraDevice + capture session; VideoRecorder only manages the MediaRecorder
 * lifecycle and hands its input Surface to the session as a recording output.
 * The frame-stall watchdog lives in CameraSession (it owns the repeating
 * request); a stall calls back into [stop].
 */
@SuppressLint("MissingPermission")
class VideoRecorder(
    private val context: Context,
    private val cameraSession: CameraSession,
) {

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
    }

    private val handlerThread = HandlerThread("VideoRec").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "VideoRec-exec") }

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var file: File? = null
    @Volatile private var startedAtMs: Long = 0
    @Volatile private var recording = false
    @Volatile private var paused = false

    fun isRecording(): Boolean = recording
    fun isPaused(): Boolean = paused
    fun currentFile(): File? = file

    fun start(onResult: (File?, Throwable?) -> Unit) = GT.section("cap.video.start") {
        Log.i(TAG, "start entry recording=$recording")
        if (recording) {
            onResult(file, null)
            return@section
        }
        // doStart blocks on CameraSession.setRecorderSurface (which configures
        // the shared session). Run on executor so it doesn't block the caller.
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
                // Stop the camera feeding the recorder surface BEFORE stopping
                // the recorder, so no frames arrive at a torn-down encoder.
                // clearRecorderSurface() returns false if the detach did not
                // complete (op timed out) -- in that case the surface may still
                // be attached, so frames can hit the encoder during stop(). We
                // still stop (the recording must end) but log a WARN.
                val cleared = cameraSession.clearRecorderSurface()
                if (!cleared) {
                    Log.w(TAG, "clearRecorderSurface did not confirm detach; frames may have hit the encoder during stop")
                }
                try { recorder?.stop() } catch (e: Exception) { Log.w(TAG, "recorder.stop: ${e.message}") }
                cleanupRecorder()
                val size = if (f.exists()) f.length() else 0L
                Log.i(TAG, "stopped: ${f.absolutePath} duration=${duration}ms size=$size")
                onResult(f, duration, size, null)
            } catch (e: Throwable) {
                cleanupRecorder()
                onResult(f, duration, f.length(), e)
            }
        }
    }

    private fun doStart() {
        val out = FileNamer.videoFile()
        file = out

        // Derive the orientation hint from SENSOR_ORIENTATION instead of
        // hardcoding the device's current value.
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

        // Hand the recorder input Surface to the shared camera session. This
        // opens the camera if needed (first holder) or rebuilds the session to
        // include the recorder output (e.g. when a frame stream is already up).
        // The camera's repeating request + frame-stall watchdog live in
        // CameraSession; a stall calls back into stop() via this lambda.
        val configured = cameraSession.setRecorderSurface(rec.surface) {
            Log.e(TAG, "camera stall while recording - stopping")
            stop { _, _, _, _ -> }
        }
        if (!configured) {
            throw IllegalStateException("camera session did not accept recorder surface")
        }

        Log.i(TAG, "MediaRecorder.start (HEVC ${WIDTH}x${HEIGHT} ${FRAME_RATE}fps vBr=$VIDEO_BITRATE aBr=$AUDIO_BITRATE)")
        val tRec = SystemClock.elapsedRealtime()
        rec.start()
        startedAtMs = SystemClock.elapsedRealtime()
        recording = true
        paused = false
        Log.i(TAG, "started: ${out.absolutePath} recStartMs=${startedAtMs - tRec}")
    }

    /** Release the MediaRecorder. The camera/session is owned by CameraSession
     *  and is detached separately via clearRecorderSurface(). */
    private fun cleanupRecorder() {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        recording = false
        paused = false
    }

    private fun cleanupQuietly() {
        Log.i(TAG, "cleanupQuietly entry recording=$recording")
        if (recording) {
            try { cameraSession.clearRecorderSurface() } catch (_: Exception) {}
            try { recorder?.stop() } catch (_: Exception) {}
        }
        cleanupRecorder()
    }

    fun shutdown() {
        handler.post { cleanupQuietly() }
        handlerThread.quitSafely()
        executor.shutdown()
    }
}
