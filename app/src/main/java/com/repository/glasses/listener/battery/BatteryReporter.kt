package com.repository.glasses.listener.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Subscribes to ACTION_BATTERY_CHANGED (sticky) and emits a callback every
 * time the integer percentage value crosses to a new step. Zero polling --
 * the broadcast itself fires once on registration with the current SoC
 * and then on every kernel update.
 */
class BatteryReporter(
    private val context: Context,
    private val onLevelChanged: (level: Int, epochNanos: Long) -> Unit,
) {
    private var lastReportedLevel: Int = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = (level * 100 / scale).coerceIn(0, 100)
            if (pct != lastReportedLevel) {
                lastReportedLevel = pct
                onLevelChanged(pct, System.nanoTime())
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
    }
}
