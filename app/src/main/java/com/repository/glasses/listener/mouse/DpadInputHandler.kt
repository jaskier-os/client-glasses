package com.repository.glasses.listener.mouse

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.repository.glasses.tracing.GT

/**
 * DPAD input state machine for mouse gestures.
 *
 * Tap disambiguation on DPAD_CENTER:
 *   Single tap  -> left click  (after 200ms double-tap window)
 *   Double tap  -> right click  (within 200ms window)
 *
 * Long press is handled externally via Rokid OS broadcast (ACTION_AI_START),
 * NOT through key event timing.
 *
 * DPAD directions -> scroll when tracking is ON.
 * When tracking is OFF, all events pass through.
 */
class DpadInputHandler {

    companion object {
        private const val TAG = "App:Dpad"
        private const val DOUBLE_TAP_MS = 200L
    }

    interface Listener {
        fun onLeftClick()
        fun onRightClick()
        fun onToggleTracking()
        fun onScrollUp()
        fun onScrollDown()
        fun onScrollLeft()
        fun onScrollRight()
    }

    var listener: Listener? = null
    var trackingEnabled = false

    private enum class State { IDLE, PRESSED, RELEASED_ONCE }

    private var state = State.IDLE
    private val handler = Handler(Looper.getMainLooper())

    private val singleTapRunnable = Runnable {
        // No second tap arrived -> single tap = left click
        state = State.IDLE
        Log.d(TAG, "Single tap -> left click")
        listener?.onLeftClick()
    }

    /**
     * Handle key down events.
     * @return true if the event was consumed (tracking is ON and gesture recognized)
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = GT.section("input.dpad.down") {
        Log.v(TAG, "event=keydown code=$keyCode tracking=$trackingEnabled")
        // DPAD directions: scroll when tracking is ON
        if (trackingEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    listener?.onScrollUp()
                    return@section true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    listener?.onScrollDown()
                    return@section true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    listener?.onScrollLeft()
                    return@section true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    listener?.onScrollRight()
                    return@section true
                }
            }
        }

        // DPAD_CENTER / ENTER: tap state machine (only when tracking is ON)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!trackingEnabled) return@section false
            return@section handleCenterDown()
        }

        // BACK = Rokid double-tap -> right click when tracking is ON
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!trackingEnabled) return@section false
            Log.d(TAG, "BACK (double-tap) -> right click")
            listener?.onRightClick()
            return@section true
        }

        false
    }

    /**
     * Handle key up events.
     * @return true if the event was consumed
     */
    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = GT.section("input.dpad.up") {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!trackingEnabled) return@section false
            return@section handleCenterUp()
        }

        // Consume BACK up when tracking (prevent activity finish)
        if (keyCode == KeyEvent.KEYCODE_BACK && trackingEnabled) return@section true

        // Consume DPAD direction ups when tracking
        if (trackingEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return@section true
            }
        }

        false
    }

    private fun handleCenterDown(): Boolean {
        when (state) {
            State.IDLE -> {
                state = State.PRESSED
                return true
            }
            State.RELEASED_ONCE -> {
                // Second tap arrived within window -> double tap = right click
                handler.removeCallbacks(singleTapRunnable)
                state = State.IDLE
                Log.d(TAG, "Double tap -> right click")
                listener?.onRightClick()
                return true
            }
            State.PRESSED -> {
                // Ignore repeat key events while pressed
                return true
            }
        }
    }

    private fun handleCenterUp(): Boolean {
        when (state) {
            State.PRESSED -> {
                state = State.RELEASED_ONCE
                handler.postDelayed(singleTapRunnable, DOUBLE_TAP_MS)
                return true
            }
            else -> return false
        }
    }

    fun reset() {
        handler.removeCallbacks(singleTapRunnable)
        state = State.IDLE
    }
}
