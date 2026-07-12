package com.repository.glasses.headlockoverlay.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LazyFollowReferenceTest {

    @Test
    fun deadzoneLocksCenter() {
        val ref = LazyFollowReference()
        val offset = ref.update(3f, 0f, 0.016f)
        assertEquals(0f, offset.yawDeg, 1e-2f)
        // no follow inside deadzone
        assertEquals(0f, ref.refYaw, 1e-2f)
    }

    @Test
    fun revealOutsideDeadzone() {
        val ref = LazyFollowReference()
        val offset = ref.update(30f, 0f, 0.016f)
        // ref barely moved: 0.6*30*0.016 ~= 0.29 -> err ~= 29.71 -> offset ~= -29.71
        assertEquals(-30f, offset.yawDeg, 0.5f)
        assertTrue(offset.yawDeg < 0f)
        assertTrue(abs(offset.yawDeg) > 25f)
    }

    @Test
    fun reCenterOnSustainedHold() {
        val ref = LazyFollowReference()
        var offset = LazyFollowReference.Offset(0f, 0f)
        repeat(200) {
            offset = ref.update(90f, 0f, 0.1f)
        }
        // Following is deadzone-gated: it halts once |err| < deadzone, so the ref
        // asymptotes to (head - deadzone), not exactly the head. The behavioural
        // point is that the viewport offset has returned inside the deadzone
        // (content re-centered) after a sustained hold.
        assertTrue(ref.refYaw > 90f - ref.deadzoneDeg - 0.5f)
        assertTrue(ref.refYaw <= 90f)
        assertTrue(abs(offset.yawDeg) < ref.deadzoneDeg)
    }

    @Test
    fun pitchBehavesSameAsYaw() {
        val ref = LazyFollowReference()
        val offset = ref.update(0f, 20f, 0.016f)
        assertEquals(-20f, offset.pitchDeg, 0.5f)
        assertEquals(0f, offset.yawDeg, 1e-2f)
    }

    @Test
    fun recenterSnapsReference() {
        val ref = LazyFollowReference()
        ref.recenter(45f, -10f)
        assertEquals(45f, ref.refYaw, 1e-2f)
        assertEquals(-10f, ref.refPitch, 1e-2f)
        val offset = ref.update(45f, -10f, 0.016f)
        assertEquals(0f, offset.yawDeg, 1e-2f)
        assertEquals(0f, offset.pitchDeg, 1e-2f)
    }

    @Test
    fun wrapCorrectnessAcross180() {
        val ref = LazyFollowReference()
        ref.recenter(170f, 0f)
        val offset = ref.update(-170f, 0f, 0.016f)
        // err = wrap180(-170 - 170) = wrap180(-340) = 20 -> offset ~= -20, NOT -340
        assertEquals(-20f, offset.yawDeg, 0.5f)
    }
}
