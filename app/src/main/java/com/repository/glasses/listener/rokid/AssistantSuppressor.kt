package com.repository.glasses.listener.rokid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class AssistantSuppressor(
    private val context: Context,
    private val log: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "AssistantSuppressor"
        private const val ACTION_LONG_PRESS = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        private const val ACTION_BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        private const val ACTION_AI_START = "com.android.action.ACTION_AI_START"
        private const val ASSIST_PACKAGE = "com.rokid.os.sprite.assistserver"
    }

    var onSensorLongPress: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            log("Intercepted Rokid action: $action")
            // Only suppress AI assistant launch, let button actions (photo/video) through
            if (action == ACTION_AI_START) {
                try {
                    abortBroadcast()
                    log("AI_START broadcast aborted")
                } catch (_: Exception) {
                    log("abortBroadcast failed (not an ordered broadcast)")
                }
            } else {
                log("Letting button action through: $action")
            }
            if (action == ACTION_AI_START) {
                onSensorLongPress?.invoke()
            }
        }
    }

    private var registered = false

    fun suppress() {
        val filter = IntentFilter().apply {
            addAction(ACTION_LONG_PRESS)
            addAction(ACTION_BUTTON_UP)
            addAction(ACTION_AI_START)
            priority = 999
        }
        context.registerReceiver(receiver, filter)
        registered = true
        log("Broadcast receiver registered (priority=999)")
    }

    fun release() {
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
            registered = false
        }
    }
}
