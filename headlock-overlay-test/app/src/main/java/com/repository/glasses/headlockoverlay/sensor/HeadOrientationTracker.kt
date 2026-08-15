package com.repository.glasses.headlockoverlay.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.repository.glasses.headlockoverlay.math.wrap180
import kotlin.math.hypot

/**
 * Reads the gyroscope and reports head yaw/pitch in degrees via [onPose] by directly
 * integrating angular velocity. Pure rate integration (no absolute reference) matches the
 * approach proven on this exact hardware in the main glasses app's HeadTracker; long-term
 * drift is bounded downstream by the lazy-follow reference + recenter, not here.
 *
 * Glasses gyro axis convention (from the shipping HeadTracker, empirically verified on device):
 *   values[0] = rotation about X = pitch (up/down nod)
 *   values[1] = rotation about Y = yaw   (left/right head turn)
 *   values[2] = rotation about Z = roll  (unused)
 * Signs are chosen so turning the head RIGHT increases yaw and looking UP increases pitch.
 *
 * Test seam: pass [sensorManager] = null and drive [injectGyro] directly.
 */
class HeadOrientationTracker(private val sensorManager: SensorManager? = null) {

    var onPose: ((yawDeg: Float, pitchDeg: Float, rollDeg: Float) -> Unit)? = null

    var yawDeg: Float = 0f
        private set
    var pitchDeg: Float = 0f
        private set
    var rollDeg: Float = 0f
        private set

    /**
     * Head angular speed in deg/s, derived DIRECTLY from the gyro rate magnitude (yaw+pitch
     * axes) and lightly EMA-smoothed. This is a true velocity from the sensor, so it is immune
     * to the position/dt differentiation artifact that made a naive speed spike to hundreds of
     * deg/s whenever Android batched two events with a near-zero timestamp gap.
     */
    var headSpeedDegPerSec: Float = 0f
        private set

    private var lastTimestampNs = 0L

    private var started = false
    private var listener: SensorEventListener? = null

    fun start() {
        if (started) return
        started = true
        val sm = sensorManager ?: return // no-op in tests / when no manager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    injectGyro(event.values[0], event.values[1], event.values[2], event.timestamp)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        gyro?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        val l = listener
        if (l != null) {
            sensorManager?.unregisterListener(l)
            listener = null
        }
        started = false
    }

    /**
     * Integrate one gyro sample. [gx]/[gy]/[gz] are angular velocity (rad/s) about the device
     * X/Y/Z axes; [timestampNs] is the event timestamp in nanoseconds.
     */
    fun injectGyro(gx: Float, gy: Float, gz: Float, timestampNs: Long) {
        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            return
        }
        val dt = ((timestampNs - lastTimestampNs) / 1e9f).coerceIn(0f, MAX_DT)
        lastTimestampNs = timestampNs
        if (dt <= 0f) return

        // Direct rate integration using the glasses' proven axis mapping:
        //   yaw   from Y-axis rate (gy), negated so head-right = +yaw
        //   pitch from X-axis rate (gx), so looking-up = +pitch (verified on device)
        val yawRateDegS = radiansToDegrees(-gy)
        val pitchRateDegS = radiansToDegrees(gx)
        yawDeg = wrap180(yawDeg + yawRateDegS * dt)
        pitchDeg = wrap180(pitchDeg + pitchRateDegS * dt)
        rollDeg = wrap180(rollDeg + radiansToDegrees(gz) * dt)

        // Speed straight from the gyro rate (a velocity already), EMA-smoothed. No dt division,
        // so event batching cannot make it spike.
        val instSpeed = hypot(yawRateDegS.toDouble(), pitchRateDegS.toDouble()).toFloat()
        headSpeedDegPerSec += SPEED_SMOOTHING * (instSpeed - headSpeedDegPerSec)

        onPose?.invoke(yawDeg, pitchDeg, rollDeg)
    }

    fun recenter() {
        yawDeg = 0f
        pitchDeg = 0f
        rollDeg = 0f
        headSpeedDegPerSec = 0f
        lastTimestampNs = 0L
    }

    companion object {
        // Skip stale gyro readings (seconds).
        private const val MAX_DT = 0.1f

        // EMA weight for the head-speed estimate (0 = frozen, 1 = no smoothing).
        private const val SPEED_SMOOTHING = 0.3f
    }
}
