package com.ledcamtest

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG_LIFE = "LCT:Life"
private const val TAG_INPUT = "LCT:Input"
private const val TAG_CAM = "LCT:Cam"
private const val TAG_SYSFS = "LCT:Sysfs"
private const val TAG_THREAD = "LCT:Thread"
private const val TAG_FRAME = "LCT:Frame"
private const val TAG_STREAM = "LCT:Stream"

class MainActivity : Activity() {
    private val TAG = "LedCamTest"
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private lateinit var status: TextView

    private var frameCount: Long = 0L
    private var sessionFrameCount: Long = 0L

    override fun onCreate(s: Bundle?) {
        Log.i(TAG_LIFE, "onCreate enter")
        val t0 = System.nanoTime()
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 18f
            text = "idle"
        }
        val open = Button(this).apply { text = "OPEN CAMERA" }
        val close = Button(this).apply { text = "CLOSE CAMERA" }
        val probe = Button(this).apply { text = "READ session_open" }
        open.setOnClickListener {
            Log.i(TAG_INPUT, "button OPEN CAMERA clicked")
            ensurePermThen { openCam() }
        }
        close.setOnClickListener {
            Log.i(TAG_INPUT, "button CLOSE CAMERA clicked")
            closeCam()
        }
        probe.setOnClickListener {
            Log.i(TAG_INPUT, "button READ session_open clicked")
            val v = getProp("vendor.rkd.camera.session_open")
            status.text = "session_open=$v"
        }
        root.addView(status)
        root.addView(open)
        root.addView(close)
        root.addView(probe)
        setContentView(root)
        val durMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.i(TAG_LIFE, "onCreate exit durMs=$durMs")
    }

    private fun ensurePermThen(block: () -> Unit) {
        Log.i(TAG_LIFE, "ensurePermThen enter")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG_LIFE, "camera permission already granted")
            block()
        } else {
            Log.i(TAG_LIFE, "requesting camera permission")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        val granted = r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED
        Log.i(TAG_LIFE, "onRequestPermissionsResult rc=$rc granted=$granted")
        if (rc == 1 && granted) openCam()
    }

    @Suppress("MissingPermission")
    private fun openCam() {
        Log.i(TAG_CAM, "openCam enter")
        val tOpen = System.nanoTime()
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull() ?: run {
            Log.w(TAG_CAM, "openCam exit no camera available")
            status.text = "no camera"
            return
        }
        Log.i(TAG_THREAD, "starting HandlerThread name=cam")
        thread = HandlerThread("cam").also { it.start() }
        handler = Handler(thread!!.looper)
        Log.i(TAG_THREAD, "HandlerThread started tid=${thread!!.threadId}")

        Log.i(TAG_CAM, "creating ImageReader 640x480 YUV_420_888 maxImages=2")
        reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        frameCount = 0L
        reader!!.setOnImageAvailableListener({
            frameCount++
            val img = it.acquireLatestImage()
            img?.close()
            if (frameCount % 30L == 0L) {
                Log.i(TAG_FRAME, "onImageAvailable frameCount=$frameCount acquired=${img != null}")
            }
        }, handler)

        status.text = "opening id=$id"
        Log.i(TAG_CAM, "openCamera request id=$id")
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                val dOpenMs = (System.nanoTime() - tOpen) / 1_000_000.0
                Log.i(TAG_CAM, "onOpened id=$id openDurMs=$dOpenMs")
                cameraDevice = d
                val prop = getProp("vendor.rkd.camera.session_open")
                Log.i(TAG_SYSFS, "getProp vendor.rkd.camera.session_open=$prop (post-open)")
                runOnUiThread { status.text = "OPENED id=$id sess=$prop" }
                Log.i(TAG, "onOpened, session_open=$prop")
                Log.i(TAG_CAM, "createCaptureSession enter")
                val tSess = System.nanoTime()
                d.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cs: CameraCaptureSession) {
                        val dSessMs = (System.nanoTime() - tSess) / 1_000_000.0
                        Log.i(TAG_CAM, "onConfigured sessionConfigDurMs=$dSessMs")
                        session = cs
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        req.addTarget(reader!!.surface)
                        Log.i(TAG_STREAM, "setRepeatingRequest enter")
                        sessionFrameCount = 0L
                        cs.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                s: CameraCaptureSession,
                                r: android.hardware.camera2.CaptureRequest,
                                result: android.hardware.camera2.TotalCaptureResult
                            ) {
                                sessionFrameCount++
                                if (sessionFrameCount % 30L == 0L) {
                                    Log.i(TAG_STREAM, "onCaptureCompleted sessionFrameCount=$sessionFrameCount")
                                }
                            }
                        }, handler)
                        Log.i(TAG_STREAM, "setRepeatingRequest exit")
                        val p2 = getProp("vendor.rkd.camera.session_open")
                        Log.i(TAG_SYSFS, "getProp vendor.rkd.camera.session_open=$p2 (post-configure)")
                        runOnUiThread { status.text = "STREAMING sess=$p2" }
                        Log.i(TAG, "streaming, session_open=$p2")
                    }
                    override fun onConfigureFailed(cs: CameraCaptureSession) {
                        Log.e(TAG_CAM, "onConfigureFailed")
                        runOnUiThread { status.text = "session config failed" }
                    }
                }, handler)
            }
            override fun onDisconnected(d: CameraDevice) {
                Log.w(TAG_CAM, "onDisconnected id=$id")
                d.close(); cameraDevice = null
            }
            override fun onError(d: CameraDevice, e: Int) {
                Log.e(TAG_CAM, "onError id=$id err=$e")
                d.close(); cameraDevice = null
                runOnUiThread { status.text = "err $e" }
            }
        }, handler)
        Log.i(TAG_CAM, "openCam exit (async openCamera issued)")
    }

    private fun closeCam() {
        Log.i(TAG_CAM, "closeCam enter")
        val t0 = System.nanoTime()
        session?.close(); session = null
        cameraDevice?.close(); cameraDevice = null
        reader?.close(); reader = null
        Log.i(TAG_THREAD, "stopping HandlerThread (quitSafely)")
        thread?.quitSafely(); thread = null; handler = null
        val p = getProp("vendor.rkd.camera.session_open")
        Log.i(TAG_SYSFS, "getProp vendor.rkd.camera.session_open=$p (post-close)")
        status.text = "CLOSED sess=$p"
        Log.i(TAG, "closed, session_open=$p")
        val durMs = (System.nanoTime() - t0) / 1_000_000.0
        Log.i(TAG_CAM, "closeCam exit durMs=$durMs totalFrames=$frameCount totalSessionFrames=$sessionFrameCount")
    }

    override fun onDestroy() {
        Log.i(TAG_LIFE, "onDestroy enter")
        closeCam()
        super.onDestroy()
        Log.i(TAG_LIFE, "onDestroy exit")
    }

    private fun getProp(name: String): String {
        val t0 = System.nanoTime()
        val result = try {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java, String::class.java).invoke(null, name, "?") as String
        } catch (e: Exception) { "err:${e.message}" }
        val durUs = (System.nanoTime() - t0) / 1_000.0
        Log.i(TAG_SYSFS, "getProp name=$name value=$result durUs=$durUs")
        return result
    }
}
