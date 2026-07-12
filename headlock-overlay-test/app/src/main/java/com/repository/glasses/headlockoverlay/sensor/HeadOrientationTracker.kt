package com.repository.glasses.headlockoverlay.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.repository.glasses.headlockoverlay.math.wrap180
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Reads gyro + accel and reports head yaw/pitch/roll in degrees via [onPose].
 *
 * Yaw comes from gyro integration (drift-prone, but "stops when still").
 * Pitch/roll are gyro-fast but continuously corrected toward gravity (accel) via a
 * complementary filter, so they do not drift.
 *
 * Test seam: pass [sensorManager] = null and drive [injectGyro] / [injectAccel] directly.
 * The real SensorEventListener path funnels into the same inject methods.
 */
class HeadOrientationTracker(private val sensorManager: SensorManager? = null) {

    var onPose: ((yawDeg: Float, pitchDeg: Float, rollDeg: Float) -> Unit)? = null

    var yawDeg: Float = 0f
        private set
    var pitchDeg: Float = 0f
        private set
    var rollDeg: Float = 0f
        private set

    private val integrator = GyroQuaternionIntegrator()

    // Latest gravity-derived pitch/roll (degrees); null until the first accel sample.
    private var accelPitchDeg: Float? = null
    private var accelRollDeg: Float? = null

    // Last gyro-euler pitch/roll (degrees) so we can apply the incremental gyro delta
    // to the fused estimate rather than resetting to the raw integrator value.
    private var haveLastGyro = false
    private var lastGyroPitchDeg = 0f
    private var lastGyroRollDeg = 0f

    private var started = false
    private var listener: SensorEventListener? = null

    fun start() {
        if (started) return
        started = true
        val sm = sensorManager ?: return // no-op in tests / when no manager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE ->
                        injectGyro(event.values[0], event.values[1], event.values[2], event.timestamp)
                    Sensor.TYPE_ACCELEROMETER ->
                        injectAccel(event.values[0], event.values[1], event.values[2])
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        gyro?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        val l = listener
        if (l != null) {
            sensorManager?.unregisterListener(l)
            listener = null
        }
        started = false
    }

    fun injectAccel(ax: Float, ay: Float, az: Float) {
        // Standard phone-style gravity tilt. At rest, accel ~= (0,0,+g) -> level (pitch=roll=0).
        //   pitch = atan2(-ax, sqrt(ay*ay + az*az))   (positive ax => nose pitches negative)
        //   roll  = atan2(ay, az)
        // NOTE: the exact axis convention on these glasses is unknown; signs may need
        // on-device tuning. Keep this formula in ONE place (here) if adjusting.
        val pitch = atan2(-ax, sqrt(ay * ay + az * az))
        val roll = atan2(ay, az)
        accelPitchDeg = radiansToDegrees(pitch)
        accelRollDeg = radiansToDegrees(roll)
    }

    fun injectGyro(gx: Float, gy: Float, gz: Float, timestampNs: Long) {
        integrator.onGyroscope(gx, gy, gz, timestampNs)
        val (gyroPitchRad, gyroRollRad, gyroYawRad) = integrator.orientation.toEulerRadians()

        yawDeg = wrap180(radiansToDegrees(gyroYawRad))

        val gyroPitchDeg = radiansToDegrees(gyroPitchRad)
        val gyroRollDeg = radiansToDegrees(gyroRollRad)

        // Advance the fused estimate by the gyro DELTA (high-frequency), then pull a small
        // fraction toward the accel gravity estimate (low-frequency drift correction).
        // On the first sample there is no delta, so seed from the raw gyro euler.
        val dPitch = if (haveLastGyro) gyroPitchDeg - lastGyroPitchDeg else gyroPitchDeg
        val dRoll = if (haveLastGyro) gyroRollDeg - lastGyroRollDeg else gyroRollDeg
        lastGyroPitchDeg = gyroPitchDeg
        lastGyroRollDeg = gyroRollDeg
        haveLastGyro = true

        val ap = accelPitchDeg
        val ar = accelRollDeg
        val advPitch = pitchDeg + dPitch
        val advRoll = rollDeg + dRoll
        pitchDeg = if (ap != null) (1f - ACCEL_BLEND) * advPitch + ACCEL_BLEND * ap else advPitch
        rollDeg = if (ar != null) (1f - ACCEL_BLEND) * advRoll + ACCEL_BLEND * ar else advRoll

        onPose?.invoke(yawDeg, pitchDeg, rollDeg)
    }

    companion object {
        // Complementary-filter weight given to the accel (gravity) estimate per sample.
        private const val ACCEL_BLEND = 0.02f
    }
}
