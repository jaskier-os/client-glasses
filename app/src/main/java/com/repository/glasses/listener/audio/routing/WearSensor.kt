package com.repository.glasses.listener.audio.routing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Wear detection on Rokid AR Lite glasses.
 *
 * Canonical signal chain:
 *   PSoC psensor driver -> kernel extcon uevent (JIG=0/1)
 *     -> Rokid's PsensorObserver (in RokidSysConfig.apk)
 *     -> broadcast `com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED`
 *     -> property `vendor.rkd.glasses.is_take_on`.
 *
 * We listen for the broadcast (zero polling, debounced by the kernel/PsensorObserver)
 * and read the property once at start() for the initial state.
 *
 * Prerequisites on device:
 *   - `enforce_psensor` / `enforce_hall` sysfs flags must be 0, otherwise the
 *     extcon bits latch and PsensorObserver never fires. glasses-power-daemon
 *     writes "0" to both at startup.
 *   - `persist.rkd.enablePsensor` must not be explicitly "false" (default is
 *     true; an empty value still loads PsensorObserver).
 */
class WearSensor(
    private val ctx: Context,
    private val log: (String) -> Unit = {},
) {
    interface Listener { fun onWearChanged(wearing: Boolean) }

    @Volatile private var listener: Listener? = null
    @Volatile private var lastStable: Boolean? = null
    private var receiver: BroadcastReceiver? = null

    private fun readInitial(): Boolean? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            val v = m.invoke(null, "vendor.rkd.glasses.is_take_on", "") as String
            when (v) {
                "1" -> true
                "0" -> false
                else -> null
            }
        } catch (t: Throwable) {
            log("WearSensor readInitial failed: ${t.message}")
            null
        }
    }

    /**
     * Force the internal stable-state baseline to [b]. Used when an external
     * source (debug broadcast, manual sync) already applied the wear transition
     * so the receiver won't re-emit a redundant onWearChanged.
     */
    fun setLastStable(b: Boolean) {
        lastStable = b
    }

    fun start(l: Listener) {
        if (receiver != null) return
        listener = l

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val raw = intent.getStringExtra("glasses_take_state")
                val worn = when (raw) {
                    "1" -> true
                    "0" -> false
                    else -> {
                        log("WearSensor received malformed glasses_take_state=$raw")
                        return
                    }
                }
                log("WearSensor broadcast worn=$worn raw=$raw")
                if (lastStable == worn) return
                lastStable = worn
                listener?.onWearChanged(worn)
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            ctx,
            r,
            IntentFilter(ACTION_TAKE_STATUS_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        log("WearSensor started (listening for $ACTION_TAKE_STATUS_CHANGED)")

        readInitial()?.let { worn ->
            if (lastStable != worn) {
                lastStable = worn
                log("WearSensor initial property is_take_on=$worn")
                listener?.onWearChanged(worn)
            }
        }
    }

    fun stop() {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        listener = null
        log("WearSensor stopped")
    }

    companion object {
        private const val ACTION_TAKE_STATUS_CHANGED =
            "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
    }
}
