package com.repository.glasses.listener.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the glasses HUD/screen overlay using MediaProjection API.
 * Requires MediaProjection consent token from Activity.
 */
class HudRecorder(private val context: Context) {

    companion object {
        private const val TAG = "HudRecorder"
    }

    var remoteLog: ((String) -> Unit)? = null

    @Volatile
    private var mediaProjection: MediaProjection? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var mediaRecorder: MediaRecorder? = null
    @Volatile
    var isRecording = false
        private set

    private var outputFile: File? = null
    private var projectionResultCode: Int = 0
    private var projectionResultData: Intent? = null

    fun setProjectionToken(resultCode: Int, resultData: Intent) {
        projectionResultCode = resultCode
        projectionResultData = resultData
        remoteLog?.invoke("$TAG: projection token stored")
    }

    val hasProjectionToken: Boolean
        get() = projectionResultData != null

    fun start(durationSeconds: Int, callback: ((String?) -> Unit)? = null) {
        if (isRecording) {
            remoteLog?.invoke("$TAG: already recording, ignoring start")
            callback?.invoke(null)
            return
        }

        val data = projectionResultData
        if (data == null) {
            remoteLog?.invoke("$TAG: no projection token -- cannot record")
            callback?.invoke(null)
            return
        }

        try {
            val projManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projManager.getMediaProjection(projectionResultCode, data.clone() as Intent)
            remoteLog?.invoke("$TAG: MediaProjection created")
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: failed to create MediaProjection: ${e.message}")
            callback?.invoke(null)
            return
        }

        // Get display metrics
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDpi = metrics.densityDpi
        remoteLog?.invoke("$TAG: display ${screenWidth}x${screenHeight} @ ${screenDpi}dpi")

        // Create output file in ScreenRecorder directory (same as Rokid system app)
        val screenRecDir = File(Environment.getExternalStorageDirectory(), "ScreenRecorder")
        if (!screenRecDir.exists()) screenRecDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        outputFile = File(screenRecDir, "hud-$timestamp.mp4")
        remoteLog?.invoke("$TAG: output file: ${outputFile!!.absolutePath}")

        try {
            mediaRecorder = MediaRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile!!.absolutePath)
                setVideoEncodingBitRate(2_000_000)
                setVideoFrameRate(30)
                setVideoSize(screenWidth, screenHeight)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (durationSeconds > 0) {
                    setMaxDuration(durationSeconds * 1000)
                }
                prepare()
            }
            remoteLog?.invoke("$TAG: MediaRecorder prepared")
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: MediaRecorder prepare failed: ${e.message}")
            cleanup()
            callback?.invoke(null)
            return
        }

        try {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "HudRecorder",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder!!.surface,
                null, null
            )
            remoteLog?.invoke("$TAG: VirtualDisplay created")
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: VirtualDisplay creation failed: ${e.message}")
            cleanup()
            callback?.invoke(null)
            return
        }

        try {
            mediaRecorder!!.start()
            isRecording = true
            remoteLog?.invoke("$TAG: recording started (${durationSeconds}s)")
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: MediaRecorder start failed: ${e.message}")
            cleanup()
            callback?.invoke(null)
            return
        }

        // Auto-stop after duration
        if (durationSeconds > 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                stop(callback)
            }, durationSeconds * 1000L)
        }
    }

    fun stop(callback: ((String?) -> Unit)? = null) {
        if (!isRecording) {
            remoteLog?.invoke("$TAG: not recording, ignoring stop")
            callback?.invoke(null)
            return
        }
        isRecording = false

        try {
            mediaRecorder?.stop()
            remoteLog?.invoke("$TAG: recording stopped")
        } catch (e: Exception) {
            remoteLog?.invoke("$TAG: stop failed: ${e.message}")
        }

        cleanup()

        val file = outputFile
        if (file != null && file.exists() && file.length() > 0) {
            remoteLog?.invoke("$TAG: output saved: ${file.absolutePath} (${file.length() / 1024}KB)")
            callback?.invoke(file.absolutePath)
        } else {
            remoteLog?.invoke("$TAG: output file missing or empty")
            callback?.invoke(null)
        }
    }

    private fun cleanup() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }
}
