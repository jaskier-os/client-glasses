package com.repository.glasses.listener.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.repository.glasses.listener.util.LogCollector

/**
 * Dual-purpose accessibility service:
 *  1. Screen-off trigger -- performs GLOBAL_ACTION_LOCK_SCREEN on broadcast.
 *  2. System-wide function-button capture -- [onKeyEvent] intercepts KEYCODE_CAMERA
 *     regardless of foreground activity and forwards it to the listener backend via a
 *     local broadcast, so the function button drives photo/video even when Rokid's home,
 *     another app, or the screen-off state has focus instead of our MainActivity.
 *
 * Enabled automatically by deploy-to-glasses.sh via
 *   `settings put secure enabled_accessibility_services <pkg>/<service>`.
 */
class ScreenOffAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_LOCK_SCREEN = "com.repository.glasses.listener.ACTION_LOCK_SCREEN"
        /** Broadcast carrying a function-button event. Extras: [EXTRA_EVENT_ACTION] = "DOWN"|"UP", [EXTRA_REPEAT] = Int. */
        const val ACTION_FN_KEY = "com.repository.glasses.listener.ACTION_FN_KEY"
        const val EXTRA_EVENT_ACTION = "ev_action"
        const val EXTRA_REPEAT = "ev_repeat"
        private const val TAG = "ScreenOffA11y"
    }

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LogCollector.i(TAG, "Lock screen requested via broadcast")
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(lockReceiver, IntentFilter(ACTION_LOCK_SCREEN), Context.RECEIVER_NOT_EXPORTED)
        LogCollector.i(TAG, "Accessibility service connected (key filter + lock-screen)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Fires for every hardware key system-wide as long as the service config has
     * `flagRequestFilterKeyEvents` + `canRequestFilterKeyEvents`. Return true to consume
     * so Rokid OS's default camera handler doesn't also react.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.keyCode != KeyEvent.KEYCODE_CAMERA) return false
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> return false
        }
        try {
            sendBroadcast(Intent(ACTION_FN_KEY).apply {
                setPackage(packageName)
                putExtra(EXTRA_EVENT_ACTION, action)
                putExtra(EXTRA_REPEAT, event.repeatCount)
            })
        } catch (e: Exception) {
            LogCollector.w(TAG, "ACTION_FN_KEY broadcast failed: ${e.message}")
        }
        return true
    }

    override fun onDestroy() {
        try { unregisterReceiver(lockReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
