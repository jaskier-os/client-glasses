package com.repository.glasses.btmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Reads the Rokid wear-detection signal: subscribes to
 * com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED and seeds initial state from
 * the vendor.rkd.glasses.is_take_on system property.
 *
 * Default state is "worn" so behavior before the first signal is permissive.
 * Mirrors the listener app's WearSensor on purpose so bt-manager has its own
 * gate without needing to bind into the listener process.
 */
class WearGate(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    interface Listener { fun onWearChanged(worn: Boolean) }

    companion object {
        const val ACTION_TAKE_STATUS_CHANGED = "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
        private const val EXTRA_TAKE_STATE = "glasses_take_state"
        private const val PROP_NAME = "vendor.rkd.glasses.is_take_on"
    }

    private val appCtx = ctx.applicationContext
    private var listener: Listener? = null
    private var registered = false

    @Volatile var worn: Boolean = readProperty() ?: true
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_TAKE_STATUS_CHANGED) return
            val v = intent.getStringExtra(EXTRA_TAKE_STATE) ?: return
            val w = v == "1"
            if (w == worn) return
            worn = w
            log("WearGate: changed worn=$w")
            try { listener?.onWearChanged(w) } catch (e: Throwable) {
                log("WearGate listener threw: ${e.message}")
            }
        }
    }

    fun start(l: Listener) {
        listener = l
        if (registered) return
        try {
            appCtx.registerReceiver(receiver, IntentFilter(ACTION_TAKE_STATUS_CHANGED))
            registered = true
            log("WearGate registered initial=$worn")
        } catch (e: Throwable) {
            log("WearGate register failed: ${e.message}")
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
            "1" -> true
            "0" -> false
            else -> null
        }
    } catch (_: Throwable) { null }
}
