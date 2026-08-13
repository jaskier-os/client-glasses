package com.repository.glasses.listener.arstream

import android.content.Context
import com.repository.glasses.listener.input.remote.RemoteInputBridgeService

/**
 * Owns one live AR streaming session on the glasses: WiFi-Direct group, compositor, audio, and
 * the two TCP servers.
 *
 * Start and stop are all-or-nothing. A partial start tears everything back down rather than
 * leaving the camera, the mics, or the P2P group running with no consumer -- all three are
 * expensive enough that a leak is a battery incident, not a cosmetic bug.
 */
class ArStreamSession(
    private val context: Context,
    private val bridge: () -> RemoteInputBridgeService?,
    private val openWifiDirect: () -> String?,
    private val closeWifiDirect: () -> Unit,
    private val log: ((String) -> Unit)? = null
) {

    private var compositor: LiveArCompositor? = null
    private var audio: ArAudioBridge? = null
    private var server: ArStreamServer? = null

    @Volatile
    var isActive = false
        private set

    /** @return the WiFi-Direct details JSON from filesync on success, or null on failure. */
    @Synchronized
    fun start(): String? {
        if (isActive) {
            log?.invoke("ArStreamSession: already active")
            return null
        }

        val details = openWifiDirect()
        if (details == null) {
            log?.invoke("ArStreamSession: WiFi-Direct group failed to come up")
            return null
        }

        val aud = ArAudioBridge(log)
        if (!aud.start()) {
            log?.invoke("ArStreamSession: audio bridge failed to start")
            closeWifiDirect()
            return null
        }
        audio = aud

        val comp = LiveArCompositor(context).apply { remoteLog = log }
        compositor = comp

        val srv = ArStreamServer(comp, aud, log)
        server = srv
        srv.onStopRequested = { stop() }

        if (!srv.start()) {
            log?.invoke("ArStreamSession: stream server failed to start")
            teardown()
            return null
        }

        // A latch, not wait/notify: the callback can fire before this thread reaches the wait,
        // and that missed signal would stall the BT reply for the full timeout.
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.CountDownLatch(1)
        comp.start(
            onFrame = { payload, key, config -> srv.onEncodedFrame(payload, key, config) },
            hudSurfaceReady = { surface, w, h ->
                // No UI sink means no HUD layer. The stream still carries the camera, which is
                // useful, so continue -- but say so, because "AR stream with no AR" otherwise
                // looks like a compositing bug.
                if (bridge()?.pushHudSurface(surface, w, h) != true) {
                    log?.invoke("ArStreamSession: no UI sink attached -- streaming camera only")
                }
            },
            callback = { ok ->
                started.set(ok)
                done.countDown()
            }
        )
        done.await(COMPOSITOR_START_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)

        if (!started.get()) {
            log?.invoke("ArStreamSession: compositor failed to start")
            teardown()
            return null
        }

        isActive = true
        log?.invoke("ArStreamSession: started")
        return details
    }

    @Synchronized
    fun stop() {
        if (!isActive && compositor == null && audio == null && server == null) return
        teardown()
        log?.invoke("ArStreamSession: stopped")
    }

    /** Reverse order of construction; every step independently guarded. */
    private fun teardown() {
        isActive = false
        try { bridge()?.clearHudSurface() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        try { compositor?.stop() } catch (_: Exception) {}
        try { audio?.stop() } catch (_: Exception) {}
        try { closeWifiDirect() } catch (_: Exception) {}
        server = null
        compositor = null
        audio = null
    }

    private companion object {
        const val COMPOSITOR_START_TIMEOUT_MS = 10_000L
    }
}
