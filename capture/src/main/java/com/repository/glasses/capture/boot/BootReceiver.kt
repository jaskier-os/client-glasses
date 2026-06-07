package com.repository.glasses.capture.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.repository.glasses.capture.CaptureService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, CaptureService::class.java))
        }
    }
}
