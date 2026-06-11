package com.repository.glasses.listener.audio.routing

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.repository.glasses.listener.media.MediaSessionMonitor
import com.repository.glasses.tracing.GT
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "App:Route"

enum class WearState { UNKNOWN, OFF_HEAD, ON_HEAD, TRANSITIONING_ON, TRANSITIONING_OFF }

/**
 * Routes A2DP audio based on wear state.
 *
 * When the IR proximity sensor reports the glasses are worn, connect to all paired
 * audio sources via A2DP_SINK so the phone routes music to us. When taken off, drop
 * the sink connection so the phone resumes its own speaker output. Pauses the active
 * media session briefly around the switch to avoid audible pops.
 *
 * State-machine invariants (see class-level argument at bottom of file):
 *   - A transitionGeneration AtomicLong uniquely identifies each in-flight transition.
 *     Any new wear event increments the generation, and older transitions detect the
 *     mismatch and abort their tail actions (no more stuck TRANSITIONING_*).
 *   - A hard timeout (TRANSITION_HARD_TIMEOUT_MS) force-coerces TRANSITIONING_* into a
 *     terminal state if the A2DP callback never fires.
 *   - On BluetoothAdapter STATE_OFF/STATE_TURNING_OFF we drop cached proxy and mark
 *     the wear state UNKNOWN, skipping any disconnect attempt (nothing to disconnect).
 *   - A music-active watchdog polls AudioManager.isMusicActive while ON_HEAD and forces
 *     a reconnect cycle if the sink is wedged (connected but silent).
 */
class AudioRoutingController(
    private val ctx: Context,
    private val mediaMonitor: MediaSessionMonitor,
    private val log: (String) -> Unit = {},
) {

    companion object {
        const val DEBUG_ACTION_DISCOVERABLE = "com.repository.glasses.listener.DEBUG_DISCOVERABLE"
        const val DEBUG_EXTRA_DURATION = "duration_s"

        private const val TRANSITION_HARD_TIMEOUT_MS = 6000L
        private const val OFF_SWEEP_INTERVAL_MS = 3000L
        private const val OFF_SWEEP_MAX_PASSES = 3
        private const val WATCHDOG_POLL_MS = 2000L
        private const val WATCHDOG_STALL_THRESHOLD_MS = 15000L
        private const val WATCHDOG_MAX_RECOVERIES = 3
    }

    private val a2dp = A2dpSinkController(ctx, log)
    private val audioMgr = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val stillness = StillnessSensor(ctx, log)

    /**
     * Fold state mirrored from ListenerService.handleFoldChange. Sole gate
     * for ON_HEAD / OFF_HEAD. Nullable so the first setFolded() call from
     * the boot-time poll always passes the dedup, regardless of value.
     */
    @Volatile private var foldedRef: Boolean? = null

    /**
     * True while the system audio mode is non-NORMAL (MODE_IN_CALL,
     * MODE_IN_COMMUNICATION, MODE_RINGTONE). While set, A2DP music routing
     * to the glasses is forbidden -- we force-disconnect any source and
     * refuse to reconnect until the call ends. Toggled by OnModeChangedListener.
     */
    @Volatile private var inCallSuspend: Boolean = false

    private val modeChangedListener = AudioManager.OnModeChangedListener { mode ->
        val callActive = mode != AudioManager.MODE_NORMAL
        log("audio mode changed -> ${audioModeName(mode)} callActive=$callActive")
        if (callActive == inCallSuspend) return@OnModeChangedListener
        inCallSuspend = callActive
        val h = workHandler ?: return@OnModeChangedListener
        if (callActive) {
            // Bump generation so any in-flight transitionOn aborts before it
            // setActive's a sink, then drop all sources unconditionally.
            val myGen = transitionGeneration.incrementAndGet()
            h.post { disconnectForCall(myGen) }
        } else {
            // Call ended. Re-evaluate wear state so we reconnect if the user
            // is wearing the glasses (and re-disconnect if all three off-head
            // conditions hold).
            h.post { resumeFromCall() }
        }
    }

    private fun disconnectForCall(myGen: Long) {
        if (supersededBy(myGen)) return
        log("disconnectForCall: forcing A2DP source disconnect for HFP call")
        try { safePause() } catch (_: Throwable) {}
        a2dp.disconnectAllSources(5000) { ok ->
            log("disconnectForCall: a2dp.disconnectAllSources ok=$ok")
        }
    }

    private fun resumeFromCall() {
        log("resumeFromCall: re-evaluating fold after call end")
        // Resume based on fold state alone. If unfolded, glasses are on the user
        // (or about to be); reconnect. If folded, stay off.
        if (foldedRef != true) {
            val myGen = transitionGeneration.incrementAndGet()
            workHandler?.post { transitionOn(myGen) }
        } else {
            reevaluateOffHead("call-end")
        }
    }

    private val stateRef = AtomicReference(WearState.UNKNOWN)
    val state: WearState get() = stateRef.get()

    /**
     * Monotonically increasing generation counter. Each onWearChanged bumps this;
     * any in-flight transition captures its own generation on entry and aborts
     * tail actions if the current generation has moved past it. Cancellation is
     * cooperative -- checked at each await boundary.
     */
    private val transitionGeneration = AtomicLong(0L)

    private var workThread: HandlerThread? = null
    private var workHandler: Handler? = null

    @Volatile private var running: Boolean = false

    // Watchdog state: timestamps + counters tied to the active connect-session.
    @Volatile private var onHeadSinceMs: Long = 0L
    @Volatile private var lastMusicActiveMs: Long = 0L
    @Volatile private var watchdogRecoveries: Int = 0

    private val debugDiscoverableReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dur = i.getIntExtra(DEBUG_EXTRA_DURATION, 180).coerceIn(30, 3600)
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val m = adapter.javaClass.getMethod(
                    "setScanMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                val ok = m.invoke(adapter, 23, dur)
                log("DEBUG setScanMode(DISCOVERABLE,$dur) result=$ok")
            } catch (t: Throwable) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val m = adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
                    val ok = m.invoke(adapter, 23)
                    log("DEBUG setScanMode(DISCOVERABLE) fallback result=$ok")
                } catch (t2: Throwable) {
                    log("DEBUG discoverable failed: ${t2.message}")
                }
            }
        }
    }

    /**
     * Handles the BluetoothAdapter state transitions triggered by fold/unfold.
     * On TURNING_OFF/OFF: proxy is about to go away; coerce state to UNKNOWN and
     * skip any pending disconnect work. On ON: re-bind the proxy and re-evaluate
     * wear from the sensor.
     */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            Log.d(TAG, "event=bt_state state=$st")
            log("BluetoothAdapter state change -> $st")
            when (st) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    // Proxy is invalid; mark state UNKNOWN, bump generation so any
                    // pending transition aborts rather than trying to drive a dead proxy.
                    transitionGeneration.incrementAndGet()
                    stateRef.set(WearState.UNKNOWN)
                    try { a2dp.release() } catch (_: Throwable) {}
                    onHeadSinceMs = 0L
                    lastMusicActiveMs = 0L
                    watchdogRecoveries = 0
                }
                BluetoothAdapter.STATE_ON -> {
                    // Re-bind proxy. If unfolded, also re-trigger transitionOn so
                    // we sweep paired sources / claim active. Without this, the
                    // boot-time race (setFolded ran before BT proxy was ready)
                    // leaves stateRef stuck at OFF_HEAD until the user re-folds.
                    a2dp.init { ok ->
                        log("A2dpSinkController re-init ready=$ok")
                        if (ok && running && foldedRef == false && !inCallSuspend) {
                            val myGen = transitionGeneration.incrementAndGet()
                            log("BT back ON + unfolded; re-running transitionOn gen=$myGen")
                            workHandler?.post { transitionOn(myGen) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reactive watchdog: fires on every A2DP sink state change broadcast.
     * - ON_HEAD: make the new source the active device (PCM starts flowing).
     * - OFF_HEAD: force-disconnect (we know the user isn't wearing them).
     * - UNKNOWN: do NOT disconnect. The A2DP SINK profile only routes PCM after
     *   setActiveDevice(), so a CONNECTED-but-not-active sink is silent. Holding
     *   the link without claiming active lets the user put the glasses on at any
     *   moment and have audio start instantly via the wear-sensor transitionOn
     *   path -- without the phone having to reconnect (which it often refuses
     *   for several seconds after an unsolicited disconnect).
     */
    private val reactiveWatcher = object : A2dpSinkController.Watcher {
        override fun onDeviceState(dev: android.bluetooth.BluetoothDevice, state: Int) {
            GT.counter("audio.a2dp.connected", if (state == A2dpSinkController.STATE_CONNECTED) 1L else 0L)
            if (!running) return
            if (state != A2dpSinkController.STATE_CONNECTED) return
            if (inCallSuspend) {
                log("reactive: A2DP connection ${dev.address} while inCallSuspend; force-disconnect")
                workHandler?.post {
                    a2dp.disconnectOne(dev) { ok ->
                        log("reactive in-call disconnect ok=$ok for ${dev.address}")
                    }
                }
                return
            }
            val cur = stateRef.get()
            when (cur) {
                WearState.OFF_HEAD, WearState.TRANSITIONING_OFF -> {
                    log("reactive: A2DP connection ${dev.address} while $cur; auto-disconnect")
                    workHandler?.post {
                        a2dp.disconnectOne(dev) { ok ->
                            log("reactive disconnect ok=$ok for ${dev.address}")
                        }
                    }
                }
                WearState.UNKNOWN -> {
                    // Wear sensor hasn't produced a definitive reading yet.
                    // Hold the link silent (no setActive) and let the next
                    // onWearChanged decide. transitionOn() walks bonded devices
                    // and calls setActive on whatever is already CONNECTED, so
                    // audio will start within ~100ms of the user putting the
                    // glasses on -- no reconnect dance required.
                    log("reactive: A2DP connected ${dev.address} while UNKNOWN; holding silent (no setActive) until wear sensor resolves")
                    workHandler?.post { forceNormalAudioMode() }
                }
                WearState.ON_HEAD, WearState.TRANSITIONING_ON -> {
                    log("reactive: A2DP connected ${dev.address} while $cur; maybe setActive + audio-mode check")
                    workHandler?.post {
                        if (a2dp.shouldClaimActive(dev)) {
                            try { a2dp.setActive(dev) } catch (e: Throwable) {
                                log("reactive setActive threw: ${e.message}")
                            }
                        } else {
                            log("reactive: skip setActive for ${dev.address}; active=${a2dp.getActiveSinkDevice()?.address}; letting AOSP handoff decide")
                        }
                        forceNormalAudioMode()
                    }
                }
            }
        }
    }

    fun start() {
        if (workThread != null) {
            log("AudioRoutingController.start ignored: already started")
            return
        }
        workThread = HandlerThread("audio-routing").also { it.start() }
        workHandler = Handler(workThread!!.looper)
        a2dp.init { ok ->
            log("A2dpSinkController ready=$ok")
            if (!ok) return@init
            // The reactive watcher only fires on STATE CHANGES. If a source is already
            // CONNECTED before our BT proxy binds (the common case when ListenerService
            // starts after a reboot with the phone already paired + music playing),
            // the watcher never sees the connection. Sweep existing connections here
            // and reactively disconnect them if we're OFF_HEAD.
            val curState = stateRef.get()
            if (curState == WearState.OFF_HEAD && a2dp.anyConnected()) {
                log("proxy-ready sweep: OFF_HEAD with existing A2DP connections -- force disconnect")
                val h = workHandler
                if (h != null) {
                    val myGen = transitionGeneration.incrementAndGet()
                    h.post { transitionOff(myGen) }
                } else {
                    log("proxy-ready sweep skipped: workHandler not ready")
                }
            }
        }
        a2dp.addWatcher(reactiveWatcher)
        running = true
        try {
            audioMgr.addOnModeChangedListener(
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                modeChangedListener
            )
            // Seed initial state in case we're already in a call when starting.
            inCallSuspend = audioMgr.mode != AudioManager.MODE_NORMAL
            log("OnModeChangedListener registered initialMode=${audioModeName(audioMgr.mode)} inCallSuspend=$inCallSuspend")
        } catch (e: Throwable) {
            log("addOnModeChangedListener failed: ${e.message}")
        }
        stillness.listener = object : StillnessSensor.Listener {
            override fun onStillnessChanged(still: Boolean) {
                if (!running) return
                log("stillness changed -> still=$still")
                // WEAR-DECOUPLED 2026-05-08: stillness only mattered combined
                // with wear=0 for the off-head decision. With A2DP gated on
                // fold-only, stillness no longer drives transitions. Sensor
                // stays running -- callers (e.g. isStill) may still want it.
                // if (still) reevaluateOffHead("stillness")
            }
        }
        stillness.start()
        try {
            val flags2 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_EXPORTED else 0
            ctx.registerReceiver(
                debugDiscoverableReceiver,
                IntentFilter(DEBUG_ACTION_DISCOVERABLE),
                flags2
            )
            log("debugDiscoverableReceiver registered action=$DEBUG_ACTION_DISCOVERABLE")
        } catch (e: Exception) {
            log("debugDiscoverableReceiver register failed: ${e.message}")
        }
        try {
            val flags3 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_EXPORTED else 0
            ctx.registerReceiver(
                btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                flags3
            )
            log("btStateReceiver registered")
        } catch (e: Exception) {
            log("btStateReceiver register failed: ${e.message}")
        }
        // Music-active watchdog is permanently disabled on this hardware.
        // Reason: on the Rokid AOSP fork, A2dpSinkStreamHandler.isPlaying()
        // requires BOTH mStreamAvailable==true AND mAudioFocus in
        // {GAIN, DUCK}. The BT stack itself cycles audio focus
        // (request -> abandon) ~every 9s while the iPhone source is
        // streaming -- visible in `dumpsys audio` as
        // com.android.bluetooth calling requestAudioFocus/abandonAudioFocus
        // from A2dpSinkStreamHandler$1. During the "no focus" windows,
        // isA2dpPlaying() returns false, AudioManager.isMusicActive
        // returns false, and ACTION_AUDIO_STATE_CHANGED is never
        // broadcast to userspace at all on this build. There is no
        // reliable positive signal, so the watchdog's "no audio
        // activity" premise is a false positive during healthy playback
        // and forceReconnectCycle() just interrupts real audio. The
        // reactive path (onWearChanged transitions + setActiveDevice)
        // is sufficient; if bug #4 (wedged sink) recurs, investigate
        // that specific reproducer rather than polling.
        log("AudioRoutingController started")
    }

    fun stop() {
        running = false
        try { ctx.unregisterReceiver(debugDiscoverableReceiver) } catch (_: Exception) {}
        try { ctx.unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        try { a2dp.removeWatcher(reactiveWatcher) } catch (_: Throwable) {}
        try { audioMgr.removeOnModeChangedListener(modeChangedListener) } catch (_: Throwable) {}
        stillness.stop()
        stillness.listener = null
        a2dp.release()
        workHandler?.removeCallbacksAndMessages(null)
        workThread?.quitSafely()
        workThread = null
        workHandler = null
        log("AudioRoutingController stopped")
    }

    /**
     * Mirror fold state from ListenerService.handleFoldChange. Folding the
     * legs is a strong intent signal that the user is putting the glasses
     * down, so a fold transition triggers an off-head re-evaluation.
     */
    fun setFolded(folded: Boolean) {
        if (foldedRef == folded) return
        foldedRef = folded
        log("setFolded($folded)")
        if (folded) {
            reevaluateOffHead("fold")
        } else {
            // WEAR-DECOUPLED 2026-05-08: fold-unfold is now the on-head trigger
            // since wear no longer drives transitionOn. Mirrors the historical
            // wear=true path: bump generation, post transitionOn.
            if (!running) return
            if (inCallSuspend) {
                log("setFolded(false) deferred: inCallSuspend; resumeFromCall will replay")
                return
            }
            val cur = stateRef.get()
            val myGen = transitionGeneration.incrementAndGet()
            if (cur == WearState.TRANSITIONING_ON || cur == WearState.TRANSITIONING_OFF) {
                log("setFolded(false) supersede in-flight transition (was $cur); gen=$myGen")
            }
            workHandler?.post { transitionOn(myGen) }
        }
    }

    /**
     * Commit an OFF_HEAD transition when the legs are folded -- a 100% guarantee
     * the glasses are off the user's face. Idempotent: no-op when already
     * OFF_HEAD. Called from setFolded(true).
     */
    private fun reevaluateOffHead(reason: String) {
        if (!running) return
        val cur = stateRef.get()
        if (cur == WearState.OFF_HEAD || cur == WearState.TRANSITIONING_OFF) return
        val folded = foldedRef == true
        if (!folded) {
            log("reevaluateOffHead($reason): conditions unmet folded=$folded; staying $cur")
            return
        }
        val h = workHandler ?: return
        val myGen = transitionGeneration.incrementAndGet()
        log("reevaluateOffHead($reason): off-head confirmed (folded=$folded); transitionOff gen=$myGen")
        h.post { transitionOff(myGen) }
    }

    private fun supersededBy(myGen: Long): Boolean {
        val cur = transitionGeneration.get()
        if (cur != myGen) {
            log("transition gen=$myGen superseded by gen=$cur; aborting tail")
            return true
        }
        return false
    }

    private fun transitionOn(myGen: Long) {
        if (supersededBy(myGen)) return
        if (inCallSuspend || audioMgr.mode != AudioManager.MODE_NORMAL) {
            // HFP call (or any non-NORMAL mode) is active. Music must NEVER
            // play through the glasses during a call. Leave the wear state
            // alone -- when the call ends, resumeFromCall() will re-fire
            // transitionOn if wear is still true.
            log("transitionOn deferred: inCallSuspend=$inCallSuspend mode=${audioModeName(audioMgr.mode)}")
            return
        }
        // If already ON_HEAD, still re-verify (idempotent).
        stateRef.set(WearState.TRANSITIONING_ON)
        val started = System.currentTimeMillis()
        safePause()
        sleepQuiet(200)
        if (supersededBy(myGen)) return
        val done = java.util.concurrent.CountDownLatch(1)
        val result = AtomicReference<Pair<Boolean, Int>>(Pair(false, 0))
        a2dp.connectAllPairedSources(5000) { ok, n ->
            result.set(Pair(ok, n))
            done.countDown()
        }
        done.await(TRANSITION_HARD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (supersededBy(myGen)) return
        val (ok, n) = result.get()
        log("transitionOn connected=$n ok=$ok elapsedMs=${System.currentTimeMillis() - started}")
        // transitionOn is only ever invoked when we want to be ON_HEAD
        // (setFolded(false) / resumeFromCall). The connect callback may time
        // out or land 0 connections (proxy not ready, bond not done, BT slow),
        // but that is not a reason to flip OFF_HEAD -- the reactive watcher
        // claims active on any sink that connects later. Always commit ON_HEAD.
        sleepQuiet(100)
        forceNormalAudioMode()
        try {
            val stragglers = a2dp.bondedNonDisconnected()
            for (d in stragglers) {
                if (a2dp.getCurrentState(d) == A2dpSinkController.STATE_CONNECTED &&
                    a2dp.shouldClaimActive(d)) {
                    a2dp.setActive(d)
                }
            }
        } catch (e: Throwable) {
            log("post-ON setActive sweep threw: ${e.message}")
        }
        safePlay()
        stateRef.set(WearState.ON_HEAD)
        onHeadSinceMs = System.currentTimeMillis()
        lastMusicActiveMs = 0L
        watchdogRecoveries = 0
        log("transitionOn committed ON_HEAD: connect ok=$ok n=$n")
    }

    private fun transitionOff(myGen: Long) {
        if (supersededBy(myGen)) return
        // Note: no audio-mode bail here. Disconnecting is always safe regardless
        // of call state; in fact during a call we WANT the sink dropped.
        stateRef.set(WearState.TRANSITIONING_OFF)
        onHeadSinceMs = 0L
        lastMusicActiveMs = 0L
        watchdogRecoveries = 0
        safePause()
        sleepQuiet(200)
        if (supersededBy(myGen)) return
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = AtomicReference(false)
        a2dp.disconnectAllSources(5000) { success ->
            ok.set(success)
            done.countDown()
        }
        done.await(TRANSITION_HARD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (supersededBy(myGen)) return
        val okVal = ok.get()
        log("transitionOff disconnect ok=$okVal")
        if (!okVal) log("WARN disconnect timed out; coercing to OFF_HEAD and sweeping")
        sleepQuiet(100)
        safePlay()
        stateRef.set(WearState.OFF_HEAD)
        scheduleOffSweep(myGen, pass = 1)
    }

    /**
     * Idempotent, repeat-up-to-3x disconnect sweep after OFF_HEAD. The phone may
     * auto-reconnect 1-5s after we drop the link; the reactive watcher catches most,
     * this polls for the rest. Each pass checks generation so it no-ops if the user
     * put the glasses back on during the sweep window.
     */
    private fun scheduleOffSweep(myGen: Long, pass: Int) {
        val h = workHandler ?: return
        h.postDelayed({
            if (!running) return@postDelayed
            if (supersededBy(myGen)) return@postDelayed
            if (stateRef.get() != WearState.OFF_HEAD) return@postDelayed
            val stragglers = a2dp.bondedNonDisconnected()
            if (stragglers.isEmpty()) {
                log("post-OFF sweep pass=$pass: all clean")
                return@postDelayed
            }
            log("post-OFF sweep pass=$pass: ${stragglers.size} straggler(s), disconnecting")
            for (d in stragglers) {
                a2dp.disconnectOne(d) { ok -> log("post-OFF sweep disconnect ${d.address} ok=$ok") }
            }
            if (pass < OFF_SWEEP_MAX_PASSES) scheduleOffSweep(myGen, pass + 1)
            else log("post-OFF sweep: max passes reached")
        }, OFF_SWEEP_INTERVAL_MS)
    }

    /**
     * Watchdog: every WATCHDOG_POLL_MS, while ON_HEAD + A2DP connected, poll
     * AudioManager.isMusicActive(). If music-active has never been true since the
     * connection was established AND we've been connected >WATCHDOG_STALL_THRESHOLD_MS,
     * the sink is wedged (bug 4/6A). Force a reconnect cycle. Capped to
     * WATCHDOG_MAX_RECOVERIES per session to avoid infinite loops.
     */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (!running) return
                val cur = stateRef.get()
                if (cur == WearState.ON_HEAD && a2dp.anyConnected()) {
                    val now = System.currentTimeMillis()
                    val connectedSince = onHeadSinceMs
                    // Authoritative signal: AOSP A2DP SINK broadcasts AVDTP stream
                    // START/STOP/SUSPEND via ACTION_AUDIO_STATE_CHANGED, and exposes
                    // isA2dpPlaying(device) via the hidden profile proxy. Both come
                    // from the real BT protocol layer. AudioManager.isMusicActive is
                    // not a valid signal on the sink role (PCM is decoded inside the
                    // BT stack, not played through a local AudioTrack), and the
                    // MediaController bridge (BluetoothMediaBrowserService) is not
                    // reliably enumerated on this build, so MediaSessionMonitor was
                    // also false-negative. Use the protocol-level signal only.
                    val avdtpPlaying = try { a2dp.isAnyPlayingNow() } catch (_: Throwable) { false }
                    val avdtpStartedAt = try { a2dp.lastAudioStartedMs() } catch (_: Throwable) { 0L }
                    if (avdtpPlaying || avdtpStartedAt > 0L) {
                        lastMusicActiveMs = now
                    }
                    if (connectedSince > 0 &&
                        (now - connectedSince) > WATCHDOG_STALL_THRESHOLD_MS &&
                        lastMusicActiveMs == 0L &&
                        watchdogRecoveries < WATCHDOG_MAX_RECOVERIES
                    ) {
                        watchdogRecoveries++
                        log("WARN watchdog: sink wedged (connected ${(now - connectedSince)}ms, never musicActive); " +
                                "forcing reconnect cycle #$watchdogRecoveries")
                        workHandler?.post { forceReconnectCycle() }
                    }
                }
            } catch (e: Throwable) {
                log("watchdog threw: ${e.message}")
            } finally {
                workHandler?.postDelayed(this, WATCHDOG_POLL_MS)
            }
        }
    }

    /**
     * Disconnect all sinks, wait 500ms, then reconnect. Used by the watchdog to
     * recover a wedged sink. Does not touch stateRef (remains ON_HEAD throughout).
     */
    private fun forceReconnectCycle() {
        val done = java.util.concurrent.CountDownLatch(1)
        a2dp.disconnectAllSources(4000) { ok ->
            log("forceReconnectCycle: disconnect ok=$ok")
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        sleepQuiet(500)
        a2dp.connectAllPairedSources(5000) { ok, n ->
            log("forceReconnectCycle: reconnect ok=$ok n=$n")
        }
        // Reset the connection-session anchor so the watchdog re-arms cleanly.
        onHeadSinceMs = System.currentTimeMillis()
    }

    /**
     * Ensure AudioManager isn't stuck in a telephony mode from a prior (possibly
     * crashed) caller. Logs before/after for diagnostics; idempotent.
     */
    private fun forceNormalAudioMode() {
        val before = audioMgr.mode
        val beforeName = audioModeName(before)
        if (before != AudioManager.MODE_NORMAL) {
            try {
                audioMgr.mode = AudioManager.MODE_NORMAL
                val after = audioMgr.mode
                Log.i("App:Call", "event=audio_mode_change from=$beforeName to=${audioModeName(after)}")
                log("audio mode forced MODE_NORMAL (was=$before now=$after)")
            } catch (e: Throwable) {
                Log.w("App:Call", "event=audio_mode_change_fail from=$beforeName err=${e.message}")
                log("failed to force MODE_NORMAL from $before: ${e.message}")
            }
        } else {
            Log.d("App:Call", "event=audio_mode_noop mode=$beforeName")
            log("audio mode already MODE_NORMAL")
        }
    }

    private fun audioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        else -> "MODE_UNKNOWN($mode)"
    }

    private fun safePause() {
        try { mediaMonitor.pauseActive() } catch (_: Throwable) { }
    }
    private fun safePlay() {
        try { mediaMonitor.playActive() } catch (_: Throwable) { }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/*
 * Why the state machine cannot wedge:
 *
 *   1. Every wear event bumps transitionGeneration. An older transition executing on
 *      the work thread checks supersededBy() at every await boundary and exits early
 *      if a newer event has arrived. The "guard against TRANSITIONING_*" from the old
 *      code is replaced by "always cancel and restart", so we can never drop a wear
 *      event.
 *   2. Every await uses a hard timeout (TRANSITION_HARD_TIMEOUT_MS). If the A2DP
 *      callback never fires, transitionOn() falls through to stateRef.set(OFF_HEAD)
 *      and transitionOff() falls through to stateRef.set(OFF_HEAD) -- the state never
 *      remains TRANSITIONING_*.
 *   3. BluetoothAdapter STATE_OFF/TURNING_OFF bumps the generation and drops the
 *      proxy, so any pending transition aborts cleanly; STATE_ON re-binds and
 *      re-evaluates fold state.
 *   4. The watchdog handles the silent-sink case (bug 4/6A): if audio is never
 *      flowing despite a connected sink, it force-reconnects. Capped at 3 recoveries
 *      per session to prevent loops.
 *   5. The post-OFF sweep repeats up to 3 times, so even a slow auto-reconnect is
 *      always torn back down.
 */
