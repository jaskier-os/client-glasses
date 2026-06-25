package com.repository.glasses.listener.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.repository.glasses.capture.ICapture
import com.repository.glasses.capture.ICaptureCallback
import com.repository.glasses.tracing.GT
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AIDL client to the standalone capture APK. Mirrors BtManagerBridge's rebind-on-death pattern:
 * DeathRecipient handles force-stop, startForegroundService + FLAG_INCLUDE_STOPPED_PACKAGES
 * wakes the STOPPED package state.
 */
class CaptureBridge(private val context: Context) {

    companion object {
        private const val TAG = "App:CapBridge"
        private const val PKG = "com.repository.glasses.capture"
        private const val SVC = "com.repository.glasses.capture.CaptureService"
    }

    interface Listener {
        fun onPhotoTaken(absPath: String, sizeBytes: Long) {}
        /** RAW burst fully captured -- "photo taken, you can move now". */
        fun onShutterComplete() {}
        fun onVideoStarted(absPath: String) {}
        fun onVideoPaused(absPath: String) {}
        fun onVideoResumed(absPath: String) {}
        fun onVideoStopped(absPath: String, durationMs: Long, sizeBytes: Long) {}
        fun onCaptureError(code: Int, msg: String) {}
        /**
         * Capture priv-app process died while a recording was active. activePath is the file
         * the kill orphaned (header-only mp4); listener should surface this to the user since
         * the captured content is gone. Triggered from binderDied() / onServiceDisconnected()
         * when the local recording flag was still set -- distinguishes lmkd kill / native crash
         * from a clean stop.
         */
        fun onCaptureKilledDuringRecording(activePath: String?) {}
        /**
         * A ReID still arrived from the capture process (one per captureReidFrame call).
         * Delivered on the binder thread; consumers must hand off heavy work to their own
         * executor. The byte[] is owned by the AIDL transaction -- copy out anything retained
         * past this call. rotationDeg is 0 (the capture side bakes rotation into the pixels).
         */
        fun onFrame(jpeg: ByteArray, width: Int, height: Int, rotationDeg: Int, frameId: Long) {}
        /**
         * One batch of rPPG forehead skin-color samples from the capture process, one
         * batch per processed frame. Arrays are parallel by face: trackingIds[i] has
         * mean RGB at rgb[i*3..i*3+2]; tMs is the frame timestamp (elapsedRealtime ms).
         * Delivered on the binder thread (oneway); consumers must not block.
         */
        fun onRppgSamples(trackingIds: LongArray, rgb: FloatArray, tMs: Long) {}
    }

    /**
     * Invoked synchronously on the main thread immediately before startVideo() forwards to the
     * AIDL. Listener wires this to free memory aggressively (stop reid/night-vision, drop
     * caches, request GC) so capture isn't the highest-RSS target if lmkd activates mid-record.
     */
    var beforeVideoStart: (() -> Unit)? = null

    @Volatile private var recordingActive: Boolean = false
    @Volatile private var activeRecordingPath: String? = null

    var remoteLog: ((String) -> Unit)? = null
    private fun logMsg(msg: String) {
        android.util.Log.i(TAG, msg)
        remoteLog?.invoke(msg)
    }
    var isBound = false
        private set

    private var api: ICapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile private var stopped = false
    @Volatile private var bindPending: Runnable? = null
    @Volatile private var currentBinder: IBinder? = null

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val callback = object : ICaptureCallback.Stub() {
        override fun onPhotoTaken(absPath: String, sizeBytes: Long) = GT.section("cap.cb.photo_taken") {
            logMsg("Capture: photo $absPath ($sizeBytes B)")
            listeners.forEach { it.onPhotoTaken(absPath, sizeBytes) }
        }
        override fun onShutterComplete() = GT.section("cap.cb.shutter_complete") {
            listeners.forEach { it.onShutterComplete() }
        }
        override fun onVideoStarted(absPath: String) = GT.section("cap.cb.video_started") {
            recordingActive = true
            activeRecordingPath = absPath
            logMsg("Capture: video started $absPath")
            listeners.forEach { it.onVideoStarted(absPath) }
        }
        override fun onVideoPaused(absPath: String) = GT.section("cap.cb.video_paused") {
            logMsg("Capture: video paused")
            listeners.forEach { it.onVideoPaused(absPath) }
        }
        override fun onVideoResumed(absPath: String) = GT.section("cap.cb.video_resumed") {
            logMsg("Capture: video resumed")
            listeners.forEach { it.onVideoResumed(absPath) }
        }
        override fun onVideoStopped(absPath: String, durationMs: Long, sizeBytes: Long) = GT.section("cap.cb.video_stopped") {
            recordingActive = false
            activeRecordingPath = null
            logMsg("Capture: video stopped $absPath dur=${durationMs}ms size=$sizeBytes")
            listeners.forEach { it.onVideoStopped(absPath, durationMs, sizeBytes) }
        }
        override fun onCaptureError(code: Int, msg: String) = GT.section("cap.cb.error") {
            logMsg("Capture: error code=$code $msg")
            listeners.forEach { it.onCaptureError(code, msg) }
        }
        override fun onFrame(jpeg: ByteArray, width: Int, height: Int, rotationDeg: Int, frameId: Long) = GT.section("cap.cb.frame") {
            listeners.forEach { it.onFrame(jpeg, width, height, rotationDeg, frameId) }
        }
        override fun onRppgSamples(trackingIds: LongArray, rgb: FloatArray, tMs: Long) = GT.section("cap.cb.rppg_samples") {
            listeners.forEach { it.onRppgSamples(trackingIds, rgb, tMs) }
        }
    }

    private fun reportDeathDuringRecording(reason: String) {
        if (recordingActive) {
            val path = activeRecordingPath
            recordingActive = false
            activeRecordingPath = null
            logMsg("Capture: KILLED DURING RECORDING ($reason) -- orphaned file=$path; likely lmkd OOM kill")
            listeners.forEach {
                try { it.onCaptureKilledDuringRecording(path) } catch (e: Exception) {
                    logMsg("Capture: onCaptureKilledDuringRecording listener threw: ${e.message}")
                }
            }
        }
    }

    private val deathRecipient: IBinder.DeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            reportDeathDuringRecording("binderDied")
            logMsg("Capture: binder DIED -- rebinding")
            api = null
            isBound = false
            val old = currentBinder
            currentBinder = null
            try { old?.unlinkToDeath(this, 0) } catch (_: Exception) {}
            val c = connection
            if (c != null) try { context.unbindService(c) } catch (_: Exception) {}
            if (!stopped) scheduleBind(1500)
        }
    }

    private var connection: ServiceConnection? = null

    init {
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                logMsg("Capture: connected to $name")
                api = ICapture.Stub.asInterface(service)
                isBound = true
                currentBinder = service
                try { service?.linkToDeath(deathRecipient, 0) } catch (_: Exception) {}
                try { api?.registerCallback(callback) } catch (e: Exception) {
                    logMsg("Capture: registerCallback failed: ${e.message}")
                }
                // Pre-open the camera + run 3A warmup as soon as the AIDL
                // is alive. Without this the first FN-button shutter pays
                // ~3s of cold-open latency before the HAL accepts the
                // capture request. Pool stays hot for WARM_IDLE_MS (60s)
                // and is refreshed by every takePhoto.
                try { api?.warmUp() } catch (e: Exception) {
                    logMsg("Capture: warmUp failed: ${e.message}")
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                reportDeathDuringRecording("onServiceDisconnected")
                logMsg("Capture: DISCONNECTED -- rebinding")
                api = null
                isBound = false
                currentBinder = null
                val c = connection
                if (c != null) try { context.unbindService(c) } catch (_: Exception) {}
                if (!stopped) scheduleBind(1500)
            }
        }
    }

    private fun scheduleBind(delayMs: Long) {
        bindPending?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { if (!stopped) bind() }
        bindPending = r
        mainHandler.postDelayed(r, delayMs)
    }

    fun bind() {
        if (stopped) return
        bindPending = null
        try {
            val component = ComponentName(PKG, SVC)
            val c = connection ?: return
            try {
                val startIntent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                Log.d(TAG, "event=cap_recording")
                context.startForegroundService(startIntent)
            } catch (e: Exception) {
                logMsg("Capture: startForegroundService failed: ${e.message}")
            }
            val bindIntent = Intent().apply { this.component = component }
            // BIND_INCLUDE_CAPABILITIES lets the capture process inherit the
            // listener's foreground/camera capability through the binding.
            // Without it, on Android 12+ the capture-side camera open fails
            // with "Camera 0 disabled by policy" because its appop CAMERA:
            // foreground is not satisfied -- the capture FGS notification
            // can be revoked while the listener still holds it bound, so
            // bind alone doesn't lift its uid state high enough.
            val bindFlags = Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES
            val ok = context.bindService(bindIntent, c, bindFlags)
            logMsg("Capture: bindService=$ok flags=$bindFlags")
            if (!ok) scheduleBind(2000)
        } catch (e: Exception) {
            logMsg("Capture: bind exception: ${e.message}")
            scheduleBind(2000)
        }
    }

    fun unbind() {
        stopped = true
        bindPending?.let { mainHandler.removeCallbacks(it) }
        bindPending = null
        try { api?.unregisterCallback(callback) } catch (_: Exception) {}
        try { connection?.let { context.unbindService(it) } } catch (_: Exception) {}
        api = null
        isBound = false
    }

    // -- control --

    fun warmUp() = GT.section("cap.bridge.warm_up") {
        try { api?.warmUp() } catch (e: Exception) { logMsg("Capture: warmUp failed: ${e.message}") }
    }

    fun takePhoto() = GT.section("cap.bridge.take_photo") {
        try { api?.takePhoto() } catch (e: Exception) { logMsg("Capture: takePhoto failed: ${e.message}") }
    }
    fun startVideo() = GT.section("cap.bridge.start_video") {
        try { beforeVideoStart?.invoke() } catch (e: Exception) {
            logMsg("Capture: beforeVideoStart hook threw: ${e.message}")
        }
        try { api?.startVideo() } catch (e: Exception) { logMsg("Capture: startVideo failed: ${e.message}") }
    }
    fun togglePauseVideo() = GT.section("cap.bridge.toggle_pause") {
        try { api?.togglePauseVideo() } catch (e: Exception) { logMsg("Capture: togglePause failed: ${e.message}") }
    }
    fun stopVideo() = GT.section("cap.bridge.stop_video") {
        try { api?.stopVideo() } catch (e: Exception) { logMsg("Capture: stopVideo failed: ${e.message}") }
    }
    /** Trigger ONE exposed ReID still. The capture side delivers it via Listener.onFrame
     *  (rotationDeg=0, rotation baked in) or reports failure via Listener.onCaptureError.
     *  The caller (ReidController) drives this periodically and self-throttles. */
    fun captureReidFrame() = GT.section("cap.bridge.capture_reid_frame") {
        try { api?.captureReidFrame(callback) } catch (e: Exception) { logMsg("captureReidFrame failed: ${e.message}") }
    }

    /**
     * Run the SCRFD-10G face detector (Hexagon V73 NPU, in the capture process) on
     * a ReID JPEG. Returns a flat int[]: [count, then per face x0,y0,x1,y1] in JPEG
     * pixel coordinates, or null if the capture binder is unreachable (caller then
     * uses its ML Kit CPU fallback). A [0] result means the NPU ran but found no
     * faces, OR the NPU was unavailable in the capture process (it also falls back).
     * Synchronous binder call (~11ms HTP); the ReID worker invokes it off its own
     * thread, never the binder/main thread.
     */
    fun detectFaces(jpeg: ByteArray): IntArray? = GT.section("cap.bridge.detect_faces") {
        try { api?.detectFaces(jpeg) } catch (e: Exception) {
            logMsg("detectFaces failed: ${e.message}"); null
        }
    }
    /** Start the silent rPPG stream; samples arrive via Listener.onRppgSamples. */
    fun startRppg() = GT.section("cap.bridge.start_rppg") {
        try { api?.startRppg() } catch (e: Exception) { logMsg("Capture: startRppg failed: ${e.message}") }
    }
    /** Stop the rPPG stream. */
    fun stopRppg() = GT.section("cap.bridge.stop_rppg") {
        try { api?.stopRppg() } catch (e: Exception) { logMsg("Capture: stopRppg failed: ${e.message}") }
    }
    fun isRecording(): Boolean = try { api?.isRecording ?: false } catch (_: Exception) { false }
    fun isPaused(): Boolean = try { api?.isPaused ?: false } catch (_: Exception) { false }

    /**
     * Authoritative recording state from the live camera session (recorder
     * surface present), NOT the VideoRecorder boolean. The FN-button toggle must
     * use this: a binder/HAL wedge can make isRecording() return false while the
     * camera + white LED are actually up, which would flip a long-press into a
     * second startVideo() instead of a stop and strand the LED on. When the
     * binder itself is unreachable we cannot know the real state; default to
     * true so the toggle prefers STOP (which is now an unconditional, idempotent
     * camera + LED teardown -- safe even if nothing was recording).
     */
    fun isRecordingActive(): Boolean = try { api?.isRecordingActive ?: true } catch (_: Exception) { true }
}
