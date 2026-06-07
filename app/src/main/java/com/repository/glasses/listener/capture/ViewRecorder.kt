package com.repository.glasses.listener.capture

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.View
import java.io.File

/**
 * Records an Android View hierarchy to MP4 video by drawing it to a MediaCodec input Surface.
 * Uses Choreographer for vsync-accurate frame timing.
 * Runs entirely in the Activity process -- no MediaProjection or system permissions needed.
 */
class ViewRecorder {

    companion object {
        private const val TAG = "App:ViewRec"
        private const val LOG_EVERY_N_FRAMES = 30
    }

    var remoteLog: ((String) -> Unit)? = null
    private var encodedFrames = 0L

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null
    private var frameCount = 0
    private var skipFrames = 1 // Draw every Nth vsync (1 = every frame, 2 = every other)

    @Volatile
    var isRecording = false
        private set

    private var outputFile: File? = null

    fun start(rootView: View, output: File, fps: Int = 30, callback: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "event=start fps=$fps file=${output.name}")
        if (isRecording) {
            remoteLog?.invoke("ViewRecorder: already recording")
            callback?.invoke(false)
            return
        }

        outputFile = output
        output.parentFile?.mkdirs()

        // Reset per-recording state. The instance is reused across recordings
        // (MainActivity holds a single ViewRecorder), so stale flags from the
        // previous session would otherwise make drainEncoder skip addTrack on
        // the new muxer, leaving an empty mp4.
        trackIndex = -1
        muxerStarted = false
        encodedFrames = 0
        frameCount = 0

        val width = rootView.width
        val height = rootView.height
        if (width == 0 || height == 0) {
            remoteLog?.invoke("ViewRecorder: view has zero size ${width}x${height}")
            callback?.invoke(false)
            return
        }

        // On a 60Hz display, skipFrames=2 gives 30fps, skipFrames=1 gives 60fps
        skipFrames = if (fps >= 60) 1 else 2

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000) // High bitrate to keep black pixels clean
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            // Pick a software AVC encoder explicitly. The camera pipeline
            // (ArVideoRecorder/MediaRecorder) holds the single hardware encoder
            // slot on this Qualcomm SoC; without this, the View encoder gets a
            // hardware codec instance that never emits output frames, leaving
            // an empty mp4 on every AR recording after the first.
            val swEncoderName = pickSoftwareAvcEncoder()
            encoder = if (swEncoderName != null) {
                remoteLog?.invoke("ViewRecorder: using software encoder=$swEncoderName")
                MediaCodec.createByCodecName(swEncoderName)
            } else {
                remoteLog?.invoke("ViewRecorder: no software AVC found, falling back to default")
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder!!.createInputSurface()
            encoder!!.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            drainThread = HandlerThread("ViewRecorderDrain").also { it.start() }
            drainHandler = Handler(drainThread!!.looper)

            isRecording = true
            frameCount = 0

            // Use Choreographer for vsync-synced frame capture
            val choreographer = Choreographer.getInstance()
            val frameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!isRecording) return
                    frameCount++
                    if (frameCount % skipFrames == 0) {
                        drawFrame(rootView)
                        drainHandler?.post { drainEncoder(false) }
                    }
                    choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(frameCallback)

            remoteLog?.invoke("ViewRecorder: started ${width}x${height} @ ${fps}fps (skip=$skipFrames) -> ${output.name}")
            callback?.invoke(true)
        } catch (e: Exception) {
            remoteLog?.invoke("ViewRecorder: start failed: ${e.message}")
            cleanup()
            callback?.invoke(false)
        }
    }

    private fun pickSoftwareAvcEncoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.isHardwareAccelerated) continue
            val supports = info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            if (supports) return info.name
        }
        return null
    }

    private fun drawFrame(rootView: View) {
        val surface = inputSurface ?: return
        try {
            val canvas: Canvas = surface.lockHardwareCanvas()
                ?: surface.lockCanvas(null)
                ?: return
            canvas.drawColor(android.graphics.Color.BLACK)
            rootView.draw(canvas)
            surface.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        if (endOfStream) {
            try { enc.signalEndOfInputStream() } catch (e: Exception) {
                remoteLog?.invoke("ViewRecorder: signalEOS failed: ${e.message}")
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = try {
                enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            } catch (e: Exception) {
                remoteLog?.invoke("ViewRecorder: dequeue failed: ${e.message}")
                break
            }

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) continue
                try {
                    val fmt = enc.outputFormat
                    val idx = mux.addTrack(fmt)
                    if (idx < 0) {
                        remoteLog?.invoke("ViewRecorder: addTrack returned $idx, fmt=$fmt")
                        break
                    }
                    trackIndex = idx
                    mux.start()
                    muxerStarted = true
                    remoteLog?.invoke("ViewRecorder: muxer started track=$trackIndex")
                } catch (e: Exception) {
                    remoteLog?.invoke("ViewRecorder: muxer start failed: ${e.message}")
                    break
                }
            } else if (outputIndex >= 0) {
                try {
                    val outputBuffer = enc.getOutputBuffer(outputIndex)
                    if (outputBuffer == null) {
                        try { enc.releaseOutputBuffer(outputIndex, false) } catch (_: Exception) {}
                        continue
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted && trackIndex >= 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        encodedFrames++
                        if (encodedFrames % LOG_EVERY_N_FRAMES == 0L) {
                            Log.v(TAG, "event=video_frame n=$encodedFrames size=${bufferInfo.size}")
                        }
                    } else if (bufferInfo.size > 0 && !muxerStarted) {
                        remoteLog?.invoke("ViewRecorder: dropping pre-format-change sample size=${bufferInfo.size}")
                    }
                    enc.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } catch (e: Exception) {
                    remoteLog?.invoke("ViewRecorder: write failed (track=$trackIndex started=$muxerStarted): ${e.message}")
                    try { enc.releaseOutputBuffer(outputIndex, false) } catch (_: Exception) {}
                    break
                }
            }
        }
    }

    fun stop(callback: ((String?) -> Unit)? = null) {
        Log.d(TAG, "event=stop frames=$encodedFrames recording=$isRecording")
        if (!isRecording) {
            callback?.invoke(null)
            return
        }
        isRecording = false

        // Drain final frames on the SAME drain thread to avoid racing
        // with in-flight drainEncoder(false) calls from Choreographer ticks.
        val handler = drainHandler
        val finalize = Runnable {
            try { drainEncoder(true) } catch (e: Exception) {
                remoteLog?.invoke("ViewRecorder: final drain failed: ${e.message}")
            }
            cleanup()

            val file = outputFile
            Handler(android.os.Looper.getMainLooper()).post {
                if (file != null && file.exists() && file.length() > 0) {
                    remoteLog?.invoke("ViewRecorder: saved ${file.absolutePath} (${file.length() / 1024}KB) frames=$frameCount")
                    callback?.invoke(file.absolutePath)
                } else {
                    remoteLog?.invoke("ViewRecorder: output missing or empty (started=$muxerStarted track=$trackIndex)")
                    callback?.invoke(null)
                }
            }
        }
        if (handler != null) handler.postDelayed(finalize, 300)
        else Handler(android.os.Looper.getMainLooper()).postDelayed(finalize, 300)
    }

    private fun cleanup() {
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        drainThread?.quitSafely()
        drainThread = null
        drainHandler = null
    }
}
