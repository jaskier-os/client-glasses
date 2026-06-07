package com.repository.glasses.listener.capture

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
import java.io.File
import java.util.concurrent.Executors

/**
 * Records camera video to file via Camera2 + MediaRecorder WITHOUT displaying anything.
 * The camera feed goes directly to MediaRecorder's Surface -- nothing on screen.
 */
class ArVideoRecorder(private val context: Context) {

    var remoteLog: ((String) -> Unit)? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null

    companion object {
        private const val FRAME_RATE = 30
        // 1080p30 HEVC at 7 Mbps. 60 fps doubled the camera-HAL CPU and
        // wedged the device on the 4-core A55.
        private const val VIDEO_BITRATE = 7_000_000
        private const val TAG = "ArVideoRecorder"
    }

    @Volatile
    var isRecording = false
        private set

    private var outputFile: File? = null

    /**
     * @param orientation "portrait" (select a landscape-shaped sensor size, which
     *   after the sensor's 270-deg mount rotation yields upright portrait content)
     *   or "landscape" (select a portrait-shaped sensor size, which after the
     *   mount rotation yields upright landscape content).
     */
    /**
     * All heavy work (camera characteristics query, MediaRecorder.prepare,
     * openCamera, configureStreams, mediaRecorder.start) is posted to the
     * dedicated cameraHandler thread. The caller -- typically the listener
     * service main thread -- returns immediately. Without this, prepare()
     * and the synchronous open path can block the caller for 1+ seconds
     * (Camera2 open + MediaRecorder.prepare both do binder IPC + native
     * codec setup) and we observed 30-50 dropped frames + system-server
     * broadcast queue contention as a result.
     */
    fun start(output: File, orientation: String = "portrait", callback: (Boolean) -> Unit) {
        if (isRecording) {
            remoteLog?.invoke("ArVideoRecorder: already recording")
            callback(false)
            return
        }

        outputFile = output
        output.parentFile?.mkdirs()

        cameraThread = HandlerThread("ArVideoRecorder").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        cameraHandler!!.post { startOnHandler(output, orientation, callback) }
    }

    private fun startOnHandler(output: File, orientation: String, callback: (Boolean) -> Unit) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCamera(manager)
        if (cameraId == null) {
            remoteLog?.invoke("ArVideoRecorder: no camera found")
            cleanup()
            callback(false)
            return
        }

        // Determine capture size and sensor rotation.
        val chars = manager.getCameraCharacteristics(cameraId)
        val sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java)
        // Video resolution is hardcoded: 1920x1080 as the target pixel count.
        // Pick the nearest supported size that matches the requested orientation.
        val targetPixels = 1920 * 1080
        val wantSensorLandscape = orientation != "landscape"
        val filtered = sizes?.filter {
            if (wantSensorLandscape) it.width >= it.height else it.width < it.height
        }
        val size = filtered?.minByOrNull { Math.abs(it.width * it.height - targetPixels) }
            ?: sizes?.minByOrNull { Math.abs(it.width * it.height - targetPixels) }
            ?: sizes?.firstOrNull()
        val width = size?.width ?: if (wantSensorLandscape) 1920 else 1080
        val height = size?.height ?: if (wantSensorLandscape) 1080 else 1920
        val fps = FRAME_RATE
        remoteLog?.invoke("ArVideoRecorder: camera=$cameraId size=${width}x${height} fps=$fps sensorRotation=$sensorRotation orientation=$orientation")

        try {
            mediaRecorder = MediaRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(output.absolutePath)
                setVideoEncodingBitRate(VIDEO_BITRATE)
                setVideoFrameRate(fps)
                setVideoSize(width, height)
                setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                // The Rokid sensor reports SENSOR_ORIENTATION=270 which makes
                // players display the saved mp4 rotated 270 deg from upright
                // (camera frames go in landscape, players rotate them an extra
                // 270 -> wrong way). Correct by rotating an additional 90 CCW
                // i.e. subtract 90 from the hint we set on the file. Modulo
                // 360 keeps the sane 0..359 range if the sensor ever reports
                // a different base rotation on a future device.
                setOrientationHint(((sensorRotation - 90) + 360) % 360)
                prepare()
            }
            remoteLog?.invoke("ArVideoRecorder: MediaRecorder prepared")
        } catch (e: Exception) {
            remoteLog?.invoke("ArVideoRecorder: MediaRecorder prepare failed: ${e.message}")
            cleanup()
            callback(false)
            return
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startRecordingSession(camera, callback)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    remoteLog?.invoke("ArVideoRecorder: camera disconnected")
                    camera.close()
                    cameraDevice = null
                    isRecording = false
                    callback(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    remoteLog?.invoke("ArVideoRecorder: camera error: $error")
                    camera.close()
                    cameraDevice = null
                    isRecording = false
                    callback(false)
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            remoteLog?.invoke("ArVideoRecorder: camera permission denied")
            cleanup()
            callback(false)
        } catch (e: Exception) {
            remoteLog?.invoke("ArVideoRecorder: openCamera failed: ${e.message}")
            cleanup()
            callback(false)
        }
    }

    private fun startRecordingSession(camera: CameraDevice, callback: (Boolean) -> Unit) {
        val recorderSurface = mediaRecorder!!.surface

        val stateCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(recorderSurface)
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                // Fixed-focus AR camera: lock AF to infinity to avoid the
                // continuous-AF refocus loop on an immovable lens.
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(FRAME_RATE, FRAME_RATE)
                )
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )

                val frameCounter = java.util.concurrent.atomic.AtomicLong(0L)
                val lastLogAt = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
                val frameCb = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        req: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult
                    ) {
                        val n = frameCounter.incrementAndGet()
                        if (n % 300L == 0L) {
                            val now = SystemClock.elapsedRealtime()
                            val prev = lastLogAt.getAndSet(now)
                            val windowMs = now - prev
                            val fps = if (windowMs > 0) 300.0 * 1000.0 / windowMs else 0.0
                            val msg = "ArVideoRecorder: frameStream n=$n windowMs=$windowMs fps=${"%.2f".format(fps)}"
                            Log.i(TAG, msg)
                            remoteLog?.invoke(msg)
                        }
                    }
                }

                session.setRepeatingRequest(builder.build(), frameCb, cameraHandler)
                mediaRecorder!!.start()
                isRecording = true
                remoteLog?.invoke("ArVideoRecorder: recording started -> ${outputFile?.name}")
                callback(true)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                remoteLog?.invoke("ArVideoRecorder: session config failed")
                cleanup()
                callback(false)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cbExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ArVideoRec-cb") }
                camera.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(recorderSurface)),
                        cbExecutor,
                        stateCb
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(recorderSurface), stateCb, cameraHandler)
            }
        } catch (e: IllegalArgumentException) {
            remoteLog?.invoke("ArVideoRecorder: createCaptureSession failed: ${e.message}")
            cleanup()
            callback(false)
        }
    }

    /**
     * Posts the entire teardown to the cameraHandler thread. Calling
     * captureSession.stopRepeating / mediaRecorder.stop / cameraDevice.close
     * inline raced with configureStreamsChecked still running on the handler
     * thread and produced a 700ms+ monitor wait on the caller (CameraDeviceImpl
     * close() blocks behind any in-flight session operation), freezing the UI
     * process via the broadcast/binder chain. Doing it on cameraHandler also
     * naturally serializes teardown after any pending start-completion work.
     */
    fun stop(callback: ((String?) -> Unit)? = null) {
        if (!isRecording) {
            remoteLog?.invoke("ArVideoRecorder: not recording")
            callback?.invoke(null)
            return
        }
        isRecording = false

        val handler = cameraHandler
        if (handler == null) {
            // start() never made it far enough to spin up the handler.
            cleanup()
            callback?.invoke(null)
            return
        }
        handler.post { stopOnHandler(callback) }
    }

    private fun stopOnHandler(callback: ((String?) -> Unit)?) {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        try {
            mediaRecorder?.stop()
            remoteLog?.invoke("ArVideoRecorder: recording stopped")
        } catch (e: Exception) {
            remoteLog?.invoke("ArVideoRecorder: stop failed: ${e.message}")
        }

        cleanup()

        val file = outputFile
        if (file != null && file.exists() && file.length() > 0) {
            remoteLog?.invoke("ArVideoRecorder: saved ${file.absolutePath} (${file.length() / 1024}KB)")
            callback?.invoke(file.absolutePath)
        } else {
            remoteLog?.invoke("ArVideoRecorder: output missing or empty")
            callback?.invoke(null)
        }
    }

    private fun findCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK ||
                facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun cleanup() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
