package com.repository.glasses.listener.audio.routing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Coarse motion / stillness detector for the IMU.
 *
 * Used to disambiguate a wear-sensor blip ("user took the glasses off") from
 * a sensor flutter while the user is actively wearing them. Linear acceleration
 * (gravity removed) gives a near-zero magnitude when the device is sitting on
 * a table; even quiet wear produces small but persistent jitter from breathing,
 * micro-head-motion, and walking.
 *
 * The detector keeps a sticky "last non-still timestamp" -- a single sample
 * above the threshold flips state to moving and the next still verdict has to
 * wait STILL_WINDOW_MS of quiet to come back.
 */
class StillnessSensor(
    ctx: Context,
    private val log: (String) -> Unit = {},
    // When true, select the WAKE-UP sensor variant so motion events keep
    // arriving while the screen is off and the AP is idle. Required for the
    // battery-LED use case (glasses charging on a table, screen off): the
    // default non-wakeup sensor silently stops delivering during idle, which
    // freezes the stillness verdict. A wakeup sensor stays low-power when the
    // device is motionless (no events to wake on) and fires the instant it
    // moves. Default false keeps the original audio-routing behavior unchanged.
    private val useWakeup: Boolean = false,
) : SensorEventListener {

    interface Listener { fun onStillnessChanged(still: Boolean) }

    companion object {
        // Magnitude (m/s^2) above which we declare motion. Linear-accel removes
        // gravity, so resting on a table sits near 0.0; quiet handheld is ~0.05;
        // breathing/wear jitter is ~0.15-0.4; walking is >1.0. 0.35 cleanly
        // separates table-rest from worn.
        private const val MOTION_THRESHOLD = 0.35f
        // Quiet window required before we flip back to still.
        private const val STILL_WINDOW_MS = 1500L
        private const val SENSOR_RATE = SensorManager.SENSOR_DELAY_UI
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Prefer linear-accel, fall back to raw accel. When useWakeup is set, ask for
    // the wake-up variant first so events flow during screen-off/idle; fall back
    // to the non-wakeup variant if the wake-up one isn't present on this device.
    private val sensor: Sensor? = pickSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: pickSensor(Sensor.TYPE_ACCELEROMETER)
    private val usingLinear: Boolean = sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION

    private fun pickSensor(type: Int): Sensor? {
        if (useWakeup) {
            val w = sm.getDefaultSensor(type, true)
            if (w != null) return w
        }
        return sm.getDefaultSensor(type)
    }

    @Volatile var listener: Listener? = null
    @Volatile private var registered = false
    @Volatile private var lastMotionMs: Long = 0L
    @Volatile private var stillState: Boolean = true

    fun start() {
        if (registered) return
        val s = sensor ?: run {
            log("StillnessSensor: no accelerometer; defaulting to still=true")
            return
        }
        lastMotionMs = System.currentTimeMillis()
        stillState = false
        sm.registerListener(this, s, SENSOR_RATE)
        registered = true
        log("StillnessSensor started (linear=$usingLinear wakeup=${useWakeup && s.isWakeUpSensor})")
    }

    fun stop() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
        log("StillnessSensor stopped")
    }

    /**
     * Snapshot stillness verdict. true once no above-threshold sample has been
     * seen for STILL_WINDOW_MS. If the sensor is unavailable, returns true
     * (the rule reduces to wear+fold).
     */
    fun isStill(): Boolean {
        if (!registered || sensor == null) return true
        return System.currentTimeMillis() - lastMotionMs >= STILL_WINDOW_MS
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // For raw accel (no linear-accel sensor) subtract gravity magnitude
        // approximately -- we care about motion, not orientation.
        val mag = if (usingLinear) {
            sqrt(x * x + y * y + z * z)
        } else {
            // crude: rolling-mean removed via |mag - 9.81|
            kotlin.math.abs(sqrt(x * x + y * y + z * z) - 9.81f)
        }
        val now = System.currentTimeMillis()
        if (mag > MOTION_THRESHOLD) {
            lastMotionMs = now
            if (stillState) {
                stillState = false
                listener?.onStillnessChanged(false)
            }
        } else {
            if (!stillState && now - lastMotionMs >= STILL_WINDOW_MS) {
                stillState = true
                listener?.onStillnessChanged(true)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
