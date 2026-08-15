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
    /** Brings the WiFi-Direct group up and BLOCKS until its details are known, or times out. */
    private val openWifiDirect: (timeoutMs: Long) -> String?,
    private val closeWifiDirect: () -> Unit,
    /**
     * Hold the phone's A2DP link to these glasses DOWN (true) / release it (false).
     *
     * The phone plays our uplink mic on STREAM_MUSIC, and because these glasses are the
     * phone's A2DP sink, Android routes that straight back to our own speaker: a real
     * acoustic loop (glasses mic -> WiFi-Direct -> phone -> A2DP -> glasses speaker ->
     * glasses mic). Held for the WHOLE session, not dropped once -- both Android and our
     * own ProfileAutoConnector re-establish A2DP within seconds, so the enforcement lives
     * in bt-manager's A2dpSinkController, which re-drops any reconnect while the hold is
     * held. Release is unconditional and idempotent so a failed start or a service death
     * can never leave the user without BT audio.
     */
    private val setA2dpSuspended: (Boolean) -> Unit = {},
    private val log: ((String) -> Unit)? = null
) {

    private var compositor: LiveArCompositor? = null
    private var audio: ArAudioBridge? = null
    private var server: ArStreamServer? = null

    @Volatile
    var isActive = false
        private set

    /** Tracks whether the A2DP hold is currently ours to release. */
    @Volatile
    private var a2dpSuspended = false

    /** @return the WiFi-Direct details JSON from filesync on success, or null on failure. */
    @Synchronized
    fun start(): String? = try {
        startInner()
    } catch (t: Throwable) {
        // The caller runs this on a bare Thread{} with no catch, so an escaping throw
        // kills the process -- with the camera, the mics, the P2P group and the A2DP
        // hold all still up. Tear down first, then rethrow so the failure is still loud.
        log?.invoke("ArStreamSession: start threw: ${t.message}")
        try { teardown() } catch (_: Throwable) {}
        throw t
    }

    private fun startInner(): String? {
        if (isActive) {
            log?.invoke("ArStreamSession: already active")
            return null
        }

        val details = openWifiDirect(WIFI_READY_TIMEOUT_MS)
        if (details == null) {
            log?.invoke("ArStreamSession: WiFi-Direct group failed to come up")
            return null
        }

        // Before the audio bridge starts: the loop exists the instant the phone has mic
        // audio to play, so the A2DP link must already be down by then.
        suspendA2dp()

        val aud = ArAudioBridge(context, log)
        if (!aud.start()) {
            log?.invoke("ArStreamSession: audio bridge failed to start")
            releaseA2dp()
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
        if (!isActive && compositor == null && audio == null && server == null) {
            // Nothing to tear down, but a hold could still be outstanding (e.g. start()
            // died between suspendA2dp and the first assignment, or a PREVIOUS instance
            // of this class leaked one before the process restarted). Never leak it.
            releaseA2dp()
            return
        }
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
        // Unconditional, and last: whatever else failed, the user must get their
        // Bluetooth audio back. releaseA2dp() is idempotent.
        releaseA2dp()
        server = null
        compositor = null
        audio = null
    }

    /**
     * Idempotent: only the first call per session opens the hold, but the hold is a
     * LEASE that must be renewed for as long as the session lives. The heartbeat is
     * what makes the hold impossible to leak: if this process is force-stopped or
     * crashes, renewals stop and bt-manager expires the lease on its own. A `finally`
     * cannot do that -- it does not run when the process is killed.
     */
    private fun suspendA2dp() {
        if (a2dpSuspended) return
        a2dpSuspended = true
        log?.invoke("ArStreamSession: a2dp suspend requested (echo loop guard)")
        pushSuspend(true)
        val hb = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ar-a2dp-lease").apply { isDaemon = true }
        }
        heartbeat = hb
        hb.scheduleWithFixedDelay(
            {
                // Swallow: a failed renewal must not kill the heartbeat thread, or the
                // hold would expire mid-session and the echo loop would come back.
                if (a2dpSuspended) try { pushSuspend(true) } catch (_: Throwable) {}
            },
            A2DP_LEASE_RENEW_MS, A2DP_LEASE_RENEW_MS, java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    /**
     * Release the hold. Deliberately NOT gated on [a2dpSuspended]: that flag is
     * per-instance, so after a process restart a fresh session object would think no
     * hold exists and every release path -- stop_ar_stream, onDestroy -- would become
     * a silent no-op while a hold from the previous process was still in force. The
     * release itself is idempotent downstream, so calling it unconditionally is free.
     */
    private fun releaseA2dp() {
        a2dpSuspended = false
        try { heartbeat?.shutdownNow() } catch (_: Exception) {}
        heartbeat = null
        log?.invoke("ArStreamSession: a2dp suspend released")
        pushSuspend(false)
    }

    private fun pushSuspend(on: Boolean) {
        try { setA2dpSuspended(on) } catch (e: Exception) {
            log?.invoke("ArStreamSession: a2dp suspend=$on failed: ${e.message}")
        }
    }

    @Volatile
    private var heartbeat: java.util.concurrent.ScheduledExecutorService? = null

    private companion object {
        /**
         * Renewal cadence, well under bt-manager's 5s lease so two consecutive missed
         * renewals (binder hiccup, GC pause) still keep the hold mid-session.
         */
        const val A2DP_LEASE_RENEW_MS =
            com.repository.glasses.listener.bt.BtManagerBridge.A2DP_SUPPRESS_RENEW_MS

        const val COMPOSITOR_START_TIMEOUT_MS = 10_000L

        /** Group formation measured at ~5.5s on this hardware; allow generous headroom. */
        const val WIFI_READY_TIMEOUT_MS = 30_000L
    }
}
