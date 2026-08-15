package com.repository.glasses.listener.bt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.repository.glasses.btmanager.IBtManager
import com.repository.glasses.btmanager.IBtManagerCallback
import com.repository.glasses.tracing.GT
import java.util.concurrent.CopyOnWriteArrayList

class BtManagerBridge(private val context: Context) {

    companion object {
        private const val TAG = "App:BtBridge"
        private const val BT_MANAGER_PACKAGE = "com.repository.glasses.btmanager"
        private const val BT_MANAGER_SERVICE = "com.repository.glasses.btmanager.BtManagerService"

        /**
         * A2DP suppression lease length. Must exceed the caller's renewal interval
         * with enough slack to survive a missed heartbeat, and be short enough that
         * a dead holder costs the user only a few seconds of BT audio.
         */
        const val A2DP_SUPPRESS_LEASE_MS = 5_000L

        /** Renewal cadence for the above. */
        const val A2DP_SUPPRESS_RENEW_MS = 2_000L
    }

    interface RfcommListener {
        fun onConnected(socketId: String, address: String, name: String) {}
        fun onDisconnected(socketId: String) {}
        fun onData(socketId: String, data: ByteArray) {}
        fun onError(socketId: String, error: String) {}
    }

    interface AdvertisingListener {
        fun onStarted(tag: String) {}
        fun onFailed(tag: String, errorCode: Int) {}
        fun onStopped(tag: String) {}
    }

    interface BondListener {
        fun onBondStateChanged(address: String, name: String, prevState: Int, newState: Int)
    }

    interface DeviceListener {
        fun onDeviceConnected(address: String, name: String, transport: Int) {}
        fun onDeviceDisconnected(address: String, transport: Int) {}
    }

    interface CallListener {
        fun onCallStateChanged(
            deviceAddress: String,
            callId: Int,
            uuid: String,
            state: Int,
            number: String,
            name: String,
            multiParty: Boolean,
            outgoing: Boolean
        ) {}
        fun onCallAudioStateChanged(deviceAddress: String, audioState: Int) {}
        fun onHfpConnectionChanged(deviceAddress: String, state: Int) {}
    }

    var remoteLog: ((String) -> Unit)? = null
    var isBound = false
        private set
    private var manager: IBtManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Legacy single onBound callback. Prefer addOnBound for multi-subscriber scenarios. */
    var onBound: (() -> Unit)? = null

    private val onBoundListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addOnBoundListener(l: () -> Unit) {
        onBoundListeners.add(l)
        if (isBound) l.invoke()  // fire immediately if already bound
    }
    fun removeOnBoundListener(l: () -> Unit) { onBoundListeners.remove(l) }

    private val rfcommListeners = CopyOnWriteArrayList<RfcommListener>()
    private val advertisingListeners = CopyOnWriteArrayList<AdvertisingListener>()
    private val bondListeners = CopyOnWriteArrayList<BondListener>()
    private val deviceListeners = CopyOnWriteArrayList<DeviceListener>()
    private val callListeners = CopyOnWriteArrayList<CallListener>()

    @Volatile private var bindPending: Runnable? = null
    @Volatile private var stopped = false

    fun addRfcommListener(l: RfcommListener) { rfcommListeners.add(l) }
    fun removeRfcommListener(l: RfcommListener) { rfcommListeners.remove(l) }
    fun addAdvertisingListener(l: AdvertisingListener) { advertisingListeners.add(l) }
    fun removeAdvertisingListener(l: AdvertisingListener) { advertisingListeners.remove(l) }
    fun addBondListener(l: BondListener) { bondListeners.add(l) }
    fun removeBondListener(l: BondListener) { bondListeners.remove(l) }
    fun addDeviceListener(l: DeviceListener) { deviceListeners.add(l) }
    fun removeDeviceListener(l: DeviceListener) { deviceListeners.remove(l) }
    fun addCallListener(l: CallListener) { callListeners.add(l) }
    fun removeCallListener(l: CallListener) { callListeners.remove(l) }

    private val callback = object : IBtManagerCallback.Stub() {
        override fun onAdapterStateChanged(state: Int) {
            remoteLog?.invoke("BtManager: adapter state=$state")
        }

        override fun onBondStateChanged(address: String, name: String, prevState: Int, newState: Int) {
            remoteLog?.invoke("BtManager: bond $address ($name) $prevState->$newState")
            bondListeners.forEach { it.onBondStateChanged(address, name, prevState, newState) }
        }

        override fun onAdvertisingStarted(tag: String) {
            remoteLog?.invoke("BtManager: advertising started tag=$tag")
            advertisingListeners.forEach { it.onStarted(tag) }
        }

        override fun onAdvertisingFailed(tag: String, errorCode: Int) {
            remoteLog?.invoke("BtManager: advertising failed tag=$tag error=$errorCode")
            advertisingListeners.forEach { it.onFailed(tag, errorCode) }
        }

        override fun onAdvertisingStopped(tag: String) {
            remoteLog?.invoke("BtManager: advertising stopped tag=$tag")
            advertisingListeners.forEach { it.onStopped(tag) }
        }

        override fun onRfcommConnected(socketId: String, address: String, name: String) {
            remoteLog?.invoke("BtManager: rfcomm connected $socketId -> $name ($address)")
            rfcommListeners.forEach { it.onConnected(socketId, address, name) }
        }

        override fun onRfcommDisconnected(socketId: String) {
            remoteLog?.invoke("BtManager: rfcomm disconnected $socketId")
            rfcommListeners.forEach { it.onDisconnected(socketId) }
        }

        override fun onRfcommData(socketId: String, data: ByteArray) {
            rfcommListeners.forEach { it.onData(socketId, data) }
        }

        override fun onRfcommError(socketId: String, error: String) {
            remoteLog?.invoke("BtManager: rfcomm error $socketId: $error")
            rfcommListeners.forEach { it.onError(socketId, error) }
        }

        override fun onDeviceConnected(address: String, name: String, transport: Int) {
            remoteLog?.invoke("BtManager: device connected $name ($address) transport=$transport")
            deviceListeners.forEach { it.onDeviceConnected(address, name, transport) }
        }

        override fun onDeviceDisconnected(address: String, transport: Int) {
            remoteLog?.invoke("BtManager: device disconnected $address transport=$transport")
            deviceListeners.forEach { it.onDeviceDisconnected(address, transport) }
        }

        override fun onCallStateChanged(
            deviceAddress: String,
            callId: Int,
            uuid: String,
            state: Int,
            number: String,
            name: String,
            multiParty: Boolean,
            outgoing: Boolean
        ) {
            remoteLog?.invoke(
                "BtManager: call state addr=$deviceAddress id=$callId state=$state " +
                    "num=$number name=$name multi=$multiParty out=$outgoing"
            )
            callListeners.forEach {
                it.onCallStateChanged(deviceAddress, callId, uuid, state, number, name, multiParty, outgoing)
            }
        }

        override fun onCallAudioStateChanged(deviceAddress: String, audioState: Int) {
            remoteLog?.invoke("BtManager: call audio addr=$deviceAddress state=$audioState")
            callListeners.forEach { it.onCallAudioStateChanged(deviceAddress, audioState) }
        }

        override fun onHfpConnectionChanged(deviceAddress: String, state: Int) {
            remoteLog?.invoke("BtManager: hfp conn addr=$deviceAddress state=$state")
            callListeners.forEach { it.onHfpConnectionChanged(deviceAddress, state) }
        }

        override fun onA2dpConnectionChanged(deviceAddress: String, prevState: Int, newState: Int) {
            remoteLog?.invoke("BtManager: a2dp conn addr=$deviceAddress prev=$prevState new=$newState")
        }

        override fun onA2dpAudioStateChanged(deviceAddress: String, state: Int) {
            remoteLog?.invoke("BtManager: a2dp audio addr=$deviceAddress state=$state")
        }
    }

    @Volatile private var currentBinder: IBinder? = null

    private val deathRecipient: IBinder.DeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            remoteLog?.invoke("BtManager: binder DIED -- unbinding + rebinding")
            android.util.Log.w("BtManagerBridge", "binder died, unbinding+rebinding")
            manager = null
            isBound = false
            val oldBinder = currentBinder
            currentBinder = null
            try { oldBinder?.unlinkToDeath(this, 0) } catch (_: Exception) {}
            // After force-stop Android keeps the binding in a DEAD state and will NOT
            // auto-rebind even with BIND_AUTO_CREATE. Must explicitly unbind then rebind.
            val c = connection
            if (c != null) try { context.unbindService(c) } catch (_: Exception) {}
            if (!stopped) scheduleBind(1500)
        }
    }

    private var connection: ServiceConnection? = null

    init {
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                android.util.Log.i("BtManagerBridge", "onServiceConnected: $name")
                remoteLog?.invoke("BtManager: connected to $name")
                manager = IBtManager.Stub.asInterface(service)
                isBound = true
                currentBinder = service
                try {
                    service?.linkToDeath(deathRecipient, 0)
                } catch (e: Exception) {
                    remoteLog?.invoke("BtManager: linkToDeath failed: ${e.message}")
                }
                try {
                    manager?.registerCallback(callback)
                } catch (e: Exception) {
                    remoteLog?.invoke("BtManager: registerCallback failed: ${e.message}")
                }
                // Before any listener runs: a rebind invalidated the old token, so the
                // hold must be re-taken (or stay released) to match the desired state.
                try { reassertA2dpSuppression() } catch (e: Exception) {
                    remoteLog?.invoke("reassertA2dpSuppression failed: ${e.message}")
                }
                try { onBound?.invoke() } catch (e: Exception) {
                    remoteLog?.invoke("onBound failed: ${e.message}")
                }
                onBoundListeners.forEach {
                    try { it.invoke() } catch (e: Exception) {
                        remoteLog?.invoke("onBoundListener failed: ${e.message}")
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                android.util.Log.w("BtManagerBridge", "onServiceDisconnected: $name")
                remoteLog?.invoke("BtManager: DISCONNECTED -- unbinding + rebinding")
                manager = null
                isBound = false
                currentBinder = null
                // Explicit unbind so the next bindService creates a fresh connection.
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
        GT.begin("bt.bridge.bind")
        try {
            val component = ComponentName(BT_MANAGER_PACKAGE, BT_MANAGER_SERVICE)
            val c = connection ?: run {
                remoteLog?.invoke("BtManager: connection is null, skipping bind")
                return
            }
            // After `am force-stop`, the bt-manager package is in the STOPPED state.
            // A plain bindService(BIND_AUTO_CREATE) will NOT wake a stopped package, so we
            // first call startForegroundService with FLAG_INCLUDE_STOPPED_PACKAGES to bring
            // the package back to RUNNING, then bind.
            try {
                Log.d(TAG, "event=foreground_start pkg=$BT_MANAGER_PACKAGE")
                val startIntent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.startForegroundService(startIntent)
                Log.d(TAG, "event=foreground_start_done pkg=$BT_MANAGER_PACKAGE")
            } catch (e: Exception) {
                Log.w(TAG, "event=foreground_start_fail msg=${e.message}")
                remoteLog?.invoke("BtManager: startForegroundService failed: ${e.message}")
            }
            val bindIntent = Intent().apply { this.component = component }
            val result = context.bindService(
                bindIntent,
                c,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            remoteLog?.invoke("BtManager: bindService result=$result")
            if (!result) {
                remoteLog?.invoke("BtManager: bindService returned false, retrying in 2s")
                scheduleBind(2000)
            }
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: bind exception: ${e.message}")
            scheduleBind(2000)
        } finally {
            GT.end()
        }
    }

    fun unbind() {
        stopped = true
        bindPending?.let { mainHandler.removeCallbacks(it) }
        bindPending = null
        try {
            manager?.unregisterCallback(callback)
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: unregisterCallback failed: ${e.message}")
        }
        try {
            connection?.let { context.unbindService(it) }
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: unbind failed: ${e.message}")
        }
        manager = null
        isBound = false
    }

    // -- Advertising --

    fun startAdvertising(tag: String, serviceUuid: String, includeDeviceName: Boolean) {
        try {
            manager?.startAdvertising(tag, serviceUuid, includeDeviceName)
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: startAdvertising failed: ${e.message}")
        }
    }

    fun stopAdvertising(tag: String) {
        try {
            manager?.stopAdvertising(tag)
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: stopAdvertising failed: ${e.message}")
        }
    }

    fun isAdvertising(tag: String): Boolean {
        return try { manager?.isAdvertising(tag) ?: false } catch (_: Exception) { false }
    }

    // -- RFCOMM --

    fun connectRfcommOutbound(deviceAddress: String, uuid: String): String? {
        return GT.section("bt.bridge.connect_outbound") {
            try {
                manager?.connectRfcommOutbound(deviceAddress, uuid)
            } catch (e: Exception) {
                remoteLog?.invoke("BtManager: connectOutbound failed: ${e.message}")
                null
            }
        }
    }

    fun listenRfcommInbound(serviceName: String, uuid: String): String? {
        return GT.section("bt.bridge.listen_inbound") {
            try {
                manager?.listenRfcommInbound(serviceName, uuid)
            } catch (e: Exception) {
                remoteLog?.invoke("BtManager: listenInbound failed: ${e.message}")
                null
            }
        }
    }

    fun closeRfcommSocket(socketId: String) {
        GT.section("bt.bridge.close_socket") {
            try {
                manager?.closeRfcommSocket(socketId)
            } catch (e: Exception) {
                remoteLog?.invoke("BtManager: closeSocket failed: ${e.message}")
            }
        }
    }

    fun writeRfcomm(socketId: String, data: ByteArray): Boolean {
        val m = manager ?: return false
        return GT.section("bt.bridge.write") {
            try {
                m.writeRfcommSocket(socketId, data)
                true
            } catch (e: Exception) {
                remoteLog?.invoke("BtManager: writeSocket failed: ${e.message}")
                false
            }
        }
    }

    fun isRfcommConnected(socketId: String): Boolean {
        return try { manager?.isRfcommConnected(socketId) ?: false } catch (_: Exception) { false }
    }

    // -- Query --

    fun isBluetoothEnabled(): Boolean {
        return try { manager?.isBluetoothEnabled ?: false } catch (_: Exception) { false }
    }

    fun getBondedDevicesJson(): String {
        return try { manager?.bondedDevicesJson ?: "[]" } catch (_: Exception) { "[]" }
    }

    fun getActiveConnectionsJson(): String {
        return try { manager?.activeConnectionsJson ?: "[]" } catch (_: Exception) { "[]" }
    }

    // -- HFP call control --

    fun acceptCall(deviceAddress: String, flag: Int = 0): Boolean {
        return try {
            manager?.acceptCall(deviceAddress, flag) ?: false
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: acceptCall failed: ${e.message}")
            false
        }
    }

    fun rejectCall(deviceAddress: String): Boolean {
        return try {
            manager?.rejectCall(deviceAddress) ?: false
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: rejectCall failed: ${e.message}")
            false
        }
    }

    fun terminateCall(deviceAddress: String, callId: Int): Boolean {
        return try {
            manager?.terminateCall(deviceAddress, callId) ?: false
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: terminateCall failed: ${e.message}")
            false
        }
    }

    fun getCallSnapshotJson(): String {
        return try { manager?.callSnapshotJson ?: "{}" } catch (_: Exception) { "{}" }
    }

    fun getPrimaryHfpDeviceAddress(): String {
        return try { manager?.primaryHfpDeviceAddress ?: "" } catch (_: Exception) { "" }
    }

    fun setHfMicMute(deviceAddress: String, muted: Boolean): Boolean {
        return try {
            manager?.setHfMicMute(deviceAddress, muted) ?: false
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: setHfMicMute failed: ${e.message}")
            false
        }
    }

    // ---- A2DP suppression lease ----
    //
    // Holds the phone's A2DP Sink link to these glasses DOWN for the duration of an AR
    // stream session: the phone plays our own uplink mic on STREAM_MUSIC, which Android
    // routes back to us over A2DP, so the wearer hears themselves and the echo canceller
    // is poisoned.
    //
    // The bridge, not the caller, owns the token and the DESIRED state. Two reasons:
    //   - A bt-manager crash/rebind loses the token; the desired state lets the next
    //     bind re-assert it instead of silently dropping the hold (or, worse, dropping
    //     a RELEASE, which would strand the user with no BT audio).
    //   - The lease owner binder must live exactly as long as this PROCESS, so that
    //     bt-manager's linkToDeath releases the hold the instant the listener dies.

    /** Owner token for the lease. Its death (= this process dying) releases the hold. */
    private val a2dpSuppressOwner = android.os.Binder()

    /** Address we want held down, or null for "no hold". The authority on intent. */
    @Volatile private var a2dpSuppressWantAddr: String? = null

    /** Token from the current bt-manager instance; null when unbound or not held. */
    @Volatile private var a2dpSuppressToken: String? = null

    /**
     * Ask bt-manager to hold [deviceAddress] down, or refresh an existing hold.
     *
     * Safe (and expected) to call repeatedly: this is the heartbeat that keeps the
     * lease alive. If the token was lost to a rebind it silently re-acquires.
     *
     * @return true if a hold is currently established with bt-manager.
     */
    fun holdA2dpSuppressed(deviceAddress: String): Boolean {
        a2dpSuppressWantAddr = deviceAddress
        val m = manager ?: return false
        return try {
            val tok = a2dpSuppressToken
            if (tok != null) {
                m.renewA2dpSuppression(tok)
                true
            } else {
                val fresh = m.acquireA2dpSuppression(
                    deviceAddress, A2DP_SUPPRESS_LEASE_MS, a2dpSuppressOwner
                )
                a2dpSuppressToken = fresh
                remoteLog?.invoke("BtManager: a2dp suppression acquired addr=$deviceAddress token=$fresh")
                fresh != null
            }
        } catch (e: Exception) {
            // Died mid-call: drop the token so the next heartbeat re-acquires.
            a2dpSuppressToken = null
            remoteLog?.invoke("BtManager: a2dp suppression hold failed: ${e.message}")
            false
        }
    }

    /**
     * Drop the hold. Never a no-op on "we think nothing is held": clearing intent is
     * what stops a rebind from re-asserting a stale hold, and the release itself is
     * oneway so a wedged bt-manager cannot block the caller's teardown.
     */
    fun clearA2dpSuppression() {
        a2dpSuppressWantAddr = null
        val tok = a2dpSuppressToken ?: return
        a2dpSuppressToken = null
        try {
            manager?.releaseA2dpSuppression(tok)
            remoteLog?.invoke("BtManager: a2dp suppression released token=$tok")
        } catch (e: Exception) {
            // The lease expires by itself within seconds, so a failed release costs
            // the user a few seconds of silence, never a permanent one.
            remoteLog?.invoke("BtManager: a2dp suppression release failed: ${e.message}")
        }
    }

    /** Re-assert the desired hold after a (re)bind. Called from the onBound path. */
    private fun reassertA2dpSuppression() {
        a2dpSuppressToken = null
        val want = a2dpSuppressWantAddr ?: return
        remoteLog?.invoke("BtManager: re-asserting a2dp suppression addr=$want after rebind")
        holdA2dpSuppressed(want)
    }

    // -- Active-session ref-counting (gates RFCOMM idle teardown in bt-manager) --

    fun setActiveSession(label: String) {
        try {
            manager?.setActiveSession(label)
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: setActiveSession($label) failed: ${e.message}")
        }
    }

    fun clearActiveSession(label: String) {
        try {
            manager?.clearActiveSession(label)
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: clearActiveSession($label) failed: ${e.message}")
        }
    }

    fun isAnySessionActive(): Boolean {
        return try { manager?.isAnySessionActive ?: false } catch (_: Exception) { false }
    }

    /**
     * G3 BLE wake notify: forward to bt-manager which fans out via GATT to any
     * subscribed peer (the phone's BleWakeNotifyClient).
     */
    fun notifyPhone(eventCode: Byte, epochNanos: Long): Boolean {
        return try {
            manager?.notifyPhone(eventCode, epochNanos) ?: false
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: notifyPhone($eventCode) failed: ${e.message}")
            false
        }
    }

    /** G3 BLE wake notify with one-byte payload (e.g. battery SoC). */
    fun notifyPhoneWithData(eventCode: Byte, data: Byte, epochNanos: Long): Boolean {
        return try {
            manager?.notifyPhoneWithData(eventCode, data, epochNanos) ?: false
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: notifyPhoneWithData($eventCode,$data) failed: ${e.message}")
            false
        }
    }

    /**
     * Ask bt-manager's BleWakeService to emit a BLE_HELLO notify to any
     * subscribed central (phone). If no central is subscribed yet, the hello
     * is armed and flushed at the next CCCD-enable.
     */
    fun sendBleHello(): Boolean {
        return try {
            manager?.sendBleHello()
            manager != null
        } catch (e: Exception) {
            remoteLog?.invoke("BtManager: sendBleHello failed: ${e.message}")
            false
        }
    }
}
