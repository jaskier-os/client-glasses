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
    }

    private lateinit var photo: PhotoCapturer
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
            Log.i(TAG, "AIDL warmUp entry recording=${video.isRecording()}")
            if (video.isRecording()) return
            photo.warmUp()
        }

        override fun takePhoto() {
            Log.i(TAG, "AIDL takePhoto entry recording=${video.isRecording()}")
            if (video.isRecording()) {
                Log.i(TAG, "takePhoto ignored: recording in progress")
                return
            }
            // YUV preview-tap path. Two decoupled flows:
            //   1. onPreview fires the moment the un-denoised JPEG hits disk
            //      (~500ms warm). Drives the on-glasses preview overlay +
            //      LED pulse via the AIDL onPhotoTaken broadcast. Pure UX.
            //   2. onDenoised fires after a background SplitterDenoiser pass
            //      overwrites the file with cleaner bytes. Pushes the
            //      denoised version to the phone via filesync. The on-glasses
            //      preview never waits for this.
            photo.takePhoto(
                onPreview = onPrev@ { file, err ->
                    Log.i(TAG, "takePhoto preview err=${err?.message} file=${file?.absolutePath} size=${file?.length()}")
                    if (err != null || file == null) {
                        broadcast { it.onCaptureError(ERR_CAMERA, err?.message ?: "photo failed") }
                        return@onPrev
                    }
                    try { LedController.pulseWhite() } catch (_: Exception) {}
                    broadcast { it.onPhotoTaken(file.absolutePath, file.length()) }
                },
                onDenoised = { file ->
                    Log.i(TAG, "takePhoto denoised; pushing to phone $file size=${file.length()}")
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
                // Solid white LED while recording.
                try { LedController.turnOnWhite() } catch (_: Exception) {}
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
            Log.i(TAG, "AIDL stopVideo entry recording=${video.isRecording()}")
            if (!video.isRecording()) {
                Log.i(TAG, "stopVideo ignored: not recording")
                return
            }
            video.stop { file, durationMs, sizeBytes, err ->
                Log.i(TAG, "stopVideo callback err=${err?.message} file=${file?.absolutePath} durMs=$durationMs bytes=$sizeBytes")
                // Always turn the LED off when recording ends, even on error. Cancel the
                // Rokid CAMERA_OPEN event first -- otherwise it holds the LED lit at
                // priority 8000 even after our turnOff.
                try {
                    LedController.cancelCameraOpenEvent()
                    LedController.turnOffWhite()
                } catch (_: Exception) {}
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
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate entry pid=${android.os.Process.myPid()}")
        val tOnCreate = android.os.SystemClock.elapsedRealtime()
        super.onCreate()

        // LED state heal moved into PhotoCapturer.init (single owner of
        // the LED state machine). PhotoCapturer is constructed below.

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
        lowLight = LowLightCapturer(this)
        photo = PhotoCapturer(this, lowLight = lowLight)
        rawStill = RawStillCapturer(this)
        video = VideoRecorder(this)
        notifier = SyncNotifier(this)

        Log.i(TAG, "CaptureService started, root=${FileNamer.rootDir.absolutePath} onCreateMs=${android.os.SystemClock.elapsedRealtime() - tOnCreate}")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind client bound action=${intent?.action}")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId action=${intent?.action}")
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
                    try { LedController.turnOnWhite() } catch (_: Exception) {}
                    broadcast { it.onVideoStarted(file.absolutePath) }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ADB_STOP_VIDEO) {
            Log.i(TAG, "ADB STOP_VIDEO entry recording=${video.isRecording()}")
            if (video.isRecording()) {
                video.stop { file, durMs, bytes, err ->
                    Log.i(TAG, "ADB STOP_VIDEO callback err=${err?.message} file=${file?.absolutePath} durMs=$durMs bytes=$bytes")
                    try { LedController.cancelCameraOpenEvent(); LedController.turnOffWhite() } catch (_: Exception) {}
                    if (err != null) broadcast { it.onCaptureError(ERR_CAMERA, err.message ?: "video stop failed") }
                    if (file != null) broadcast { it.onVideoStopped(file.absolutePath, durMs, bytes) }
                }
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
        try { photo.shutdown() } catch (_: Exception) {}
        try { rawStill.shutdown() } catch (_: Exception) {}
        try { video.shutdown() } catch (_: Exception) {}
        try { lowLight.close() } catch (_: Exception) {}
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
