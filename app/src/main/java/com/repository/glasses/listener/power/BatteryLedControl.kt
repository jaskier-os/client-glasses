package com.repository.glasses.listener.power

import android.util.Log
import java.io.File

/**
 * Writes the battery-LED arm flag the glasses-power-daemon watches. The daemon
 * (root) owns the actual LED; the app only signals "charging + still, light it"
 * vs "off". Both the app UID and the daemon (root) can read/write this dir
 * (same dir PowerDaemonControl uses for glasses-power.conf).
 */
object BatteryLedControl {
    private const val TAG = "BatteryLedControl"
    private const val FLAG_PATH = "/data/local/diy-overlay/glasses-led-battery-arm"

    @Volatile private var last: Boolean? = null

    /** Idempotent: only writes on change. arm=true -> "1", false -> "0". */
    fun setArmed(arm: Boolean) {
        if (arm == last) return
        try {
            File(FLAG_PATH).parentFile?.mkdirs()
            File(FLAG_PATH).writeText(if (arm) "1" else "0")
            last = arm
            Log.i(TAG, "arm flag -> $arm")
        } catch (e: Exception) {
            Log.w(TAG, "failed to write $FLAG_PATH: ${e.message}")
        }
    }
}
