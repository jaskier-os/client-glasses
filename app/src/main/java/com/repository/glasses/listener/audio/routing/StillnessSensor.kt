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
 * The detector keeps a sticky "last non-still timestamp". By default a single
 * sample above the threshold flips state to moving and the next still verdict
 * has to wait stillWindowMs of quiet to come back. With motionSustainMs > 0,
 * motion must instead PERSIST for that long before we flip to moving -- an
 * isolated spike (a desk tap, a passing vibration) is ignored. This keeps the
 * detector armed on a slightly-vibrating surface while still catching the
 * continuous jitter of a worn device.
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
    // Per-instance tunables. Defaults reproduce the original audio-routing
    // behavior exactly, so that shared instance is unaffected.
    private val motionThreshold: Float = DEFAULT_MOTION_THRESHOLD,
    private val stillWindowMs: Long = DEFAULT_STILL_WINDOW_MS,
    // Continuous duration (ms) that motion must persist before we declare the
    // device "moving". 0 = original instant behavior (one spike flips state).
    // A few seconds rejects isolated desk vibrations that would otherwise keep
    // resetting a downstream still-timer (e.g. the battery-LED 60s arm window).
    private val motionSustainMs: Long = 0L,
) : SensorEventListener {

    interface Listener { fun onStillnessChanged(still: Boolean) }

    companion object {
        // Magnitude (m/s^2) above which we declare motion. Linear-accel removes
        // gravity, so resting on a table sits near 0.0; quiet handheld is ~0.05;
        // breathing/wear jitter is ~0.15-0.4; walking is >1.0. 0.35 cleanly
        // separates table-rest from worn. (Raising this risks worn-jitter
        // falling below it, which would defeat the worn-glasses privacy gate --
        // prefer motionSustainMs hysteresis over a higher threshold.)
        private const val DEFAULT_MOTION_THRESHOLD = 0.35f
        // Quiet window required before we flip back to still.
        private const val DEFAULT_STILL_WINDOW_MS = 1500L
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
    // Start of the current run of above-threshold samples (0 = not in motion).
    // Used to require motion persist >= motionSustainMs before flipping to
    // moving. Reset whenever a quiet sample arrives.
    @Volatile private var motionRunStartMs: Long = 0L

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
        return System.currentTimeMillis() - lastMotionMs >= stillWindowMs
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
        if (mag > motionThreshold) {
            // Open a motion run on the first spike. The run tolerates short
            // sub-threshold gaps (handled in the else branch) so it isn't reset
            // by the dips between samples of genuine, ongoing motion -- it only
            // ends after stillWindowMs of real quiet. This is what lets worn
            // jitter (frequent spikes, gaps < stillWindowMs) accumulate the
            // sustain window while an isolated desk spike (followed by quiet)
            // never does.
            if (motionRunStartMs == 0L) motionRunStartMs = now
            lastMotionMs = now
            // Flip to moving only once the run has persisted for motionSustainMs.
            // With motionSustainMs == 0 the first sample already qualifies
            // (original instant behavior).
            if (stillState && now - motionRunStartMs >= motionSustainMs) {
                stillState = false
                listener?.onStillnessChanged(false)
            }
        } else {
            // Quiet sample. Only after stillWindowMs of continuous quiet do we
            // both close the motion run (so the next spike starts a fresh
            // sustain window) and flip back to still.
            if (now - lastMotionMs >= stillWindowMs) {
                motionRunStartMs = 0L
                if (!stillState) {
                    stillState = true
                    listener?.onStillnessChanged(true)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
