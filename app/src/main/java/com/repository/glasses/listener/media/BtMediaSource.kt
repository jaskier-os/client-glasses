package com.repository.glasses.listener.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Canonical music source: the phone's A2DP source pushes metadata through AVRCP
 * which AOSP's AvrcpControllerService bridges into a local MediaSession owned by
 * [com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService]. We
 * attach a [MediaBrowser] to that service, then a [MediaController] on the
 * resulting session token, and emit state changes + expose transport controls.
 *
 * This replaces the old custom CH_NOW_PLAYING / CH_MEDIA_CMD channels -- no
 * phone-app cooperation is required: everything rides on standard Bluetooth.
 *
 * Auto-reconnects on connection failures (2-second backoff, unbounded).
 */
class BtMediaSource(
    private val ctx: Context,
    private val log: (String) -> Unit = {},
) {
    data class State(
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        /** Reported position in ms, -1 if unknown. */
        val positionMs: Long,
        /** Duration in ms, -1 if unknown. */
        val durationMs: Long,
        /**
         * SystemClock.elapsedRealtime() on THIS device at the moment [positionMs]
         * was sampled. Use this + the current elapsedRealtime to extrapolate live
         * position while [playing].
         */
        val positionTs: Long,
    )

    interface Listener { fun onState(s: State) }

    @Volatile private var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private var mediaBrowser: MediaBrowser? = null
    private var mediaController: MediaController? = null
    private var controllerCb: MediaController.Callback? = null
    @Volatile private var started = false

    private val component = ComponentName(
        "com.android.bluetooth",
        "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService",
    )

    private val reconnectRunnable = Runnable {
        if (!started) return@Runnable
        log("BtMediaSource: reconnecting MediaBrowser")
        openBrowser()
    }

    private val browserCb = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val token = mediaBrowser?.sessionToken ?: run {
                log("BtMediaSource: onConnected but no sessionToken")
                scheduleReconnect()
                return
            }
            val mc = try {
                MediaController(ctx, token)
            } catch (t: Throwable) {
                log("BtMediaSource: MediaController() threw: ${t.message}")
                scheduleReconnect()
                return
            }
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { emit() }
                override fun onPlaybackStateChanged(state: PlaybackState?) { emit() }
                override fun onSessionDestroyed() {
                    log("BtMediaSource: session destroyed")
                    detachController()
                    scheduleReconnect()
                }
            }
            try {
                mc.registerCallback(cb, main)
            } catch (t: Throwable) {
                log("BtMediaSource: registerCallback failed: ${t.message}")
                scheduleReconnect()
                return
            }
            mediaController = mc
            controllerCb = cb
            log("BtMediaSource: connected (pkg=${mc.packageName})")
            emit()
        }

        override fun onConnectionFailed() {
            log("BtMediaSource: MediaBrowser connection FAILED")
            scheduleReconnect()
        }

        override fun onConnectionSuspended() {
            log("BtMediaSource: MediaBrowser suspended")
            detachController()
            scheduleReconnect()
        }
    }

    fun start(l: Listener) {
        if (started) return
        started = true
        listener = l
        openBrowser()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(reconnectRunnable)
        detachController()
        mediaBrowser?.let { runCatching { it.disconnect() } }
        mediaBrowser = null
        listener = null
        log("BtMediaSource stopped")
        emitEmpty()
    }

    /** cmd in {"play","pause","toggle","next","previous","stop"}. */
    fun sendCommand(cmd: String): Boolean {
        val tc = try { mediaController?.transportControls } catch (_: Exception) { null }
        if (tc == null) {
            log("BtMediaSource sendCommand($cmd): no transportControls")
            return false
        }
        return try {
            when (cmd) {
                "play" -> tc.play()
                "pause" -> tc.pause()
                "toggle" -> {
                    val s = try { mediaController?.playbackState?.state } catch (_: Exception) { null }
                    if (s == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
                }
                "next" -> tc.skipToNext()
                "previous", "prev" -> tc.skipToPrevious()
                "stop" -> tc.stop()
                else -> {
                    log("BtMediaSource sendCommand: unknown cmd '$cmd'")
                    return false
                }
            }
            log("BtMediaSource sendCommand: $cmd OK")
            true
        } catch (e: Exception) {
            log("BtMediaSource sendCommand($cmd) failed: ${e.message}")
            false
        }
    }

    private fun openBrowser() {
        try {
            mediaBrowser?.let { runCatching { it.disconnect() } }
            val mb = MediaBrowser(ctx, component, browserCb, null)
            mediaBrowser = mb
            mb.connect()
            log("BtMediaSource: connecting to $component")
        } catch (t: Throwable) {
            log("BtMediaSource: connect() threw: ${t.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!started) return
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, 2_000L)
    }

    private fun detachController() {
        val mc = mediaController
        val cb = controllerCb
        if (mc != null && cb != null) runCatching { mc.unregisterCallback(cb) }
        mediaController = null
        controllerCb = null
    }

    private fun emit() {
        val mc = mediaController ?: return emitEmpty()
        val md = try { mc.metadata } catch (_: Exception) { null }
        val ps = try { mc.playbackState } catch (_: Exception) { null }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val positionMs = ps?.position ?: -1L
        val durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        // ps.lastPositionUpdateTime is SystemClock.elapsedRealtime() at sample time.
        val positionTs = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime()
        listener?.onState(State(title, artist, album, playing, positionMs, durationMs, positionTs))
    }

    private fun emitEmpty() {
        listener?.onState(State("", "", "", false, -1L, -1L, SystemClock.elapsedRealtime()))
    }
}
