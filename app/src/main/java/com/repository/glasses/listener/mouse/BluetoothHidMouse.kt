package com.repository.glasses.listener.mouse

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
import java.util.LinkedList
import java.util.UUID

/**
 * BLE HID (HID over GATT) mouse implementation.
 *
 * GATT server only -- advertising and bond management are handled by BtManagerService.
 *
 * Report format (6 bytes):
 *   [0] buttons  -- 3 bits (left=0x01, right=0x02, middle=0x04) + 5 padding
 *   [1] X low    -- 16-bit signed LE relative X
 *   [2] X high
 *   [3] Y low    -- 16-bit signed LE relative Y
 *   [4] Y high
 *   [5] scroll   -- 8-bit signed relative scroll wheel
 */
@SuppressLint("MissingPermission")
class BluetoothHidMouse(private val context: Context) {

    companion object {
        private const val TAG = "GlassesMouse"

        val HID_SERVICE_UUID: UUID           = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        val HID_INFORMATION_UUID: UUID       = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
        val HID_REPORT_MAP_UUID: UUID        = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
        val HID_CONTROL_POINT_UUID: UUID     = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
        val HID_REPORT_UUID: UUID            = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        val PROTOCOL_MODE_UUID: UUID         = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
        val REPORT_REFERENCE_UUID: UUID      = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")
        val CCC_DESCRIPTOR_UUID: UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID: UUID       = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID         = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE_UUID: UUID   = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME_UUID: UUID     = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val PNP_ID_UUID: UUID                = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
    }

    interface Listener {
        fun onRegistered(success: Boolean)
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
    }

    var listener: Listener? = null

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var reportCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false

    private val pendingServices = LinkedList<BluetoothGattService>()
    private var addingService = false

    val isConnected: Boolean get() = connectedDevice != null && notificationsEnabled
    val deviceName: String? get() = connectedDevice?.name

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "UNKNOWN($newState)"
            }
            Log.i(TAG, "GATT connection: ${device.name ?: device.address} -> $stateName (status=$status)")

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                    notificationsEnabled = false
                }
            }
            listener?.onConnectionStateChanged(device, newState)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "Service added: ${service.uuid} status=$status")
            addingService = false
            addNextService()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Read: ${characteristic.uuid} offset=$offset")
            val value = characteristic.value ?: ByteArray(0)
            val response = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            Log.d(TAG, "Desc read: ${descriptor.uuid}")
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor.value ?: ByteArray(0))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                val enabled = value.isNotEmpty() && value[0] != 0.toByte()
                if (enabled) {
                    val prev = connectedDevice
                    if (prev != null && prev.address != device.address) {
                        Log.i(TAG, "New HID host ${device.name ?: device.address} takes over from ${prev.name ?: prev.address} -- cancelling previous GATT (LE only; other profiles unaffected)")
                        gattServer?.cancelConnection(prev)
                    }
                    connectedDevice = device
                    notificationsEnabled = true
                    Log.i(TAG, "Notifications ENABLED by ${device.name ?: device.address}")
                    listener?.onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTED)
                } else {
                    if (connectedDevice?.address == device.address) {
                        notificationsEnabled = false
                    }
                    Log.i(TAG, "Notifications DISABLED by ${device.name ?: device.address}")
                }
                descriptor.value = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            Log.d(TAG, "Char write: ${characteristic.uuid}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    fun init(): Boolean {
        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }

        pendingServices.add(buildDeviceInfoService())
        pendingServices.add(buildBatteryService())
        pendingServices.add(buildHidService())
        addNextService()

        return true
    }

    private fun addNextService() {
        if (addingService) return
        val service = pendingServices.poll() ?: run {
            Log.i(TAG, "All GATT services added")
            listener?.onRegistered(true)
            return
        }
        addingService = true
        gattServer?.addService(service)
    }

    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, scroll: Int): Boolean {
        val device = connectedDevice ?: return false
        val server = gattServer ?: return false
        val characteristic = reportCharacteristic ?: return false
        if (!notificationsEnabled) return false

        val clampedDx = dx.coerceIn(-32767, 32767)
        val clampedDy = dy.coerceIn(-32767, 32767)
        val clampedScroll = scroll.coerceIn(-127, 127)

        val report = ByteArray(HidReportDescriptor.REPORT_SIZE)
        report[0] = (buttons and 0x07).toByte()
        report[1] = (clampedDx and 0xFF).toByte()
        report[2] = ((clampedDx shr 8) and 0xFF).toByte()
        report[3] = (clampedDy and 0xFF).toByte()
        report[4] = ((clampedDy shr 8) and 0xFF).toByte()
        report[5] = clampedScroll.toByte()

        characteristic.value = report
        return server.notifyCharacteristicChanged(device, characteristic, false)
    }

    fun disconnect() {
        connectedDevice?.let { gattServer?.cancelConnection(it) }
    }

    fun destroy() {
        gattServer?.close()
        gattServer = null
        connectedDevice = null
        notificationsEnabled = false
        reportCharacteristic = null
        Log.i(TAG, "BluetoothHidMouse destroyed")
    }

    // -- Service builders --

    private fun buildDeviceInfoService(): BluetoothGattService {
        val service = BluetoothGattService(DEVICE_INFO_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val manufacturer = BluetoothGattCharacteristic(
            MANUFACTURER_NAME_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        manufacturer.value = "Repository".toByteArray()
        service.addCharacteristic(manufacturer)

        val pnpId = BluetoothGattCharacteristic(
            PNP_ID_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        pnpId.value = byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x00, 0x00, 0x01)
        service.addCharacteristic(pnpId)
        return service
    }

    private fun buildBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(BATTERY_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val batteryLevel = BluetoothGattCharacteristic(
            BATTERY_LEVEL_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        batteryLevel.value = byteArrayOf(100)
        val ccc = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        ccc.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        batteryLevel.addDescriptor(ccc)
        service.addCharacteristic(batteryLevel)
        return service
    }

    private fun buildHidService(): BluetoothGattService {
        val service = BluetoothGattService(HID_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val hidInfo = BluetoothGattCharacteristic(
            HID_INFORMATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        hidInfo.value = byteArrayOf(0x11, 0x01, 0x00, 0x02)
        service.addCharacteristic(hidInfo)

        val reportMap = BluetoothGattCharacteristic(
            HID_REPORT_MAP_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        reportMap.value = HidReportDescriptor.MOUSE_DESCRIPTOR
        service.addCharacteristic(reportMap)

        val controlPoint = BluetoothGattCharacteristic(
            HID_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )
        service.addCharacteristic(controlPoint)

        val protocolMode = BluetoothGattCharacteristic(
            PROTOCOL_MODE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )
        protocolMode.value = byteArrayOf(0x01)
        service.addCharacteristic(protocolMode)

        reportCharacteristic = BluetoothGattCharacteristic(
            HID_REPORT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        reportCharacteristic!!.value = ByteArray(HidReportDescriptor.REPORT_SIZE)

        val reportRef = BluetoothGattDescriptor(
            REPORT_REFERENCE_UUID,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED
        )
        reportRef.value = byteArrayOf(0x00, 0x01)
        reportCharacteristic!!.addDescriptor(reportRef)

        val ccc = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
        )
        ccc.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        reportCharacteristic!!.addDescriptor(ccc)

        service.addCharacteristic(reportCharacteristic!!)
        return service
    }
}
