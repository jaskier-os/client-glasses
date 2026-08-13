package com.repository.glasses.listener.arstream

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Composites the world camera and the HUD overlay into ONE live H.264 stream.
 *
 * Unlike [com.repository.glasses.listener.capture.VideoCompositor], which merges two finished
 * MP4 files after the fact, this runs in real time:
 *
 *   camera -> SurfaceTexture (OES, base layer)  --.
 *                                                 |-> GLES2 (luma-as-alpha overlay)
 *   MainActivity draws root view -> SurfaceTexture --'      -> encoder input EGLSurface
 *                                                           -> MediaCodec AVC -> callback
 *
 * The HUD lives in the UI process, so its SurfaceTexture's [Surface] is handed out via
 * [start]'s hudSurfaceReady callback and passed to MainActivity over binder.
 *
 * This uses the HARDWARE AVC encoder. ViewRecorder deliberately picks a software encoder because
 * ArVideoRecorder holds the single hardware slot during a normal AR recording -- in live mode
 * neither of those runs, so the hardware encoder is free.
 */
class LiveArCompositor(private val context: Context) {

    var remoteLog: ((String) -> Unit)? = null

    /** payload, isKeyframe, isConfig. */
    private var onEncodedFrame: ((ByteArray, Boolean, Boolean) -> Unit)? = null

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var notifyThread: HandlerThread? = null
    private var notifyHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null

    private var egl: EglCore? = null
    private var eglSurface: EGLSurface? = null
    private var program: OesProgram? = null

    private var cameraTexId = 0
    private var hudTexId = 0
    private var cameraTexture: SurfaceTexture? = null
    private var hudTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var hudSurface: Surface? = null

    @Volatile private var running = false
    @Volatile private var cameraFrameAvailable = false
    @Volatile private var hudFrameAvailable = false
    private val frameLock = Object()

    /** Cached codec-config (SPS/PPS) blob, re-sent to every new client. */
    @Volatile var configFrame: ByteArray? = null
        private set

    private val cameraTexMtx = FloatArray(16)
    private val hudTexMtx = FloatArray(16)
    private val identityMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
        remoteLog?.invoke(msg)
    }

    /**
     * @param hudSurfaceReady invoked once with the Surface the UI process must draw its root
     *   view into, plus the dimensions to draw at.
     */
    fun start(
        onFrame: (ByteArray, Boolean, Boolean) -> Unit,
        hudSurfaceReady: (Surface, Int, Int) -> Unit,
        callback: (Boolean) -> Unit
    ) {
        if (running) {
            log("LiveArCompositor: already running")
            callback(false)
            return
        }
        onEncodedFrame = onFrame

        glThread = HandlerThread("LiveArComp-GL").also { it.start() }
        glHandler = Handler(glThread!!.looper)
        // SurfaceTexture listeners must not be dispatched on the GL thread: the GL loop blocks
        // waiting for a frame, and self-dispatch would deadlock (same reason VideoCompositor
        // keeps a separate notify thread).
        notifyThread = HandlerThread("LiveArComp-Notify").also { it.start() }
        notifyHandler = Handler(notifyThread!!.looper)

        glHandler!!.post {
            try {
                setupOnGlThread(hudSurfaceReady)
                running = true
                callback(true)
                renderLoop()
            } catch (t: Throwable) {
                log("LiveArCompositor: setup failed: ${t.javaClass.simpleName}: ${t.message}")
                releaseOnGlThread()
                callback(false)
            }
        }
    }

    private fun setupOnGlThread(hudSurfaceReady: (Surface, Int, Int) -> Unit) {
        // 1) Encoder first -- its input Surface is the EGL window surface.
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            // 1s GOP: a client joining mid-stream waits at most a second for a decodable frame.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val enc = MediaCodec.createEncoderByType(MIME)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = enc.createInputSurface()
        enc.start()
        encoder = enc

        // 2) EGL on the encoder surface.
        val core = EglCore()
        egl = core
        eglSurface = core.createWindowSurface(encoderSurface!!)
        core.makeCurrent(eglSurface!!)
        program = OesProgram()

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // 3) Two OES textures + their SurfaceTextures.
        cameraTexId = createOesTexture()
        hudTexId = createOesTexture()

        cameraTexture = SurfaceTexture(cameraTexId).apply {
            setDefaultBufferSize(WIDTH, HEIGHT)
            setOnFrameAvailableListener({
                synchronized(frameLock) { cameraFrameAvailable = true; frameLock.notifyAll() }
            }, notifyHandler)
        }
        cameraSurface = Surface(cameraTexture)

        hudTexture = SurfaceTexture(hudTexId).apply {
            setDefaultBufferSize(WIDTH, HEIGHT)
            setOnFrameAvailableListener({
                synchronized(frameLock) { hudFrameAvailable = true; frameLock.notifyAll() }
            }, notifyHandler)
        }
        hudSurface = Surface(hudTexture)

        // 4) Camera.
        openCamera()

        hudSurfaceReady(hudSurface!!, WIDTH, HEIGHT)
        log("LiveArCompositor: started ${WIDTH}x$HEIGHT @${FPS}fps")
    }

    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager) ?: throw RuntimeException("no camera")

        val opened = Object()
        var failure: String? = null

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                try {
                    configureSession(camera)
                } catch (t: Throwable) {
                    failure = "configureSession: ${t.message}"
                }
                synchronized(opened) { opened.notifyAll() }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); cameraDevice = null
                failure = "camera disconnected"
                synchronized(opened) { opened.notifyAll() }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); cameraDevice = null
                failure = "camera error $error"
                synchronized(opened) { opened.notifyAll() }
            }
        }, glHandler)

        synchronized(opened) { opened.wait(CAMERA_OPEN_TIMEOUT_MS) }
        failure?.let { throw RuntimeException(it) }
        if (cameraDevice == null) throw RuntimeException("camera open timed out")
    }

    private fun configureSession(camera: CameraDevice) {
        val target = cameraSurface ?: throw RuntimeException("no camera surface")
        val stateCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(target)
                // Fixed-focus AR camera: same 3A setup ArVideoRecorder uses.
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(FPS, FPS)
                )
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                session.setRepeatingRequest(builder.build(), null, glHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                log("LiveArCompositor: capture session config failed")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "LiveArComp-cb") }
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(target)),
                    exec,
                    stateCb
                )
            )
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(target), stateCb, glHandler)
        }
    }

    private fun renderLoop() {
        val startNanos = System.nanoTime()
        while (running) {
            synchronized(frameLock) {
                if (!cameraFrameAvailable && !hudFrameAvailable) {
                    try { frameLock.wait(FRAME_WAIT_MS) } catch (_: InterruptedException) {}
                }
            }
            if (!running) break

            try {
                // Always update both textures we have new data for; the HUD updates at its own
                // pace and simply reuses its last frame when the UI process is idle.
                if (cameraFrameAvailable) {
                    synchronized(frameLock) { cameraFrameAvailable = false }
                    cameraTexture?.updateTexImage()
                    cameraTexture?.getTransformMatrix(cameraTexMtx)
                }
                if (hudFrameAvailable) {
                    synchronized(frameLock) { hudFrameAvailable = false }
                    hudTexture?.updateTexImage()
                    hudTexture?.getTransformMatrix(hudTexMtx)
                }

                GLES20.glViewport(0, 0, WIDTH, HEIGHT)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                program?.draw(cameraTexId, identityMvp, cameraTexMtx, MODE_OPAQUE)
                program?.draw(hudTexId, identityMvp, hudTexMtx, MODE_LUMA_ALPHA)

                egl?.setPresentationTime(eglSurface!!, System.nanoTime() - startNanos)
                egl?.swapBuffers(eglSurface!!)

                drainEncoder()
            } catch (t: Throwable) {
                if (running) log("LiveArCompositor: render error: ${t.message}")
            }
        }
        releaseOnGlThread()
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx < 0) continue

            val buf: ByteBuffer = enc.getOutputBuffer(idx) ?: continue
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val payload = ByteArray(info.size)
            buf.get(payload)
            enc.releaseOutputBuffer(idx, false)

            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (isConfig) {
                // Cache it: ScreenStreamDecoder caches no SPS/PPS, so every new client and every
                // rebuilt decoder needs this replayed or it renders black forever, silently.
                configFrame = payload
            }
            onEncodedFrame?.invoke(payload, isKey, isConfig)
        }
    }

    /** Ask the encoder for an IDR, e.g. when a client connects or its Surface was recreated. */
    fun requestKeyframe() {
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(params)
        } catch (e: Exception) {
            log("LiveArCompositor: requestKeyframe failed: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        synchronized(frameLock) { frameLock.notifyAll() }
        glThread?.quitSafely()
        notifyThread?.quitSafely()
        glThread = null
        notifyThread = null
        notifyHandler = null
        glHandler = null
        log("LiveArCompositor: stopped")
    }

    private fun releaseOnGlThread() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null

        try { cameraSurface?.release() } catch (_: Exception) {}
        try { hudSurface?.release() } catch (_: Exception) {}
        try { cameraTexture?.release() } catch (_: Exception) {}
        try { hudTexture?.release() } catch (_: Exception) {}
        cameraSurface = null; hudSurface = null
        cameraTexture = null; hudTexture = null

        try { program?.release() } catch (_: Exception) {}
        program = null
        try { eglSurface?.let { egl?.releaseSurface(it) } } catch (_: Exception) {}
        eglSurface = null
        try { egl?.release() } catch (_: Exception) {}
        egl = null
        configFrame = null
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return ids[0]
    }

    private fun findCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK ||
                facing == CameraCharacteristics.LENS_FACING_EXTERNAL
            ) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    /** Minimal EGL14 wrapper, same shape as VideoCompositor's. */
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

        fun setPresentationTime(s: EGLSurface, nanos: Long) {
            android.opengl.EGLExt.eglPresentationTimeANDROID(display, s, nanos)
        }

        fun swapBuffers(s: EGLSurface) {
            EGL14.eglSwapBuffers(display, s)
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

    /**
     * GLES2 program sampling one OES texture. Mode 0 = opaque (camera base layer),
     * mode 1 = luma-as-alpha (HUD overlay: black pixels are transparent, matching the
     * waveguide convention where black means "pixel off").
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
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_POS); position(0) }
        private val texBuf: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_TEX); position(0) }

        init {
            val v = compileShader(GLES20.GL_VERTEX_SHADER, V_SHADER)
            val f = compileShader(GLES20.GL_FRAGMENT_SHADER, F_SHADER)
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, v); GLES20.glAttachShader(prog, f)
            GLES20.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val l = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("program link failed: $l")
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

        fun release() = GLES20.glDeleteProgram(prog)

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val l = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("shader compile failed: $l")
            }
            return s
        }
    }

    private companion object {
        private const val TAG = "LiveArCompositor"
        private const val MIME = "video/avc"
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val FPS = 30
        private const val BITRATE = 4_000_000
        private const val FRAME_WAIT_MS = 100L
        private const val CAMERA_OPEN_TIMEOUT_MS = 5_000L

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

        private val QUAD_POS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        private val QUAD_TEX = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )
    }
}
