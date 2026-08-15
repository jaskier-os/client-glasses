package com.repository.glasses.headlockoverlay.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LazyFollowReferenceTest {

    // Head is "still" (speed below idle threshold) unless a test says otherwise.
    private val STILL = 0f
    private val MOVING = 100f

    /** One still-head frame. */
    private fun LazyFollowReference.still(yaw: Float, pitch: Float, dt: Float = 0.016f) =
        update(yaw, pitch, STILL, dt)

    /** Feed the same head angle for [seconds] at [dt] spacing, head reported STILL. */
    private fun LazyFollowReference.hold(
        yaw: Float,
        pitch: Float,
        seconds: Float,
        dt: Float = 0.05f,
    ): LazyFollowReference.Offset {
        var offset = LazyFollowReference.Offset(0f, 0f)
        val steps = (seconds / dt).toInt()
        repeat(steps) { offset = update(yaw, pitch, STILL, dt) }
        return offset
    }

    @Test
    fun deadzoneLocksCenter() {
        val ref = LazyFollowReference()
        val offset = ref.still(3f, 0f)
        assertEquals(0f, offset.yawDeg, 1e-2f)
        assertEquals(0f, ref.refYaw, 1e-2f)
    }

    @Test
    fun revealOutsideDeadzone() {
        val ref = LazyFollowReference() // deadzone 6
        val offset = ref.still(30f, 0f)
        // Soft deadzone: offset = -(|err| - D) = -(30 - 6) = -24. No dwell yet, ref unmoved.
        assertEquals(-24f, offset.yawDeg, 1e-2f)
    }

    @Test
    fun softDeadzoneIsContinuousAtEdge() {
        val ref = LazyFollowReference() // deadzone 6
        // Just inside the edge -> ~0; just outside -> ~0. No jump across the boundary.
        assertEquals(0f, ref.still(5.9f, 0f).yawDeg, 1e-2f)
        assertEquals(0f, ref.still(6.1f, 0f).yawDeg, 0.2f)
    }

    @Test
    fun noFollowBeforeDwell() {
        // Holding a new heading for less than dwellSeconds must NOT move the reference yet.
        val ref = LazyFollowReference() // dwell 2.0s
        ref.hold(40f, 0f, seconds = 1.5f)
        assertEquals("ref must not move before the dwell elapses", 0f, ref.refYaw, 1e-3f)
    }

    @Test
    fun followBeginsGraduallyAfterDwell() {
        val ref = LazyFollowReference() // dwell 2.0s, ramp 1.5s
        // Hold just past the dwell + a little ramp; ref should have started moving, but only a little.
        ref.hold(40f, 0f, seconds = 2.6f)
        assertTrue("ref should have begun following after dwell, got ${ref.refYaw}", ref.refYaw > 0f)
        // Eased-in + slow rate: only a small fraction of the 40deg error consumed so far.
        assertTrue("follow should be gradual, not a snap, got ${ref.refYaw}", ref.refYaw < 8f)
    }

    @Test
    fun movementResetsDwell() {
        val ref = LazyFollowReference()
        // Hold still almost to the dwell...
        ref.hold(40f, 0f, seconds = 1.8f)
        // ...then a MOVING frame (speed above idle) must reset the dwell timer.
        ref.update(80f, 0f, MOVING, 0.05f)
        // Now hold at 80 (still) for a short time (< dwell). Ref must still be parked at 0.
        ref.hold(80f, 0f, seconds = 1.0f)
        assertEquals("a head move should reset the dwell so follow restarts", 0f, ref.refYaw, 1e-3f)
    }

    @Test
    fun sustainedMovementNeverFollows() {
        // Even holding a fixed angle, if speed stays above idle the ref must never move.
        val ref = LazyFollowReference()
        repeat(400) { ref.update(50f, 0f, MOVING, 0.05f) }
        assertEquals("moving head must never trigger follow", 0f, ref.refYaw, 1e-3f)
    }

    @Test
    fun reCentersAfterLongSustainedHold() {
        val ref = LazyFollowReference()
        // Hold long enough for the slow eased follow to bring content back inside the deadzone.
        val offset = ref.hold(90f, 0f, seconds = 120f)
        assertTrue("ref advanced toward head, got ${ref.refYaw}", ref.refYaw > 90f - ref.deadzoneDeg - 1f)
        assertTrue("content re-centered (offset within deadzone), got ${offset.yawDeg}",
            abs(offset.yawDeg) < ref.deadzoneDeg)
    }

    @Test
    fun pitchBehavesSameAsYaw() {
        val ref = LazyFollowReference() // deadzone 6
        val offset = ref.still(0f, 20f)
        // Soft deadzone: -(20 - 6) = -14.
        assertEquals(-14f, offset.pitchDeg, 1e-2f)
        assertEquals(0f, offset.yawDeg, 1e-2f)
    }

    @Test
    fun recenterSnapsReference() {
        val ref = LazyFollowReference()
        ref.recenter(45f, -10f)
        assertEquals(45f, ref.refYaw, 1e-2f)
        assertEquals(-10f, ref.refPitch, 1e-2f)
        val offset = ref.still(45f, -10f)
        assertEquals(0f, offset.yawDeg, 1e-2f)
        assertEquals(0f, offset.pitchDeg, 1e-2f)
    }

    @Test
    fun wrapCorrectnessAcross180() {
        val ref = LazyFollowReference()
        ref.recenter(170f, 0f)
        val offset = ref.still(-170f, 0f)
        // err = wrap180(-170 - 170) = wrap180(-340) = 20 -> soft offset = -(20-6) = -14, NOT -334
        assertEquals(-14f, offset.yawDeg, 0.5f)
    }
}
