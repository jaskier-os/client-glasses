package com.repository.glasses.listener.mouse

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.repository.glasses.tracing.GT
import kotlin.math.abs

/**
 * Tracks head rotation via raw Gyroscope.
 *
 * Uses angular velocity (rad/s) with EMA smoothing and dead zone.
 * No absolute position tracking = no drift accumulation.
 */
class HeadTracker(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "App:Head"
        private const val SENSOR_RATE = SensorManager.SENSOR_DELAY_GAME
        private const val LOG_EVERY_N_EVENTS = 500

        // EMA smoothing: 0 = max smooth, 1 = no smooth
        private const val SMOOTHING = 0.4f

        // Dead zone in rad/s -- ignore sensor noise below this
        private const val DEAD_ZONE = 0.015f

        // Skip stale readings
        private const val MAX_DT = 0.1f
    }

    interface Listener {
        fun onHeadMove(dx: Float, dy: Float)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    var listener: Listener? = null
    var sensitivityX = 1800f
    var sensitivityY = 4200f

    private var isTracking = false
    @Volatile private var registered = false
    private var lastTimestamp = 0L
    private var gyroEventCount = 0L
    private var gyroEmittedCount = 0L

    // Smoothed angular velocities
    private var smoothX = 0f  // yaw (left/right)
    private var smoothY = 0f  // pitch (up/down)

    fun start(): Boolean = GT.section("head.start") {
        Log.d(TAG, "event=start")
        if (gyroscope == null) {
            Log.e(TAG, "event=start_fail reason=no_gyro")
            return@section false
        }
        if (registered) {
            Log.d(TAG, "event=start_skip reason=already_registered")
            isTracking = true
            return@section true
        }
        lastTimestamp = 0L
        smoothX = 0f
        smoothY = 0f
        isTracking = true
        gyroEventCount = 0L
        gyroEmittedCount = 0L
        sensorManager.registerListener(this, gyroscope, SENSOR_RATE)
        registered = true
        Log.i(TAG, "event=started sensX=$sensitivityX sensY=$sensitivityY")
        true
    }

    fun stop() = GT.section("head.stop") {
        isTracking = false
        if (!registered) {
            Log.d(TAG, "event=stop_skip reason=not_registered")
            return@section
        }
        sensorManager.unregisterListener(this)
        registered = false
        Log.i(TAG, "event=stopped total_events=$gyroEventCount emitted=$gyroEmittedCount")
    }

    fun recenter() {
        Log.d(TAG, "event=recenter")
        smoothX = 0f
        smoothY = 0f
    }

    override fun onSensorChanged(event: SensorEvent) = GT.section("head.sensor") {
        if (!isTracking) return@section
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return@section
        gyroEventCount++
        if (gyroEventCount % LOG_EVERY_N_EVENTS == 0L) {
            Log.v(TAG, "event=gyro_tick n=$gyroEventCount emitted=$gyroEmittedCount smX=$smoothX smY=$smoothY")
            GT.counter("head.gyro_events", gyroEventCount)
        }

        val timestamp = event.timestamp
        if (lastTimestamp == 0L) {
            lastTimestamp = timestamp
            return@section
        }

        val dt = (timestamp - lastTimestamp) / 1_000_000_000f  // ns -> s
        lastTimestamp = timestamp

        if (dt <= 0f || dt > MAX_DT) return@section

        // Gyroscope values: angular velocity in rad/s
        // values[0] = rotation around X axis (pitch -- looking up/down)
        // values[1] = rotation around Y axis (yaw -- looking left/right)
        // values[2] = rotation around Z axis (roll -- not used)
        var gyroYaw = -event.values[1]    // Y axis = left/right head turn (negated for glasses orientation)
        var gyroPitch = -event.values[0] // X axis = up/down nod (negated for glasses orientation)

        // Dead zone
        if (abs(gyroYaw) < DEAD_ZONE) gyroYaw = 0f
        if (abs(gyroPitch) < DEAD_ZONE) gyroPitch = 0f

        // EMA smoothing
        smoothX = SMOOTHING * gyroYaw + (1f - SMOOTHING) * smoothX
        smoothY = SMOOTHING * gyroPitch + (1f - SMOOTHING) * smoothY

        // Angular velocity * dt * sensitivity = pixel delta
        val dx = smoothX * dt * sensitivityX
        val dy = smoothY * dt * sensitivityY

        if (abs(dx) > 0.1f || abs(dy) > 0.1f) {
            gyroEmittedCount++
            listener?.onHeadMove(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
