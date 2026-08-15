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

    /**
     * Camera2 callbacks MUST NOT be delivered to the GL thread: setup runs as one long message on
     * that thread and then blocks waiting for onOpened, so a callback queued behind it could never
     * run -- a guaranteed self-timeout.
     */
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    /** Capture-session callback executor; shut down with the session or it leaks a thread. */
    private var sessionExecutor: java.util.concurrent.ExecutorService? = null

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

    /** True once threads exist, so a failed start still tears them down. */
    @Volatile private var started = false
    @Volatile private var cameraFrameAvailable = false
    @Volatile private var hudFrameAvailable = false

    /** The HUD texture has no image until MainActivity draws once; sampling it before that is UB. */
    @Volatile private var hudEverUpdated = false

    private var frameIndex = 0L

    /**
     * Flicker instrumentation. Kept in production: two counter increments per frame are free, and
     * they are the only direct evidence that every emitted frame carried a HUD update. A healthy
     * stream has hudUpdatesApplied == framesRendered and fps == the camera rate (~30). The bug this
     * measures showed as a 0.67 ratio at ~90fps.
     */
    private var framesRendered = 0L
    private var hudUpdatesApplied = 0L

    @Volatile private var cameraEverUpdated = false
    @Volatile private var lastCameraFrameAtMs = 0L
    private var cameraStaleLogged = false
    private val frameLock = Object()

    /** Cached codec-config (SPS/PPS) blob, re-sent to every new client. */
    @Volatile var configFrame: ByteArray? = null
        private set

    // Identity until the first updateTexImage: a zero matrix would collapse every texture
    // coordinate to (0,0) and smear one undefined texel across the frame.
    private val cameraStMtx = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val hudTexMtx = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /**
     * The camera quad covers the WHOLE output frame, unscaled.
     *
     * The camera's own SurfaceTexture transform (getTransformMatrix) already carries the sensor
     * rotation on this hardware -- measured on-device, it transposes ([0]~0, [1]~+-1, [4]~+-1,
     * [5]~0). So the sampled content is already PORTRAIT 3:4, which is exactly the 720x960 output
     * aspect: no extra rotation and no aspect fit are needed, and applying one squashed the
     * picture to 56% height (the min(720/960, 960/720) = 0.75 fit against a wrongly-transposed
     * source aspect).
     */
    private val cameraMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /**
     * The HUD spans the WHOLE frame.
     *
     * Now that the overlay carries real alpha its black areas are transparent, so covering the
     * full frame costs nothing and matches what the wearer sees: HUD elements sit where they sit
     * on the panel, over the entire view rather than inside an inset rectangle.
     */
    private val hudMvp = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

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
        cameraThread = HandlerThread("LiveArComp-Cam").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        started = true

        glHandler!!.post {
            try {
                setupOnGlThread(hudSurfaceReady)
                running = true
                callback(true)
                renderLoop()
            } catch (t: Throwable) {
                log("LiveArCompositor: setup failed: ${t.javaClass.simpleName}: ${t.message}")
                releaseOnGlThread()
                started = false
                callback(false)
                // Threads were created in start(); without this a failed session leaks all three.
                // Safe from this thread: quitSafely() on one's own looper just ends it after the
                // current message. stop() is a no-op afterwards because started is already false.
                quitThreads()
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
        // Blending is enabled per-layer in the render loop, not globally.

        // 3) Two OES textures + their SurfaceTextures.
        cameraTexId = createOesTexture()
        hudTexId = createOesTexture()

        // Sensor-native landscape buffer; the GL rotation turns it upright into the portrait
        // encoder frame. Requesting a portrait buffer from the camera would make the HAL letterbox
        // or refuse the size.
        cameraTexture = SurfaceTexture(cameraTexId).apply {
            setDefaultBufferSize(HEIGHT, WIDTH)
            setOnFrameAvailableListener({
                synchronized(frameLock) { cameraFrameAvailable = true; frameLock.notifyAll() }
            }, notifyHandler)
        }
        cameraSurface = Surface(cameraTexture)

        // The HUD buffer is the glasses' own display size, not the stream size. The UI process
        // draws its root view 1:1 into it and the shader stretches it across the quad; sizing the
        // buffer to 1280x720 instead would leave the view in a 480x640 corner of a black frame.
        hudTexture = SurfaceTexture(hudTexId).apply {
            setDefaultBufferSize(HUD_WIDTH, HUD_HEIGHT)
            setOnFrameAvailableListener({
                synchronized(frameLock) { hudFrameAvailable = true; frameLock.notifyAll() }
            }, notifyHandler)
        }
        hudSurface = Surface(hudTexture)

        // 4) Camera.
        openCamera()

        hudSurfaceReady(hudSurface!!, HUD_WIDTH, HUD_HEIGHT)
        log("LiveArCompositor: started ${WIDTH}x$HEIGHT @${FPS}fps hud=${HUD_WIDTH}x$HUD_HEIGHT")
    }

    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager) ?: throw RuntimeException("no camera")

        // No app-side rotation: the SurfaceTexture transform already stands the frame upright
        // (logged once on the first camera frame as "stMtx=[...]" -- it transposes). Logged here
        // for the record only.
        val sensorOrientation = try {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        } catch (e: Exception) {
            270
        }
        log("LiveArCompositor: sensorOrientation=$sensorOrientation (no app-side rotation applied)")

        // A latch, not wait/notify: onOpened can complete before this thread would have reached
        // wait(), and that missed signal would cost the full timeout.
        val opened = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                try {
                    configureSession(camera)
                } catch (t: Throwable) {
                    failure.set("configureSession: ${t.message}")
                }
                opened.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); cameraDevice = null
                failure.set("camera disconnected")
                opened.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); cameraDevice = null
                // ERROR_CAMERA_IN_USE (1) means another client (the capture APK) holds the HAL.
                failure.set(
                    if (error == ERROR_CAMERA_IN_USE) {
                        "camera already in use by another app"
                    } else {
                        "camera error $error"
                    }
                )
                opened.countDown()
            }
            // Callbacks go to the CAMERA thread. Delivering them to glHandler would queue them
            // behind this very message, which is blocked below -- a guaranteed deadlock.
        }, cameraHandler)

        if (!opened.await(CAMERA_OPEN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw RuntimeException("camera open timed out")
        }
        failure.get()?.let { throw RuntimeException(it) }
        if (cameraDevice == null) throw RuntimeException("camera failed to open")
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
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                log("LiveArCompositor: capture session config failed")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "LiveArComp-cb") }
            sessionExecutor = exec
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
            camera.createCaptureSession(listOf(target), stateCb, cameraHandler)
        }
    }

    private fun renderLoop() {
        val startNanos = System.nanoTime()
        var lastStatsAtMs = System.currentTimeMillis()
        var statsBaseFrames = 0L
        while (running) {
            synchronized(frameLock) {
                // Wait on the CAMERA only. The HUD posts at display rate (~60Hz) and the camera at
                // ~30Hz; waking (and swapping) on either produced ~90 frames/s into a 30fps stream,
                // and every camera-only iteration skipped the HUD update -- which, combined with
                // BufferQueue slot recycling, sampled an all-black HUD buffer and dropped the
                // overlay for ~1 frame in 3 (the measured flicker).
                if (!cameraFrameAvailable) {
                    try { frameLock.wait(FRAME_WAIT_MS) } catch (_: InterruptedException) {}
                }
            }
            if (!running) break

            try {
                // Claim both flags under the same monitor that sets them, so a frame arriving
                // between a read and a clear cannot lose its notification.
                val takeCamera: Boolean
                synchronized(frameLock) {
                    takeCamera = cameraFrameAvailable
                    cameraFrameAvailable = false
                }
                // Output rate == camera rate. No camera buffer means nothing new to encode, so do
                // NOT draw and do NOT swap: an extra swap here is a duplicate frame the encoder
                // has to spend bitrate on and a chance to sample a half-recycled HUD slot.
                // hudFrameAvailable is deliberately NOT cleared here -- clearing it on a skipped
                // iteration would lose the only signal that the UI process has ever drawn.
                if (!takeCamera) continue

                // Claimed separately, and only on iterations that actually render, so the
                // "HUD has produced at least one frame" edge cannot be dropped by a skip.
                val hudPosted: Boolean
                synchronized(frameLock) {
                    hudPosted = hudFrameAvailable
                    hudFrameAvailable = false
                }

                cameraTexture?.updateTexImage()
                cameraTexture?.getTransformMatrix(cameraStMtx)
                lastCameraFrameAtMs = System.currentTimeMillis()
                if (!cameraEverUpdated) {
                    cameraEverUpdated = true
                    // The whole geometry decision rests on this matrix: if it transposes
                    // ([0]~0,[1]~+-1,[4]~+-1,[5]~0) the sensor rotation is already baked in and
                    // the MVP must stay identity. Logged so a regression is one line away.
                    log("LiveArCompositor: first camera frame stMtx=" +
                        cameraStMtx.joinToString(",") { "%.3f".format(it) })
                }

                // Warn once when the camera stops feeding while the HUD keeps going: that state
                // used to render as a full-frame green wash (stale/undefined camera texture with
                // the HUD drawn over it) and looked like a colour bug rather than a stalled feed.
                val cameraStale = cameraEverUpdated &&
                    System.currentTimeMillis() - lastCameraFrameAtMs > CAMERA_STALE_MS
                if (cameraStale != cameraStaleLogged) {
                    cameraStaleLogged = cameraStale
                    log("LiveArCompositor: camera feed ${if (cameraStale) "STALLED" else "resumed"}")
                }
                // Update the HUD UNCONDITIONALLY on every rendered frame -- no freshness gate.
                //
                // SurfaceTexture.updateTexImage() with nothing queued is a documented no-op that
                // RETAINS the currently bound image, so calling it always is safe and guarantees
                // the overlay is the latest POSTED buffer (worst case the previous valid one).
                // Gating it on hudFrameAvailable was the flicker: on a camera-only wake the HUD
                // was left bound to a slot the producer had already released and refilled, which
                // sampled as black -- and black gives a = max(rgb) = 0, i.e. no overlay at all.
                //
                // hudEverUpdated still latches on the first ACTUAL post, because the draw below
                // must not sample a texture that has never had an image bound (undefined data).
                if (hudPosted) hudEverUpdated = true
                val hud = hudTexture
                if (hud != null) {
                    hud.updateTexImage()
                    hud.getTransformMatrix(hudTexMtx)
                    hudUpdatesApplied++
                }

                GLES20.glViewport(0, 0, WIDTH, HEIGHT)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                // Camera base layer: opaque, blending OFF. Leaving blending enabled here let the
                // camera's own alpha modulate the frame, which on the waveguide's green-dominant
                // content showed up as a green tint that pulsed with the content.
                GLES20.glDisable(GLES20.GL_BLEND)
                // Only sample the camera once it has actually produced a frame; an OES texture
                // with no bound image reads as undefined data (observed as a green field).
                if (cameraEverUpdated) {
                    program?.draw(cameraTexId, cameraMvp, cameraStMtx, MODE_OPAQUE)
                }

                // Skip the overlay until the UI process has actually produced a frame: an OES
                // texture with no bound image samples undefined data over the whole picture.
                // No staleness gate: the HUD is drawn at its own rate and reusing its last buffer
                // is correct (that is what the recorded-AR path does). Gating on freshness made
                // the overlay blink in and out whenever the UI process paused between draws.
                if (hudEverUpdated) {
                    // PREMULTIPLIED source factor (GL_ONE), matching VideoCompositor. GL_SRC_ALPHA
                    // applies alpha a second time on top of the shader's luma-as-alpha, which
                    // darkens and tints the overlay.
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    program?.draw(hudTexId, hudMvp, hudTexMtx, MODE_LUMA_ALPHA)
                    GLES20.glDisable(GLES20.GL_BLEND)
                }

                egl?.setPresentationTime(eglSurface!!, System.nanoTime() - startNanos)
                egl?.swapBuffers(eglSurface!!)
                frameIndex++
                framesRendered++

                drainEncoder()

                val nowMs = System.currentTimeMillis()
                val elapsed = nowMs - lastStatsAtMs
                if (elapsed >= STATS_INTERVAL_MS) {
                    val delta = framesRendered - statsBaseFrames
                    val fps = delta * 1000.0 / elapsed
                    log(
                        "LiveArCompositor: stats framesRendered=$framesRendered " +
                            "hudUpdatesApplied=$hudUpdatesApplied " +
                            "fps=${"%.1f".format(fps)}"
                    )
                    lastStatsAtMs = nowMs
                    statsBaseFrames = framesRendered
                }
            } catch (t: Throwable) {
                if (running) {
                    log("LiveArCompositor: render error: ${t.javaClass.name}: ${t.message}")
                }
                // A dead codec or a released EGL/SurfaceTexture never recovers: the old code
                // swallowed it and then threw the identical exception every frame forever, so the
                // session looked alive while emitting nothing. Exit the loop instead, which runs
                // releaseOnGlThread() for a clean teardown.
                if (t is MediaCodec.CodecException || t is IllegalStateException) {
                    log("LiveArCompositor: unrecoverable render error, stopping loop")
                    running = false
                }
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
        // Keyed on `started`, not `running`: a start that failed during setup never set running
        // but did create the threads, and skipping teardown there leaks all three per attempt.
        if (!started) return
        started = false
        running = false
        synchronized(frameLock) { frameLock.notifyAll() }

        // JOIN the GL thread before quitting the loopers. renderLoop() runs inside a posted
        // message, so quitSafely() does NOT interrupt it -- it exits because `running` is false,
        // and only then does releaseOnGlThread() run. Returning without waiting would report the
        // camera/encoder/EGL as released while they are still held, and the next start would hit
        // ERROR_CAMERA_IN_USE against our own stale session.
        val gl = glThread
        if (gl != null && Thread.currentThread() !== gl) {
            try { gl.join(RELEASE_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
            if (gl.isAlive) log("LiveArCompositor: GL thread did not finish releasing in time")
        }
        quitThreads()
        log("LiveArCompositor: stopped")
    }

    private fun quitThreads() {
        glThread?.quitSafely()
        notifyThread?.quitSafely()
        cameraThread?.quitSafely()
        glThread = null
        notifyThread = null
        cameraThread = null
        notifyHandler = null
        cameraHandler = null
        glHandler = null
    }

    private fun releaseOnGlThread() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { sessionExecutor?.shutdown() } catch (_: Exception) {}
        sessionExecutor = null

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
        // PORTRAIT output at the SENSOR'S OWN 4:3 aspect (rotated), not a forced 9:16.
        //
        // Using 720x1280 meant the 4:3 sensor image had to be letterboxed into a 9:16 frame, which
        // wasted a third of the picture on black bars. 720x960 matches what the camera actually
        // produces, so the stream looks like an ordinary recording of the same scene.
        private const val WIDTH = 720
        private const val HEIGHT = 960

        /** Rokid waveguide panel size -- the HUD layer is captured at its native resolution. */
        private const val HUD_WIDTH = 480
        private const val HUD_HEIGHT = 640

        /** Fraction of the streamed frame width the HUD overlay spans. */
        private const val HUD_COVERAGE = 0.55f
        private const val FPS = 30
        private const val BITRATE = 4_000_000
        private const val FRAME_WAIT_MS = 100L
        private const val CAMERA_OPEN_TIMEOUT_MS = 5_000L

        /** Long enough for one render iteration plus full camera/encoder/EGL release. */
        private const val RELEASE_JOIN_TIMEOUT_MS = 3_000L

        /**
         * How long a HUD buffer stays valid to composite. The UI draws at display rate, so ~3
         * video frames of slack covers normal jitter while still dropping the overlay when the UI
         * process stalls, rather than smearing a stale buffer over the picture.
         */

        /** How long without a camera buffer counts as a stalled feed. */
        private const val CAMERA_STALE_MS = 1_000L

        /** Cadence of the framesRendered / hudUpdatesApplied flicker instrumentation line. */
        private const val STATS_INTERVAL_MS = 5_000L

        /** CameraDevice.StateCallback.ERROR_CAMERA_IN_USE */
        private const val ERROR_CAMERA_IN_USE = 1

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
            // No floor. Verified offline by compositing the real HUD and camera layers (the two
            // files record_ar_screen produces) with this exact blend: with the premultiply in
            // place the background tint is 0.00/255 at EVERY floor, so a floor buys nothing and
            // only destroys content -- 0.0 keeps 46.9% of HUD content pixels, 0.12 keeps 36.6%,
            // and the lost 10% is the antialiased text edges that kept disappearing on device.
            #define HUD_BLACK_FLOOR 0.0
            uniform samplerExternalOES uTex;
            uniform int uMode;
            varying vec2 vTex;
            void main() {
                vec4 c = texture2D(uTex, vTex);
                if (uMode == 0) {
                    // Camera: opaque, channels as sampled.
                    gl_FragColor = vec4(c.rgb, 1.0);
                } else {
                    // Luma-as-alpha, PREMULTIPLIED.
                    //
                    // The premultiply is the fix for the green wash, and it is not optional: the
                    // blend is GL_ONE/GL_ONE_MINUS_SRC_ALPHA, so src.rgb is added to the camera
                    // REGARDLESS of alpha. Emitting straight colour therefore poured the HUD
                    // background into every camera pixel even where alpha was 0 -- measured 125 of
                    // 201 frames washed with straight colour, 0 of 202 with this premultiply.
                    //
                    // The tiny floor only discards sensor/encode noise around true black. It must
                    // stay small: an earlier 0.34 floor sat just above the background level and
                    // any per-frame jitter flipped the whole panel between opaque and invisible,
                    // which is what made the HUD flicker.
                    float i = max(c.r, max(c.g, c.b));
                    float a = clamp((i - HUD_BLACK_FLOOR) / (1.0 - HUD_BLACK_FLOOR), 0.0, 1.0);
                    gl_FragColor = vec4(c.rgb * a, a);
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
