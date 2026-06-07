package com.repository.glasses.listener.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repository.glasses.listener.bt.BtManagerBridge
import com.repository.glasses.listener.service.ListenerService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "App:BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context.startForegroundService(Intent(context, ListenerService::class.java))
            try {
                val activityIntent = Intent(context,
                    Class.forName("com.repository.glasses.listener.MainActivity")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(activityIntent)
            } catch (_: Exception) {}

            // Fire an unsolicited BLE_HELLO so the phone learns the glasses are
            // up without waiting for a polling cycle. Binds bt-manager,
            // triggers the hello on bind, then unbinds shortly after.
            try {
                val appCtx = context.applicationContext
                val bridge = BtManagerBridge(appCtx)
                bridge.remoteLog = { msg -> Log.d(TAG, msg) }
                val mainHandler = Handler(Looper.getMainLooper())
                bridge.addOnBoundListener {
                    try {
                        val ok = bridge.sendBleHello()
                        Log.i(TAG, "event=boot.ble_hello.tx ok=$ok")
                    } catch (e: Exception) {
                        Log.w(TAG, "event=boot.ble_hello.fail err=${e.message}")
                    }
                    // Unbind a couple of seconds after to release the bt-manager
                    // hold; the listener service rebinds on its own lifecycle.
                    mainHandler.postDelayed({
                        try { bridge.unbind() } catch (_: Exception) {}
                    }, 3000L)
                }
                bridge.bind()
            } catch (e: Exception) {
                Log.w(TAG, "event=boot.ble_hello.bind_fail err=${e.message}")
            }
        }
    }
}
