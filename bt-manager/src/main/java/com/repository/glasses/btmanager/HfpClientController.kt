package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Controls the hidden BluetoothHeadsetClient profile via reflection.
 *
 * Mirrors the A2dpSinkController reflection pattern. Profile id for HEADSET_CLIENT is 16.
 *
 * Responsibilities:
 *  - Open profile proxy on init.
 *  - Track AG_CALL_CHANGED / AUDIO_STATE_CHANGED / CONNECTION_STATE_CHANGED broadcasts.
 *  - Cache the raw BluetoothHeadsetClientCall Parcelable per call id so terminate() can
 *    pass it back without Parcelable round-trips across APK classloaders.
 *  - Flatten callback data to primitives for the AIDL boundary.
 */
@SuppressLint("MissingPermission")
class HfpClientController(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    private val appCtx: Context = ctx.applicationContext

    companion object {
        private const val TAG = "HfpClientCtl"

        // Hidden constants
        private const val HEADSET_CLIENT_PROFILE_ID = 16

        private const val ACTION_AG_CALL_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
        private const val ACTION_AUDIO_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
        private const val ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"

        private const val EXTRA_CALL = "android.bluetooth.headsetclient.extra.CALL"
        private const val EXTRA_STATE = BluetoothProfile.EXTRA_STATE
        private const val EXTRA_DEVICE = BluetoothDevice.EXTRA_DEVICE

        // Call states (AOSP BluetoothHeadsetClientCall)
        const val CALL_STATE_ACTIVE = 0
        const val CALL_STATE_HELD = 1
        const val CALL_STATE_DIALING = 2
        const val CALL_STATE_ALERTING = 3
        const val CALL_STATE_INCOMING = 4
        const val CALL_STATE_WAITING = 5
        const val CALL_STATE_HELD_BY_RESPONSE_AND_HOLD = 6
        const val CALL_STATE_TERMINATED = 7

        // Audio states. AOSP BluetoothHeadsetClient (HF profile) defines:
        //   STATE_AUDIO_DISCONNECTED = 0
        //   STATE_AUDIO_CONNECTING   = 1
        //   STATE_AUDIO_CONNECTED    = 2
        // (Note: BluetoothProfile.STATE_* uses CONNECTED=2 too, but the
        // BluetoothHeadset / HF audio-state extras share these specific values.)
        /**
         * Returned by [scoActiveAddress] when the SCO owner could not be
         * determined. Distinct from "" (= determined, nobody has SCO) so callers
         * can fail closed instead of mistaking ignorance for an idle link.
         */
        const val SCO_STATE_UNKNOWN = "?"

        /**
         * How long after being evicted a device is assumed to be auto-retrying
         * rather than being chosen. Comfortably longer than the 1-5s an iPhone
         * or BlueZ takes to re-initiate, short enough that a real switch back a
         * few seconds later still works. A deliberate handoff bypasses it
         * entirely via claimHfpSlot().
         */
        private const val EVICTION_BACKOFF_MS = 10_000L
        const val AUDIO_STATE_DISCONNECTED = 0
        const val AUDIO_STATE_CONNECTING = 1
        const val AUDIO_STATE_CONNECTED = 2

        // BluetoothProfile.CONNECTION_POLICY_ALLOWED (Android 12+)
        private const val CONNECTION_POLICY_ALLOWED = 100
        // BluetoothProfile.CONNECTION_POLICY_FORBIDDEN -- blocks both our own
        // reconnect and any AG-initiated (phone) reconnect while set.
        private const val CONNECTION_POLICY_FORBIDDEN = 0
        // Pre-12 fallback: BluetoothProfile.PRIORITY_AUTO_CONNECT.
        private const val PRIORITY_AUTO_CONNECT = 1000
        // Pre-12 fallback: BluetoothProfile.PRIORITY_OFF.
        private const val PRIORITY_OFF = 0

        fun connectionStateLabel(state: Int): String = when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED($state)"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING($state)"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED($state)"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING($state)"
            else -> "UNKNOWN($state)"
        }

        fun hfpAudioStateLabel(state: Int): String = when (state) {
            AUDIO_STATE_DISCONNECTED -> "DISCONNECTED($state)"
            AUDIO_STATE_CONNECTING -> "CONNECTING($state)"
            AUDIO_STATE_CONNECTED -> "CONNECTED($state)"
            else -> "UNKNOWN($state)"
        }

        fun callStateLabel(state: Int): String = when (state) {
            CALL_STATE_ACTIVE -> "ACTIVE($state)"
            CALL_STATE_HELD -> "HELD($state)"
            CALL_STATE_DIALING -> "DIALING($state)"
            CALL_STATE_ALERTING -> "ALERTING($state)"
            CALL_STATE_INCOMING -> "INCOMING($state)"
            CALL_STATE_WAITING -> "WAITING($state)"
            CALL_STATE_HELD_BY_RESPONSE_AND_HOLD -> "HELD_BY_RAH($state)"
            CALL_STATE_TERMINATED -> "TERMINATED($state)"
            else -> "UNKNOWN($state)"
        }
    }

    interface Listener {
        fun onCallStateChanged(
            deviceAddress: String,
            callId: Int,
            uuid: String,
            state: Int,
            number: String,
            name: String,
            multiParty: Boolean,
            outgoing: Boolean,
        )
        fun onCallAudioStateChanged(deviceAddress: String, audioState: Int)
        fun onHfpConnectionChanged(deviceAddress: String, state: Int)
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var proxy: BluetoothProfile? = null
    private var listener: Listener? = null
    @Volatile private var receiverRegistered = false
    private var methodsLogged = false

    // Cached raw BluetoothHeadsetClientCall Parcelables keyed by call id,
    // needed because terminateCall takes that object by reference.
    private val callCache = ConcurrentHashMap<Int, Parcelable>()

    // Last device+audioState observed (for snapshotJson).
    @Volatile private var lastAudioState: Int = AUDIO_STATE_DISCONNECTED
    @Volatile private var lastHfpState: Int = BluetoothProfile.STATE_DISCONNECTED
    @Volatile private var lastDeviceAddress: String = ""

    // Remembered mute state per device so we can re-assert it if SCO renegotiates
    // mid-call (HFP can drop+restore SCO and the AG side resets gain on bring-up).
    private val muteByAddr = ConcurrentHashMap<String, Boolean>()

    // Per-device state tracking for cross-device transition logging
    private val deviceHfpStates = ConcurrentHashMap<String, Int>()
    private val deviceAudioStates = ConcurrentHashMap<String, Int>()

    // When each device most recently reached HFP CONNECTED. Picks the winner of
    // the single HFP slot: last holder wins.
    private val lastConnectedMs = ConcurrentHashMap<String, Long>()

    // When each device was last evicted from the HFP slot, to tell an AG's
    // automatic retry apart from a genuine user handoff.
    private val evictedAtMs = ConcurrentHashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_AG_CALL_CHANGED -> handleCallChanged(intent)
                ACTION_AUDIO_STATE_CHANGED -> handleAudioStateChanged(intent)
                ACTION_CONNECTION_STATE_CHANGED -> handleConnectionStateChanged(intent)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
            if (profile == HEADSET_CLIENT_PROFILE_ID) {
                proxy = p
                log("HfpClient proxy connected profile=$profile proxy=${p != null}")
                if (p != null && !methodsLogged) {
                    methodsLogged = true
                    dumpProxyMethods(p)
                }
                // SCO may already be up before our broadcast receiver registered
                // (e.g. PC already streaming HFP audio when our app started).
                // Synthesize an onCallAudioStateChanged for any device whose
                // proxy reports AudioOn so CallController + UI sync up.
                if (p != null) seedAudioStateFromProxy()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == HEADSET_CLIENT_PROFILE_ID) {
                proxy = null
                log("HfpClient proxy disconnected profile=$profile")
            }
        }
    }

    fun init(listener: Listener) {
        this.listener = listener
        val a = adapter
        if (a == null) {
            log("HfpClient init: BluetoothAdapter null")
            return
        }
        // Clear any stale system-wide mic mute left over from a previous bt-manager
        // run that crashed while muted. muteByAddr starts empty, so without this the
        // next SCO session would silently inherit the orphan mute and the wearer
        // would have no UI state showing why they sound silent.
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (am.isMicrophoneMute) {
                am.isMicrophoneMute = false
                log("HfpClient init: cleared stale AudioManager mic mute")
            }
        } catch (e: Throwable) {
            log("HfpClient init: AudioManager unmute failed: ${e.message}")
        }
        try {
            val ok = a.getProfileProxy(appCtx, profileListener, HEADSET_CLIENT_PROFILE_ID)
            log("HfpClient getProfileProxy requested ok=$ok")
        } catch (e: Throwable) {
            log("HfpClient getProfileProxy threw: ${e.message}")
        }
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(ACTION_AG_CALL_CHANGED)
                    addAction(ACTION_AUDIO_STATE_CHANGED)
                    addAction(ACTION_CONNECTION_STATE_CHANGED)
                }
                // RECEIVER_EXPORTED: these are system broadcasts from com.android.bluetooth,
                // a different UID. RECEIVER_NOT_EXPORTED would silently drop them on API 33+.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appCtx.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
                log("HfpClient receivers registered")
            } catch (e: Throwable) {
                log("HfpClient receiver register failed: ${e.message}")
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
            val p = proxy
            val a = adapter
            if (a != null && p != null) {
                a.closeProfileProxy(HEADSET_CLIENT_PROFILE_ID, p)
            }
        } catch (e: Throwable) {
            log("HfpClient closeProfileProxy failed: ${e.message}")
        }
        proxy = null
        listener = null
        callCache.clear()
        deviceHfpStates.clear()
        deviceAudioStates.clear()
        log("HfpClient released")
    }

    fun isConnected(deviceAddress: String): Boolean {
        val p = proxy ?: return false
        return try {
            val m = p.javaClass.getMethod("getConnectedDevices")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = m.invoke(p) as? List<BluetoothDevice>
            list?.any { it.address.equals(deviceAddress, true) } == true
        } catch (e: Throwable) {
            log("HfpClient.getConnectedDevices failed: ${e.message}")
            false
        }
    }

    /**
     * Elapsed ms since [deviceAddress] most recently reached HFP CONNECTED, or
     * Long.MAX_VALUE if never. Picks the winner of the single HFP slot:
     * last holder wins.
     */
    fun msSinceConnected(deviceAddress: String): Long {
        val at = lastConnectedMs[deviceAddress] ?: return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - at
    }

    /**
     * Drop every HFP-HF holder except [keepAddress].
     *
     * Last-holder-wins: whoever connected most recently owns the slot. Uses
     * disconnectTransient so the evicted device keeps CONNECTION_POLICY_ALLOWED
     * and can take the slot back later by connecting again.
     */
    fun evictOtherHfpHolders(keepAddress: String) {
        val others = connectedDevices().filter { !it.address.equals(keepAddress, true) }
        if (others.isEmpty()) return
        log("event=hfp.evict keep=$keepAddress dropping=${others.joinToString { "${it.address}(${it.name})" }}")
        val now = SystemClock.elapsedRealtime()
        for (d in others) {
            evictedAtMs[d.address] = now
            disconnectTransient(d.address)
        }
    }

    /**
     * Mark [deviceAddress] as the deliberately chosen HFP owner.
     *
     * A user-driven handoff must always win, so it clears any eviction backoff
     * on the winner and starts one on everyone else. Without this the backoff
     * that stops AG auto-retry storms would also block a genuine switch made a
     * few seconds after the last one.
     */
    fun claimHfpSlot(deviceAddress: String) {
        evictedAtMs.remove(deviceAddress)
        lastConnectedMs[deviceAddress] = SystemClock.elapsedRealtime()
    }

    /**
     * True when [deviceAddress] was evicted so recently that its arrival is
     * almost certainly the AG retrying by itself rather than the user choosing
     * it. Both an iPhone and BlueZ re-initiate HFP within seconds of being
     * dropped, and since eviction deliberately leaves the connection policy
     * ALLOWED, nothing else stops them: A evicts B, B's AG reconnects and evicts
     * A, forever. Inside the window the newcomer loses instead of winning, which
     * ends the exchange.
     */
    private fun inEvictionBackoff(deviceAddress: String): Boolean {
        val at = evictedAtMs[deviceAddress] ?: return false
        return SystemClock.elapsedRealtime() - at < EVICTION_BACKOFF_MS
    }

    /**
     * Every device currently connected on HFP-HF.
     */
    fun connectedDevices(): List<BluetoothDevice> {
        val p = proxy ?: return emptyList()
        return try {
            val m = p.javaClass.getMethod("getConnectedDevices")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (m.invoke(p) as? List<BluetoothDevice>).orEmpty()
        } catch (e: Throwable) {
            log("HfpClient.connectedDevices failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Make [deviceAddress] the ONLY device connected on HFP-HF.
     *
     * Fluoride's HF client cannot actually serve two AGs at once. With both an
     * iPhone and a PC connected it fails to resolve the right control block
     * ("bta_hf_client_find_cb_by_rfc_handle: no cb yet") and answers the second
     * AG's SCO request with HCI 0x0f, Connection Rejected due to Unacceptable
     * BD_ADDR -- so call audio silently never arrives on that device even though
     * dumpsys reports it CONNECTED. Keeping the slot single-occupancy is what
     * makes HFP work at all; it is not a policy preference.
     *
     * Losers are dropped with [disconnectTransient] so their connection policy
     * stays ALLOWED and the sweep can hand the slot back later.
     */
    fun connectExclusive(deviceAddress: String): Boolean = GT.section("bt.hfp.connectExclusive") {
        val current = connectedDevices()
        val alreadyOnly = current.size == 1 &&
            current.first().address.equals(deviceAddress, true)
        if (alreadyOnly) return@section true
        log("event=hfp.connectExclusive addr=$deviceAddress current=${current.joinToString { "${it.address}(${it.name})" }.ifEmpty { "none" }}")
        // A deliberate handoff, so this device must win even if we evicted it
        // moments ago.
        claimHfpSlot(deviceAddress)
        for (d in current) {
            if (!d.address.equals(deviceAddress, true)) {
                log("event=hfp.connectExclusive.kick addr=${d.address} name=${d.name} target=$deviceAddress")
                disconnectTransient(d.address)
            }
        }
        connect(deviceAddress)
    }

    /**
     * Drop HFP-HF to [deviceAddress] WITHOUT forbidding the connection policy,
     * so the sweep (or the AG itself) can bring it back later. Used when handing
     * the single HFP slot to another device.
     */
    fun disconnectTransient(deviceAddress: String): Boolean = GT.section("bt.hfp.disconnectTransient") {
        val p = proxy ?: return@section false
        val dev = resolveDevice(deviceAddress) ?: return@section false
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "disconnect" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java
            } ?: return@section false
            m.isAccessible = true
            val ok = (m.invoke(p, dev) as? Boolean) ?: false
            log("event=hfp.disconnectTransient addr=${dev.address} name=${dev.name} result=$ok")
            ok
        } catch (e: Throwable) {
            log("event=hfp.disconnectTransient.error addr=$deviceAddress err=${e.message}")
            false
        }
    }

    fun connect(deviceAddress: String): Boolean = GT.section("bt.hfp.connect") {
        val p = proxy ?: run {
            log("HfpClient.connect: proxy null addr=$deviceAddress")
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
                log("HfpClient.connect: no method addr=$deviceAddress")
                return@section false
            }
            m.isAccessible = true
            val ok = (m.invoke(p, dev) as? Boolean) ?: false
            log("event=hfp.connect addr=${dev.address} name=${dev.name} result=$ok")
            ok
        } catch (e: Throwable) {
            log("HfpClient.connect.error addr=$deviceAddress err=${e.message}")
            false
        }
    }

    /**
     * Tear down HFP-HF to [deviceAddress] and forbid the connection policy so
     * neither our own sweep nor the AG (phone) re-establishes it. Used on fold:
     * a folded device must drop HFP entirely so the BT stack releases its kernel
     * wakelock (hal_bluetooth_lock) and the power daemon can freeze. connect()
     * re-allows the policy on unfold.
     */
    fun disconnect(deviceAddress: String): Boolean = GT.section("bt.hfp.disconnect") {
        val p = proxy ?: run {
            log("HfpClient.disconnect: proxy null addr=$deviceAddress")
            return@section false
        }
        val dev = resolveDevice(deviceAddress) ?: return@section false
        setConnectionPolicyForbidden(dev)
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "disconnect" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java
            }
            if (m == null) {
                log("HfpClient.disconnect: no method addr=$deviceAddress")
                return@section false
            }
            m.isAccessible = true
            val ok = (m.invoke(p, dev) as? Boolean) ?: false
            log("event=hfp.disconnect addr=${dev.address} name=${dev.name} result=$ok")
            ok
        } catch (e: Throwable) {
            log("HfpClient.disconnect.error addr=$deviceAddress err=${e.message}")
            false
        }
    }

    private fun setConnectionPolicyForbidden(dev: BluetoothDevice) {
        val p = proxy ?: return
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "setConnectionPolicy" && it.parameterTypes.size == 2
            }
            if (m != null) {
                m.isAccessible = true
                val ok = m.invoke(p, dev, CONNECTION_POLICY_FORBIDDEN)
                log("HfpClient.setConnectionPolicy(${dev.address}, FORBIDDEN) -> $ok")
                return
            }
            val mPri = p.javaClass.methods.firstOrNull {
                it.name == "setPriority" && it.parameterTypes.size == 2
            }
            if (mPri != null) {
                mPri.isAccessible = true
                val ok = mPri.invoke(p, dev, PRIORITY_OFF)
                log("HfpClient.setPriority(${dev.address}, OFF) -> $ok")
            }
        } catch (e: Throwable) {
            log("HfpClient.setConnectionPolicy(FORBIDDEN) failed: ${e.message}")
        }
    }

    private fun setConnectionPolicyAllowed(dev: BluetoothDevice) {
        val p = proxy ?: return
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "setConnectionPolicy" && it.parameterTypes.size == 2
            }
            if (m != null) {
                m.isAccessible = true
                val ok = m.invoke(p, dev, CONNECTION_POLICY_ALLOWED)
                log("HfpClient.setConnectionPolicy(${dev.address}, ALLOWED) -> $ok")
                return
            }
            val mPri = p.javaClass.methods.firstOrNull {
                it.name == "setPriority" && it.parameterTypes.size == 2
            }
            if (mPri != null) {
                mPri.isAccessible = true
                val ok = mPri.invoke(p, dev, PRIORITY_AUTO_CONNECT)
                log("HfpClient.setPriority(${dev.address}, AUTO_CONNECT) -> $ok")
            }
        } catch (e: Throwable) {
            log("HfpClient.setConnectionPolicy failed: ${e.message}")
        }
    }

    fun primaryHfpDeviceAddress(): String {
        val p = proxy ?: return ""
        return try {
            val m = p.javaClass.getMethod("getConnectedDevices")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = (m.invoke(p) as? List<BluetoothDevice>).orEmpty()
            if (list.isEmpty()) return ""
            // Prefer the device that currently has SCO up. With multiple paired
            // AGs (e.g. phone + PC), getConnectedDevices order is arbitrary --
            // picking the first one will silently mask which device is actually
            // streaming. AUDIO_CONNECTED beats AUDIO_CONNECTING beats DISCONNECTED.
            val ranked = list.maxByOrNull { dev ->
                when (liveAudioState(dev.address)) {
                    AUDIO_STATE_CONNECTED -> 2
                    AUDIO_STATE_CONNECTING -> 1
                    else -> 0
                }
            }
            ranked?.address ?: list.first().address
        } catch (e: Throwable) {
            log("HfpClient.getConnectedDevices failed: ${e.message}")
            ""
        }
    }

    fun accept(deviceAddress: String, flag: Int): Boolean = GT.section("bt.hfp.accept") {
        val p = proxy ?: return@section false
        val dev = resolveDevice(deviceAddress) ?: return@section false
        try {
            val m = p.javaClass.getMethod(
                "acceptCall", BluetoothDevice::class.java, Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            val ok = (m.invoke(p, dev, flag) as? Boolean) ?: false
            log("event=hfp.acceptCall addr=${dev.address} flag=$flag result=$ok")
            ok
        } catch (e: Throwable) {
            log("HfpClient.acceptCall.error addr=$deviceAddress flag=$flag err=${e.message}")
            false
        }
    }

    fun reject(deviceAddress: String): Boolean = GT.section("bt.hfp.reject") {
        val p = proxy ?: return@section false
        val dev = resolveDevice(deviceAddress) ?: return@section false
        try {
            val m = p.javaClass.getMethod("rejectCall", BluetoothDevice::class.java)
            m.isAccessible = true
            val ok = (m.invoke(p, dev) as? Boolean) ?: false
            log("event=hfp.rejectCall addr=${dev.address} result=$ok")
            ok
        } catch (e: Throwable) {
            log("HfpClient.rejectCall.error addr=$deviceAddress err=${e.message}")
            false
        }
    }

    /**
     * Mute/unmute the HF microphone during an active SCO session.
     *
     * AOSP's BluetoothHeadsetClient profile exposes no public/hidden API for
     * sending AT+VGM (only sendVendorAtCommand for vendor-specific codes),
     * so the only working mechanism on this Android 12 build is to mute the
     * local microphone via AudioManager. While SCO is up, the local mic feeds
     * the SCO uplink exclusively, so muting it sends silence to the AG --
     * which is exactly the wearer-pressed-mute-button behavior every HFP
     * headset implements. Wakeword/listener AudioRecord paths cannot be
     * running during SCO anyway (mic is busy), so global mute here doesn't
     * regress any unrelated subsystem.
     */
    fun setMicrophoneMute(deviceAddress: String, muted: Boolean): Boolean = GT.section("bt.hfp.micMute") {
        val dev = resolveDevice(deviceAddress) ?: return@section false
        muteByAddr[dev.address] = muted
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isMicrophoneMute = muted
            val verify = am.isMicrophoneMute
            log("event=hfp.setMicrophoneMute addr=${dev.address} muted=$muted verify=$verify")
            verify == muted
        } catch (e: Throwable) {
            log("HfpClient.setMicrophoneMute.error addr=$deviceAddress muted=$muted err=${e.message}")
            false
        }
    }

    /** Cached mute state set via setMicrophoneMute(); empty string maps to "no record". */
    fun isMuted(deviceAddress: String): Boolean = muteByAddr[deviceAddress] == true

    /**
     * Clear AudioManager.isMicrophoneMute when no remembered mute remains. Used on
     * SCO disconnect / call termination to make sure the next unrelated session
     * doesn't inherit a stale device-wide mute.
     */
    private fun clearAudioManagerMuteIfStale() {
        if (muteByAddr.values.any { it }) return
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (am.isMicrophoneMute) {
                am.isMicrophoneMute = false
                log("HfpClient AudioManager mic unmuted (no devices muted)")
            }
        } catch (e: Throwable) {
            log("HfpClient clearAudioManagerMuteIfStale failed: ${e.message}")
        }
    }

    fun terminate(deviceAddress: String, callId: Int): Boolean = GT.section("bt.hfp.terminate") {
        val p = proxy ?: return@section false
        val dev = resolveDevice(deviceAddress) ?: return@section false
        val call = callCache[callId]
        if (call == null) {
            log("HfpClient.terminateCall no_cached_call id=$callId addr=$deviceAddress cache_size=${callCache.size}")
            return@section false
        }
        try {
            // Find terminateCall(BluetoothDevice, BluetoothHeadsetClientCall).
            val methods = p.javaClass.methods.filter { it.name == "terminateCall" }
            val method = methods.firstOrNull { m ->
                val params = m.parameterTypes
                params.size == 2 &&
                    params[0] == BluetoothDevice::class.java &&
                    params[1].isInstance(call)
            } ?: methods.firstOrNull { it.parameterTypes.size == 2 }
            if (method == null) {
                log("HfpClient.terminateCall: no_method addr=$deviceAddress id=$callId")
                return@section false
            }
            method.isAccessible = true
            val ok = (method.invoke(p, dev, call) as? Boolean) ?: false
            log("event=hfp.terminateCall addr=${dev.address} id=$callId result=$ok cache_size=${callCache.size}")
            ok
        } catch (e: Throwable) {
            log("HfpClient.terminateCall.error addr=$deviceAddress id=$callId err=${e.message}")
            false
        }
    }

    /**
     * Query the live audio state for a device via reflection on
     * BluetoothHeadsetClient.getAudioState(BluetoothDevice). Returns one of
     * AUDIO_STATE_* constants, or -1 if unavailable.
     */
    fun liveAudioState(deviceAddress: String): Int {
        val p = proxy ?: return -1
        val dev = resolveDevice(deviceAddress) ?: return -1
        return try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "getAudioState" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == BluetoothDevice::class.java
            } ?: return -1
            m.isAccessible = true
            (m.invoke(p, dev) as? Int) ?: -1
        } catch (e: Throwable) {
            log("HfpClient.getAudioState failed: ${e.message}")
            -1
        }
    }

    /**
     * Address of the device that currently holds the SCO link, or "" if none.
     *
     * SCO is physically single-occupancy: exactly one AG can own the speaker and
     * mic at a time. That device is in a call right now, so it outranks every
     * policy decision -- dropping its HFP would cut live call audio mid-sentence.
     * Callers use this to leave it alone. A2DP has no such rule: its slot is
     * freely reassigned, since interrupting music costs the user nothing.
     */
    fun scoActiveAddress(): String {
        // No proxy means this app has no HFP-HF profile bound at all, so there is
        // no device it could be holding a call for. That is a determinate "no
        // call", NOT unknown -- returning unknown here would fail closed forever
        // if the proxy never binds, permanently blocking every A2DP connect.
        val p = proxy ?: return ""
        return try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == "getConnectedDevices" && it.parameterTypes.isEmpty()
            } ?: return SCO_STATE_UNKNOWN
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val devs = (m.invoke(p) as? List<BluetoothDevice>).orEmpty()
            devs.firstOrNull { liveAudioState(it.address) == AUDIO_STATE_CONNECTED }
                ?.address ?: ""
        } catch (e: Throwable) {
            log("HfpClient.scoActiveAddress failed: ${e.message}")
            SCO_STATE_UNKNOWN
        }
    }

    /**
     * True when it is safe to disturb the shared audio path -- i.e. we know for
     * certain no device is on a call, and it is not [exceptFor].
     *
     * Deliberately fails CLOSED: if the proxy is missing or reflection throws we
     * cannot prove there is no call, and wrongly disturbing a live call is much
     * worse than briefly deferring a music handoff.
     */
    /**
     * True only when we know NO device has SCO up.
     *
     * The right test before touching A2DP: SCO is live call audio no matter who
     * owns it, and reconnecting A2DP renegotiates the shared audio path -- on
     * the SCO owner too, not just on the other device. A Linux host makes that
     * fatal: its BlueZ card carries
     * either a2dp-sink or headset-head-unit, never both, so it drops A2DP the
     * moment something opens the mic. Answering that drop with a reconnect
     * fights the host for the profile and the peer tears SCO down (HCI 0x13,
     * Remote User Terminated).
     *
     * Deliberately does NOT depend on a call being reported. Discord, Zoom and
     * the rest never raise a call indication -- they just open SCO -- so the
     * call state is IDLE while real audio is flowing. SCO itself is the signal.
     *
     * Fails closed: unknown means do not touch A2DP.
     */
    fun noCallAudioAnywhere(): Boolean {
        val owner = scoActiveAddress()
        if (owner == SCO_STATE_UNKNOWN) return false
        return owner.isEmpty()
    }

    /**
     * For every currently-connected HF device, query its audio state and emit
     * an onCallAudioStateChanged() event so the listener can rebuild its view.
     * Used both on proxy connect and as the basis for live snapshot queries.
     */
    private fun seedAudioStateFromProxy() {
        val p = proxy ?: return
        try {
            val m = p.javaClass.getMethod("getConnectedDevices")
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val devs = (m.invoke(p) as? List<BluetoothDevice>).orEmpty()
            for (d in devs) {
                val state = liveAudioState(d.address)
                log("HfpClient seed audio state addr=${d.address} state=$state")
                if (state >= 0) {
                    lastAudioState = state
                    lastDeviceAddress = d.address
                    try {
                        listener?.onCallAudioStateChanged(d.address, state)
                    } catch (e: Throwable) {
                        log("seed onCallAudioStateChanged threw: ${e.message}")
                    }
                }
            }
        } catch (e: Throwable) {
            log("HfpClient seedAudioStateFromProxy failed: ${e.message}")
        }
    }

    fun snapshotJson(): String {
        // Refresh from live proxy before serializing -- AUDIO_STATE_CHANGED
        // broadcasts can fire before we register a receiver, so cached state
        // alone is unreliable.
        val primary = primaryHfpDeviceAddress()
        if (primary.isNotEmpty()) {
            val live = liveAudioState(primary)
            if (live >= 0) {
                lastAudioState = live
                lastDeviceAddress = primary
            }
        }
        val obj = JSONObject()
        obj.put("address", lastDeviceAddress)
        obj.put("state", lastHfpState)
        obj.put("audioState", lastAudioState)
        val firstCall = callCache.values.firstOrNull()
        if (firstCall != null) {
            val c = JSONObject()
            val ps = parseCallToString(firstCall.toString())
            c.put("id", ps.id ?: reflectInt(firstCall, "getId"))
            c.put("state", ps.state ?: reflectInt(firstCall, "getState"))
            c.put("number", reflectString(firstCall, "getNumber").ifEmpty { ps.number ?: "" })
            c.put("name", reflectString(firstCall, "getName").ifEmpty { ps.name ?: "" })
            c.put("multiParty", ps.multiParty ?: reflectBool(firstCall, "isMultiParty"))
            c.put("outgoing", ps.outgoing ?: reflectBool(firstCall, "isOutgoing"))
            obj.put("call", c)
        } else {
            obj.put("call", JSONObject.NULL)
        }
        return obj.toString()
    }

    // ---- Broadcast handlers ----

    private fun handleCallChanged(intent: Intent) {
        val call: Parcelable? = try {
            intent.getParcelableExtra(EXTRA_CALL)
        } catch (e: Throwable) {
            log("AG_CALL_CHANGED: extra read failed: ${e.message}")
            null
        }
        if (call == null) {
            log("AG_CALL_CHANGED: no extra.CALL parcelable")
            return
        }
        val dev: BluetoothDevice? = try {
            intent.getParcelableExtra(EXTRA_DEVICE)
        } catch (_: Throwable) { null }
        val addr = dev?.address ?: lastDeviceAddress

        val parsed = parseCallToString(call.toString())
        val callId = parsed.id ?: reflectInt(call, "getId")
        val uuid = parsed.uuid ?: reflectUuid(call, "getUUID")
        val state = parsed.state ?: reflectInt(call, "getState")
        // toString() anonymizes number/name (logs hashCode for privacy), so
        // always prefer real reflection. Only fall back to the (hashed) toString
        // value if reflection genuinely fails -- still better than empty.
        val number = reflectString(call, "getNumber").ifEmpty { parsed.number ?: "" }
        val name = reflectString(call, "getName").ifEmpty { parsed.name ?: "" }
        val multiParty = parsed.multiParty ?: reflectBool(call, "isMultiParty")
        val outgoing = parsed.outgoing ?: reflectBool(call, "isOutgoing")

        if (state == CALL_STATE_TERMINATED) {
            callCache.remove(callId)
            // Drop any remembered mute for this device so the next call starts unmuted.
            if (addr.isNotEmpty()) muteByAddr.remove(addr)
            clearAudioManagerMuteIfStale()
        } else {
            callCache[callId] = call
        }

        log("event=hfp.AG_CALL_CHANGED addr=$addr name=${dev?.name} id=$callId state=${callStateLabel(state)} number=$number outgoing=$outgoing cache_size=${callCache.size}")
        try {
            listener?.onCallStateChanged(addr, callId, uuid, state, number, name, multiParty, outgoing)
        } catch (e: Throwable) {
            log("onCallStateChanged listener threw: ${e.message}")
        }
    }

    private fun handleAudioStateChanged(intent: Intent) {
        val dev: BluetoothDevice? = try {
            intent.getParcelableExtra(EXTRA_DEVICE)
        } catch (_: Throwable) { null }
        val addr = dev?.address ?: lastDeviceAddress
        val prevBroadcast = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val newState = intent.getIntExtra(EXTRA_STATE, -1)
        if (newState < 0) {
            log("HfpClient AUDIO_STATE_CHANGED: no state extra")
            return
        }
        val prevTracked = if (addr.isNotEmpty()) {
            val prev = deviceAudioStates[addr] ?: AUDIO_STATE_DISCONNECTED
            deviceAudioStates[addr] = newState
            prev
        } else lastAudioState
        lastAudioState = newState
        if (addr.isNotEmpty()) lastDeviceAddress = addr
        log("event=hfp.AUDIO_STATE_CHANGED addr=$addr name=${dev?.name} prev=${hfpAudioStateLabel(if (prevBroadcast >= 0) prevBroadcast else prevTracked)} new=${hfpAudioStateLabel(newState)} conn=${connectionStateLabel(deviceHfpStates[addr] ?: -1)}")
        // Re-assert remembered mute on SCO bring-up; AG can reset gain when SCO
        // renegotiates and the user expects the mute to persist across the blip.
        if (newState == AUDIO_STATE_CONNECTED && addr.isNotEmpty()) {
            val want = muteByAddr[addr]
            if (want == true) {
                log("HfpClient SCO reconnected, re-asserting mute=$want addr=$addr")
                try { setMicrophoneMute(addr, true) } catch (e: Throwable) {
                    log("HfpClient mute re-assert failed: ${e.message}")
                }
            }
        }
        if (newState == AUDIO_STATE_DISCONNECTED && addr.isNotEmpty()) {
            // SCO gone -- drop the remembered mute for this device and unmute the
            // local mic if no other muted device remains. Otherwise the next
            // unrelated SCO session inherits a muted mic.
            muteByAddr.remove(addr)
            clearAudioManagerMuteIfStale()
        }
        try {
            listener?.onCallAudioStateChanged(addr, newState)
        } catch (e: Throwable) {
            log("onCallAudioStateChanged listener threw: ${e.message}")
        }
    }

    private fun handleConnectionStateChanged(intent: Intent) {
        val dev: BluetoothDevice? = try {
            intent.getParcelableExtra(EXTRA_DEVICE)
        } catch (_: Throwable) { null }
        val addr = dev?.address ?: ""
        val prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
        val newState = intent.getIntExtra(EXTRA_STATE, -1)
        if (newState < 0) {
            log("HfpClient CONNECTION_STATE_CHANGED: no state extra")
            return
        }
        // Per-device tracking + anomaly detection
        if (addr.isNotEmpty()) {
            val tracked = deviceHfpStates[addr] ?: BluetoothProfile.STATE_DISCONNECTED
            if (prevState >= 0 && tracked != prevState) {
                Log.w(TAG, "event=hfp.state_mismatch addr=$addr name=${dev?.name} tracked=${connectionStateLabel(tracked)} broadcast_prev=${connectionStateLabel(prevState)}")
            }
            if (prevState == newState) {
                Log.w(TAG, "event=hfp.state_noop addr=$addr name=${dev?.name} state=${connectionStateLabel(newState)}")
            }
            deviceHfpStates[addr] = newState
        }
        val otherDevices = deviceHfpStates.entries
            .filter { !it.key.equals(addr, true) }
            .joinToString { "${it.key}=${connectionStateLabel(it.value)}" }
        lastHfpState = newState
        if (addr.isNotEmpty()) lastDeviceAddress = addr
        log("event=hfp.CONNECTION_STATE_CHANGED addr=$addr name=${dev?.name} prev=${connectionStateLabel(prevState)} new=${connectionStateLabel(newState)}${if (otherDevices.isNotEmpty()) " other_devices=[$otherDevices]" else ""}")
        // Last holder wins: the device that just connected takes the slot, and
        // every other holder is dropped immediately. Enforced here rather than
        // in the sweep because the sweep runs up to 5s later, and for that whole
        // window two AGs are connected -- which is the state the HF client
        // cannot serve and answers with an SCO reject.
        if (newState == BluetoothProfile.STATE_CONNECTED && addr.isNotEmpty()) {
            if (inEvictionBackoff(addr)) {
                // We dropped this device moments ago and its AG dialled straight
                // back. Honouring last-holder-wins here would hand it the slot
                // and evict the current owner, whose AG would do the same in
                // return. Drop the newcomer instead; the exchange ends.
                log("event=hfp.evict.backoff addr=$addr reconnected during backoff, dropping again")
                disconnectTransient(addr)
            } else {
                lastConnectedMs[addr] = SystemClock.elapsedRealtime()
                evictOtherHfpHolders(addr)
            }
        }
        try {
            listener?.onHfpConnectionChanged(addr, newState)
        } catch (e: Throwable) {
            log("onHfpConnectionChanged listener threw: ${e.message}")
        }
    }

    // ---- Helpers ----

    private fun resolveDevice(address: String): BluetoothDevice? {
        val a = adapter ?: return null
        if (address.isBlank()) return null
        return try {
            a.getRemoteDevice(address)
        } catch (e: Throwable) {
            log("resolveDevice($address) failed: ${e.message}")
            null
        }
    }

    // Hidden-API bypass: getMethod / getDeclaredMethod are filtered on Android 9+
    // even for priv-apps for @hide methods on system classes like
    // BluetoothHeadsetClientCall. Bootstrapping via Class.getDeclaredMethod called
    // *itself* through reflection escapes the filter (the filter is keyed on the
    // immediate caller, and the bootstrap call originates inside libcore).
    private val metaGetDeclaredMethod: Method? = try {
        Class::class.java.getDeclaredMethod(
            "getDeclaredMethod",
            String::class.java,
            arrayOf<Class<*>>()::class.java,
        )
    } catch (e: Throwable) {
        log("meta getDeclaredMethod bootstrap failed: ${e.message}"); null
    }

    private fun resolveMethod(target: Any, methodName: String): Method? {
        val meta = metaGetDeclaredMethod ?: return null
        return try {
            (meta.invoke(target.javaClass, methodName, emptyArray<Class<*>>()) as? Method)
                ?.also { it.isAccessible = true }
        } catch (e: Throwable) {
            log("resolve $methodName failed: ${e.message}"); null
        }
    }

    private fun reflectInt(target: Any, methodName: String): Int {
        val m = resolveMethod(target, methodName) ?: return -1
        return try {
            (m.invoke(target) as? Int) ?: -1
        } catch (e: Throwable) {
            log("invoke $methodName failed: ${e.message}"); -1
        }
    }

    private fun reflectString(target: Any, methodName: String): String {
        val m = resolveMethod(target, methodName) ?: return ""
        return try {
            (m.invoke(target) as? String) ?: ""
        } catch (e: Throwable) {
            log("invoke $methodName failed: ${e.message}"); ""
        }
    }

    private fun reflectBool(target: Any, methodName: String): Boolean {
        val m = resolveMethod(target, methodName) ?: return false
        return try {
            (m.invoke(target) as? Boolean) ?: false
        } catch (e: Throwable) {
            log("invoke $methodName failed: ${e.message}"); false
        }
    }

    private fun reflectUuid(target: Any, methodName: String): String {
        val m = resolveMethod(target, methodName) ?: return ""
        return try {
            (m.invoke(target) as? UUID)?.toString() ?: ""
        } catch (e: Throwable) {
            log("invoke $methodName failed: ${e.message}"); ""
        }
    }

    private data class ParsedCall(
        val id: Int? = null,
        val uuid: String? = null,
        val state: Int? = null,
        val number: String? = null,
        val name: String? = null,
        val multiParty: Boolean? = null,
        val outgoing: Boolean? = null,
    )

    /**
     * Parse BluetoothHeadsetClientCall.toString() output. AOSP format example:
     *   BluetoothHeadsetClientCall{mDevice: -907345213, mId: 1,
     *     mUUID: 2b2e9341-..., mState: INCOMING, mNumber: +1234567890,
     *     mMultiParty: false, mOutgoing: false, mInBandRing: false}
     * Hidden API filter blocks reflection on getId/getState/etc., so this is the
     * only path to extract call data on Android 12 priv-apps that don't have a
     * platform-signed cert.
     */
    private fun parseCallToString(s: String): ParsedCall {
        fun grab(field: String): String? {
            val key = "m$field: "
            val i = s.indexOf(key)
            if (i < 0) return null
            val start = i + key.length
            var end = start
            while (end < s.length && s[end] != ',' && s[end] != '}') end++
            return s.substring(start, end).trim().takeIf { it.isNotEmpty() }
        }
        val stateStr = grab("State")
        val state = when (stateStr) {
            "ACTIVE" -> CALL_STATE_ACTIVE
            "HELD" -> CALL_STATE_HELD
            "DIALING" -> CALL_STATE_DIALING
            "ALERTING" -> CALL_STATE_ALERTING
            "INCOMING" -> CALL_STATE_INCOMING
            "WAITING" -> CALL_STATE_WAITING
            "HELD_BY_RESPONSE_AND_HOLD" -> CALL_STATE_HELD_BY_RESPONSE_AND_HOLD
            "TERMINATED" -> CALL_STATE_TERMINATED
            else -> stateStr?.toIntOrNull()
        }
        return ParsedCall(
            id = grab("Id")?.toIntOrNull(),
            uuid = grab("UUID"),
            state = state,
            number = grab("Number"),
            name = grab("Name"),
            multiParty = grab("MultiParty")?.toBooleanStrictOrNull(),
            outgoing = grab("Outgoing")?.toBooleanStrictOrNull(),
        )
    }

    private fun dumpProxyMethods(p: BluetoothProfile) {
        try {
            val names = p.javaClass.methods.joinToString(",") { it.name }
            log("HfpClient proxy methods: $names")
            Log.i(TAG, "HfpClient proxy methods: $names")
        } catch (e: Throwable) {
            log("HfpClient dumpProxyMethods failed: ${e.message}")
        }
    }
}
