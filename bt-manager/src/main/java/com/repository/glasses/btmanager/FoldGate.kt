package com.repository.glasses.btmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Reads the Rokid fold-detection signal: subscribes to
 * com.rokid.sprite.ACTION_LEG_STATUS_CHANGED and seeds initial state from
 * the vendor.rkd.glasses.is_spread system property.
 *
 * Fold is the canonical "off-head, not in use" signal for power management.
 * When folded, bt-manager drops A2DP + HFP so the BT stack releases its kernel
 * wakelock (hal_bluetooth_lock) and glasses-power-daemon can enter freeze.
 *
 * Default state is "unfolded" so behavior before the first signal is permissive
 * (profiles allowed to connect). Self-contained so bt-manager owns its own fold
 * gate without binding into the listener process.
 *
 * Polarity: is_spread / glasses_leg_state "1" = spread = UNFOLDED, "0" = FOLDED.
 */
class FoldGate(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    interface Listener { fun onFoldChanged(folded: Boolean) }

    companion object {
        const val ACTION_LEG_STATUS_CHANGED = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"
        private const val EXTRA_LEG_STATE = "glasses_leg_state"
        private const val PROP_NAME = "vendor.rkd.glasses.is_spread"
    }

    private val appCtx = ctx.applicationContext
    private var listener: Listener? = null
    private var registered = false

    @Volatile var folded: Boolean = readProperty() ?: false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_LEG_STATUS_CHANGED) return
            val raw = intent.getStringExtra(EXTRA_LEG_STATE) ?: return
            val f = when (raw) {
                "1" -> false
                "0" -> true
                else -> {
                    log("FoldGate: malformed glasses_leg_state=$raw")
                    return
                }
            }
            if (f == folded) return
            folded = f
            log("FoldGate: changed folded=$f")
            try { listener?.onFoldChanged(f) } catch (e: Throwable) {
                log("FoldGate listener threw: ${e.message}")
            }
        }
    }

    fun start(l: Listener) {
        listener = l
        if (registered) return
        try {
            appCtx.registerReceiver(receiver, IntentFilter(ACTION_LEG_STATUS_CHANGED))
            registered = true
            log("FoldGate registered initial folded=$folded")
        } catch (e: Throwable) {
            log("FoldGate register failed: ${e.message}")
        }
    }

    fun stop() {
        try { if (registered) appCtx.unregisterReceiver(receiver) } catch (_: Throwable) {}
        registered = false
        listener = null
    }

    private fun readProperty(): Boolean? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val m = cls.getMethod("get", String::class.java, String::class.java)
        when (m.invoke(null, PROP_NAME, "") as String) {
            "1" -> false
            "0" -> true
            else -> null
        }
    } catch (_: Throwable) { null }
}
