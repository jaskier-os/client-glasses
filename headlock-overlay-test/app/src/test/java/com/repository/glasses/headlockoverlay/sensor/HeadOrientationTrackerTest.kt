package com.repository.glasses.headlockoverlay.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadOrientationTrackerTest {

    @Test
    fun `gyro yaw integrates to about 57 degrees over one radian`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        // 101 samples; first seeds the timestamp, remaining 100 each advance 0.01s at 1 rad/s.
        for (i in 0..100) {
            tracker.injectGyro(0f, 0f, 1f, i * 10_000_000L)
        }
        // 100 * 0.01s * 1 rad/s = 1 rad = 57.2958 deg.
        assertEquals(57.2958f, tracker.yawDeg, 1.5f)
    }

    @Test
    fun `onPose callback fires with final yaw`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        var lastYaw = Float.NaN
        var lastPitch = Float.NaN
        var lastRoll = Float.NaN
        var count = 0
        tracker.onPose = { yaw, pitch, roll ->
            lastYaw = yaw
            lastPitch = pitch
            lastRoll = roll
            count++
        }
        for (i in 0..100) {
            tracker.injectGyro(0f, 0f, 1f, i * 10_000_000L)
        }
        assertEquals(tracker.yawDeg, lastYaw, 1e-3f)
        assertTrue("callback fired at least 100 times, got $count", count >= 100)
        // sanity: pitch/roll present
        assertTrue(!lastPitch.isNaN() && !lastRoll.isNaN())
    }

    @Test
    fun `gravity keeps pitch near level and converges toward tilt`() {
        val tracker = HeadOrientationTracker(sensorManager = null)

        // Phase 1: level accel (0,0,9.8) interleaved with zero-rate gyro to publish.
        var t = 0L
        for (i in 0 until 500) {
            tracker.injectAccel(0f, 0f, 9.8f)
            t += 10_000_000L
            tracker.injectGyro(0f, 0f, 0f, t)
        }
        assertTrue("pitch stays near level, got ${tracker.pitchDeg}", kotlin.math.abs(tracker.pitchDeg) < 3f)

        // Phase 2: tilt. Chosen formula: pitch = atan2(-ax, sqrt(ay*ay+az*az)).
        // A positive ax therefore drives pitch negative. Feed a clear +ax tilt.
        val g = 9.8f
        val ax = g * kotlin.math.sin(Math.toRadians(30.0)).toFloat()  // ~4.9
        val az = g * kotlin.math.cos(Math.toRadians(30.0)).toFloat()  // ~8.49
        // Expected accel pitch = atan2(-4.9, 8.49) = -30 deg.
        for (i in 0 until 1000) {
            tracker.injectAccel(ax, 0f, az)
            t += 10_000_000L
            tracker.injectGyro(0f, 0f, 0f, t)
        }
        // Direction: pitch should move negative and approach -30 within 5 deg.
        assertTrue("pitch moved negative, got ${tracker.pitchDeg}", tracker.pitchDeg < -5f)
        assertEquals(-30f, tracker.pitchDeg, 5f)
    }

    @Test
    fun `start and stop with null manager do not throw`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        tracker.start()
        tracker.stop()
        tracker.start()
        tracker.stop()
    }
}
