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
    fun isRecording(): Boolean = try { api?.isRecording ?: false } catch (_: Exception) { false }
    fun isPaused(): Boolean = try { api?.isPaused ?: false } catch (_: Exception) { false }
}
