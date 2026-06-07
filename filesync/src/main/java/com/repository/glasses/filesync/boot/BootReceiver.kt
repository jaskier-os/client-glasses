package com.repository.glasses.filesync.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.repository.glasses.filesync.FileSyncService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, FileSyncService::class.java))
        }
    }
}
