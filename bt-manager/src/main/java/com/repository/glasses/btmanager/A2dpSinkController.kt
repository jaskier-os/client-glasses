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
