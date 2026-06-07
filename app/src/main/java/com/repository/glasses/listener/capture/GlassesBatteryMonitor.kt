package com.repository.glasses.listener.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.repository.glasses.listener.config.GlassesConfig

/**
 * Tracks battery percentage via the sticky ACTION_BATTERY_CHANGED broadcast and
 * mirrors it into [GlassesConfig.batteryPct]. When the percentage crosses the
 * 5% threshold (in either direction), invokes [onCross]. On a downward cross
 * (>=5 -> <5) it also clears [GlassesConfig.onDemandRecordingActive] so a
 * battery recovery does not silently re-enable on-demand recording.
 *
 * Zero polling: the sticky broadcast fires once at registration with the
 * current SoC and then on every kernel update.
 */
class GlassesBatteryMonitor(
    private val onCross: (Int) -> Unit,
) {

    companion object {
        private const val LOW_THRESHOLD = 5
    }

    private val lock = Any()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = (level * 100 / scale).coerceIn(0, 100)

            val crossed: Boolean
            val wentBelow: Boolean
            synchronized(lock) {
                val prev = GlassesConfig.batteryPct
                if (prev == pct) return
                val prevLow = prev < LOW_THRESHOLD
                val nowLow = pct < LOW_THRESHOLD
                crossed = prevLow != nowLow
                wentBelow = !prevLow && nowLow
                GlassesConfig.batteryPct = pct
                if (wentBelow) {
                    GlassesConfig.onDemandRecordingActive = false
                }
            }
            if (crossed) {
                onCross(pct)
            }
        }
    }

    fun start(ctx: Context) {
        try {
            ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {}
    }

    fun stop(ctx: Context) {
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) {}
    }
}
