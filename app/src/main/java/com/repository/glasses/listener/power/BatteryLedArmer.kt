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
        // Motion must persist this long before we treat the device as moving.
        // Resting on a charger, the surface picks up isolated micro-vibrations
        // (HVAC, footsteps, the desk being bumped) every ~30-50s; without
        // hysteresis each lone spike flips stillness->false and restarts the
        // 60s arm countdown, so the LED almost never arms. Requiring ~2s of
        // SUSTAINED motion ignores those spikes while still catching the
        // continuous jitter of a worn device (which keeps the privacy gate).
        private const val MOTION_SUSTAIN_MS = 2_000L
    }

    // useWakeup=true: the glasses sit on a charger with the screen off, so the
    // non-wakeup IMU would stop delivering and freeze the stillness verdict.
    // The wakeup variant keeps motion detection alive during idle.
    // motionSustainMs: reject isolated desk vibrations (see MOTION_SUSTAIN_MS).
    //
    // A wake-up sensor asserts the kernel SensorsHAL_WAKEUP wakeup source on
    // every sample, which aborts s2idle outright ("Abort: Pending Wakeup
    // Sources: SensorsHAL_WAKEUP"). So it is registered ONLY while charging --
    // see reconcileSensor(). That is safe precisely because glasses-power-daemon
    // refuses to arm fold-suspend while the charger is online, so the LED and
    // fold-suspend never need the AP awake at the same time. Off-charger -- the
    // only state in which fold-suspend runs -- the sensor is fully unregistered.
    private val stillness = StillnessSensor(
        ctx, log, useWakeup = true, motionSustainMs = MOTION_SUSTAIN_MS,
    )
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var charging = false
    @Volatile private var still = false
    private var running = false
    // Mirrors whether stillness.start() is currently in effect, so we only
    // touch the SensorManager on real transitions.
    private var sensorRunning = false

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
        // Do NOT start the sensor here -- it is gated on charging so the
        // wake-up sensor cannot block fold-suspend. setCharging() (seeded by
        // ListenerService right after start()) brings it up when relevant.
        reconcileSensor("start")
        log("BatteryLedArmer started")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(armRunnable)
        reconcileSensor("stop")
        stillness.listener = null
        BatteryLedControl.setArmed(false)
        log("BatteryLedArmer stopped")
    }

    /** Call from ListenerService whenever charging state changes. */
    fun setCharging(isCharging: Boolean) {
        if (isCharging == charging) return
        charging = isCharging
        reconcileSensor("charging=$isCharging")
        reevaluate("charging=$isCharging")
    }

    /**
     * The wake-up IMU runs only while running && charging. Unregistering it
     * off-charger is what lets the kernel reach s2idle on fold.
     *
     * Not synchronized: every caller (start/stop from service lifecycle,
     * setCharging from the battery BroadcastReceiver) is on the main thread.
     * Keep it that way, or add synchronization -- a lost update here would
     * strand the wake-up sensor registered and silently block suspend again.
     */
    private fun reconcileSensor(reason: String) {
        val want = running && charging
        if (want == sensorRunning) return
        sensorRunning = want
        if (want) {
            stillness.start()
            log("battery-led stillness sensor started ($reason)")
        } else {
            stillness.stop()
            // The verdict is stale the moment we stop sampling. Reset it so a
            // later charge does not arm the LED off a pre-unplug "still" that
            // was never re-validated.
            still = false
            log("battery-led stillness sensor stopped, wakeup source released ($reason)")
        }
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
