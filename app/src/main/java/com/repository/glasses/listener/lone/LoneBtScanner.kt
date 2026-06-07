package com.repository.glasses.listener.lone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread

/**
 * Glasses-side continuous Bluetooth scanner for Lone mode.
 *
 * Phase-0 spike (see plan) verified on this hardware that both an in-process BLE scan and
 * classic discovery work AND that classic discovery does NOT disrupt A2DP audio. So we run:
 *   - ONE held-open BLE scan (started once, never stop/restart -> avoids the >5/30s scan park),
 *   - periodic classic-discovery windows (each ~12s, auto-finishing), spaced by [CLASSIC_GAP_MS].
 *
 * Every discovered device is reported to [onDevice]; deduplication / aging lives in the controller.
 */
class LoneBtScanner(
    private val context: Context,
    private val onDevice: (address: String, name: String?, rssi: Int) -> Unit,
    private val log: (String) -> Unit
) {
    companion object {
        private const val CLASSIC_GAP_MS = 8_000L  // idle gap between classic discovery windows
        private const val NO_RSSI = Short.MIN_VALUE.toInt()
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    @Volatile private var running = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device?.address ?: return
            val name = try { result.device?.name } catch (_: SecurityException) { null }
            onDevice(addr, name, result.rssi)
        }
        override fun onScanFailed(errorCode: Int) {
            log("LoneScanner: BLE scan failed code=$errorCode")
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, NO_RSSI.toShort()).toInt()
                    val name = try { device.name } catch (_: SecurityException) { null }
                    onDevice(device.address, name, rssi)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Space out the next window so we are not discovering back-to-back.
                    if (running) handler?.postDelayed({ startClassicWindow() }, CLASSIC_GAP_MS)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            log("LoneScanner: adapter null or disabled, scan not started")
            return
        }
        running = true
        // Classic discovery loop on its own thread.
        thread = HandlerThread("lone-scanner").also { it.start() }
        handler = Handler(thread!!.looper)
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(classicReceiver, filter)
        // Held-open BLE scan (balanced mode = continuous-friendly power profile).
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            a.bluetoothLeScanner?.startScan(null, settings, bleCallback)
            log("LoneScanner: BLE held-open scan started")
        } catch (e: Exception) {
            log("LoneScanner: BLE start failed: ${e.message}")
        }
        handler?.post { startClassicWindow() }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicWindow() {
        if (!running) return
        val a = adapter ?: return
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: Exception) {
            log("LoneScanner: classic startDiscovery failed: ${e.message}")
            handler?.postDelayed({ startClassicWindow() }, CLASSIC_GAP_MS)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        try { adapter?.bluetoothLeScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        try { if (adapter?.isDiscovering == true) adapter?.cancelDiscovery() } catch (_: Exception) {}
        try { context.unregisterReceiver(classicReceiver) } catch (_: Exception) {}
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        log("LoneScanner: stopped")
    }
}
