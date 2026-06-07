package com.repository.glasses.btmanager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

@SuppressLint("MissingPermission")
class BondWatcher(
    private val listener: (address: String, name: String, prevState: Int, newState: Int) -> Unit
) {

    companion object {
        private const val TAG = "BtMgr:Bond"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            Log.i(TAG, "event=bond.stateChanged addr=${device.address} name=${device.name} prev=$prev state=$state")
            listener(device.address, device.name ?: "", prev, state)
        }
    }

    fun register(context: Context) {
        Log.d(TAG, "event=bond.register")
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    fun unregister(context: Context) {
        Log.d(TAG, "event=bond.unregister")
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
