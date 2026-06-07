package com.repository.glasses.listener.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.repository.glasses.tracing.GT

private const val TAG = "App:MediaSess"

/**
 * Monitors active media sessions (including AVRCP-provided remote sessions)
 * and provides transport controls. Device-agnostic -- works with any A2DP source.
 */
class MediaSessionMonitor(
    private val context: Context,
    private val log: (String) -> Unit
) {

    interface Callback {
        fun onMediaStateChanged(track: String, playing: Boolean)
        fun onProgressUpdate(positionMs: Long, durationMs: Long) {}
    }

    var callback: Callback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nslComponent =
        ComponentName(context, MediaNotificationListener::class.java)

    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    // De-duplication state
    private var lastTrack = ""
    private var lastPlaying = false

    /** Whether any media session is currently playing. Safe to read from any thread. */
    val isPlaying: Boolean get() = lastPlaying

    // Progress polling
    private val PROGRESS_POLL_MS = 1000L
    private val progressRunnable = object : Runnable {
        override fun run() {
            emitProgress()
            mainHandler.postDelayed(this, PROGRESS_POLL_MS)
        }
    }
    private var progressPolling = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            mainHandler.post { updateActiveController(controllers) }
        }

    private val SCAN_INTERVAL_MS = 5000L
    @Volatile private var scanning = false

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!scanning) return
            Log.v(TAG, "event=session_poll")
            try {
                val controllers = mediaSessionManager.getActiveSessions(nslComponent)
                if (controllers.isNotEmpty()) {
                    log("MediaSessionMonitor: scan found ${controllers.size} sessions")
                    updateActiveController(controllers)
                    // activeController set means we stop scanning
                    if (activeController != null) {
                        scanning = false
                        return
                    }
                }
            } catch (_: SecurityException) {
                // NLS not ready yet, keep scanning
            }
            mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    fun start() {
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, nslComponent)
        } catch (_: SecurityException) {
            log("MediaSessionMonitor: NLS not enabled, listener registration deferred")
        }
        // Initial check + start background scan if nothing found
        try {
            val controllers = mediaSessionManager.getActiveSessions(nslComponent)
            log("MediaSessionMonitor started, ${controllers.size} active sessions")
            updateActiveController(controllers)
        } catch (_: SecurityException) {}
        // Keep scanning until a session is found (handles NLS reconnect delay)
        if (activeController == null) {
            scanning = true
            mainHandler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
        }
    }

    fun stop() {
        scanning = false
        mainHandler.removeCallbacks(scanRunnable)
        stopProgressPolling()
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {}
        detachController()
    }

    /**
     * Pause the currently-tracked controller if any. Used by AudioRoutingController
     * to suppress playback across an A2DP route switch. Safe no-op when nothing active.
     */
    fun pauseActive() {
        val transport = activeController?.transportControls ?: run {
            log("pauseActive: no active controller")
            return
        }
        log("pauseActive: sending pause to ${activeController?.packageName}")
        try { transport.pause() } catch (e: Throwable) { log("pauseActive failed: ${e.message}") }
    }

    /**
     * Resume the currently-tracked controller if any. Paired with [pauseActive] around
     * A2DP route switches.
     */
    fun playActive() {
        val transport = activeController?.transportControls ?: run {
            log("playActive: no active controller")
            return
        }
        log("playActive: sending play to ${activeController?.packageName}")
        try { transport.play() } catch (e: Throwable) { log("playActive failed: ${e.message}") }
    }

    fun dispatchCommand(command: String) {
        val transport = activeController?.transportControls
        if (transport != null) {
            log("Media dispatch via session: $command")
            when (command) {
                "play" -> transport.play()
                "pause" -> transport.pause()
                "next" -> transport.skipToNext()
                "prev" -> transport.skipToPrevious()
            }
            return
        }
        // Fallback: dispatch media key event (works even without active session)
        val keyCode = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        log("Media dispatch via key event: $command (keyCode=$keyCode)")
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            log("No active media sessions")
            detachController()
            notifyState("", false)
            return
        }

        // Prefer the first playing session, otherwise first session
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val controller = playing ?: controllers[0]

        // Skip if same session
        if (controller.sessionToken == activeController?.sessionToken) return

        log("Switching to media session: ${controller.packageName}")
        detachController()
        attachController(controller)
    }

    private fun attachController(controller: MediaController) {
        activeController = controller
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                emitCurrentState()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                emitCurrentState()
            }

            override fun onSessionDestroyed() {
                log("Media session destroyed: ${controller.packageName}")
                detachController()
                notifyState("", false)
                // Re-check for other sessions
                try {
                    val remaining = mediaSessionManager.getActiveSessions(nslComponent)
                    updateActiveController(remaining)
                } catch (_: Exception) {}
                // Resume scanning if no replacement found
                if (activeController == null && !scanning) {
                    scanning = true
                    mainHandler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                }
            }
        }
        controllerCallback = cb
        controller.registerCallback(cb, mainHandler)
        emitCurrentState()
    }

    private fun detachController() {
        controllerCallback?.let { activeController?.unregisterCallback(it) }
        activeController = null
        controllerCallback = null
    }

    private fun emitCurrentState() {
        val controller = activeController ?: return
        val metadata = controller.metadata
        val state = controller.playbackState

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val track = when {
            artist.isNotEmpty() && title.isNotEmpty() -> "$artist - $title"
            title.isNotEmpty() -> title
            else -> ""
        }
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        notifyState(track, isPlaying)
    }

    private fun notifyState(track: String, playing: Boolean) {
        if (track == lastTrack && playing == lastPlaying) return
        lastTrack = track
        lastPlaying = playing
        callback?.onMediaStateChanged(track, playing)
        if (playing) startProgressPolling() else stopProgressPolling()
    }

    private fun emitProgress() {
        val controller = activeController ?: return
        val state = controller.playbackState ?: return
        val metadata = controller.metadata ?: return
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration <= 0) return
        val position = state.position +
            ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                state.playbackSpeed).toLong()
        val positionMs = position.coerceIn(0, duration)
        GT.counter("audio.playback.position_ms", positionMs)
        GT.counter("audio.playback.active", if (isPlaying) 1L else 0L)
        callback?.onProgressUpdate(positionMs, duration)
    }

    private fun startProgressPolling() {
        if (progressPolling) return
        progressPolling = true
        mainHandler.post(progressRunnable)
    }

    private fun stopProgressPolling() {
        progressPolling = false
        mainHandler.removeCallbacks(progressRunnable)
    }
}
