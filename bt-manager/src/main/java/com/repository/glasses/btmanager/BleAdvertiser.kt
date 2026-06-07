package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleAdvertiser(
    private val adapter: BluetoothAdapter?,
    private val listener: (tag: String, event: Event, errorCode: Int) -> Unit
) {

    enum class Event { STARTED, FAILED, STOPPED }

    companion object {
        private const val TAG = "BtMgr:Ble"
    }

    private val activeCallbacks = ConcurrentHashMap<String, AdvertiseCallback>()

    fun start(tag: String, serviceUuid: String, includeDeviceName: Boolean, serviceData: ByteArray? = null) = GT.section("bt.adv.start") {
        val tStart = SystemClock.elapsedRealtime()
        Log.d(TAG, "event=ble.start.request tag=$tag uuid=$serviceUuid includeName=$includeDeviceName hasData=${serviceData != null}")
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "event=ble.start.noAdvertiser tag=$tag")
            listener(tag, Event.FAILED, -1)
            return@section
        }
        if (activeCallbacks.containsKey(tag)) {
            Log.w(TAG, "event=ble.start.alreadyActive tag=$tag")
            return@section
        }

        // Tuned for power: glasses-phone link is short-range and doesn't need the radio at full
        // strength; 1 s advertise interval is fast enough for first-pair / reconnect after the
        // phone moves out and back. LOW_POWER ~1000 ms interval (vs LOW_LATENCY ~100 ms),
        // TX_POWER_LOW vs HIGH cuts radio current substantially. Still connectable for GATT.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .build()

        val puuid = ParcelUuid(UUID.fromString(serviceUuid))
        // Main advertisement: just the service UUID (fits in 31 bytes)
        val data = AdvertiseData.Builder()
            .addServiceUuid(puuid)
            .build()

        // Scan response: service data (magic marker) and optionally device name.
        // Phone verifies the magic to positively identify our glasses.
        val scanResponseBuilder = AdvertiseData.Builder()
        if (serviceData != null) {
            scanResponseBuilder.addServiceData(puuid, serviceData)
        }
        if (includeDeviceName) {
            scanResponseBuilder.setIncludeDeviceName(true)
        }
        val scanResponse = scanResponseBuilder.build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "event=ble.start.ok tag=$tag uuid=$serviceUuid includeName=$includeDeviceName dt_ms=${SystemClock.elapsedRealtime() - tStart}")
                listener(tag, Event.STARTED, 0)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "event=ble.start.fail tag=$tag error=$errorCode dt_ms=${SystemClock.elapsedRealtime() - tStart}")
                activeCallbacks.remove(tag)
                listener(tag, Event.FAILED, errorCode)
            }
        }

        activeCallbacks[tag] = callback
        Log.d(TAG, "event=ble.start.invoke tag=$tag hasServiceData=${serviceData != null}")
        advertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    fun stop(tag: String) = GT.section("bt.adv.stop") {
        Log.d(TAG, "event=ble.stop.request tag=$tag")
        val callback = activeCallbacks.remove(tag) ?: run {
            Log.d(TAG, "event=ble.stop.notActive tag=$tag")
            return@section
        }
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        Log.i(TAG, "event=ble.stop.ok tag=$tag")
        listener(tag, Event.STOPPED, 0)
    }

    fun isActive(tag: String): Boolean = activeCallbacks.containsKey(tag)

    fun stopAll() {
        val tags = activeCallbacks.keys.toList()
        tags.forEach { stop(it) }
    }
}
