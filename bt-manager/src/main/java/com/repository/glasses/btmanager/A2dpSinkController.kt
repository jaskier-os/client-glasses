package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the hidden BluetoothA2dpSink profile (id 11) for the glasses HF role.
 *
 * Provides:
 *  - getProfileProxy lifecycle.
 *  - connect(device) via reflection, with setConnectionPolicy(ALLOWED) preflight.
 *  - isConnected(addr) check.
 *  - Broadcast receivers for CONNECTION_STATE_CHANGED and AUDIO_STATE_CHANGED
 *    to track profile lifecycle transitions.
 *
 * Audio routing / sink-conn cap lifting are handled elsewhere; this class is
 * only about getting the profile *connected* so audio can flow at all.
 */
@SuppressLint("MissingPermission")
class A2dpSinkController(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    private val appCtx: Context = ctx.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    @Volatile private var proxy: BluetoothProfile? = null
    private var listener: Listener? = null
    @Volatile private var receiverRegistered = false

    companion object {
        private const val TAG = "BtMgr:A2dp"
        private const val A2DP_SINK_PROFILE_ID = 11
        private const val CONNECTION_POLICY_ALLOWED = 100
        private const val PRIORITY_AUTO_CONNECT = 1000

        /** Safety net cadence for re-dropping a suppressed link that came back. */
        private const val SUPPRESS_WATCHDOG_MS = 3_000L

        /**
         * How long a suppression lease stays valid without renewal.
         *
         * 5s with the holder renewing every 2s: two consecutive missed renewals
         * (binder hiccup, GC pause) still keep the hold, while a dead holder costs
         * the user at most ~5s + one watchdog tick of silence. MIN/MAX just keep a
         * caller from asking for something absurd in either direction.
         */
        const val DEFAULT_LEASE_MS = 5_000L
        private const val MIN_LEASE_MS = 2_000L
        private const val MAX_LEASE_MS = 60_000L

        /** Bounded post-release reconnect: attempts spread over ~10s. */
        private const val RECONNECT_RETRY_MS = 2_000L
        private const val RECONNECT_MAX_ATTEMPTS = 6

        // Hidden BluetoothA2dpSink intent actions (AOSP)
        private const val ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.AUDIO_STATE_CHANGED"

        // BluetoothA2dpSink audio states (AOSP)
        const val AUDIO_STATE_PLAYING = 10
        const val AUDIO_STATE_NOT_PLAYING = 11

        fun profileStateLabel(state: Int): String = when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED($state)"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING($state)"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED($state)"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING($state)"
            else -> "UNKNOWN($state)"
        }

        fun audioStateLabel(state: Int): String = when (state) {
            AUDIO_STATE_PLAYING -> "PLAYING($state)"
            AUDIO_STATE_NOT_PLAYING -> "NOT_PLAYING($state)"
            else -> "UNKNOWN($state)"
        }
    }

    interface Listener {
        fun onA2dpConnectionChanged(deviceAddress: String, prevState: Int, newState: Int)
        fun onA2dpAudioStateChanged(deviceAddress: String, state: Int)
    }

    @Volatile var lastConnectionState: Int = BluetoothProfile.STATE_DISCONNECTED
        private set
    @Volatile var lastAudioState: Int = AUDIO_STATE_NOT_PLAYING
        private set
    @Volatile var lastDeviceAddress: String = ""
        private set

    // Per-device state tracking for cross-device transition logging
    private val deviceConnectionStates = ConcurrentHashMap<String, Int>()
    private val deviceAudioStates = ConcurrentHashMap<String, Int>()

    // Timestamp (elapsedRealtime) of our last outbound connect() per device.
    // Lets callers distinguish a self-initiated ACL (raised by our own connect
    // attempt) from a genuine user-initiated connection.
    private val lastConnectAttempt = ConcurrentHashMap<String, Long>()

    // ---- Suppression leases ----
    //
    // While an AR stream session is open the phone plays the glasses' own uplink mic
    // on STREAM_MUSIC, and because these glasses are the phone's A2DP sink Android
    // routes it straight back to our speaker -- a real acoustic loop. So the link to
    // that ONE address must be held down for the session.
    //
    // Enforcement is CONSTANT, not one-shot: Android's own profile-reconnect logic
    // and our ProfileAutoConnector both re-establish A2DP within seconds, so a single
    // disconnect() loses the race. Three enforcement points:
    //   1. connect()/connectExclusive() refuse outright (blocks our own sweeps).
    //   2. CONNECTION_STATE_CHANGED to CONNECTING/CONNECTED re-disconnects
    //      (catches remote-initiated reconnects).
    //   3. The watchdog re-disconnects anything that slipped past both.
    //
    // The hold is a LEASE keyed by an opaque token, never a boolean flag. A flag can
    // only be cleared by code that runs; a client that is force-stopped, kill -9'd, or
    // that crashes runs no cleanup at all -- which is exactly how a hold survived five
    // minutes and two sessions in production. A lease needs positive action to STAY
    // held, so every failure mode ends in release. Leases are token-keyed (a multiset,
    // not a set) so overlapping holders cannot release each other's hold, and the map
    // is in-memory only, so a bt-manager restart or a reboot starts fully unsuppressed.
    private class Lease(
        val addr: String,
        val leaseMs: Long,
        @Volatile var expiresAt: Long,
        val owner: android.os.IBinder?,
        val deathRecipient: android.os.IBinder.DeathRecipient?,
    )

    private val leases = ConcurrentHashMap<String, Lease>()
    private val suppressHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Bumped on every change to [leases]. The watchdog captures it at entry and
     * re-checks before acting: `removeCallbacks` cannot stop a runnable that has
     * already started, so without this a tick already in flight when the last lease
     * is released would disconnect the link we just restored.
     */
    private val suppressGeneration = java.util.concurrent.atomic.AtomicLong(0)

    private val suppressWatchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            for ((token, lease) in leases) {
                if (now >= lease.expiresAt) {
                    // Not renewed: the holder is gone (killed, crashed, wedged, or
                    // simply never tore down). This is the path that makes a leak
                    // impossible, so it is logged loudly.
                    log("event=a2dp.suppress.expired token=$token addr=${lease.addr} overdue_ms=${now - lease.expiresAt}")
                    dropLease(token, "lease_expired")
                }
            }
            for (addr in suppressedAddresses()) {
                if (isConnected(addr)) {
                    log("event=a2dp.suppress.rearm reason=watchdog addr=$addr")
                    disconnect(addr)
                }
            }
            if (leases.isNotEmpty()) suppressHandler.postDelayed(this, SUPPRESS_WATCHDOG_MS)
        }
    }

    private fun suppressedAddresses(): Set<String> =
        leases.values.mapTo(HashSet()) { it.addr }

    /**
     * Take a suppression lease on [deviceAddress], valid for [leaseMs] and renewable.
     *
     * [owner] is a binder belonging to the caller. Its death releases the lease
     * immediately, which is the fast path; the lease expiry is the backstop that also
     * covers a caller that is alive but wedged. Returns the token, or null if rejected.
     */
    fun acquire(deviceAddress: String, leaseMs: Long, owner: android.os.IBinder?): String? {
        if (deviceAddress.isBlank()) {
            log("event=a2dp.suppress.reject reason=blank_addr")
            return null
        }
        val effLeaseMs = leaseMs.coerceIn(MIN_LEASE_MS, MAX_LEASE_MS)
        val token = "s${suppressGeneration.incrementAndGet()}-${SystemClock.elapsedRealtime()}"
        var recipient: android.os.IBinder.DeathRecipient? = null
        if (owner != null) {
            recipient = android.os.IBinder.DeathRecipient {
                log("event=a2dp.suppress.owner_died token=$token addr=$deviceAddress")
                suppressHandler.post { dropLease(token, "owner_died") }
            }
            try {
                owner.linkToDeath(recipient, 0)
            } catch (e: Throwable) {
                // Already dead: refuse rather than take a hold nothing will release.
                log("event=a2dp.suppress.reject reason=owner_dead addr=$deviceAddress err=${e.message}")
                return null
            }
        }
        val wasSuppressed = isSuppressed(deviceAddress)
        leases[token] = Lease(
            deviceAddress, effLeaseMs, SystemClock.elapsedRealtime() + effLeaseMs, owner, recipient
        )
        suppressGeneration.incrementAndGet()
        log("event=a2dp.suppress.on token=$token addr=$deviceAddress new=${!wasSuppressed} connected=${isConnected(deviceAddress)} lease_ms=$effLeaseMs holders=${leases.count { it.value.addr == deviceAddress }}")
        cancelReconnect(deviceAddress)
        disconnect(deviceAddress)
        suppressHandler.removeCallbacks(suppressWatchdog)
        suppressHandler.postDelayed(suppressWatchdog, SUPPRESS_WATCHDOG_MS)
        return token
    }

    /** Extend a lease. Unknown/expired tokens are ignored (the hold is already gone). */
    fun renew(token: String?) {
        val lease = leases[token ?: return] ?: run {
            log("event=a2dp.suppress.renew.unknown token=$token")
            return
        }
        lease.expiresAt = SystemClock.elapsedRealtime() + lease.leaseMs
    }

    /** Explicit release. Idempotent; the fast path when teardown does run normally. */
    fun releaseLease(token: String?) {
        dropLease(token ?: return, "released")
    }

    private fun dropLease(token: String, reason: String) {
        val lease = leases.remove(token) ?: return
        suppressGeneration.incrementAndGet()
        try {
            if (lease.owner != null && lease.deathRecipient != null) {
                lease.owner.unlinkToDeath(lease.deathRecipient, 0)
            }
        } catch (_: Throwable) {}
        val stillHeld = isSuppressed(lease.addr)
        log("event=a2dp.suppress.off token=$token addr=${lease.addr} reason=$reason still_held=$stillHeld")
        if (leases.isEmpty()) suppressHandler.removeCallbacks(suppressWatchdog)
        // Only the LAST holder of an address restores it. With a plain set, an
        // overlapping second session's release would have un-suppressed the address
        // out from under the first one.
        if (!stillHeld) scheduleReconnect(lease.addr, reason)
    }

    // ---- Post-release reconnect ----
    //
    // Clearing the flag only stops the refusals; nothing brings the link back.
    // ProfileAutoConnector's sweep eventually would, but its per-device connect
    // cooldown is 20s and the device may be marked stale, so the user can sit
    // without BT audio for minutes. Drive it explicitly, with bounded retries
    // until the profile actually reports CONNECTED.

    private val reconnectRunnables = ConcurrentHashMap<String, Runnable>()

    private fun cancelReconnect(addr: String) {
        reconnectRunnables.remove(addr)?.let { suppressHandler.removeCallbacks(it) }
    }

    fun scheduleReconnect(deviceAddress: String, reason: String) {
        cancelReconnect(deviceAddress)
        var attempt = 0
        val r = object : Runnable {
            override fun run() {
                if (isSuppressed(deviceAddress)) {
                    log("event=a2dp.restore.abort reason=resuppressed addr=$deviceAddress")
                    reconnectRunnables.remove(deviceAddress)
                    return
                }
                if (isConnected(deviceAddress)) {
                    log("event=a2dp.restore.ok addr=$deviceAddress reason=$reason attempts=$attempt")
                    reconnectRunnables.remove(deviceAddress)
                    return
                }
                attempt++
                if (attempt > RECONNECT_MAX_ATTEMPTS) {
                    log("event=a2dp.restore.giveup addr=$deviceAddress reason=$reason attempts=${attempt - 1}")
                    reconnectRunnables.remove(deviceAddress)
                    return
                }
                // connectExclusive, not connect: the sink slot is last-writer-wins and
                // another source may have taken it while we held the link down. The
                // user's expectation after a session is that their phone is back.
                val ok = connectExclusive(deviceAddress)
                log("event=a2dp.restore.attempt addr=$deviceAddress reason=$reason attempt=$attempt result=$ok")
                suppressHandler.postDelayed(this, RECONNECT_RETRY_MS)
            }
        }
        reconnectRunnables[deviceAddress] = r
        log("event=a2dp.restore.start addr=$deviceAddress reason=$reason")
        suppressHandler.post(r)
    }

    fun isSuppressed(deviceAddress: String): Boolean =
        leases.values.any { it.addr.equals(deviceAddress, true) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONNECTION_STATE_CHANGED -> handleConnectionStateChanged(intent)
                ACTION_AUDIO_STATE_CHANGED -> handleAudioStateChanged(intent)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
            if (profile == A2DP_SINK_PROFILE_ID) {
                proxy = p
                log("event=a2dp.proxy.connected proxy=${p != null}")
                if (p != null) seedConnectionStateFromProxy()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == A2DP_SINK_PROFILE_ID) {
                proxy = null
                log("event=a2dp.proxy.disconnected")
            }
        }
    }

    fun init(listener: Listener? = null) {
        this.listener = listener
        val a = adapter ?: run { log("event=a2dp.init.fail reason=adapter_null"); return }
        try {
            val ok = a.getProfileProxy(appCtx, profileListener, A2DP_SINK_PROFILE_ID)
            log("event=a2dp.init ok=$ok")
        } catch (e: Throwable) {
            log("event=a2dp.init.error err=${e.message}")
        }
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(ACTION_CONNECTION_STATE_CHANGED)
                    addAction(ACTION_AUDIO_STATE_CHANGED)
                }
                // RECEIVER_EXPORTED: these are system broadcasts from com.android.bluetooth,
                // a different UID. RECEIVER_NOT_EXPORTED would silently drop them on API 33+.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appCtx.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
                log("event=a2dp.receivers.registered")
            } catch (e: Throwable) {
                log("event=a2dp.receivers.register.fail err=${e.message}")
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
            val p = proxy; val a = adapter
            if (a != null && p != null) a.closeProfileProxy(A2DP_SINK_PROFILE_ID, p)
        } catch (_: Throwable) {}
        proxy = null
        listener = null
        suppressHandler.removeCallbacks(suppressWatchdog)
        for (r in reconnectRunnables.values) suppressHandler.removeCallbacks(r)
        reconnectRunnables.clear()
        for (t in leases.keys.toList()) dropLease(t, "controller_released")
        leases.clear()
        deviceConnectionStates.clear()
        deviceAudioStates.clear()
        lastConnectAttempt.clear()
        log("event=a2dp.released")
    }

    fun isConnected(deviceAddress: String): Boolean {
        return connectedDevices().any { it.address.equals(deviceAddress, true) }
    }

    /**
     * Milliseconds since we last called connect() on [deviceAddress], or
     * Long.MAX_VALUE if we never did. Used to tell whether an ACL_CONNECTED
     * was raised by our own connect attempt vs a user-initiated connection.
     */
    fun msSinceConnectAttempt(deviceAddress: String): Long {
        val t = lastConnectAttempt[deviceAddress] ?: return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - t
    }

    fun connectedDevices(): List<BluetoothDevice> {
        val p = proxy ?: return emptyList()
        return try {
            val m = p.javaClass.getMethod("getConnectedDevices")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (m.invoke(p) as? List<BluetoothDevice>) ?: emptyList()
        } catch (e: Throwable) {
            log("event=a2dp.getConnectedDevices.fail err=${e.message}")
            emptyList()
        }
    }

    fun disconnect(deviceAddress: String): Boolean = GT.section("bt.a2dp.disconnect") {
        val p = proxy ?: return@section false
        val dev = resolveDevice(deviceAddress) ?: return@section false
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "disconnect" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java
            } ?: run {
                log("event=a2dp.disconnect.fail reason=no_method addr=$deviceAddress")
                return@section false
            }
            m.isAccessible = true
            val ok = (m.invoke(p, dev) as? Boolean) ?: false
            log("event=a2dp.disconnect addr=${dev.address} name=${dev.name} result=$ok")
            ok
        } catch (e: Throwable) {
            log("event=a2dp.disconnect.error addr=$deviceAddress err=${e.message}")
            false
        }
    }

    /**
     * Last-writer-wins A2DP Sink slot. Drops every other currently-connected
     * source so the slot is free, then requests connection from [deviceAddress].
     * Idempotent: if the target is already the only connected device, no-op.
     */
    fun connectExclusive(deviceAddress: String): Boolean = GT.section("bt.a2dp.connectExclusive") {
        if (isSuppressed(deviceAddress)) {
            log("event=a2dp.connectExclusive.refused reason=suppressed addr=$deviceAddress")
            return@section false
        }
        val current = connectedDevices()
        val alreadyOnly = current.size == 1 &&
            current.first().address.equals(deviceAddress, true)
        if (alreadyOnly) {
            log("event=a2dp.connectExclusive.noop addr=$deviceAddress already_only=true")
            return@section true
        }
        log("event=a2dp.connectExclusive addr=$deviceAddress current_count=${current.size} current=${current.joinToString { "${it.address}(${it.name})" }}")
        for (d in current) {
            if (!d.address.equals(deviceAddress, true)) {
                log("event=a2dp.connectExclusive.kick addr=${d.address} name=${d.name} target=$deviceAddress")
                disconnect(d.address)
            }
        }
        connect(deviceAddress)
    }

    fun connect(deviceAddress: String): Boolean = GT.section("bt.a2dp.connect") {
        if (isSuppressed(deviceAddress)) {
            log("event=a2dp.connect.refused reason=suppressed addr=$deviceAddress")
            return@section false
        }
        lastConnectAttempt[deviceAddress] = SystemClock.elapsedRealtime()
        val p = proxy ?: run {
            log("event=a2dp.connect.fail reason=proxy_null addr=$deviceAddress")
            return@section false
        }
        val dev = resolveDevice(deviceAddress) ?: return@section false
        setConnectionPolicyAllowed(dev)
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "connect" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java
            }
            if (m == null) {
                log("event=a2dp.connect.fail reason=no_method addr=$deviceAddress")
                return@section false
            }
            m.isAccessible = true
            val ok = (m.invoke(p, dev) as? Boolean) ?: false
            log("event=a2dp.connect addr=${dev.address} name=${dev.name} result=$ok")
            ok
        } catch (e: Throwable) {
            log("event=a2dp.connect.error addr=$deviceAddress err=${e.message}")
            false
        }
    }

    // ---- Broadcast handlers ----

    private fun handleConnectionStateChanged(intent: Intent) {
        val dev: BluetoothDevice? = try {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (_: Throwable) { null }
        val addr = dev?.address ?: ""
        val prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        if (newState < 0) {
            log("event=a2dp.CONNECTION_STATE_CHANGED.invalid no_state_extra")
            return
        }
        // Per-device state tracking + anomaly detection
        if (addr.isNotEmpty()) {
            val tracked = deviceConnectionStates[addr] ?: BluetoothProfile.STATE_DISCONNECTED
            if (prevState >= 0 && tracked != prevState) {
                Log.w(TAG, "event=a2dp.state_mismatch addr=$addr name=${dev?.name} tracked=${profileStateLabel(tracked)} broadcast_prev=${profileStateLabel(prevState)}")
            }
            if (prevState == newState) {
                Log.w(TAG, "event=a2dp.state_noop addr=$addr name=${dev?.name} state=${profileStateLabel(newState)}")
            }
            deviceConnectionStates[addr] = newState
        }
        // Cross-device context: show other connected devices
        val otherDevices = deviceConnectionStates.entries
            .filter { !it.key.equals(addr, true) }
            .joinToString { "${it.key}=${profileStateLabel(it.value)}" }
        lastConnectionState = newState
        if (addr.isNotEmpty()) lastDeviceAddress = addr
        log("event=a2dp.CONNECTION_STATE_CHANGED addr=$addr name=${dev?.name} prev=${profileStateLabel(prevState)} new=${profileStateLabel(newState)}${if (otherDevices.isNotEmpty()) " other_devices=[$otherDevices]" else ""}")
        // Primary enforcement for suppression: the remote (or Android's own
        // profile-reconnect) can raise the link at any time. Drop it the moment
        // the state machine reports it coming up, without waiting for the watchdog.
        if (addr.isNotEmpty() && isSuppressed(addr) &&
            (newState == BluetoothProfile.STATE_CONNECTED ||
                newState == BluetoothProfile.STATE_CONNECTING)
        ) {
            log("event=a2dp.suppress.rearm reason=state_changed addr=$addr state=${profileStateLabel(newState)}")
            disconnect(addr)
        }
        try {
            listener?.onA2dpConnectionChanged(addr, prevState, newState)
        } catch (e: Throwable) {
            log("event=a2dp.CONNECTION_STATE_CHANGED.callback.fail err=${e.message}")
        }
    }

    private fun handleAudioStateChanged(intent: Intent) {
        val dev: BluetoothDevice? = try {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (_: Throwable) { null }
        val addr = dev?.address ?: lastDeviceAddress
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        if (state < 0) {
            log("event=a2dp.AUDIO_STATE_CHANGED.invalid no_state_extra")
            return
        }
        val prevAudio = if (addr.isNotEmpty()) {
            val prev = deviceAudioStates[addr] ?: AUDIO_STATE_NOT_PLAYING
            deviceAudioStates[addr] = state
            prev
        } else lastAudioState
        lastAudioState = state
        if (addr.isNotEmpty()) lastDeviceAddress = addr
        log("event=a2dp.AUDIO_STATE_CHANGED addr=$addr name=${dev?.name} prev=${audioStateLabel(prevAudio)} new=${audioStateLabel(state)} conn=${profileStateLabel(deviceConnectionStates[addr] ?: -1)}")
        try {
            listener?.onA2dpAudioStateChanged(addr, state)
        } catch (e: Throwable) {
            log("event=a2dp.AUDIO_STATE_CHANGED.callback.fail err=${e.message}")
        }
    }

    /**
     * On proxy connect, check if any device is already connected and log it.
     * Synthesizes the initial state so we're not blind to connections that
     * happened before our receiver was registered.
     */
    private fun seedConnectionStateFromProxy() {
        val devs = connectedDevices()
        log("event=a2dp.seed connected_count=${devs.size} devices=${devs.joinToString { "${it.address}(${it.name})" }}")
        for (d in devs) {
            deviceConnectionStates[d.address] = BluetoothProfile.STATE_CONNECTED
        }
        if (devs.isNotEmpty()) {
            lastConnectionState = BluetoothProfile.STATE_CONNECTED
            lastDeviceAddress = devs.first().address
        }
    }

    // ---- Helpers ----

    private fun setConnectionPolicyAllowed(dev: BluetoothDevice) {
        val p = proxy ?: return
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "setConnectionPolicy" && it.parameterTypes.size == 2
            }
            if (m != null) {
                m.isAccessible = true
                val ok = m.invoke(p, dev, CONNECTION_POLICY_ALLOWED)
                log("event=a2dp.setConnectionPolicy addr=${dev.address} policy=ALLOWED result=$ok")
                return
            }
            val mPri = p.javaClass.methods.firstOrNull {
                it.name == "setPriority" && it.parameterTypes.size == 2
            }
            if (mPri != null) {
                mPri.isAccessible = true
                val ok = mPri.invoke(p, dev, PRIORITY_AUTO_CONNECT)
                log("event=a2dp.setPriority addr=${dev.address} priority=AUTO_CONNECT result=$ok")
            }
        } catch (e: Throwable) {
            log("event=a2dp.setConnectionPolicy.error addr=${dev.address} err=${e.message}")
        }
    }

    private fun resolveDevice(addr: String): BluetoothDevice? {
        val a = adapter ?: return null
        if (addr.isBlank()) return null
        return try { a.getRemoteDevice(addr) } catch (_: Throwable) { null }
    }
}
