package com.repository.glasses.btmanager.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.repository.glasses.btmanager.BtManagerService

class BtBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BtMgr:Svc"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "event=boot.onReceive action=${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "event=boot.startService")
            context.startForegroundService(Intent(context, BtManagerService::class.java))
        }
    }
}
