package com.repository.glasses.listener.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Detects glasses screen ON/OFF to control audio streaming.
 * Rokid AR Lite has no keyguard, so ACTION_SCREEN_ON = fully active.
 */
class ScreenStateReceiver : BroadcastReceiver() {

    interface ScreenStateListener {
        fun onScreenOn()
        fun onScreenOff()
    }

    private var listener: ScreenStateListener? = null

    fun setListener(listener: ScreenStateListener) {
        this.listener = listener
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> listener?.onScreenOn()
            Intent.ACTION_SCREEN_OFF -> listener?.onScreenOff()
        }
    }
}
