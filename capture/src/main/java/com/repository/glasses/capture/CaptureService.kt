package com.repository.glasses.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.repository.glasses.capture.sync.SyncNotifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureService : Service() {

    companion object {
        private const val TAG = "Cap:Svc"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 200

        const val ERR_BUSY = 1
        const val ERR_CAMERA = 2
        const val ERR_STORAGE = 3
        const val ERR_INTERNAL = 99

        /**
         * ADB-triggered test actions. These bypass the AIDL binding so tests
         * from `adb shell am startservice` can drive capture without a bound
         * client or a focused MainActivity for key routing.
         *
         * Result files are written to
         *   /sdcard/Android/data/com.repository.glasses.capture/files/adb_results/<id>.json
         * with JSON { ok, status, path, bytes, durationMs, error? }.
         */
        const val ACTION_ADB_TAKE_PHOTO = "com.repository.glasses.capture.ADB_TAKE_PHOTO"

        /**
         * ADB-triggered RAW capture. Produces a standards-compliant DNG file
         * in /storage/emulated/0/DCIM/Repository/ by running
         * [LowLightCapturer.captureDng] (no ML inference). Intended for
         * off-device SID / amplification experiments.
         */
        const val ACTION_ADB_TAKE_RAW = "com.repository.glasses.capture.ADB_TAKE_RAW"

        const val ACTION_ADB_START_VIDEO = "com.repository.glasses.capture.ADB_START_VIDEO"
        const val ACTION_ADB_STOP_VIDEO = "com.repository.glasses.capture.ADB_STOP_VIDEO"
        // Test hook: run the SCRFD-10G NPU detector on a JPEG file (--es jpeg <path>)
        // and write the boxes + latency to adb_results/<id>.json. Validates the live
        // detection path end-to-end with a known face image.
        const val ACTION_ADB_DETECT_FACES = "com.repository.glasses.capture.ADB_DETECT_FACES"
    }

    private lateinit var cameraSession: CameraSession
    private lateinit var rawStill: RawStillCapturer
    private lateinit var video: VideoRecorder
    private lateinit var notifier: SyncNotifier
    private lateinit var lowLight: LowLightCapturer
    private val workerPool = Executors.newSingleThreadExecutor { r -> Thread(r, "CaptureSvc-work") }

    /** Hash + notify filesync on the worker pool so we never block the
     * capture thread or the AIDL binder. Called twice per photo: once on
     * preview-JPEG-on-disk, once on denoised-JPEG-overwrite. The phone-side
     * FileSyncService upserts on relPath so back-to-back notifies for the
     * same file are safe -- the second one just refreshes the sha+size. */
    private fun notifyPhotoSync(file: java.io.File) {
        workerPool.execute {
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                val sha = Sha256.ofFile(file)
                notifier.notify(file, sha, "photo")
                Log.i(TAG, "sync notify photo=${file.absolutePath} sha=${sha.take(12)} bytes=${file.length()} durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
            } catch (e: Exception) {
                Log.w(TAG, "sync notify failed: ${e.message}")
            }
        }
    }
    private val callbackLock = Any()

    private val callbacks = RemoteCallbackList<ICaptureCallback>()

    private val binder = object : ICapture.Stub() {
        override fun registerCallback(cb: ICaptureCallback) { callbacks.register(cb) }
        override fun unregisterCallback(cb: ICaptureCallback) { callbacks.unregister(cb) }

        override fun warmUp() {
            Log.i(TAG, "AIDL warmUp entry recording=${isRecordingForCapture()}")
            if (isRecordingForCapture()) return
            rawStill.warmUp()
        }

        override fun takePhoto() {
            Log.i(TAG, "AIDL takePhoto entry recording=${isRecordingForCapture()}")
            if (isRecordingForCapture()) {
                // RECORDING: the full RAW archival path is unavailable (camera is
                // held by the recorder). Produce a 1080p video-grade still from
                // the live record session's snapshot reader instead, written to
                // the same DCIM/Repository location and synced like any photo.
                takeSnapshotPhoto()
                return
            }
            // Non-recording func-button photo. Routes through the SAME
            // RawStillCapturer two-phase path as the ADB_TAKE_PHOTO hook (see
            // onStartCommand) so the func button and the test harness can never
            // diverge: one capture engine, one warmup/AE/demosaic/denoise
            // pipeline, one output format (full-res RAW, not an unwarmed preview
            // frame). Two decoupled flows:
            //   onPreview fires the moment the un-denoised JPEG hits disk
            //     (~few s). Drives the on-glasses preview overlay + LED pulse via
            //     the onPhotoTaken broadcast. Pure UX.
            //   onFinal fires after the background denoise overwrites the file;
            //     push the denoised version to the phone via filesync. The
            //     on-glasses preview never waits for this.
            rawStill.takePhoto(
                onPreview = onPrev@ { file, err ->
                    Log.i(TAG, "takePhoto preview err=${err?.message} file=${file?.absolutePath} size=${file?.length()}")
                    if (err != null || file == null) {
                        broadcast { it.onCaptureError(ERR_CAMERA, err?.message ?: "photo failed") }
                        return@onPrev
                    }
                    try { LedController.pulseWhite() } catch (_: Exception) {}
                    broadcast { it.onPhotoTaken(file.absolutePath, file.length()) }
                },
                onFinal = { file, _ ->
                    Log.i(TAG, "takePhoto final; pushing to phone $file size=${file.length()}")
                    notifyPhotoSync(file)
                },
            )
        }

        override fun startVideo() {
            Log.i(TAG, "AIDL startVideo entry recording=${video.isRecording()}")
            if (video.isRecording()) {
                Log.i(TAG, "startVideo ignored: already recording")
                return
            }
            video.start { file, err ->
                Log.i(TAG, "startVideo callback err=${err?.message} file=${file?.absolutePath}")
                if (err != null || file == null) {
                    broadcast { it.onCaptureError(ERR_CAMERA, err?.message ?: "video start failed") }
                    return@start
                }
                // LED is driven by CameraSession.StateListener when the recorder
                // surface becomes active.
                broadcast { it.onVideoStarted(file.absolutePath) }
            }
        }

        override fun togglePauseVideo() {
            Log.i(TAG, "AIDL togglePauseVideo entry recording=${video.isRecording()} paused=${video.isPaused()}")
            if (!video.isRecording()) {
                Log.i(TAG, "togglePause ignored: not recording")
                return
            }
            val f = video.currentFile() ?: return
            video.togglePause { nowPaused ->
                try {
                    // Clear semantics: LED off while paused (not recording frames),
                    // solid on while actively recording. Matches "LED = camera active".
                    // Rokid's camera HAL auto-fires CAMERA_OPEN at priority 8000 which
                    // overrides turnOff -- we must cancel that event explicitly.
                    if (nowPaused) {
                        LedController.cancelCameraOpenEvent()
                        LedController.turnOffWhite()
                    } else {
                        LedController.assertCameraOpenEvent()
                        LedController.turnOnWhite()
                    }
                } catch (_: Exception) {}
                if (nowPaused) broadcast { it.onVideoPaused(f.absolutePath) }
                else broadcast { it.onVideoResumed(f.absolutePath) }
            }
        }

        override fun stopVideo() {
            Log.i(TAG, "AIDL stopVideo entry recording=${video.isRecording()} cameraActive=${cameraSession.isRecordingOutputActive()}")
            // A stop request must ALWAYS clear the camera + LED, even if the
            // VideoRecorder boolean has desynced from the wedged HAL session
            // (the binder/HAL storm can read isRecording()==false while the
            // recorder surface and the white LED are actually up). Without this
            // the LED-off path is never reached and the LED stays stuck on.
            if (!video.isRecording()) {
                Log.i(TAG, "stopVideo: recorder flag false -- forcing camera + LED teardown")
                forceTeardown()
                return
            }
            video.stop { file, durationMs, sizeBytes, err ->
                Log.i(TAG, "stopVideo callback err=${err?.message} file=${file?.absolutePath} durMs=$durationMs bytes=$sizeBytes")
                // LED is driven off by CameraSession.StateListener when the
                // recorder surface is removed (in video.stop -> clearRecorderSurface).
                if (err != null) {
                    broadcast { it.onCaptureError(ERR_CAMERA, err.message ?: "video stop failed") }
                }
                if (file == null) return@stop
                broadcast { it.onVideoStopped(file.absolutePath, durationMs, sizeBytes) }
                workerPool.execute {
                    Log.i(TAG, "sync notify worker start video=${file.absolutePath}")
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    try {
                        val sha = Sha256.ofFile(file)
                        notifier.notify(file, sha, "video")
                        Log.i(TAG, "sync notify worker done video durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
                    } catch (e: Exception) {
                        Log.w(TAG, "sync notify failed: ${e.message}")
                    }
                }
            }
        }

        override fun isRecording(): Boolean = video.isRecording()
        override fun isPaused(): Boolean = video.isPaused()
        override fun isRecordingActive(): Boolean = cameraSession.isRecordingOutputActive()

        override fun captureReidFrame(cb: ICaptureCallback) {
            Log.i(TAG, "AIDL captureReidFrame recording=${isRecordingForCapture()}")
            if (isRecordingForCapture()) {
                // RECORDING: the RAW borrow is refused, but the record session
                // exposes a snapshot JPEG reader. Pull the latest video frame and
                // deliver it as a ReID frame. The LED is already on for video
                // (correct/expected), so do NOT touch the cameraLedGate here --
                // the ref count is only owned by the non-recording silent path.
                val frameId = reidFrameId.incrementAndGet()
                cameraSession.captureVideoSnapshot(
                    onJpeg = { jpeg, w, h, rotationDeg ->
                        try { cb.onFrame(jpeg, w, h, rotationDeg, frameId) }
                        catch (e: Exception) { Log.w(TAG, "reid snapshot onFrame deliver failed: ${e.message}") }
                    },
                    onError = { err ->
                        // Treated as "retry next tick" by the listener-side driver.
                        try { cb.onCaptureError(ERR_BUSY, err.message ?: "reid snapshot failed") }
                        catch (e: Exception) { Log.w(TAG, "reid snapshot onCaptureError deliver failed: ${e.message}") }
                    },
                )
                return
            }
            val frameId = reidFrameId.incrementAndGet()
            // ReID capture is SILENT by design (privacy / UX): unlike the
            // func-button photo path, which deliberately pulses the white LED,
            // a ReID frame must show no light. Disable the firmware camera
            // privacy LED at its SOURCE for the duration of the capture (see
            // CameraLedGate / LedController.setCameraLedEnabled): with the
            // vendor.rkd.camera.led.enable property 0, cameraserver does not
            // fire the CAMERA_OPEN(2014) event at all, so the white LED never
            // lights. Restored on completion. Ref-counted so concurrent silent
            // captures do not restore the LED out from under each other.
            cameraLedGate.acquireSilent()
            rawStill.captureReidFrame(
                onJpeg = { jpeg, w, h, rotationDeg ->
                    cameraLedGate.releaseSilent()
                    try { cb.onFrame(jpeg, w, h, rotationDeg, frameId) }
                    catch (e: Exception) { Log.w(TAG, "reid onFrame deliver failed: ${e.message}") }
                },
                onError = { err ->
                    cameraLedGate.releaseSilent()
                    try { cb.onCaptureError(ERR_CAMERA, err.message ?: "reid capture failed") }
                    catch (e: Exception) { Log.w(TAG, "reid onCaptureError deliver failed: ${e.message}") }
                },
            )
        }

        override fun detectFaces(jpeg: ByteArray): IntArray {
            val det = ScrfdFaceDetector.shared(applicationContext)
            if (det == null) {
                // HTP unavailable -> signal "no faces" so the listener falls back
                // to its ML Kit CPU detector. Logged once at init by ScrfdFaceDetector.
                return intArrayOf(0)
            }
            return try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val boxes = det.detect(jpeg)
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                if (boxes.isNotEmpty() && boxes[0] > 0) {
                    Log.i(TAG, "SCRFD detectFaces: ${boxes[0]} face(s) in ${ms}ms (NPU)")
                }
                boxes
            } catch (e: Throwable) {
                Log.w(TAG, "detectFaces failed: ${e.message}")
                intArrayOf(0)
            }
        }
    }

    private val reidFrameId = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Authoritative "is the snapshot-while-recording path usable" check for the
     * photo/ReID/warmUp entrypoints. The snapshot branch (takeSnapshotPhoto /
     * captureVideoSnapshot) reads a still off the LIVE record session, so it is
     * only valid when the recorder SURFACE is actually attached to the camera
     * session -- the same authoritative signal the FN button routes on
     * (CaptureBridge.isRecordingActive -> isRecordingOutputActive). The
     * VideoRecorder.isRecording() boolean alone is NOT sufficient: it can stick
     * `true` after a non-clean teardown (surface cleared by a session rebuild /
     * forceStop path without a normal stop()), and routing on it then sends a
     * plain func-button photo into captureVideoSnapshot against a dead session,
     * which returns a black 1280x720 frame. Requiring the surface to be present
     * keeps the photo path's branch decision identical to the FN button's, so
     * the two can never disagree.
     */
    private fun isRecordingForCapture(): Boolean = cameraSession.isRecordingOutputActive()

    /**
     * Unconditionally tear the camera + LED down. Called by any stop entrypoint
     * when the VideoRecorder flag reads false but the camera/LED may still be up
     * (state desync / HAL wedge). Idempotent and crash-safe: clears the recorder
     * surface (a no-op when already cleared) and force-cancels the Rokid
     * CAMERA_OPEN(2014) event + turns the white LED off, so a stop request always
     * darkens the LED even when isRecording() has desynced to false. Also resets
     * the VideoRecorder so a half-completed start can't strand state.
     */
    private fun forceTeardown() {
        try { cameraSession.clearRecorderSurface() } catch (e: Exception) {
            Log.w(TAG, "forceTeardown clearRecorderSurface failed: ${e.message}")
        }
        try { video.forceStop() } catch (e: Exception) {
            Log.w(TAG, "forceTeardown video.forceStop failed: ${e.message}")
        }
        try {
            LedController.cancelCameraOpenEvent()
            LedController.turnOffWhite()
        } catch (e: Exception) {
            Log.w(TAG, "forceTeardown LED-off failed: ${e.message}")
        }
    }

    /**
     * Func-button photo WHILE RECORDING. Grabs the latest JPEG from the record
     * session's snapshot reader, rotates it upright (snapshot delivers
     * rotationDeg = sensorOrientation), writes it to [FileNamer.photoFile], fires
     * the same onPhotoTaken UX broadcast as the RAW path, and syncs it to the
     * phone. No demosaic/denoise -- the HAL already encoded the JPEG. Video keeps
     * recording throughout (the snapshot is a non-disruptive read of the live
     * stream). The LED is already on for video, so the cameraLedGate is NOT
     * touched here.
     */
    private fun takeSnapshotPhoto() {
        cameraSession.captureVideoSnapshot(
            onJpeg = { jpeg, w, h, rotationDeg ->
                workerPool.execute {
                    try {
                        val out = FileNamer.photoFile()
                        writeUprightJpeg(jpeg, rotationDeg, out)
                        Log.i(TAG, "snapshot photo written ${out.absolutePath} bytes=${out.length()} src=${w}x${h} rot=$rotationDeg")
                        try { LedController.pulseWhite() } catch (_: Exception) {}
                        broadcast { it.onPhotoTaken(out.absolutePath, out.length()) }
                        notifyPhotoSync(out)
                    } catch (e: Throwable) {
                        Log.e(TAG, "snapshot photo failed: ${e.message}")
                        broadcast { it.onCaptureError(ERR_INTERNAL, e.message ?: "snapshot photo failed") }
                    }
                }
            },
            onError = { err ->
                Log.w(TAG, "snapshot photo no frame: ${err.message}")
                broadcast { it.onCaptureError(ERR_CAMERA, err.message ?: "snapshot photo failed") }
            },
        )
    }

    /**
     * Decode the snapshot JPEG, rotate it by [rotationDeg] so the image is
     * upright (matches the RAW path's baked-rotation convention), re-encode, and
     * stamp EXIF orientation NORMAL so downstream viewers do not re-rotate.
     */
    private fun writeUprightJpeg(jpeg: ByteArray, rotationDeg: Int, out: java.io.File) {
        val src = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalStateException("snapshot JPEG decode failed")
        val rotated = if (rotationDeg % 360 != 0) {
            val m = android.graphics.Matrix().apply { postRotate(rotationDeg.toFloat()) }
            val r = android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
            if (r !== src) src.recycle()
            r
        } else src
        try {
            java.io.FileOutputStream(out).use {
                rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)
            }
        } finally {
            rotated.recycle()
        }
        try {
            val exif = androidx.exifinterface.media.ExifInterface(out.absolutePath)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "snapshot EXIF stamp failed: ${e.message}")
        }
    }

    /**
     * Ref-counted gate over the firmware camera privacy LED
     * (`vendor.rkd.camera.led.enable`). ANY capture path can request a SILENT
     * (no-LED) open by bracketing it with [acquireSilent] / [releaseSilent];
     * the LED is disabled at the source while >= 1 silent holder exists and
     * restored when the last holder releases. Ref-counting prevents one capture
     * from re-enabling the LED while another silent capture is still in flight.
     * Used by ReID today; available to photo/video for a silent capture too.
     */
    private val cameraLedGate = object {
        private val silentHolders = java.util.concurrent.atomic.AtomicInteger(0)
        fun acquireSilent() {
            if (silentHolders.getAndIncrement() == 0) {
                LedController.setCameraLedEnabled(false)
            }
        }
        fun releaseSilent() {
            if (silentHolders.updateAndGet { if (it > 0) it - 1 else 0 } == 0) {
                LedController.setCameraLedEnabled(true)
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate entry pid=${android.os.Process.myPid()}")
        val tOnCreate = android.os.SystemClock.elapsedRealtime()
        super.onCreate()

        val channel = NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Capture")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        // Android 12+ requires startForeground() to declare the FGS type
        // explicitly when the operation needs camera/microphone appops.
        // Manifest declares foregroundServiceType="camera|microphone" but
        // that only seeds defaults; without the typed runtime call the
        // CAMERA appop check sees the FGS as untyped and the camera
        // service rejects with "Camera 0 disabled by policy".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        FileNamer.ensureRoot()

        // Eagerly warm the SCRFD-10G NPU detector on a background thread so the
        // one-time on-device HTP graph finalize (~12-13s on first execute) is paid
        // at service start, NOT on the first detectFaces() binder call from the
        // listener's ReID worker (which would otherwise stall the first detection).
        // ScrfdFaceDetector.shared is idempotent + caches the engine.
        Thread({
            try {
                val t = android.os.SystemClock.elapsedRealtime()
                val det = ScrfdFaceDetector.shared(applicationContext)
                Log.i(TAG, "SCRFD eager warmup done engaged=${det != null} " +
                    "ms=${android.os.SystemClock.elapsedRealtime() - t}")
            } catch (e: Throwable) {
                Log.w(TAG, "SCRFD eager warmup threw: ${e.message}")
            }
        }, "ScrfdWarmup").start()

        cameraSession = CameraSession(this).apply {
            emitter = object : CameraSession.FrameEmitter {
                override fun onCameraError(msg: String) {
                    broadcast { it.onCaptureError(ERR_CAMERA, msg) }
                }
            }
            stateListener = object : CameraSession.StateListener {
                override fun onRecordingOutputChanged(active: Boolean) {
                    // Camera LED reflects video recording only. A frame stream
                    // (ReID or any other subscriber) keeps the LED off.
                    try {
                        if (active) {
                            LedController.assertCameraOpenEvent()
                            LedController.turnOnWhite()
                        } else {
                            LedController.cancelCameraOpenEvent()
                            LedController.turnOffWhite()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        lowLight = LowLightCapturer(this, cameraSession)
        // onResumedPhotoProcessed: a photo recovered from a leftover RAW sidecar
        // (e.g. after an OOM kill mid-queue) has had its gray preview overwritten
        // with the full-res color JPEG. Re-notify filesync so the phone -- which
        // holds the gray preview -- pulls the corrected image (upsert on relPath
        // refreshes sha+size).
        rawStill = RawStillCapturer(this, cameraSession, onResumedPhotoProcessed = { file ->
            notifyPhotoSync(file)
        })
        video = VideoRecorder(this, cameraSession)
        notifier = SyncNotifier(this)

        // Crash-resume: pick up any RAW sidecars a previous run left unprocessed
        // (the failure that produced gray photos) and finish them off-camera.
        try { rawStill.resumePending() } catch (e: Exception) { Log.w(TAG, "resumePending failed: ${e.message}") }

        Log.i(TAG, "CaptureService started, root=${FileNamer.rootDir.absolutePath} onCreateMs=${android.os.SystemClock.elapsedRealtime() - tOnCreate}")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind client bound action=${intent?.action}")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId action=${intent?.action}")
        if (intent?.action == ACTION_ADB_DETECT_FACES) {
            val id = intent.getStringExtra("id") ?: "det_${System.currentTimeMillis()}"
            val jpegPath = intent.getStringExtra("jpeg")
            val resultsDir = java.io.File(getExternalFilesDir(null), "adb_results").apply { mkdirs() }
            val resultFile = java.io.File(resultsDir, "$id.json")
            workerPool.execute {
                try {
                    val bytes = java.io.File(jpegPath ?: "").readBytes()
                    val det = ScrfdFaceDetector.shared(applicationContext)
                    if (det == null) {
                        resultFile.writeText("""{"id":"$id","status":"error","error":"SCRFD NPU unavailable"}""")
                        return@execute
                    }
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val boxes = det.detect(bytes)
                    val ms = android.os.SystemClock.elapsedRealtime() - t0
                    val n = if (boxes.isNotEmpty()) boxes[0] else 0
                    val sb = StringBuilder()
                    sb.append("""{"id":"$id","status":"ok","faces":$n,"detectMs":$ms,"boxes":[""")
                    for (i in 0 until n) {
                        val o = 1 + i * 4
                        if (i > 0) sb.append(",")
                        sb.append("[${boxes[o]},${boxes[o+1]},${boxes[o+2]},${boxes[o+3]}]")
                    }
                    sb.append("]}")
                    resultFile.writeText(sb.toString())
                    Log.i(TAG, "ADB detectFaces id=$id faces=$n detectMs=$ms")
                } catch (e: Throwable) {
                    val msg = (e.message ?: "unknown").replace("\"", "'").take(300)
                    runCatching { resultFile.writeText("""{"id":"$id","status":"error","error":"$msg"}""") }
                    Log.w(TAG, "ADB detectFaces failed id=$id: ${e.message}")
                }
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_ADB_TAKE_PHOTO) {
            val id = intent.getStringExtra("id") ?: "adb_${System.currentTimeMillis()}"
            // (burst_n override unused for RAW path — fixed to RawStillCapturer.BURST_N)
            val resultsDir = java.io.File(getExternalFilesDir(null), "adb_results").apply { mkdirs() }
            val resultFile = java.io.File(resultsDir, "$id.json")
            val tStart = android.os.SystemClock.elapsedRealtime()
            // Write "pending" immediately so the test harness can confirm receipt.
            runCatching {
                resultFile.writeText("""{"id":"$id","status":"pending"}""")
            }.onFailure { Log.w(TAG, "adb pending write failed: ${it.message}") }

            if (isRecordingForCapture()) {
                // RECORDING: same snapshot-while-recording branch as the AIDL
                // takePhoto. Grab a video-grade still, write the adb result JSON.
                cameraSession.captureVideoSnapshot(
                    onJpeg = { jpeg, _, _, rotationDeg ->
                        workerPool.execute {
                            val ms = android.os.SystemClock.elapsedRealtime() - tStart
                            try {
                                val out = FileNamer.photoFile()
                                writeUprightJpeg(jpeg, rotationDeg, out)
                                runCatching {
                                    resultFile.writeText("""{"id":"$id","status":"ok","ok":true,"path":"${out.absolutePath}","bytes":${out.length()},"source":"snapshot","durationMs":$ms}""")
                                }
                                try { LedController.pulseWhite() } catch (_: Exception) {}
                                broadcast { it.onPhotoTaken(out.absolutePath, out.length()) }
                                notifyPhotoSync(out)
                                Log.i(TAG, "ADB takePhoto snapshot id=$id path=${out.absolutePath} durMs=$ms")
                            } catch (e: Throwable) {
                                val msg = (e.message ?: "unknown").replace("\"", "'").take(300)
                                runCatching { resultFile.writeText("""{"id":"$id","status":"error","ok":false,"durationMs":$ms,"error":"$msg"}""") }
                            }
                        }
                    },
                    onError = { err ->
                        val ms = android.os.SystemClock.elapsedRealtime() - tStart
                        val msg = (err.message ?: "unknown").replace("\"", "'").take(300)
                        runCatching { resultFile.writeText("""{"id":"$id","status":"error","ok":false,"durationMs":$ms,"error":"$msg"}""") }
                        Log.i(TAG, "ADB takePhoto snapshot err id=$id err=${err.message}")
                    },
                )
                return START_NOT_STICKY
            }

            var previewMs = 0L
            rawStill.takePhoto(
                onPreview = { file, err ->
                    previewMs = android.os.SystemClock.elapsedRealtime() - tStart
                    if (err != null || file == null) {
                        val msg = (err?.message ?: "unknown").replace("\"", "'").take(300)
                        runCatching {
                            resultFile.writeText("""{"id":"$id","status":"error","ok":false,"durationMs":$previewMs,"error":"$msg"}""")
                        }
                        Log.i(TAG, "ADB takePhoto preview-err id=$id err=${err?.message}")
                    } else {
                        runCatching {
                            resultFile.writeText("""{"id":"$id","status":"pending","stage":"preview","path":"${file.absolutePath}","bytes":${file.length()},"previewMs":$previewMs}""")
                        }
                        Log.i(TAG, "ADB takePhoto preview id=$id path=${file.absolutePath} previewMs=$previewMs")
                        // Drive the listener-overlay path just like the regular AIDL
                        // takePhoto does -- lets ADB-triggered captures also exercise
                        // PhotoPreviewOverlay for end-to-end testing.
                        try { LedController.pulseWhite() } catch (_: Exception) {}
                        broadcast { it.onPhotoTaken(file.absolutePath, file.length()) }
                    }
                },
                onFinal = { file, err ->
                    val ms = android.os.SystemClock.elapsedRealtime() - tStart
                    val (w, h) = try {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
                        o.outWidth to o.outHeight
                    } catch (_: Throwable) { 0 to 0 }
                    val json = if (err == null) {
                        """{"id":"$id","status":"ok","ok":true,"path":"${file.absolutePath}","bytes":${file.length()},"width":$w,"height":$h,"previewMs":$previewMs,"durationMs":$ms}"""
                    } else {
                        val msg = err.message?.replace("\"", "'")?.take(300) ?: "unknown"
                        // File exists (undenoised); surface path so caller can still sync.
                        """{"id":"$id","status":"partial","ok":true,"path":"${file.absolutePath}","bytes":${file.length()},"width":$w,"height":$h,"previewMs":$previewMs,"durationMs":$ms,"denoiseError":"$msg"}"""
                    }
                    runCatching { resultFile.writeText(json) }.onFailure {
                        Log.w(TAG, "adb result write failed: ${it.message}")
                    }
                    Log.i(TAG, "ADB takePhoto final id=$id path=${file.absolutePath} previewMs=$previewMs totalMs=$ms err=${err?.message}")
                },
            )
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ADB_START_VIDEO) {
            Log.i(TAG, "ADB START_VIDEO entry recording=${video.isRecording()}")
            if (!video.isRecording()) {
                video.start { file, err ->
                    Log.i(TAG, "ADB START_VIDEO callback err=${err?.message} file=${file?.absolutePath}")
                    if (err != null || file == null) {
                        broadcast { it.onCaptureError(ERR_CAMERA, err?.message ?: "video start failed") }
                        return@start
                    }
                    // LED is driven by CameraSession.StateListener when the
                    // recorder surface becomes active.
                    broadcast { it.onVideoStarted(file.absolutePath) }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ADB_STOP_VIDEO) {
            Log.i(TAG, "ADB STOP_VIDEO entry recording=${video.isRecording()} cameraActive=${cameraSession.isRecordingOutputActive()}")
            if (video.isRecording()) {
                video.stop { file, durMs, bytes, err ->
                    Log.i(TAG, "ADB STOP_VIDEO callback err=${err?.message} file=${file?.absolutePath} durMs=$durMs bytes=$bytes")
                    // LED is driven off by CameraSession.StateListener when the
                    // recorder surface is removed (video.stop -> clearRecorderSurface).
                    if (err != null) broadcast { it.onCaptureError(ERR_CAMERA, err.message ?: "video stop failed") }
                    if (file != null) broadcast { it.onVideoStopped(file.absolutePath, durMs, bytes) }
                }
            } else {
                // Desynced/wedged: recorder flag false but camera/LED may be up.
                // Always force the camera + LED down on a stop request.
                Log.i(TAG, "ADB STOP_VIDEO: recorder flag false -- forcing camera + LED teardown")
                forceTeardown()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ADB_TAKE_RAW) {
            val id = intent.getStringExtra("id") ?: "adbraw_${System.currentTimeMillis()}"
            val iso = intent.getIntExtra("iso", 0)
            val expMs = intent.getIntExtra("exposure_ms", 0)
            val expNs = if (expMs > 0) expMs.toLong() * 1_000_000L else 0L
            val resultsDir = java.io.File(getExternalFilesDir(null), "adb_results").apply { mkdirs() }
            val resultFile = java.io.File(resultsDir, "$id.json")
            val tStart = android.os.SystemClock.elapsedRealtime()
            runCatching { resultFile.writeText("""{"id":"$id","status":"pending"}""") }
            lowLight.captureDng(iso, expNs) { file, err ->
                val ms = android.os.SystemClock.elapsedRealtime() - tStart
                val json = if (err == null && file != null) {
                    """{"id":"$id","status":"ok","ok":true,"path":"${file.absolutePath}","bytes":${file.length()},"durationMs":$ms}"""
                } else {
                    val msg = (err?.message ?: "unknown").replace("\"", "'").take(300)
                    """{"id":"$id","status":"error","ok":false,"durationMs":$ms,"error":"$msg"}"""
                }
                runCatching { resultFile.writeText(json) }
                Log.i(TAG, "ADB captureDng id=$id ok=${file != null && err == null} path=${file?.absolutePath} durMs=$ms err=${err?.message}")
            }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy entry recording=${video.isRecording()}")
        val tDestroy = android.os.SystemClock.elapsedRealtime()
        // Wait for video.stop to drain Camera2/MediaCodec FDs synchronously --
        // stop() posts to a HandlerThread and returns immediately; without the
        // latch, onDestroy can return before the encoder/camera are released
        // and a tight process kill orphans those FDs. Mirrors the latch
        // pattern in doStart() for symmetry. Cap the wait at 2s so a wedged
        // HandlerThread never blocks service teardown forever.
        try {
            if (video.isRecording()) {
                val stopLatch = CountDownLatch(1)
                video.stop { _, _, _, _ -> stopLatch.countDown() }
                if (!stopLatch.await(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "onDestroy video.stop did not complete within 2s; proceeding anyway")
                }
            }
        } catch (_: Exception) {}
        // Safety: if we leave with the LED still lit (e.g., service killed mid-recording) clear it.
        try {
            LedController.cancelCameraOpenEvent()
            LedController.turnOffWhite()
        } catch (_: Exception) {}
        try { rawStill.shutdown() } catch (_: Exception) {}
        try { video.shutdown() } catch (_: Exception) {}
        try { lowLight.close() } catch (_: Exception) {}
        // Close the camera device LAST, after every capturer has released its
        // surfaces / borrows, so the shared session shuts down with no holders.
        try { cameraSession.shutdown() } catch (_: Exception) {}
        workerPool.shutdown()
        callbacks.kill()
        Log.i(TAG, "onDestroy exit durMs=${android.os.SystemClock.elapsedRealtime() - tDestroy}")
        super.onDestroy()
    }

    private fun broadcast(action: (ICaptureCallback) -> Unit) {
        synchronized(callbackLock) {
            val tB = android.os.SystemClock.elapsedRealtime()
            val n = callbacks.beginBroadcast()
            Log.i(TAG, "broadcast n=$n")
            try {
                for (i in 0 until n) {
                    try { action(callbacks.getBroadcastItem(i)) }
                    catch (e: Exception) { Log.w(TAG, "callback failed: ${e.message}") }
                }
            } finally {
                callbacks.finishBroadcast()
                Log.i(TAG, "broadcast done n=$n durMs=${android.os.SystemClock.elapsedRealtime() - tB}")
            }
        }
    }
}
