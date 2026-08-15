package com.repository.glasses.headlockoverlay.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadOrientationTrackerTest {

    @Test
    fun `head-right yaw integrates positive to about 57 degrees over one radian`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        // Yaw = integral of -gy. A -1 rad/s Y-rate = head turning right = +yaw.
        // 101 samples; first seeds the timestamp, remaining 100 each advance 0.01s.
        for (i in 0..100) {
            tracker.injectGyro(0f, -1f, 0f, (i + 1) * 10_000_000L)
        }
        // 100 * 0.01s * 1 rad/s = 1 rad = 57.2958 deg.
        assertEquals(57.2958f, tracker.yawDeg, 1.5f)
    }

    @Test
    fun `look-up pitch integrates positive to about 57 degrees over one radian`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        // Pitch = integral of gx. A +1 rad/s X-rate = looking up = +pitch.
        for (i in 0..100) {
            tracker.injectGyro(1f, 0f, 0f, (i + 1) * 10_000_000L)
        }
        assertEquals(57.2958f, tracker.pitchDeg, 1.5f)
    }

    @Test
    fun `onPose callback fires on every integrated sample`() {
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
            tracker.injectGyro(0f, -1f, 0f, (i + 1) * 10_000_000L)
        }
        assertEquals(tracker.yawDeg, lastYaw, 1e-3f)
        assertTrue("callback fired at least 100 times, got $count", count >= 100)
        assertTrue(!lastPitch.isNaN() && !lastRoll.isNaN())
    }

    @Test
    fun `still gyro does not accumulate drift`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        for (i in 0..200) {
            tracker.injectGyro(0f, 0f, 0f, (i + 1) * 10_000_000L)
        }
        assertEquals(0f, tracker.yawDeg, 1e-4f)
        assertEquals(0f, tracker.pitchDeg, 1e-4f)
    }

    @Test
    fun `head speed comes from gyro rate and does not spike on batched samples`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        // Steady 0.2 rad/s about Y (~11.5 deg/s), but delivered with erratic timestamps
        // including near-zero gaps (simulating Android sensor-event batching). A speed derived
        // from angle/dt would explode on the tiny gaps; a gyro-rate-derived speed stays put.
        var t = 10_000_000L
        val gaps = longArrayOf(20_000_000L, 200_000L, 200_000L, 15_000_000L, 100_000L, 18_000_000L)
        tracker.injectGyro(0f, 0.2f, 0f, t) // seed
        var maxSpeed = 0f
        repeat(6) { i ->
            t += gaps[i % gaps.size]
            tracker.injectGyro(0f, 0.2f, 0f, t)
            if (tracker.headSpeedDegPerSec > maxSpeed) maxSpeed = tracker.headSpeedDegPerSec
        }
        // ~11.5 deg/s expected; must never blow past a small multiple of that.
        assertTrue("speed must not spike from batching, got $maxSpeed", maxSpeed < 20f)
    }

    @Test
    fun `recenter zeroes the pose`() {
        val tracker = HeadOrientationTracker(sensorManager = null)
        for (i in 0..50) {
            tracker.injectGyro(-0.5f, -0.5f, 0f, (i + 1) * 10_000_000L)
        }
        assertTrue(kotlin.math.abs(tracker.yawDeg) > 1f)
        tracker.recenter()
        assertEquals(0f, tracker.yawDeg, 1e-6f)
        assertEquals(0f, tracker.pitchDeg, 1e-6f)
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
