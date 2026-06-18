package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.repository.glasses.tracing.GT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bidirectional BLE wake channel (Phase G3).
 *
 * Hosts a small GATT service with two characteristics:
 *   CHAR_TX  -- glasses NOTIFY -> phone
 *   CHAR_RX  -- phone WRITE     -> glasses
 *
 * Payload (10 bytes, little-endian):
 *   [0]   event_code (byte)
 *   [1]   reserved (0)
 *   [2..9] epochNanos (i64 LE)
 *
 * Event codes (mirrored on phone in BleWakeEvent):
 *   0x01 WAKE_WORD            (glasses -> phone)
 *   0x02 BUTTON_PRESS         (glasses -> phone)
 *   0x03 WEAR_CHANGED         (glasses -> phone)
 *   0x10 RFCOMM_REQUEST       (either direction)
 *   0x11 TTS_PENDING          (phone -> glasses)
 *   0x12 NOTIFICATION_PENDING (phone -> glasses)
 */
@SuppressLint("MissingPermission")
class BleWakeService(
    private val context: Context,
    private val bleAdvertiser: BleAdvertiser
) {

    companion object {
        private const val TAG = "BtMgr:BleWake"
        const val ADVERTISE_TAG = "ble_wake"

        const val SERVICE_UUID  = "c0de0001-cafe-beef-0000-000000000001"

        /** Magic prefix in BLE service data: "REPO" (4 bytes) + classic BT MAC (6 bytes).
         *  Phone uses magic to identify our glasses, and MAC to connect RFCOMM directly
         *  without needing GATT discovery. */
        private val MAGIC_PREFIX = byteArrayOf(0x52, 0x45, 0x50, 0x4F) // "REPO"
        const val CHAR_TX_UUID  = "c0de0002-cafe-beef-0000-000000000001"
        const val CHAR_RX_UUID  = "c0de0003-cafe-beef-0000-000000000001"
        const val CCCD_UUID     = "00002902-0000-1000-8000-00805f9b34fb"

        const val PAYLOAD_SIZE = 10
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var charTx: BluetoothGattCharacteristic? = null
    private var charRx: BluetoothGattCharacteristic? = null

    /** Devices that have enabled notifications on CHAR_TX via CCCD write. */
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    /** Connected (but not necessarily subscribed) GATT clients. */
    private val connectedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    /**
     * Set on every STATE_CONNECTED. Cleared after BLE_HELLO is sent on the
     * subsequent CCCD subscribe (or via triggerHello() when CCCD is already on).
     */
    @Volatile
    private var pendingHello: Boolean = false

    @Volatile
    private var rxCallback: ((Byte, Byte, Long) -> Unit)? = null

    fun setOnRxCallback(cb: (eventCode: Byte, data: Byte, epochNanos: Long) -> Unit) {
        rxCallback = cb
    }

    /** Main-thread Handler used to retry start() after openGattServer
     *  fails at boot (BT stack still warming up). Runs on main looper to
     *  avoid races with stop() which also runs on main thread. */
    private val retryHandler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }
    @Volatile private var retryAttempt = 0
    @Volatile private var advRetryAttempt = 0
    private val RETRY_BASE_MS = 1_000L
    private val RETRY_MAX_MS = 30_000L

    fun start(): Boolean = GT.section("bt.ble_wake.start") {
        if (gattServer != null) {
            Log.w(TAG, "event=ble_wake.start.alreadyRunning")
            return@section true
        }
        val server = bluetoothManager.openGattServer(context, gattCallback)
        if (server == null) {
            Log.e(TAG, "event=ble_wake.start.openFail attempt=$retryAttempt")
            // Schedule a retry. openGattServer routinely returns null in the
            // first ~10 s after boot (BT stack not ready yet). Without this,
            // bt-manager comes up, fails once, and the GATT server stays
            // null forever -- phone's BleWakeNotifyClient gets connection
            // timeouts (status=147) and there's no path to wake the
            // listener for status / RFCOMM_REQUEST events.
            val delay = (RETRY_BASE_MS shl retryAttempt.coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)
            retryAttempt += 1
            retryHandler.postDelayed({
                if (gattServer == null) start()
            }, delay)
            return@section false
        }
        retryAttempt = 0
        gattServer = server

        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val tx = BluetoothGattCharacteristic(
            UUID.fromString(CHAR_TX_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0  // no read/write perms; notify only
        )
        val cccdTx = BluetoothGattDescriptor(
            UUID.fromString(CCCD_UUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        cccdTx.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        tx.addDescriptor(cccdTx)
        service.addCharacteristic(tx)
        charTx = tx

        val rx = BluetoothGattCharacteristic(
            UUID.fromString(CHAR_RX_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(rx)
        charRx = rx

        val added = server.addService(service)
        Log.i(TAG, "event=ble_wake.start added=$added uuid=$SERVICE_UUID")

        // Tell the BLE advertiser to advertise our service UUID under our tag.
        // BLE subsystem can lag behind GATT -- retry if advertiser is not ready.
        startAdvertising()
        return@section true
    }

    @Volatile
    private var classicMacAddress: String = "00:00:00:00:00:00"

    /** Called by BtManagerService with the real adapter address. */
    fun setClassicMacAddress(mac: String) {
        classicMacAddress = mac
        Log.i(TAG, "event=ble_wake.macSet mac=$mac")
    }

    private fun buildServiceData(): ByteArray {
        // "REPO" (4 bytes) + classic BT MAC (6 bytes) + pairing flag (1 byte) so the phone
        // can RFCOMM-connect directly from the BLE scan result without GATT discovery, AND
        // tell whether this unit is currently available for pairing.
        // MAC is written by deploy script to /data/local/tmp/glasses_bt_mac
        // (all other approaches blocked by ROM permission checks).
        if (classicMacAddress == "00:00:00:00:00:00") {
            try {
                val mac = java.io.File("/data/local/tmp/glasses_bt_mac").readText().trim()
                if (mac.matches(Regex("[0-9A-Fa-f:]{17}")) && mac != "02:00:00:00:00:00") {
                    classicMacAddress = mac
                    Log.i(TAG, "event=ble_wake.macResolved source=file mac=$mac")
                }
            } catch (e: Exception) {
                Log.w(TAG, "event=ble_wake.macResolve.fail err=${e.message}")
            }
        }
        val pairingFlag = if (isPairingAvailable()) 0x01.toByte() else 0x00.toByte()
        Log.d(TAG, "event=ble_wake.serviceData mac=$classicMacAddress pairing=$pairingFlag")
        val macBytes = classicMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray()
        return MAGIC_PREFIX + macBytes + byteArrayOf(pairingFlag)
    }

    /**
     * Whether this glasses is currently advertising itself as available for pairing. True when
     * it has no bonded host yet (a freshly flashed unit) OR the user opened a pairing window on
     * the glasses. An already-paired unit returns false, so the phone's "Pair" scan ignores it
     * and only bonds a unit that is genuinely waiting to be paired. This is what lets the phone
     * pick the NEW glasses when an old, still-paired unit is also in range advertising the same
     * name.
     */
    private fun isPairingAvailable(): Boolean {
        if (pairingWindowUntilMs > android.os.SystemClock.elapsedRealtime()) return true
        return try {
            val adapter = bluetoothManager.adapter ?: return true
            (adapter.bondedDevices?.size ?: 0) == 0
        } catch (_: Exception) { true }
    }

    /** Set by setDiscoverable(): opens a timed window during which this unit advertises
     *  pairing=1 even if it already has a bond (user explicitly re-pairing on the glasses). */
    @Volatile private var pairingWindowUntilMs: Long = 0L

    fun openPairingWindow(durationSeconds: Int) {
        pairingWindowUntilMs = android.os.SystemClock.elapsedRealtime() + durationSeconds * 1000L
        Log.i(TAG, "event=ble_wake.pairingWindow open duration_s=$durationSeconds")
        // Re-advertise immediately so the flag flips without waiting for the next refresh.
        try { startAdvertising() } catch (_: Exception) {}
    }

    private fun startAdvertising() {
        bleAdvertiser.start(ADVERTISE_TAG, SERVICE_UUID, includeDeviceName = false, serviceData = buildServiceData())
        if (!bleAdvertiser.isActive(ADVERTISE_TAG)) {
            val delay = (RETRY_BASE_MS shl advRetryAttempt.coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)
            advRetryAttempt += 1
            Log.w(TAG, "event=ble_wake.adv.retryScheduled attempt=$advRetryAttempt delay_ms=$delay")
            retryHandler.postDelayed({
                if (gattServer != null && !bleAdvertiser.isActive(ADVERTISE_TAG)) {
                    startAdvertising()
                }
            }, delay)
        } else {
            advRetryAttempt = 0
        }
    }

    fun stop() = GT.section("bt.ble_wake.stop") {
        retryHandler.removeCallbacksAndMessages(null)
        retryAttempt = 0
        advRetryAttempt = 0
        bleAdvertiser.stop(ADVERTISE_TAG)
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        charTx = null
        charRx = null
        subscribers.clear()
        connectedDevices.clear()
        Log.i(TAG, "event=ble_wake.stopped")
    }

    /**
     * Push a 10-byte wake-event payload via NOTIFY on CHAR_TX to every subscribed device.
     * Returns true iff at least one notify call to a subscribed device returned true.
     */
    fun notify(eventCode: Byte, epochNanos: Long, data: Byte = 0): Boolean = GT.section("bt.ble_wake.notify") {
        GT.counter("bt.ble_wake.last_code", eventCode.toLong())
        val server = gattServer ?: run {
            // Self-heal: if openGattServer returned null at boot (BT stack
            // wasn't ready, permission race, etc.), the server stays null
            // forever and every notify falls into noServer. Try to start
            // again now -- by the time the first event happens the BT
            // stack is reliably up. start() is idempotent and cheap when
            // already running.
            Log.w(TAG, "event=ble_wake.notify.noServer code=$eventCode -- retrying start()")
            val ok = start()
            val retried = gattServer
            if (!ok || retried == null) {
                Log.w(TAG, "event=ble_wake.notify.recoverFailed code=$eventCode")
                return@section false
            }
            retried
        }
        val ch = charTx ?: run {
            Log.w(TAG, "event=ble_wake.notify.noChar code=$eventCode")
            return@section false
        }
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(eventCode)
            put(data)
            putLong(epochNanos)
        }.array()
        ch.value = payload
        if (subscribers.isEmpty()) {
            Log.d(TAG, "event=ble_wake.notify.noSubscribers code=$eventCode")
            return@section false
        }
        var anyOk = false
        for (dev in subscribers) {
            try {
                @Suppress("DEPRECATION")
                val ok = server.notifyCharacteristicChanged(dev, ch, false)
                Log.i(TAG, "event=ble_wake.notify.tx code=$eventCode dev=${dev.address} ok=$ok")
                if (ok) anyOk = true
            } catch (e: Exception) {
                Log.w(TAG, "event=ble_wake.notify.fail dev=${dev.address} err=${e.message}")
            }
        }
        return@section anyOk
    }

    /**
     * Public hook so external code (boot receiver, RFCOMM accept, etc.) can
     * request that a BLE_HELLO be emitted to the connected central.
     *
     * If at least one subscriber is already active, the hello is sent
     * immediately; otherwise the pending flag is armed and the hello will be
     * flushed the next time CCCD-enable arrives.
     */
    fun triggerHello() {
        pendingHello = true
        if (subscribers.isNotEmpty()) {
            pendingHello = false
            try {
                val ok = notify(BleWakeEvent.BLE_HELLO, System.nanoTime(), 0)
                Log.i(TAG, "event=ble_wake.hello.tx reason=trigger ok=$ok subs=${subscribers.size}")
            } catch (e: Exception) {
                Log.w(TAG, "event=ble_wake.hello.fail err=${e.message}")
            }
        } else {
            Log.i(TAG, "event=ble_wake.hello.armed reason=trigger noSubs=true")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = GT.section("bt.ble_wake.conn_state") {
            Log.i(TAG, "event=ble_wake.conn dev=${device.address} status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                // Mark a pending hello; it will be flushed once the central
                // subscribes to CHAR_TX (CCCD enable) below.
                pendingHello = true
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                subscribers.remove(device)
            }
            GT.counter("bt.ble_wake.subscribers", subscribers.size.toLong())
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "event=ble_wake.serviceAdded uuid=${service.uuid} status=$status")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) = GT.section("bt.ble_wake.rx") {
            val server = gattServer
            if (characteristic.uuid == UUID.fromString(CHAR_RX_UUID)) {
                if (value.size >= PAYLOAD_SIZE) {
                    val bb = ByteBuffer.wrap(value, 0, PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                    val code = bb.get()
                    val data = bb.get()
                    val epoch = bb.long
                    Log.i(TAG, "event=ble_wake.notify.rx code=$code data=$data epoch=$epoch dev=${device.address}")
                    if (code == BleWakeEvent.BLE_PING) {
                        // Reply immediately with PONG, echoing the request id (data byte) so
                        // the phone can correlate. Use System.nanoTime() as a monotonic stamp.
                        try {
                            val ok = notify(BleWakeEvent.BLE_PONG, System.nanoTime(), data)
                            Log.i(TAG, "event=ble_wake.pong.tx reqId=$data ok=$ok dev=${device.address}")
                        } catch (e: Exception) {
                            Log.w(TAG, "event=ble_wake.pong.fail err=${e.message}")
                        }
                    }
                    try { rxCallback?.invoke(code, data, epoch) } catch (e: Exception) {
                        Log.w(TAG, "event=ble_wake.rx.cbFail err=${e.message}")
                    }
                } else {
                    Log.w(TAG, "event=ble_wake.rx.shortPayload size=${value.size}")
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == UUID.fromString(CCCD_UUID)) {
                val enabled = value.isNotEmpty() && value[0] != 0.toByte()
                if (enabled) {
                    subscribers.add(device)
                    Log.i(TAG, "event=ble_wake.subscribed dev=${device.address} count=${subscribers.size}")
                    if (pendingHello) {
                        pendingHello = false
                        try {
                            val ok = notify(BleWakeEvent.BLE_HELLO, System.nanoTime(), 0)
                            Log.i(TAG, "event=ble_wake.hello.tx reason=cccd_enable ok=$ok dev=${device.address}")
                        } catch (e: Exception) {
                            Log.w(TAG, "event=ble_wake.hello.fail err=${e.message}")
                        }
                    }
                } else {
                    subscribers.remove(device)
                    Log.i(TAG, "event=ble_wake.unsubscribed dev=${device.address} count=${subscribers.size}")
                }
                descriptor.value = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val v = descriptor.value ?: ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
        }
    }
}
