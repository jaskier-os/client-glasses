package com.repository.glasses.listener.audio.routing

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PairingAutoAcceptReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        Log.i("PairingAutoAccept", "PAIRING_REQUEST device=${device.address} variant=$variant")
        try {
            // For passkey/consent variants, confirm true
            device.setPairingConfirmation(true)
            Log.i("PairingAutoAccept", "setPairingConfirmation(true) for ${device.address}")
        } catch (t: Throwable) {
            Log.w("PairingAutoAccept", "setPairingConfirmation failed: ${t.message}")
        }
        // For numeric-entry variants, set pin too
        try {
            val pin = (0..3).map { 0 }.fold(ByteArray(0)) { acc, _ -> acc + '0'.code.toByte() }
            device.setPin(pin)
        } catch (_: Throwable) {}
        abortBroadcast()
    }
}
