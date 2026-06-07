package com.repository.glasses.listener.audio.routing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * Controls the hidden BluetoothA2dpSink profile via reflection.
 *
 * Uses the same reflection pattern as BluetoothHidMouse does for BluetoothHidDevice.
 * The profile id for A2DP_SINK is 11 (hidden BluetoothProfile constant).
 *
 * Responsibilities:
 *  - Open profile proxy on [init].
 *  - Track CONNECTION_STATE_CHANGED broadcasts for the sink profile.
 *  - Iterate bonded devices to connect/disconnect all paired audio sources.
 */
@SuppressLint("MissingPermission")
class A2dpSinkController(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    private val appCtx: Context = ctx.applicationContext

    companion object {
        // Hidden constants
        private const val A2DP_SINK_PROFILE_ID = 11

        private const val ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        // Hidden AOSP action: fires on real AVDTP stream START/STOP/SUSPEND.
        // This is the authoritative signal for "PCM is actually flowing through
        // the sink", unlike AudioManager.isMusicActive (false on sink role) or
        // MediaController playback state (often empty on AVRCP bridge).
        private const val ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.AUDIO_STATE_CHANGED"
        private const val EXTRA_AUDIO_STATE = "android.bluetooth.profile.extra.AUDIO_STATE"
        // Hidden BluetoothA2dpSink audio-state constants.
        const val STATE_AUDIO_STOPPED = 10
        const val STATE_AUDIO_STARTED = 11
        const val STATE_AUDIO_SUSPENDED = 12

        private const val EXTRA_STATE = BluetoothProfile.EXTRA_STATE
        private const val EXTRA_PREVIOUS_STATE = BluetoothProfile.EXTRA_PREVIOUS_STATE
        private const val EXTRA_DEVICE = BluetoothDevice.EXTRA_DEVICE

        const val STATE_DISCONNECTED = BluetoothProfile.STATE_DISCONNECTED // 0
        const val STATE_CONNECTING = BluetoothProfile.STATE_CONNECTING     // 1
        const val STATE_CONNECTED = BluetoothProfile.STATE_CONNECTED       // 2
        const val STATE_DISCONNECTING = BluetoothProfile.STATE_DISCONNECTING // 3
    }

    // --- AVDTP audio-state tracking ---
    // lastAudioStartedMs is the wall-clock time of the most recent STATE_AUDIO_STARTED
    // broadcast we saw from any sink device. Watchdog uses this as the authoritative
    // "was audio ever flowing?" signal.
    @Volatile private var lastAudioStartedMs: Long = 0L
    @Volatile private var lastAudioStateCode: Int = -1
    fun lastAudioStartedMs(): Long = lastAudioStartedMs
    fun lastAudioStateCode(): Int = lastAudioStateCode

    /**
     * Poll the hidden BluetoothA2dpSink.isA2dpPlaying(device) via reflection
     * for every currently-connected device. Returns true if any sink is
     * actively streaming PCM right now.
     */
    fun isAnyPlayingNow(): Boolean {
        val p = proxy ?: return false
        return try {
            val m = p.javaClass.getMethod("isA2dpPlaying", BluetoothDevice::class.java)
            val connected = bondedNonDisconnected()
            connected.any { dev ->
                try { m.invoke(p, dev) as? Boolean ?: false } catch (_: Throwable) { false }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private val audioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_AUDIO_STATE_CHANGED) return
            val dev: BluetoothDevice? = intent.getParcelableExtra(EXTRA_DEVICE)
            val state = intent.getIntExtra(EXTRA_AUDIO_STATE, -1)
            lastAudioStateCode = state
            val label = when (state) {
                STATE_AUDIO_STARTED -> "STARTED"
                STATE_AUDIO_STOPPED -> "STOPPED"
                STATE_AUDIO_SUSPENDED -> "SUSPENDED"
                else -> "state=$state"
            }
            log("A2dpSink audio state dev=${dev?.let { safeAddr(it) } ?: "?"} $label")
            if (state == STATE_AUDIO_STARTED) {
                lastAudioStartedMs = System.currentTimeMillis()
            }
        }
    }
    private var audioStateReceiverRegistered = false

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var proxy: BluetoothProfile? = null
    private val stateMap = ConcurrentHashMap<BluetoothDevice, Int>()
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CONNECTION_STATE_CHANGED) return
            val dev: BluetoothDevice? = intent.getParcelableExtra(EXTRA_DEVICE)
            val state = intent.getIntExtra(EXTRA_STATE, -1)
            val prev = intent.getIntExtra(EXTRA_PREVIOUS_STATE, -1)
            if (dev != null && state >= 0) {
                synchronized(lock) { stateMap[dev] = state }
                log("A2dpSink state change dev=${safeAddr(dev)} prev=$prev new=$state")
                notifyWatchers(dev, state)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
            if (profile == A2DP_SINK_PROFILE_ID) {
                proxy = p
                log("A2dpSink proxy connected profile=$profile proxy=${p != null}")
                onReadyCb?.invoke(p != null)
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == A2DP_SINK_PROFILE_ID) {
                proxy = null
                log("A2dpSink proxy disconnected profile=$profile")
            }
        }
    }

    private var onReadyCb: ((Boolean) -> Unit)? = null

    // ---- Watcher registry for state transitions while waiting for connect/disconnect ----

    /**
     * Public watcher for external consumers (e.g. AudioRoutingController reactive disconnect).
     * Uses the same notification path as internal StateWatcher; all watchers are fed from the
     * CONNECTION_STATE_CHANGED broadcast receiver.
     */
    interface Watcher { fun onDeviceState(dev: BluetoothDevice, state: Int) }

    private interface StateWatcher { fun onDeviceState(dev: BluetoothDevice, state: Int) }
    private val watchers = mutableListOf<StateWatcher>()

    private fun addWatcher(w: StateWatcher) = synchronized(watchers) { watchers.add(w) }
    private fun removeWatcher(w: StateWatcher) = synchronized(watchers) { watchers.remove(w) }
    private fun notifyWatchers(dev: BluetoothDevice, state: Int) {
        val snapshot = synchronized(watchers) { watchers.toList() }
        for (w in snapshot) {
            try { w.onDeviceState(dev, state) } catch (_: Throwable) {}
        }
    }

    fun addWatcher(w: Watcher) {
        val adapter = object : StateWatcher {
            override fun onDeviceState(dev: BluetoothDevice, state: Int) = w.onDeviceState(dev, state)
        }
        synchronized(watchers) {
            externalAdapters[w] = adapter
            watchers.add(adapter)
        }
    }

    fun removeWatcher(w: Watcher) {
        synchronized(watchers) {
            val adapter = externalAdapters.remove(w) ?: return
            watchers.remove(adapter)
        }
    }

    private val externalAdapters = mutableMapOf<Watcher, StateWatcher>()

    /**
     * Disconnect a single bonded device. Completes callback when the broadcast reports
     * STATE_DISCONNECTED, or after [timeoutMs]. Used by the reactive OFF_HEAD watchdog
     * so we can undo a single rogue auto-reconnect without touching other devices.
     */
    fun disconnectOne(
        dev: BluetoothDevice,
        timeoutMs: Long = 5000L,
        cb: (success: Boolean) -> Unit,
    ) {
        val cur = getCurrentState(dev)
        if (cur == STATE_DISCONNECTED) {
            log("A2dpSink disconnectOne: ${safeAddr(dev)} already disconnected")
            cb(true); return
        }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = object : StateWatcher {
            override fun onDeviceState(d: BluetoothDevice, state: Int) {
                if (d.address != dev.address) return
                if (state == STATE_DISCONNECTED && finished.compareAndSet(false, true)) {
                    removeWatcher(this)
                    mainHandler.post { cb(true) }
                }
            }
        }
        addWatcher(watcher)
        invokeDisconnect(dev)
        mainHandler.postDelayed({
            if (finished.compareAndSet(false, true)) {
                removeWatcher(watcher)
                log("A2dpSink disconnectOne timeout ${safeAddr(dev)}")
                cb(false)
            }
        }, timeoutMs)
    }

    /**
     * Enumerate bonded devices that currently have a non-disconnected A2DP sink state.
     * Used by AudioRoutingController to sweep any stragglers after transitionOff.
     */
    fun bondedNonDisconnected(): List<BluetoothDevice> {
        val a = adapter ?: return emptyList()
        val bonded: Set<BluetoothDevice> = try { a.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
        return bonded.filter { getCurrentState(it) != STATE_DISCONNECTED }
    }

    /**
     * Return the first device returned by `BluetoothAdapter.getActiveDevices(A2DP_SINK)`,
     * or null if no sink is active. Used to gate our own `setActiveDevice` calls so we
     * don't fight AOSP's `A2dpSinkService.onStartIndCallback` soft-handoff logic when
     * multiple sources are connected and one of them starts streaming.
     */
    fun getActiveSinkDevice(): BluetoothDevice? {
        val a = adapter ?: return null
        return try {
            val m = a.javaClass.getMethod("getActiveDevices", Int::class.javaPrimitiveType)
            @Suppress("UNCHECKED_CAST")
            val list = m.invoke(a, A2DP_SINK_PROFILE_ID) as? List<BluetoothDevice>
            list?.firstOrNull { it != null }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * True iff we should claim `dev` as the active sink: either no sink is active at
     * all (bootstrap), the currently-active sink has gone disconnected (rescue), or the
     * active sink is already `dev` (idempotent re-claim). Otherwise false, meaning let
     * AOSP's soft-handoff decide based on AVDTP stream activity.
     */
    fun shouldClaimActive(dev: BluetoothDevice): Boolean {
        val active = getActiveSinkDevice() ?: return true
        if (active == dev) return true
        val connected = bondedNonDisconnected()
        return !connected.contains(active)
    }

    fun init(onReady: (Boolean) -> Unit) {
        onReadyCb = onReady
        val a = adapter
        if (a == null) {
            log("A2dpSink init: BluetoothAdapter null")
            onReady(false); return
        }
        try {
            val ok = a.getProfileProxy(appCtx, profileListener, A2DP_SINK_PROFILE_ID)
            log("A2dpSink getProfileProxy requested ok=$ok")
            if (!ok) onReady(false)
        } catch (e: Throwable) {
            log("A2dpSink getProfileProxy threw: ${e.message}")
            onReady(false)
        }
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter(ACTION_CONNECTION_STATE_CHANGED)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appCtx.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
                log("A2dpSink receiver registered for $ACTION_CONNECTION_STATE_CHANGED")
            } catch (e: Throwable) {
                log("A2dpSink receiver register failed: ${e.message}")
            }
        }
        if (!audioStateReceiverRegistered) {
            try {
                val filter = IntentFilter(ACTION_AUDIO_STATE_CHANGED)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(audioStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appCtx.registerReceiver(audioStateReceiver, filter)
                }
                audioStateReceiverRegistered = true
                log("A2dpSink audio-state receiver registered")
            } catch (e: Throwable) {
                log("A2dpSink audio-state receiver register failed: ${e.message}")
            }
        }
    }

    fun release() {
        try {
            if (receiverRegistered) {
                appCtx.unregisterReceiver(receiver)
                receiverRegistered = false
            }
        } catch (_: Throwable) {}
        try {
            if (audioStateReceiverRegistered) {
                appCtx.unregisterReceiver(audioStateReceiver)
                audioStateReceiverRegistered = false
            }
        } catch (_: Throwable) {}
        try {
            val p = proxy
            val a = adapter
            if (a != null && p != null) {
                a.closeProfileProxy(A2DP_SINK_PROFILE_ID, p)
            }
        } catch (e: Throwable) {
            log("A2dpSink closeProfileProxy failed: ${e.message}")
        }
        proxy = null
        synchronized(watchers) { watchers.clear() }
        stateMap.clear()
        log("A2dpSink released")
    }

    /**
     * Returns true if any bonded device currently has A2DP_SINK state == STATE_CONNECTED.
     * Used by AudioRoutingController to short-circuit boot-time UNKNOWN -> ON_HEAD transitions
     * when the sink link is already established (e.g. app restart with user still wearing).
     */
    fun anyConnected(): Boolean {
        val a = adapter ?: return false
        val bonded: Set<BluetoothDevice> = try { a.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
        for (dev in bonded) {
            if (getCurrentState(dev) == STATE_CONNECTED) return true
        }
        return false
    }

    /**
     * Returns true iff no bonded device is currently connected (all in STATE_DISCONNECTED).
     * Mirrors anyConnected() for the off-head boot path.
     */
    fun allDisconnected(): Boolean {
        val a = adapter ?: return true
        val bonded: Set<BluetoothDevice> = try { a.bondedDevices ?: emptySet() } catch (_: Throwable) { emptySet() }
        for (dev in bonded) {
            if (getCurrentState(dev) != STATE_DISCONNECTED) return false
        }
        return true
    }

    fun getCurrentState(dev: BluetoothDevice): Int {
        val p = proxy ?: return STATE_DISCONNECTED
        return try {
            val m = p.javaClass.getMethod("getConnectionState", BluetoothDevice::class.java)
            m.isAccessible = true
            (m.invoke(p, dev) as? Int) ?: STATE_DISCONNECTED
        } catch (e: Throwable) {
            log("A2dpSink.getConnectionState failed for ${safeAddr(dev)}: ${e.message}")
            stateMap[dev] ?: STATE_DISCONNECTED
        }
    }

    private fun invokeConnect(dev: BluetoothDevice): Boolean {
        val p = proxy
        if (p != null) {
            try {
                val m = p.javaClass.getMethod("connect", BluetoothDevice::class.java)
                m.isAccessible = true
                val ok = (m.invoke(p, dev) as? Boolean) ?: false
                log("A2dpSink.connect(${safeAddr(dev)}) reflection ok=$ok")
                return ok
            } catch (e: Throwable) {
                log("A2dpSink.connect reflection failed ${safeAddr(dev)}: ${e.message}")
            }
        }
        return shellFallback("connect", dev)
    }

    private fun invokeDisconnect(dev: BluetoothDevice): Boolean {
        val p = proxy
        if (p != null) {
            try {
                val m = p.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                m.isAccessible = true
                val ok = (m.invoke(p, dev) as? Boolean) ?: false
                log("A2dpSink.disconnect(${safeAddr(dev)}) reflection ok=$ok")
                return ok
            } catch (e: Throwable) {
                log("A2dpSink.disconnect reflection failed ${safeAddr(dev)}: ${e.message}")
            }
        }
        return shellFallback("disconnect", dev)
    }

    /**
     * Best-effort activation of the sink as the Android audio active device.
     * Without this, the sink link is CONNECTED but no PCM flows to the local speaker:
     * Android's A2DP SINK profile routes audio only when setActiveDevice(device) is
     * called, which requires hidden API reflection.
     *
     * Tries three paths in order:
     *   1. BluetoothA2dpSink.setActiveDevice(BluetoothDevice)
     *   2. BluetoothAdapter.setActiveDevice(BluetoothDevice, ACTIVE_DEVICE_AUDIO=1)
     *   3. BluetoothA2dpSink.setAudioRouteAllowed(true) -- some AOSP variants require it
     *
     * Each attempt is wrapped; failures are logged but never throw. Returns true if
     * ANY attempt succeeded (best-effort).
     */
    fun setActive(dev: BluetoothDevice): Boolean {
        var anyOk = false
        val p = proxy
        if (p != null) {
            try {
                val m = p.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
                m.isAccessible = true
                val ok = (m.invoke(p, dev) as? Boolean) ?: false
                log("A2dpSink.setActiveDevice(${safeAddr(dev)}) ok=$ok")
                anyOk = anyOk || ok
            } catch (e: Throwable) {
                log("A2dpSink.setActiveDevice reflection failed: ${e.message}")
            }
        }
        val a = adapter
        if (a != null) {
            try {
                val m = a.javaClass.getMethod(
                    "setActiveDevice",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType
                )
                m.isAccessible = true
                // ACTIVE_DEVICE_AUDIO = 1 in hidden BluetoothAdapter constants
                val ok = (m.invoke(a, dev, 1) as? Boolean) ?: false
                log("BluetoothAdapter.setActiveDevice(${safeAddr(dev)},AUDIO=1) ok=$ok")
                anyOk = anyOk || ok
            } catch (e: Throwable) {
                log("BluetoothAdapter.setActiveDevice reflection failed: ${e.message}")
            }
        }
        if (p != null) {
            try {
                val m = p.javaClass.getMethod("setAudioRouteAllowed", Boolean::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(p, true)
                log("A2dpSink.setAudioRouteAllowed(true) invoked")
                anyOk = true
            } catch (_: NoSuchMethodException) {
                // Method not present on this AOSP variant -- that's fine.
            } catch (e: Throwable) {
                log("A2dpSink.setAudioRouteAllowed reflection failed: ${e.message}")
            }
        }
        return anyOk
    }

    /**
     * Retry wrapper around invokeConnect. Some AOSP builds silently fail the first
     * reflection call (proxy not fully ready, internal state race); retrying 1-2x
     * with 1s spacing recovers almost all of those. Returns true if any attempt
     * returned true; logs ERROR on final failure.
     */
    fun connectWithRetry(dev: BluetoothDevice, maxAttempts: Int = 3, spacingMs: Long = 1000L): Boolean {
        for (i in 1..maxAttempts) {
            val ok = invokeConnect(dev)
            if (ok) return true
            if (i < maxAttempts) {
                log("A2dpSink.connect attempt $i failed for ${safeAddr(dev)}; retrying in ${spacingMs}ms")
                try { Thread.sleep(spacingMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
        }
        log("A2dpSink.connect ERROR: all $maxAttempts attempts failed for ${safeAddr(dev)}")
        return false
    }

    private fun shellFallback(verb: String, dev: BluetoothDevice): Boolean {
        return try {
            val cmd = arrayOf(
                "su", "-c",
                "cmd bluetooth_manager ${verb}-profile ${dev.address} $A2DP_SINK_PROFILE_ID"
            )
            val p = Runtime.getRuntime().exec(cmd)
            val rc = p.waitFor()
            log("A2dpSink shell fallback $verb ${safeAddr(dev)} rc=$rc")
            rc == 0
        } catch (e: Throwable) {
            log("A2dpSink shell fallback threw: ${e.message}")
            false
        }
    }

    fun connectAllPairedSources(
        timeoutMs: Long = 5000L,
        cb: (success: Boolean, connectedCount: Int) -> Unit,
    ) {
        val a = adapter
        if (a == null) { cb(false, 0); return }
        val bonded: Set<BluetoothDevice> = try { a.bondedDevices ?: emptySet() } catch (e: Throwable) {
            log("bondedDevices threw: ${e.message}"); emptySet()
        }
        if (bonded.isEmpty()) {
            log("A2dpSink connectAll: no bonded devices")
            cb(true, 0); return
        }

        // Snapshot targets; pre-count already-connected devices.
        val remaining = ConcurrentHashMap<String, BluetoothDevice>()
        var alreadyConnected = 0
        for (dev in bonded) {
            val cur = getCurrentState(dev)
            if (cur == STATE_CONNECTED) {
                alreadyConnected++
            } else {
                remaining[dev.address] = dev
            }
        }
        if (remaining.isEmpty()) {
            log("A2dpSink connectAll: all ${bonded.size} bonded already connected")
            cb(true, alreadyConnected); return
        }

        val connectedCount = java.util.concurrent.atomic.AtomicInteger(alreadyConnected)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val watcher = object : StateWatcher {
            override fun onDeviceState(dev: BluetoothDevice, state: Int) {
                val addr = dev.address ?: return
                if (!remaining.containsKey(addr)) return
                when (state) {
                    STATE_CONNECTED -> {
                        connectedCount.incrementAndGet()
                        remaining.remove(addr)
                        // Only claim active on bootstrap / rescue; otherwise let
                        // AOSP's A2dpSinkService.onStartIndCallback soft-handoff
                        // pick the streaming source when multiple are connected.
                        if (shouldClaimActive(dev)) {
                            try { setActive(dev) } catch (e: Throwable) {
                                log("setActive after connect threw for ${safeAddr(dev)}: ${e.message}")
                            }
                        } else {
                            log("skip setActive ${safeAddr(dev)}: active=${safeAddr(getActiveSinkDevice()!!)}; letting AOSP handoff decide")
                        }
                    }
                    STATE_DISCONNECTED -> {
                        // Connect attempt failed; drop from pending.
                        remaining.remove(addr)
                    }
                }
                if (remaining.isEmpty() && finished.compareAndSet(false, true)) {
                    removeWatcher(this)
                    mainHandler.post { cb(true, connectedCount.get()) }
                }
            }
        }
        addWatcher(watcher)

        // Kick off connects (with retry) and proactively mark already-connected devices
        // active, but only if nothing is active yet (or the active dev is stale).
        for (dev in bonded) {
            if (getCurrentState(dev) == STATE_CONNECTED && shouldClaimActive(dev)) {
                try { setActive(dev) } catch (e: Throwable) {
                    log("setActive on already-connected ${safeAddr(dev)} threw: ${e.message}")
                }
            }
        }
        for (dev in remaining.values.toList()) {
            connectWithRetry(dev)
        }

        // Timeout
        mainHandler.postDelayed({
            if (finished.compareAndSet(false, true)) {
                removeWatcher(watcher)
                val n = connectedCount.get()
                log("A2dpSink connectAll timeout connected=$n pending=${remaining.size}")
                cb(n > 0, n)
            }
        }, timeoutMs)
    }

    fun disconnectAllSources(
        timeoutMs: Long = 5000L,
        cb: (success: Boolean) -> Unit,
    ) {
        val a = adapter
        if (a == null) { cb(false); return }
        val bonded: Set<BluetoothDevice> = try { a.bondedDevices ?: emptySet() } catch (e: Throwable) {
            log("bondedDevices threw: ${e.message}"); emptySet()
        }
        if (bonded.isEmpty()) {
            log("A2dpSink disconnectAll: no bonded devices")
            cb(true); return
        }

        val remaining = ConcurrentHashMap<String, BluetoothDevice>()
        for (dev in bonded) {
            val cur = getCurrentState(dev)
            if (cur != STATE_DISCONNECTED) {
                remaining[dev.address] = dev
            }
        }
        if (remaining.isEmpty()) {
            log("A2dpSink disconnectAll: all already disconnected")
            cb(true); return
        }

        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = object : StateWatcher {
            override fun onDeviceState(dev: BluetoothDevice, state: Int) {
                val addr = dev.address ?: return
                if (!remaining.containsKey(addr)) return
                if (state == STATE_DISCONNECTED) {
                    remaining.remove(addr)
                }
                if (remaining.isEmpty() && finished.compareAndSet(false, true)) {
                    removeWatcher(this)
                    mainHandler.post { cb(true) }
                }
            }
        }
        addWatcher(watcher)

        for (dev in remaining.values.toList()) {
            invokeDisconnect(dev)
        }

        mainHandler.postDelayed({
            if (finished.compareAndSet(false, true)) {
                removeWatcher(watcher)
                val leftovers = remaining.size
                log("A2dpSink disconnectAll timeout pending=$leftovers")
                cb(leftovers == 0)
            }
        }, timeoutMs)
    }

    private fun safeAddr(dev: BluetoothDevice?): String =
        try { dev?.address ?: "null" } catch (_: Throwable) { "?" }
}
