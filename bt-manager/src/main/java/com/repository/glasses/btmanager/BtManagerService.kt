package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("MissingPermission")
class BtManagerService : Service() {

    companion object {
        private const val TAG = "BtMgr:Svc"
        private const val TAG_ADAPTER = "BtMgr:Adapter"
        private const val TAG_CALLBACKS = "BtMgr:Callbacks"
        private const val CHANNEL_ID = "bt_manager"
        private const val NOTIFICATION_ID = 100
    }

    private lateinit var bluetoothManager: BluetoothManager
    private var adapter: BluetoothAdapter? = null
    private val callbacks = RemoteCallbackList<IBtManagerCallback>()
    private lateinit var bleAdvertiser: BleAdvertiser
    private lateinit var bleWakeService: BleWakeService
    private lateinit var rfcommManager: RfcommManager
    private lateinit var bondWatcher: BondWatcher
    private lateinit var hfpClient: HfpClientController
    private lateinit var a2dpSink: A2dpSinkController
    private lateinit var foldGate: FoldGate
    private lateinit var profileAutoConnector: ProfileAutoConnector

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val tStart = SystemClock.elapsedRealtime()
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                    )
                    Log.i(TAG_ADAPTER, "event=adapter.stateChanged state=$state")
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            // Adapter killed BLE advertising + GATT server internally.
                            // Clear stale references so start() works on STATE_ON.
                            Log.i(TAG_ADAPTER, "event=adapter.off -- stopping BLE wake service")
                            try { bleWakeService.stop() } catch (_: Exception) {}
                        }
                        BluetoothAdapter.STATE_ON -> {
                            // Adapter is back -- re-read MAC (may have been unavailable at init)
                            // and re-open GATT server + BLE advertising.
                            val mac = android.provider.Settings.Secure.getString(
                                contentResolver, "bluetooth_address"
                            ) ?: adapter?.address ?: "00:00:00:00:00:00"
                            if (mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                                bleWakeService.setClassicMacAddress(mac)
                            }
                            Log.i(TAG_ADAPTER, "event=adapter.on -- restarting BLE wake service (mac=$mac)")
                            val ok = bleWakeService.start()
                            Log.i(TAG_ADAPTER, "event=adapter.on.bleWakeRestarted ok=$ok")
                        }
                    }
                    broadcastCallbacks { it.onAdapterStateChanged(state) }
                    Log.d(TAG_ADAPTER, "event=adapter.stateChanged.done state=$state dt_ms=${SystemClock.elapsedRealtime() - tStart}")
                }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> GT.section("bt.discovery") {
                    Log.i(TAG_ADAPTER, "event=adapter.scanModeChanged")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )
                    val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1)
                    Log.i(TAG_ADAPTER, "event=adapter.aclConnected name=${device?.name} addr=${device?.address} transport=$transport")
                    broadcastCallbacks {
                        it.onDeviceConnected(device?.address ?: "", device?.name ?: "", transport)
                    }
                    Log.d(TAG_ADAPTER, "event=adapter.aclConnected.done addr=${device?.address} dt_ms=${SystemClock.elapsedRealtime() - tStart}")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )
                    val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1)
                    Log.i(TAG_ADAPTER, "event=adapter.aclDisconnected name=${device?.name} addr=${device?.address} transport=$transport")
                    broadcastCallbacks {
                        it.onDeviceDisconnected(device?.address ?: "", transport)
                    }
                    Log.d(TAG_ADAPTER, "event=adapter.aclDisconnected.done addr=${device?.address} dt_ms=${SystemClock.elapsedRealtime() - tStart}")
                }
            }
        }
    }

    private val binder = object : IBtManager.Stub() {

        override fun registerCallback(callback: IBtManagerCallback) {
            Log.d(TAG, "event=binder.registerCallback")
            callbacks.register(callback)
        }

        override fun unregisterCallback(callback: IBtManagerCallback) {
            Log.d(TAG, "event=binder.unregisterCallback")
            callbacks.unregister(callback)
        }

        override fun isBluetoothEnabled(): Boolean {
            Log.v(TAG, "event=binder.isBluetoothEnabled")
            return adapter?.isEnabled == true
        }

        override fun getAdapterAddress(): String {
            Log.v(TAG, "event=binder.getAdapterAddress")
            return adapter?.address ?: ""
        }

        override fun getAdapterName(): String {
            Log.v(TAG, "event=binder.getAdapterName")
            return adapter?.name ?: ""
        }

        override fun getBondedDevicesJson(): String {
            Log.d(TAG, "event=binder.getBondedDevicesJson")
            val arr = JSONArray()
            adapter?.bondedDevices?.forEach { device ->
                arr.put(JSONObject().apply {
                    put("address", device.address)
                    put("name", device.name ?: "")
                    put("type", device.type)
                    put("bondState", device.bondState)
                })
            }
            return arr.toString()
        }

        override fun setDiscoverable(durationSeconds: Int) {
            Log.d(TAG, "event=binder.setDiscoverable duration_s=$durationSeconds")
            if (durationSeconds > 0) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }

        override fun isDiscoverable(): Boolean {
            Log.v(TAG, "event=binder.isDiscoverable")
            return adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
        }

        override fun startAdvertising(tag: String, serviceUuid: String, includeDeviceName: Boolean) {
            Log.d(TAG, "event=binder.startAdvertising tag=$tag uuid=$serviceUuid includeName=$includeDeviceName")
            bleAdvertiser.start(tag, serviceUuid, includeDeviceName)
        }

        override fun stopAdvertising(tag: String) {
            Log.d(TAG, "event=binder.stopAdvertising tag=$tag")
            bleAdvertiser.stop(tag)
        }

        override fun isAdvertising(tag: String): Boolean {
            Log.v(TAG, "event=binder.isAdvertising tag=$tag")
            return bleAdvertiser.isActive(tag)
        }

        override fun connectRfcommOutbound(deviceAddress: String, uuid: String): String {
            Log.d(TAG, "event=binder.connectRfcommOutbound addr=$deviceAddress uuid=$uuid")
            return rfcommManager.connectOutbound(deviceAddress, uuid)
        }

        override fun listenRfcommInbound(serviceName: String, uuid: String): String {
            Log.d(TAG, "event=binder.listenRfcommInbound service=$serviceName uuid=$uuid")
            return rfcommManager.listenInbound(serviceName, uuid)
        }

        override fun closeRfcommSocket(socketId: String) {
            Log.d(TAG, "event=binder.closeRfcommSocket id=$socketId")
            rfcommManager.close(socketId)
        }

        override fun writeRfcommSocket(socketId: String, data: ByteArray) {
            Log.v(TAG, "event=binder.writeRfcommSocket id=$socketId bytes=${data.size}")
            rfcommManager.write(socketId, data)
        }

        override fun isRfcommConnected(socketId: String): Boolean {
            Log.v(TAG, "event=binder.isRfcommConnected id=$socketId")
            return rfcommManager.isConnected(socketId)
        }

        override fun getActiveConnectionsJson(): String {
            Log.d(TAG, "event=binder.getActiveConnectionsJson")
            return rfcommManager.getActiveConnectionsJson()
        }

        override fun getCallSnapshotJson(): String {
            Log.d(TAG, "event=binder.getCallSnapshotJson")
            return hfpClient.snapshotJson()
        }

        override fun acceptCall(deviceAddress: String, flag: Int): Boolean {
            Log.d(TAG, "event=binder.acceptCall addr=$deviceAddress flag=$flag")
            return hfpClient.accept(deviceAddress, flag)
        }

        override fun rejectCall(deviceAddress: String): Boolean {
            Log.d(TAG, "event=binder.rejectCall addr=$deviceAddress")
            return hfpClient.reject(deviceAddress)
        }

        override fun terminateCall(deviceAddress: String, callId: Int): Boolean {
            Log.d(TAG, "event=binder.terminateCall addr=$deviceAddress callId=$callId")
            return hfpClient.terminate(deviceAddress, callId)
        }

        override fun getPrimaryHfpDeviceAddress(): String {
            Log.d(TAG, "event=binder.getPrimaryHfpDeviceAddress")
            return hfpClient.primaryHfpDeviceAddress()
        }

        override fun setHfMicMute(deviceAddress: String, muted: Boolean): Boolean {
            Log.d(TAG, "event=binder.setHfMicMute addr=$deviceAddress muted=$muted")
            return hfpClient.setMicrophoneMute(deviceAddress, muted)
        }

        override fun setActiveSession(label: String) {
            rfcommManager.setActiveSession(label)
        }

        override fun clearActiveSession(label: String) {
            rfcommManager.clearActiveSession(label)
        }

        override fun isAnySessionActive(): Boolean {
            return rfcommManager.isSessionActive()
        }

        override fun notifyPhone(eventCode: Byte, epochNanos: Long): Boolean {
            return try {
                bleWakeService.notify(eventCode, epochNanos)
            } catch (e: Exception) {
                Log.w(TAG, "event=binder.notifyPhone.fail err=${e.message}")
                false
            }
        }

        override fun notifyPhoneWithData(eventCode: Byte, data: Byte, epochNanos: Long): Boolean {
            return try {
                bleWakeService.notify(eventCode, epochNanos, data)
            } catch (e: Exception) {
                Log.w(TAG, "event=binder.notifyPhoneWithData.fail err=${e.message}")
                false
            }
        }

        override fun sendBleHello() {
            try {
                bleWakeService.triggerHello()
                Log.i(TAG, "event=binder.sendBleHello.ok")
            } catch (e: Exception) {
                Log.w(TAG, "event=binder.sendBleHello.fail err=${e.message}")
            }
        }
    }

    override fun onCreate() {
        GT.section("bt.svc.init") {
        val tStart = SystemClock.elapsedRealtime()
        Log.i(TAG, "event=service.onCreate.start")
        super@BtManagerService.onCreate()

        // Lift Android 9+ hidden-API restrictions for this process so reflection
        // on BluetoothHeadsetClientCall.getNumber/getName/getId etc. succeeds.
        // toString() on those objects anonymizes the phone number, so the only
        // way to surface the real caller ID on the glasses is real getter access.
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            Log.i(TAG, "event=hidden_api.bypass.applied")
        } catch (e: Throwable) {
            Log.w(TAG, "event=hidden_api.bypass.failed err=${e.message}")
        }

        val channel = NotificationChannel(
            CHANNEL_ID, "BT Manager", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Manager")
            .setContentText("Managing Bluetooth")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        Log.d(TAG, "event=service.foregroundStarted dt_ms=${SystemClock.elapsedRealtime() - tStart}")

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bluetoothManager.adapter
        Log.d(TAG, "event=service.adapterAcquired enabled=${adapter?.isEnabled} addr=${adapter?.address}")

        bleAdvertiser = BleAdvertiser(adapter) { tag, event, errorCode ->
            when (event) {
                BleAdvertiser.Event.STARTED -> broadcastCallbacks { it.onAdvertisingStarted(tag) }
                BleAdvertiser.Event.FAILED -> broadcastCallbacks { it.onAdvertisingFailed(tag, errorCode) }
                BleAdvertiser.Event.STOPPED -> broadcastCallbacks { it.onAdvertisingStopped(tag) }
            }
        }

        // G3: bidirectional BLE wake channel. Independent GATT server (the
        // HID one is in the listener app); both can coexist per Android docs.
        bleWakeService = BleWakeService(applicationContext, bleAdvertiser)

        // Read real classic BT MAC. BluetoothAdapter.getAddress() returns bogus
        // 02:00:00:00:00:00 without LOCAL_MAC_ADDRESS (can't add to privapp-permissions
        // on this ROM without boot loop). Shell out to `settings` which has access.
        val realMac = try {
            val proc = Runtime.getRuntime().exec(arrayOf("settings", "get", "secure", "bluetooth_address"))
            val result = proc.inputStream.bufferedReader().readLine()?.trim() ?: ""
            proc.waitFor()
            if (result.matches(Regex("[0-9A-Fa-f:]{17}"))) result else "00:00:00:00:00:00"
        } catch (e: Exception) {
            Log.w(TAG, "event=service.realMac.shellFail err=${e.message}")
            "00:00:00:00:00:00"
        }
        bleWakeService.setClassicMacAddress(realMac)
        Log.i(TAG, "event=service.realMac mac=$realMac")
        bleWakeService.setOnRxCallback { code, _, epoch ->
            // Cold-start relaunch: phone asks bt-manager (priv-app) to bring the
            // listener foreground service back up after it was force-stopped/killed.
            if (code == BleWakeEvent.LAUNCH_LISTENER) {
                try {
                    val intent = android.content.Intent()
                        .setComponent(android.content.ComponentName(
                            "com.repository.glasses.listener",
                            "com.repository.glasses.listener.service.ListenerService"))
                        .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    startForegroundService(intent)
                    Log.i(TAG, "event=ble_wake.rx.launchListener epoch=$epoch")
                } catch (e: Exception) {
                    Log.w(TAG, "event=ble_wake.rx.launchListenerFail err=${e.message}")
                }
                return@setOnRxCallback
            }
            // Phone wrote an event to CHAR_RX; promote to an active session so
            // bt-manager keeps RFCOMM alive while the upcoming traffic arrives.
            // Safety timeout (~30 s) auto-clears if nothing actually flows.
            val label = when (code) {
                0x10.toByte() -> "ble_wake_inbound"            // RFCOMM_REQUEST
                0x11.toByte() -> "tts_playback"                // TTS_PENDING
                0x12.toByte() -> "notification_tts"            // NOTIFICATION_PENDING
                else -> "ble_wake_inbound"
            }
            try {
                rfcommManager.setActiveSession(label)
                Log.i(TAG, "event=ble_wake.rx.session label=$label code=$code epoch=$epoch")
            } catch (e: Exception) {
                Log.w(TAG, "event=ble_wake.rx.sessionFail err=${e.message}")
            }
        }
        val wakeOk = bleWakeService.start()
        Log.i(TAG, "event=service.bleWakeStarted ok=$wakeOk")

        rfcommManager = RfcommManager(adapter) { socketId, event, address, name, data, error ->
            when (event) {
                RfcommManager.Event.CONNECTED ->
                    broadcastCallbacks { it.onRfcommConnected(socketId, address, name) }
                RfcommManager.Event.DISCONNECTED ->
                    broadcastCallbacks { it.onRfcommDisconnected(socketId) }
                RfcommManager.Event.DATA ->
                    broadcastCallbacks { it.onRfcommData(socketId, data ?: ByteArray(0)) }
                RfcommManager.Event.ERROR ->
                    broadcastCallbacks { it.onRfcommError(socketId, error ?: "unknown") }
            }
        }

        bondWatcher = BondWatcher { address, name, prevState, newState ->
            broadcastCallbacks { it.onBondStateChanged(address, name, prevState, newState) }
        }

        hfpClient = HfpClientController(applicationContext) { msg -> Log.i(TAG, msg) }
        hfpClient.init(object : HfpClientController.Listener {
            override fun onCallStateChanged(
                deviceAddress: String,
                callId: Int,
                uuid: String,
                state: Int,
                number: String,
                name: String,
                multiParty: Boolean,
                outgoing: Boolean,
            ) {
                broadcastCallbacks {
                    it.onCallStateChanged(
                        deviceAddress, callId, uuid, state, number, name, multiParty, outgoing
                    )
                }
            }
            override fun onCallAudioStateChanged(deviceAddress: String, audioState: Int) {
                broadcastCallbacks { it.onCallAudioStateChanged(deviceAddress, audioState) }
            }
            override fun onHfpConnectionChanged(deviceAddress: String, state: Int) {
                broadcastCallbacks { it.onHfpConnectionChanged(deviceAddress, state) }
            }
        })

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(adapterReceiver, filter)
        bondWatcher.register(this)

        a2dpSink = A2dpSinkController(applicationContext) { msg -> Log.i(TAG, msg) }
        a2dpSink.init(object : A2dpSinkController.Listener {
            override fun onA2dpConnectionChanged(deviceAddress: String, prevState: Int, newState: Int) {
                broadcastCallbacks { it.onA2dpConnectionChanged(deviceAddress, prevState, newState) }
            }
            override fun onA2dpAudioStateChanged(deviceAddress: String, state: Int) {
                broadcastCallbacks { it.onA2dpAudioStateChanged(deviceAddress, state) }
            }
        })

        foldGate = FoldGate(applicationContext) { msg -> Log.i(TAG, msg) }
        profileAutoConnector = ProfileAutoConnector(
            applicationContext, adapter, hfpClient, a2dpSink, foldGate
        ) { msg -> Log.i(TAG, msg) }
        profileAutoConnector.start()
        // FoldGate tracks fold state (vendor.rkd.glasses.is_spread +
        // ACTION_LEG_STATUS_CHANGED). On fold, ProfileAutoConnector drops A2DP +
        // HFP so the BT stack releases hal_bluetooth_lock and the power daemon
        // can freeze; on unfold it reconnects both.
        foldGate.start(object : FoldGate.Listener {
            override fun onFoldChanged(folded: Boolean) {
                Log.i(TAG, "event=fold.changed folded=$folded")
                profileAutoConnector.onFold(folded)
            }
        })

        Log.i(TAG, "event=service.onCreate.done adapter=${adapter?.name ?: "null"} dt_ms=${SystemClock.elapsedRealtime() - tStart}")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "event=service.onBind action=${intent?.action}")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "event=service.onStartCommand flags=$flags startId=$startId")
        return START_STICKY
    }

    override fun onDestroy() {
        GT.section("bt.svc.destroy") {
            val tStart = SystemClock.elapsedRealtime()
            Log.i(TAG, "event=service.onDestroy.start")
            try { unregisterReceiver(adapterReceiver) } catch (_: Exception) {}
            bondWatcher.unregister(this)
            try { bleWakeService.stop() } catch (_: Exception) {}
            bleAdvertiser.stopAll()
            rfcommManager.closeAll()
            try { profileAutoConnector.stop() } catch (_: Exception) {}
            try { foldGate.stop() } catch (_: Exception) {}
            try { a2dpSink.release() } catch (_: Exception) {}
            try { hfpClient.release() } catch (_: Exception) {}
            callbacks.kill()
            Log.i(TAG, "event=service.onDestroy.done dt_ms=${SystemClock.elapsedRealtime() - tStart}")
        }
        super.onDestroy()
    }

    private val callbackLock = Any()
    // Reentrancy state, guarded by callbackLock. A broadcast dispatches each callback
    // via a binder call into the listener app; the app may synchronously call back
    // (e.g. writeRfcommSocket during onRfcommData), and on a write failure that path
    // re-enters broadcastCallbacks to emit onRfcommError. Binder reuses the SAME
    // thread for that nested call, so `synchronized` re-enters instead of blocking and
    // we would call RemoteCallbackList.beginBroadcast() while one is already open --
    // which throws "beginBroadcast() called while already in a broadcast" and drops
    // the write. We instead defer nested broadcasts onto a queue drained by the
    // outer call, preserving delivery + ordering with no nested beginBroadcast().
    private var broadcasting = false
    private val deferredBroadcasts = ArrayDeque<(IBtManagerCallback) -> Unit>()

    private fun broadcastCallbacks(action: (IBtManagerCallback) -> Unit) {
        val tStart = SystemClock.elapsedRealtime()
        val tLock: Long
        synchronized(callbackLock) {
            tLock = SystemClock.elapsedRealtime() - tStart
            if (broadcasting) {
                // Reentrant call on the same (binder-reused) thread, inside an open
                // broadcast. Queue it; the outer call drains it after its dispatch.
                deferredBroadcasts.addLast(action)
                Log.v(TAG_CALLBACKS, "event=broadcast.deferred depth=${deferredBroadcasts.size}")
                return
            }
            broadcasting = true
            try {
                dispatchBroadcast(action, tStart, tLock)
                // Drain any broadcasts queued reentrantly during dispatch, in order.
                while (deferredBroadcasts.isNotEmpty()) {
                    dispatchBroadcast(deferredBroadcasts.removeFirst(), SystemClock.elapsedRealtime(), 0L)
                }
            } finally {
                broadcasting = false
            }
        }
    }

    /** Single begin/finishBroadcast cycle. Must be called with callbackLock held and
     *  `broadcasting == true` so it can never nest. */
    private fun dispatchBroadcast(
        action: (IBtManagerCallback) -> Unit,
        tStart: Long,
        tLock: Long,
    ) {
        val n = callbacks.beginBroadcast()
        var failures = 0
        try {
            for (i in 0 until n) {
                try {
                    action(callbacks.getBroadcastItem(i))
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG_CALLBACKS, "event=broadcast.item.fail i=$i err=${e.message}")
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
        val dt = SystemClock.elapsedRealtime() - tStart
        Log.v(TAG_CALLBACKS, "event=broadcast.done count=$n failures=$failures lock_ms=$tLock total_ms=$dt")
    }
}
