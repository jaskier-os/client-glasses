package com.repository.glasses.listener.capture

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Composites two MP4 videos (camera + UI) into a single output via GLES2.
 *
 * Pipeline (single GL thread):
 *   MediaExtractor + MediaCodec decoder per source -> SurfaceTexture (OES texture)
 *   -> fragment shader (luma-as-alpha for UI, opaque for camera, with rotation
 *      done in the texture matrix for the camera) -> encoder input EGLSurface
 *   -> MediaCodec H.264 encoder -> MediaMuxer.
 *
 * The earlier CPU implementation -- MediaMetadataRetriever.getFrameAtTime per
 * output frame -- was ~1 s per frame on this Cortex-A55; the GLES path runs
 * roughly real-time. Public API and `fixTimestamps()` audio remux are
 * unchanged.
 */
class VideoCompositor {

    var remoteLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        android.util.Log.i("VComp", msg)
        remoteLog?.invoke(msg)
    }
    private fun logE(msg: String, t: Throwable? = null) {
        if (t != null) android.util.Log.e("VComp", msg, t) else android.util.Log.e("VComp", msg)
        remoteLog?.invoke(msg)
    }

    private var thread: HandlerThread? = null
    // Separate thread for SurfaceTexture.OnFrameAvailableListener dispatch.
    // The GL thread blocks on frameLock.wait() waiting for the listener to
    // fire; if the listener were dispatched on the GL thread's looper (which
    // is what happens with the no-Handler overload of
    // setOnFrameAvailableListener since the SurfaceTexture is *created* on
    // the GL thread), we'd deadlock. Routing the listener through this
    // notify thread lets it set frameAvailable + notifyAll while the GL
    // thread sleeps, so wait() returns immediately.
    private var notifyThread: HandlerThread? = null
    private var notifyHandler: Handler? = null

    /**
     * @param uiPath Optional path to the HUD overlay mp4. Pass null to transcode
     *   the camera file without any overlay.
     * @param orientation "portrait" -> camera sensor is landscape-shaped, gets
     *   transposed to portrait output. "landscape" -> sensor portrait-shaped,
     *   transposed to landscape output. Either way the GL rotation is the same
     *   (270 deg CCW); only the output dimensions differ.
     */
    fun composite(
        cameraPath: String,
        uiPath: String?,
        outputPath: String,
        orientation: String = "portrait",
        callback: (String?) -> Unit
    ) {
        thread = HandlerThread("VideoCompositor-GL").also { it.start() }
        notifyThread = HandlerThread("VideoCompositor-Notify").also { it.start() }
        notifyHandler = Handler(notifyThread!!.looper)
        Handler(thread!!.looper).post {
            val result = try {
                doComposite(cameraPath, uiPath, outputPath, orientation)
            } catch (t: Throwable) {
                logE("VideoCompositor: error: ${t.javaClass.simpleName}: ${t.message}", t)
                null
            }
            Handler(android.os.Looper.getMainLooper()).post { callback(result) }
            thread?.quitSafely()
            notifyThread?.quitSafely()
            thread = null
            notifyThread = null
            notifyHandler = null
        }
    }

    private fun doComposite(cameraPath: String, uiPath: String?, outputPath: String, orientation: String): String? {
        log("VideoCompositor:compositing (GL)...")

        // 1) Probe source dimensions + duration via MediaExtractor track formats.
        val camProbe = openVideoTrack(cameraPath) ?: return null
        val camRawW = camProbe.format.getInteger(MediaFormat.KEY_WIDTH)
        val camRawH = camProbe.format.getInteger(MediaFormat.KEY_HEIGHT)
        val camDurationUs = camProbe.format.getLong(MediaFormat.KEY_DURATION)
        camProbe.extractor.release()

        val uiProbe = uiPath?.let { openVideoTrack(it) }
        val uiDurationUs = uiProbe?.format?.getLong(MediaFormat.KEY_DURATION) ?: camDurationUs
        val uiRawW = uiProbe?.format?.getInteger(MediaFormat.KEY_WIDTH) ?: 0
        val uiRawH = uiProbe?.format?.getInteger(MediaFormat.KEY_HEIGHT) ?: 0
        uiProbe?.extractor?.release()

        val durationUs = if (uiProbe != null) minOf(camDurationUs, uiDurationUs) else camDurationUs
        if (durationUs <= 0L) {
            log("VideoCompositor:invalid duration cam=${camDurationUs}us ui=${uiDurationUs}us")
            return null
        }

        // 2) Output is the camera frame transposed (we always rotate sensor 270 deg CCW).
        val outWidth = camRawH
        val outHeight = camRawW

        val fps = 15
        val frameDurationUs = 1_000_000L / fps
        val totalFrames = (durationUs / frameDurationUs).toInt()
        remoteLog?.invoke(
            "VideoCompositor: cam=${camRawW}x${camRawH} ui=${uiRawW}x${uiRawH} " +
                    "out=${outWidth}x${outHeight} ${durationUs / 1000}ms ${totalFrames}f @${fps}fps " +
                    "orientation=$orientation"
        )

        // 3) Encoder + muxer.
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrack = -1
        var muxerStarted = false

        // 4) EGL + GL program.
        val egl = EglCore()
        val windowSurface = egl.createWindowSurface(encoderInputSurface)
        egl.makeCurrent(windowSurface)
        val program = OesProgram()

        encoder.start()

        // 5) Decoder sources.
        val camSource = DecoderSource(cameraPath, "cam")
        val uiSource = if (uiPath != null) DecoderSource(uiPath, "ui") else null

        var framesWritten = 0
        try {
            // Camera transform matrix: stTransform * rotate-around-center 270 deg CCW.
            val camTexMtx = FloatArray(16)
            val rot = FloatArray(16)
            Matrix.setIdentityM(rot, 0)
            Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(rot, 0, 270f, 0f, 0f, 1f)
            Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
            val stMtx = FloatArray(16)

            // UI MVP: aspect-fit centered. Computed once -- UI dims are constant per file.
            val uiMvp = FloatArray(16)
            Matrix.setIdentityM(uiMvp, 0)
            if (uiSource != null && uiRawW > 0 && uiRawH > 0) {
                val s = minOf(outWidth.toFloat() / uiRawW, outHeight.toFloat() / uiRawH)
                val drawW = uiRawW * s
                val drawH = uiRawH * s
                Matrix.scaleM(uiMvp, 0, drawW / outWidth, drawH / outHeight, 1f)
            }
            val uiTexMtx = FloatArray(16)

            val identityMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

            for (i in 0 until totalFrames) {
                val targetUs = i * frameDurationUs

                camSource.advanceUntil(targetUs)
                uiSource?.advanceUntil(targetUs)

                GLES20.glViewport(0, 0, outWidth, outHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                // Camera: opaque, full quad, rotated read.
                if (camSource.lastFrameValid) {
                    camSource.surfaceTexture.getTransformMatrix(stMtx)
                    Matrix.multiplyMM(camTexMtx, 0, stMtx, 0, rot, 0)
                    GLES20.glDisable(GLES20.GL_BLEND)
                    program.draw(
                        textureId = camSource.textureId,
                        mvp = identityMvp,
                        texMtx = camTexMtx,
                        mode = MODE_OPAQUE
                    )
                }

                // UI: luma-as-alpha, aspect-fit, blended on top.
                if (uiSource != null && uiSource.lastFrameValid) {
                    uiSource.surfaceTexture.getTransformMatrix(uiTexMtx)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    program.draw(
                        textureId = uiSource.textureId,
                        mvp = uiMvp,
                        texMtx = uiTexMtx,
                        mode = MODE_LUMA_ALPHA
                    )
                    GLES20.glDisable(GLES20.GL_BLEND)
                }

                EGLExt.eglPresentationTimeANDROID(egl.display, windowSurface, targetUs * 1000L)
                EGL14.eglSwapBuffers(egl.display, windowSurface)
                framesWritten++

                val r = drainEncoder(encoder, muxer, muxerTrack, muxerStarted, false)
                muxerTrack = r.first
                muxerStarted = r.second

                if (i % 30 == 0) log("VideoCompositor:frame $i/$totalFrames")
            }

            encoder.signalEndOfInputStream()
            val r = drainEncoder(encoder, muxer, muxerTrack, muxerStarted, true)
            muxerTrack = r.first
            muxerStarted = r.second
        } finally {
            // Teardown order matters: GL must release the encoder Surface ref before encoder.release().
            try { program.release() } catch (_: Exception) {}
            try { egl.releaseSurface(windowSurface) } catch (_: Exception) {}
            try { egl.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { encoderInputSurface.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (e: Exception) {
                log("VideoCompositor:muxer.stop() failed: ${e.message}")
            }
            try { muxer.release() } catch (_: Exception) {}
            try { camSource.release() } catch (_: Exception) {}
            try { uiSource?.release() } catch (_: Exception) {}
        }

        val outFile = File(outputPath)
        if (!outFile.exists() || outFile.length() == 0L) {
            log("VideoCompositor:composite file missing or empty")
            return null
        }

        // Existing audio-copy remux (also normalises video PTS to fixed fps grid).
        log("VideoCompositor:re-muxing (video+audio) at ${fps}fps frames=$framesWritten...")
        val fixedPath = outputPath.replace(".mp4", "_fixed.mp4")
        val fixed = fixTimestamps(outputPath, cameraPath, fixedPath, fps)
        if (fixed) {
            File(outputPath).delete()
            File(fixedPath).renameTo(File(outputPath))
            log("VideoCompositor:remux done, size=${File(outputPath).length() / 1024}KB")
        }
        log("VideoCompositor:done frames=$framesWritten size=${File(outputPath).length() / 1024}KB")
        return outputPath
    }

    // --------------------------------------------------------------------- //
    // Helpers
    // --------------------------------------------------------------------- //

    private data class TrackProbe(val extractor: MediaExtractor, val format: MediaFormat, val trackIndex: Int)

    private fun openVideoTrack(path: String): TrackProbe? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return TrackProbe(extractor, f, i)
                }
            }
            extractor.release()
        } catch (e: Exception) {
            log("VideoCompositor:openVideoTrack($path) failed: ${e.message}")
            try { extractor.release() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * One MediaExtractor + MediaCodec decoder + SurfaceTexture, with
     * per-frame-available sync. The decoder writes YUV/whatever into the
     * SurfaceTexture; we sample it as an external OES texture in our shader.
     */
    private inner class DecoderSource(path: String, private val tag: String) {
        val textureId: Int
        val surfaceTexture: SurfaceTexture
        private val decoderSurface: Surface
        private val extractor: MediaExtractor
        private val decoder: MediaCodec
        private val trackIndex: Int

        private var inputEos = false
        private var outputEos = false

        // Latest decoded frame's PTS (us). Updated *after* updateTexImage().
        private var lastPtsUs: Long = -1L
        var lastFrameValid: Boolean = false
            private set

        // Frame-available sync: SurfaceTexture's listener fires from an
        // arbitrary background thread, so a Java monitor is sufficient and
        // doesn't deadlock the GL thread that's blocked waiting.
        private val frameLock = Object()
        @Volatile private var frameAvailable = false

        init {
            // Generate the OES texture, attach a SurfaceTexture, then a Surface for the decoder.
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkGlError("DecoderSource[$tag] tex setup")

            surfaceTexture = SurfaceTexture(textureId)
            // Listener dispatched on notifyThread, not the GL thread, so the
            // GL thread can safely block in frameLock.wait() without deadlock.
            surfaceTexture.setOnFrameAvailableListener({ _ ->
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            }, notifyHandler)
            decoderSurface = Surface(surfaceTexture)

            val probe = openVideoTrack(path) ?: throw IllegalStateException("no video track in $path")
            extractor = probe.extractor
            trackIndex = probe.trackIndex
            extractor.selectTrack(trackIndex)
            val mime = probe.format.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(probe.format, decoderSurface, null, 0)
            decoder.start()
        }

        /**
         * Walk the decoder forward until lastPtsUs >= targetUs (or input EOS
         * with no further output). Each consumed output frame is rendered to
         * the SurfaceTexture via releaseOutputBuffer(idx, true), then
         * updateTexImage() pulls it into the OES texture.
         */
        fun advanceUntil(targetUs: Long) {
            val info = MediaCodec.BufferInfo()
            while (lastPtsUs < targetUs && !outputEos) {
                // Feed input.
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, sz, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain output.
                val outIdx = decoder.dequeueOutputBuffer(info, 1_000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Spin until input is fed; continue loop.
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Decoder format change; ignore -- output is pushed to surface anyway.
                    }
                    outIdx >= 0 -> {
                        val render = info.size > 0
                        val ptsUs = info.presentationTimeUs
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        decoder.releaseOutputBuffer(outIdx, render)
                        if (render) {
                            // Wait for the frame to land on the SurfaceTexture's queue, with bounded timeout.
                            synchronized(frameLock) {
                                val deadline = System.nanoTime() + 50_000_000L
                                while (!frameAvailable) {
                                    val remainNs = deadline - System.nanoTime()
                                    if (remainNs <= 0) break
                                    val ms = remainNs / 1_000_000L
                                    val nsRem = (remainNs % 1_000_000L).toInt()
                                    try { frameLock.wait(ms, nsRem) } catch (_: InterruptedException) { break }
                                }
                                if (frameAvailable) {
                                    frameAvailable = false
                                    surfaceTexture.updateTexImage()
                                    lastPtsUs = ptsUs
                                    lastFrameValid = true
                                }
                            }
                        }
                        if (eos) {
                            outputEos = true
                            break
                        }
                    }
                }
            }
        }

        fun release() {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { decoderSurface.release() } catch (_: Exception) {}
            try { surfaceTexture.release() } catch (_: Exception) {}
        }
    }

    /**
     * Minimal EGL14 wrapper. Single context, single window surface.
     */
    private inner class EglCore {
        val display: EGLDisplay
        private val context: EGLContext
        private val config: EGLConfig

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw RuntimeException("eglInitialize failed")

            val cfgAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, configs.size, numCfg, 0) || numCfg[0] == 0) {
                throw RuntimeException("eglChooseConfig failed")
            }
            config = configs[0]!!

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
        }

        fun createWindowSurface(surface: Surface): EGLSurface {
            val attribs = intArrayOf(EGL14.EGL_NONE)
            val s = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
            if (s == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
            return s
        }

        fun makeCurrent(s: EGLSurface) {
            if (!EGL14.eglMakeCurrent(display, s, s, context)) throw RuntimeException("eglMakeCurrent failed")
        }

        fun releaseSurface(s: EGLSurface) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, s)
        }

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        }
    }

    private companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val MODE_OPAQUE = 0
        private const val MODE_LUMA_ALPHA = 1

        private const val V_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uMvp;
            uniform mat4 uTexMtx;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPos;
                vTex = (uTexMtx * aTex).xy;
            }
        """

        private const val F_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform int uMode;
            varying vec2 vTex;
            void main() {
                vec4 c = texture2D(uTex, vTex);
                if (uMode == 0) {
                    gl_FragColor = vec4(c.rgb, 1.0);
                } else {
                    float a = max(c.r, max(c.g, c.b));
                    gl_FragColor = vec4(c.rgb, a);
                }
            }
        """

        // Full-screen quad. NDC pos in xy, tex coord in zw of pos slot? No --
        // we keep them as separate vec4s for clarity; aTex is (s, t, 0, 1).
        private val QUAD_POS = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        private val QUAD_TEX = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )
    }

    /**
     * GLES2 program that samples one OES external texture with a configurable
     * MVP and texture matrix. Mode 0 = opaque (camera), mode 1 = luma-as-alpha
     * (UI overlay).
     */
    private inner class OesProgram {
        private val prog: Int
        private val aPosLoc: Int
        private val aTexLoc: Int
        private val uMvpLoc: Int
        private val uTexMtxLoc: Int
        private val uTexLoc: Int
        private val uModeLoc: Int

        private val posBuf: FloatBuffer = ByteBuffer.allocateDirect(QUAD_POS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(QUAD_POS); position(0) }
        private val texBuf: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(QUAD_TEX); position(0) }

        init {
            val v = compileShader(GLES20.GL_VERTEX_SHADER, V_SHADER)
            val f = compileShader(GLES20.GL_FRAGMENT_SHADER, F_SHADER)
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, v); GLES20.glAttachShader(prog, f)
            GLES20.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("program link failed: $log")
            }
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)

            aPosLoc = GLES20.glGetAttribLocation(prog, "aPos")
            aTexLoc = GLES20.glGetAttribLocation(prog, "aTex")
            uMvpLoc = GLES20.glGetUniformLocation(prog, "uMvp")
            uTexMtxLoc = GLES20.glGetUniformLocation(prog, "uTexMtx")
            uTexLoc = GLES20.glGetUniformLocation(prog, "uTex")
            uModeLoc = GLES20.glGetUniformLocation(prog, "uMode")
        }

        fun draw(textureId: Int, mvp: FloatArray, texMtx: FloatArray, mode: Int) {
            GLES20.glUseProgram(prog)

            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uTexMtxLoc, 1, false, texMtx, 0)
            GLES20.glUniform1i(uModeLoc, mode)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexLoc, 0)

            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 4, GLES20.GL_FLOAT, false, 0, texBuf)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPosLoc)
            GLES20.glDisableVertexAttribArray(aTexLoc)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        fun release() {
            GLES20.glDeleteProgram(prog)
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("shader compile failed: $log\n$src")
            }
            return s
        }

    }

    private fun checkGlError(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) {
            log("VideoCompositor:GL error 0x${Integer.toHexString(e)} at $where")
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec, muxer: MediaMuxer,
        trackIdx: Int, started: Boolean, eos: Boolean
    ): Pair<Int, Boolean> {
        var track = trackIdx
        var muxerStarted = started
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos) break
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
            } else if (idx >= 0) {
                val buf = encoder.getOutputBuffer(idx) ?: continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && muxerStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buf, info)
                }
                encoder.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return Pair(track, muxerStarted)
    }

    /**
     * Re-mux video at evenly-spaced FPS timestamps and copy the audio track
     * from the source camera mp4. The composite encoder is video-only, but
     * the source camera file already contains AAC audio captured during
     * recording, so we mux it in as-is here. Unchanged from the previous
     * implementation.
     */
    private fun fixTimestamps(
        videoSourcePath: String,
        audioSourcePath: String?,
        outputPath: String,
        fps: Int
    ): Boolean {
        val frameDurationUs = 1_000_000L / fps
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        try {
            videoExtractor.setDataSource(videoSourcePath)
            var videoTrackIdx = -1
            for (i in 0 until videoExtractor.trackCount) {
                val mime = videoExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { videoTrackIdx = i; break }
            }
            if (videoTrackIdx < 0) return false
            videoExtractor.selectTrack(videoTrackIdx)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null
            if (audioSourcePath != null) {
                try {
                    audioExtractor = MediaExtractor().apply { setDataSource(audioSourcePath) }
                    for (i in 0 until audioExtractor.trackCount) {
                        val mime = audioExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("audio/")) {
                            audioTrackIdx = i
                            audioFormat = audioExtractor.getTrackFormat(i)
                            break
                        }
                    }
                    if (audioTrackIdx < 0) {
                        log("VideoCompositor:no audio track in $audioSourcePath")
                        audioExtractor.release(); audioExtractor = null
                    } else {
                        audioExtractor.selectTrack(audioTrackIdx)
                    }
                } catch (e: Exception) {
                    log("VideoCompositor:audio extractor failed: ${e.message}")
                    audioExtractor?.release(); audioExtractor = null
                }
            }

            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = if (audioFormat != null) muxer.addTrack(audioFormat) else -1
            muxer.start()

            val vbuf = ByteBuffer.allocate(1024 * 1024)
            val vinfo = MediaCodec.BufferInfo()
            var frameNum = 0L
            while (true) {
                val size = videoExtractor.readSampleData(vbuf, 0)
                if (size < 0) break
                vinfo.offset = 0
                vinfo.size = size
                vinfo.presentationTimeUs = frameNum * frameDurationUs
                vinfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(outVideoTrack, vbuf, vinfo)
                frameNum++
                videoExtractor.advance()
            }

            if (audioExtractor != null && outAudioTrack >= 0) {
                val abuf = ByteBuffer.allocate(256 * 1024)
                val ainfo = MediaCodec.BufferInfo()
                var aSamples = 0L
                while (true) {
                    val size = audioExtractor.readSampleData(abuf, 0)
                    if (size < 0) break
                    ainfo.offset = 0
                    ainfo.size = size
                    ainfo.presentationTimeUs = audioExtractor.sampleTime
                    ainfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(outAudioTrack, abuf, ainfo)
                    aSamples++
                    audioExtractor.advance()
                }
                log("VideoCompositor:copied audio samples=$aSamples")
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()
            audioExtractor?.release()
            return true
        } catch (e: Exception) {
            log("VideoCompositor:fixTimestamps failed: ${e.message}")
            videoExtractor.release()
            audioExtractor?.release()
            return false
        }
    }
}
