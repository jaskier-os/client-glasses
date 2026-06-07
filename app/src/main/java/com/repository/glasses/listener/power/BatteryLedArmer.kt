package com.repository.glasses.listener.power

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.repository.glasses.listener.audio.routing.StillnessSensor

/**
 * Arms the battery LED (via BatteryLedControl) when the glasses are charging AND
 * have been physically still for >= STILL_ARM_MS continuously. Movement or unplug
 * disarms immediately. Worn glasses always micro-move, so they never reach the
 * stillness threshold -> the LED never shows while worn.
 *
 * Only the app can read the IMU, so the stillness decision lives here; the daemon
 * owns the actual LED write.
 *
 * Uses its own StillnessSensor instance. StillnessSensor is a per-instance
 * SensorEventListener wrapper (its own SensorManager + registerListener), so a
 * second instance running alongside AudioRoutingController's does not conflict --
 * both simply receive the same accelerometer callbacks.
 */
class BatteryLedArmer(
    ctx: Context,
    private val log: (String) -> Unit = {},
) {
    companion object {
        private const val STILL_ARM_MS = 60_000L
    }

    // useWakeup=true: the glasses sit on a charger with the screen off, so the
    // non-wakeup IMU would stop delivering and freeze the stillness verdict.
    // The wakeup variant keeps motion detection alive during idle.
    private val stillness = StillnessSensor(ctx, log, useWakeup = true)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var charging = false
    @Volatile private var still = false
    private var running = false

    private val armRunnable = Runnable {
        if (charging && still) {
            BatteryLedControl.setArmed(true)
            log("battery-led armed: charging+still ${STILL_ARM_MS}ms")
        }
    }

    private val stillnessListener = object : StillnessSensor.Listener {
        override fun onStillnessChanged(still: Boolean) {
            this@BatteryLedArmer.still = still
            reevaluate("stillness=$still")
        }
    }

    fun start() {
        if (running) return
        running = true
        stillness.listener = stillnessListener
        stillness.start()
        log("BatteryLedArmer started")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(armRunnable)
        stillness.stop()
        stillness.listener = null
        BatteryLedControl.setArmed(false)
        log("BatteryLedArmer stopped")
    }

    /** Call from ListenerService whenever charging state changes. */
    fun setCharging(isCharging: Boolean) {
        if (isCharging == charging) return
        charging = isCharging
        reevaluate("charging=$isCharging")
    }

    private fun reevaluate(reason: String) {
        if (!running) return
        if (charging && still) {
            handler.removeCallbacks(armRunnable)
            handler.postDelayed(armRunnable, STILL_ARM_MS)
            log("battery-led pending arm in ${STILL_ARM_MS}ms ($reason)")
        } else {
            handler.removeCallbacks(armRunnable)
            BatteryLedControl.setArmed(false)
            log("battery-led disarmed ($reason)")
        }
    }
}
