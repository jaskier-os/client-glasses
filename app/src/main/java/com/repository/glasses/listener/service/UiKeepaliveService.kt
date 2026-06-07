package com.repository.glasses.listener.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Anchors the UI process at the foreground-service oom_adj tier so the
 * lowmemorykiller leaves it alone when the camera HAL + encoder consume
 * most of the device's CPU and RAM during recording. ListenerService and
 * the capture priv-app already hold this priority via their own
 * foreground services -- without this, the UI process drops to the HOME
 * tier the moment focus shifts and gets evicted, then ANRs trying to
 * relaunch under CPU pressure.
 */
class UiKeepaliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Glasses UI", NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_invisible)
            .setContentTitle("Glasses")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .setSilent(true)
            .build()
        startForeground(NOTIFICATION_ID, n)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "ui_keepalive"
        private const val NOTIFICATION_ID = 1201
    }
}
