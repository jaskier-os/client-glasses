package com.test.camprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/*
 * CamProbe -- empirical Camera2 stream-config probe for the Rokid glasses.
 *
 * Drive entirely over ADB. Each launch runs ONE config for `dur` seconds, then
 * writes a JSON result + logs greppable lines. LED is sampled externally by the
 * harness (cat /sys/class/leds/white/brightness).
 *
 * am start -n com.test.camprobe/.MainActivity \
 *    --es config yuv|jpeg|private|yuv2|record|caps \
 *    --ei w 1280 --ei h 720 --ei dur 25 \
 *    --es ledprop "1"|"0"|"" (empty = leave untouched)
 *
 * Result file: /sdcard/Android/data/com.test.camprobe/files/result.json
 */
private const val TAG = "CamProbe"

class MainActivity : Activity() {
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private lateinit var status: TextView

    // SurfaceTexture-backed second/record targets (NO MediaRecorder, NO file).
    private var st1: SurfaceTexture? = null
    private var stSurface1: Surface? = null
    private var st2: SurfaceTexture? = null
    private var stSurface2: Surface? = null

    // measurement state
    @Volatile private var frameImagesDelivered = 0L     // ImageReader onImageAvailable
    @Volatile private var captureCompleted = 0L         // onCaptureCompleted (HAL frame)
    @Volatile private var bufferLost = 0L               // onCaptureBufferLost
    @Volatile private var firstFrameMs = 0L
    @Volatile private var lastFrameMs = 0L
    private val roiMeans = ArrayList<Int>()             // mean luma/R of center ROI per sampled frame

    private var cfg = "yuv"
    private var reqW = 1280
    private var reqH = 720
    private var durSec = 25

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // Keep screen on + show over lockscreen so the activity stays top/foreground
        // -- losing top-focus makes CameraService call finishCameraStreamingOps and
        // tear the stream down (observed ~8s stall otherwise).
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        status = TextView(this).apply {
            setTextColor(Color.GREEN); textSize = 16f; text = "camprobe"
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            addView(status)
        })
        thread = HandlerThread("camprobe").also { it.start() }
        handler = Handler(thread!!.looper)
        // Defer the run until the window is focused (foreground/resumed) so the
        // framework permits camera open -- a background camera open is rejected
        // with CAMERA_DISABLED on this build.
    }

    @Volatile private var started = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "onWindowFocusChanged hasFocus=$hasFocus elapsed=${SystemClock.elapsedRealtime()}")
        if (hasFocus && !started) {
            started = true
            runFromIntent(intent)
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        // tear down any previous run, then start fresh (already foreground here)
        handler?.post {
            closeAll()
            runOnUiThread { runFromIntent(i) }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause elapsed=${SystemClock.elapsedRealtime()} -- closing camera")
        // Free the camera if the activity leaves the foreground.
        handler?.post { closeAll() }
    }

    private fun runFromIntent(i: Intent) {
        cfg = i.getStringExtra("config") ?: "yuv"
        reqW = i.getIntExtra("w", 1280)
        reqH = i.getIntExtra("h", 720)
        durSec = i.getIntExtra("dur", 25)
        val ledprop = i.getStringExtra("ledprop")
        if (ledprop != null && ledprop.isNotEmpty()) {
            setProp("vendor.rkd.camera.led.enable", ledprop)
            Log.i(TAG, "set vendor.rkd.camera.led.enable=$ledprop")
        }
        Log.i(TAG, "RUN cfg=$cfg size=${reqW}x$reqH dur=$durSec ledprop=$ledprop")
        status.text = "RUN $cfg ${reqW}x$reqH ${durSec}s"
        resetMeasure()
        if (cfg == "caps") { dumpCaps(); return }
        ensurePermThen { handler?.post { openAndStream() } }
    }

    private fun resetMeasure() {
        frameImagesDelivered = 0; captureCompleted = 0; bufferLost = 0
        firstFrameMs = 0; lastFrameMs = 0
        synchronized(roiMeans) { roiMeans.clear() }
    }

    private fun ensurePermThen(block: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) block()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 1 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED)
            handler?.post { openAndStream() }
        else Log.e(TAG, "camera permission denied")
    }

    private fun cameraChars(): CameraCharacteristics {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        return cm.getCameraCharacteristics(cm.cameraIdList[0])
    }

    private fun dumpCaps() {
        try {
            val c = cameraChars()
            val lvl = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val lvlName = when (lvl) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "UNKNOWN($lvl)"
            }
            val sb = StringBuilder()
            sb.append("HARDWARE_LEVEL=$lvlName\n")
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            sb.append("CAPABILITIES=${caps?.joinToString(",")}\n")
            sb.append("SENSOR_ORIENTATION=${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}\n")
            sb.append("AE_FPS_RANGES=${c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString(",")}\n")
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            for (fmt in intArrayOf(ImageFormat.YUV_420_888, ImageFormat.JPEG, ImageFormat.PRIVATE, ImageFormat.RAW_SENSOR)) {
                val name = when (fmt) {
                    ImageFormat.YUV_420_888 -> "YUV_420_888"
                    ImageFormat.JPEG -> "JPEG"
                    ImageFormat.PRIVATE -> "PRIVATE"
                    ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                    else -> "fmt$fmt"
                }
                val sizes = map.getOutputSizes(fmt)
                if (sizes == null) { sb.append("$name: <none>\n"); continue }
                sb.append("$name sizes:\n")
                for (sz in sizes) {
                    val minDur = map.getOutputMinFrameDuration(fmt, sz)
                    val stall = map.getOutputStallDuration(fmt, sz)
                    val maxFps = if (minDur > 0) 1e9 / minDur else 0.0
                    sb.append("  ${sz.width}x${sz.height} minDurNs=$minDur (~${"%.1f".format(maxFps)}fps) stallNs=$stall\n")
                }
            }
            // SurfaceTexture/PRIVATE output sizes also reflect what a preview surface can take.
            Log.i(TAG, "CAPS:\n$sb")
            for (line in sb.toString().split("\n")) if (line.isNotBlank()) Log.i("$TAG.caps", line)
            writeResult("{\"config\":\"caps\",\"hardwareLevel\":\"$lvlName\"}")
            runOnUiThread { status.text = "caps -> level $lvlName (see log)" }
        } catch (e: Throwable) {
            Log.e(TAG, "dumpCaps failed", e)
            writeResult("{\"config\":\"caps\",\"error\":\"${e.message}\"}")
        }
    }

    private fun pickSize(fmt: Int): Size {
        val map = cameraChars().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(fmt) ?: return Size(reqW, reqH)
        // exact match else closest by area to requested
        return sizes.firstOrNull { it.width == reqW && it.height == reqH }
            ?: sizes.minByOrNull { Math.abs(it.width.toLong() * it.height - reqW.toLong() * reqH) }
            ?: Size(reqW, reqH)
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (firstFrameMs == 0L) firstFrameMs = now
            lastFrameMs = now
            captureCompleted++
            if (captureCompleted % 30L == 0L) Log.i(TAG, "onCaptureCompleted n=$captureCompleted")
        }
        override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
            bufferLost++
            Log.w(TAG, "onCaptureBufferLost n=$bufferLost frame=$frameNumber")
        }
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
            Log.w(TAG, "onCaptureFailed reason=${failure.reason} frame=${failure.frameNumber}")
        }
    }

    private fun openAndStream() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList[0]
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    cameraDevice = d
                    Log.i(TAG, "camera onOpened id=$id session_open=${getProp("vendor.rkd.camera.session_open")}")
                    try { configure(d) } catch (e: Throwable) {
                        Log.e(TAG, "configure threw", e); finishRun("configure_threw:${e.message}")
                    }
                }
                override fun onDisconnected(d: CameraDevice) { Log.w(TAG, "camera onDisconnected"); d.close() }
                override fun onError(d: CameraDevice, err: Int) { Log.e(TAG, "camera onError $err"); d.close(); finishRun("camera_error_$err") }
            }, handler)
        } catch (e: Throwable) {
            Log.e(TAG, "openCamera threw", e); finishRun("open_threw:${e.message}")
        }
    }

    private fun configure(d: CameraDevice) {
        val outputs = ArrayList<Surface>()
        val template: Int
        var repeatTargets: List<Surface>

        when (cfg) {
            "yuv" -> {
                val sz = pickSize(ImageFormat.YUV_420_888)
                reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 4).also {
                    it.setOnImageAvailableListener({ r -> onYuv(r) }, handler)
                }
                Log.i(TAG, "YUV reader ${sz.width}x${sz.height}")
                outputs.add(reader!!.surface); template = CameraDevice.TEMPLATE_PREVIEW
                repeatTargets = listOf(reader!!.surface)
            }
            "jpeg" -> {
                val sz = pickSize(ImageFormat.JPEG)
                reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.JPEG, 3).also {
                    it.setOnImageAvailableListener({ r -> onJpeg(r) }, handler)
                }
                Log.i(TAG, "JPEG reader ${sz.width}x${sz.height}")
                outputs.add(reader!!.surface); template = CameraDevice.TEMPLATE_PREVIEW
                repeatTargets = listOf(reader!!.surface)
            }
            "private" -> {
                // PRIVATE ImageReader: HAL-opaque, can't read pixels, but proves a
                // GPU/preview-class stream delivers buffers. fps from onCaptureCompleted.
                val sz = pickSize(ImageFormat.PRIVATE)
                reader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.PRIVATE, 4).also {
                    it.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); frameImagesDelivered++ }, handler)
                }
                Log.i(TAG, "PRIVATE reader ${sz.width}x${sz.height}")
                outputs.add(reader!!.surface); template = CameraDevice.TEMPLATE_PREVIEW
                repeatTargets = listOf(reader!!.surface)
            }
            "yuv2" -> {
                // YUV reader + a second PRIVATE SurfaceTexture preview, BOTH repeating.
                val szY = pickSize(ImageFormat.YUV_420_888)
                reader = ImageReader.newInstance(szY.width, szY.height, ImageFormat.YUV_420_888, 4).also {
                    it.setOnImageAvailableListener({ r -> onYuv(r) }, handler)
                }
                val szP = pickSize(ImageFormat.PRIVATE)
                st1 = SurfaceTexture(0).also { it.setDefaultBufferSize(szP.width, szP.height); it.detachFromGLContext() }
                stSurface1 = Surface(st1)
                Log.i(TAG, "YUV2: yuv ${szY.width}x${szY.height} + ST ${szP.width}x${szP.height}")
                outputs.add(reader!!.surface); outputs.add(stSurface1!!)
                template = CameraDevice.TEMPLATE_PREVIEW
                repeatTargets = listOf(reader!!.surface, stSurface1!!)
            }
            "record" -> {
                // TEMPLATE_RECORD with a SurfaceTexture target -- NO MediaRecorder, NO file.
                // Plus a YUV reader so we can read pixels & prove live frames.
                val szY = pickSize(ImageFormat.YUV_420_888)
                reader = ImageReader.newInstance(szY.width, szY.height, ImageFormat.YUV_420_888, 4).also {
                    it.setOnImageAvailableListener({ r -> onYuv(r) }, handler)
                }
                val szP = pickSize(ImageFormat.PRIVATE)
                st1 = SurfaceTexture(0).also { it.setDefaultBufferSize(szP.width, szP.height); it.detachFromGLContext() }
                stSurface1 = Surface(st1)
                Log.i(TAG, "RECORD: ST ${szP.width}x${szP.height} + yuv ${szY.width}x${szY.height}, TEMPLATE_RECORD")
                outputs.add(stSurface1!!); outputs.add(reader!!.surface)
                template = CameraDevice.TEMPLATE_RECORD
                repeatTargets = listOf(stSurface1!!, reader!!.surface)
            }
            else -> { finishRun("unknown_config_$cfg"); return }
        }

        d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(cs: CameraCaptureSession) {
                session = cs
                Log.i(TAG, "session onConfigured cfg=$cfg session_open=${getProp("vendor.rkd.camera.session_open")}")
                val req = d.createCaptureRequest(template)
                for (t in repeatTargets) req.addTarget(t)
                req.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                req.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                try {
                    cs.setRepeatingRequest(req.build(), captureCb, handler)
                    Log.i(TAG, "setRepeatingRequest OK cfg=$cfg")
                    // Fire one AE precapture so exposure converges (some HALs need it).
                    val trig = d.createCaptureRequest(template)
                    for (t in repeatTargets) trig.addTarget(t)
                    trig.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    cs.capture(trig.build(), captureCb, handler)
                } catch (e: Throwable) {
                    Log.e(TAG, "setRepeatingRequest threw", e); finishRun("repeating_threw:${e.message}")
                    return
                }
                runOnUiThread { status.text = "STREAMING $cfg" }
                handler?.postDelayed({ finishRun("ok") }, durSec * 1000L)
            }
            override fun onConfigureFailed(cs: CameraCaptureSession) {
                Log.e(TAG, "session onConfigureFailed cfg=$cfg")
                finishRun("session_config_failed")
            }
        }, handler)
    }

    private fun onYuv(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            frameImagesDelivered++
            // Sample mean of Y plane over a center ROI to prove live, varying frames.
            if (frameImagesDelivered % 5L == 0L) {
                val mean = centerYMean(img)
                synchronized(roiMeans) { roiMeans.add(mean) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "onYuv read err ${e.message}")
        } finally { img.close() }
    }

    private fun centerYMean(img: Image): Int {
        val y = img.planes[0]
        val buf = y.buffer
        val rowStride = y.rowStride
        val w = img.width; val h = img.height
        val cx = w / 2; val cy = h / 2; val half = 40
        var sum = 0L; var cnt = 0L
        var yy = cy - half
        while (yy < cy + half) {
            if (yy in 0 until h) {
                var xx = cx - half
                while (xx < cx + half) {
                    if (xx in 0 until w) {
                        val idx = yy * rowStride + xx
                        if (idx < buf.limit()) { sum += (buf.get(idx).toInt() and 0xFF); cnt++ }
                    }
                    xx++
                }
            }
            yy++
        }
        return if (cnt > 0) (sum / cnt).toInt() else -1
    }

    private fun onJpeg(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            frameImagesDelivered++
            if (frameImagesDelivered % 5L == 0L) {
                val buf = img.planes[0].buffer
                // mean of first N bytes of the JPEG stream as a cheap "varies" proxy
                var sum = 0L; val n = Math.min(2000, buf.remaining())
                val pos = buf.position()
                for (k in 0 until n) sum += (buf.get(pos + k).toInt() and 0xFF)
                synchronized(roiMeans) { roiMeans.add((sum / n).toInt()) }
            }
        } catch (e: Throwable) { Log.w(TAG, "onJpeg err ${e.message}") } finally { img.close() }
    }

    @Volatile private var finished = false
    private fun finishRun(reason: String) {
        if (finished) return
        finished = true
        val elapsed = if (firstFrameMs > 0 && lastFrameMs > firstFrameMs) (lastFrameMs - firstFrameMs) else 0L
        val halFps = if (elapsed > 0) captureCompleted * 1000.0 / elapsed else 0.0
        val imgFps = if (elapsed > 0) frameImagesDelivered * 1000.0 / elapsed else 0.0
        val means = synchronized(roiMeans) { roiMeans.toList() }
        val distinct = means.distinct().size
        val mn = means.minOrNull() ?: -1; val mx = means.maxOrNull() ?: -1
        val varies = distinct > 1
        val json = "{\"config\":\"$cfg\",\"size\":\"${reqW}x$reqH\",\"durSec\":$durSec," +
            "\"reason\":\"$reason\",\"halFrames\":$captureCompleted,\"imgFrames\":$frameImagesDelivered," +
            "\"bufferLost\":$bufferLost,\"streamMs\":$elapsed," +
            "\"halFps\":${"%.2f".format(halFps)},\"imgFps\":${"%.2f".format(imgFps)}," +
            "\"roiSamples\":${means.size},\"roiDistinct\":$distinct,\"roiMin\":$mn,\"roiMax\":$mx,\"roiVaries\":$varies}"
        Log.i(TAG, "RESULT $json")
        writeResult(json)
        runOnUiThread { status.text = "DONE $cfg halFps=${"%.1f".format(halFps)} imgFps=${"%.1f".format(imgFps)} varies=$varies" }
        handler?.post { closeAll() }
    }

    private fun closeAll() {
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        try { stSurface1?.release() } catch (_: Throwable) {}
        try { st1?.release() } catch (_: Throwable) {}
        try { stSurface2?.release() } catch (_: Throwable) {}
        try { st2?.release() } catch (_: Throwable) {}
        st1 = null; stSurface1 = null; st2 = null; stSurface2 = null
        finished = false
    }

    private fun writeResult(json: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "result.json").writeText(json)
        } catch (e: Throwable) { Log.w(TAG, "writeResult err ${e.message}") }
    }

    private fun getProp(key: String): String = try {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (e: Throwable) { "" }

    private fun setProp(key: String, value: String) {
        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
        } catch (e: Throwable) { Log.w(TAG, "setProp $key err ${e.message}") }
    }

    override fun onDestroy() { super.onDestroy(); handler?.post { closeAll() }; thread?.quitSafely() }
}
