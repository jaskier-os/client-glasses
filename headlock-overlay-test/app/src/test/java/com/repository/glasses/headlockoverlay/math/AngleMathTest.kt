package com.repository.glasses.headlockoverlay.math

import org.junit.Assert.assertEquals
import org.junit.Test

class AngleMathTest {
    @Test
    fun wrapsAboveHalf() {
        assertEquals(-170f, wrap180(190f), 1e-3f)
    }

    @Test
    fun wrapsBelowNegativeHalf() {
        assertEquals(170f, wrap180(-190f), 1e-3f)
    }

    @Test
    fun leavesInRangeUntouched() {
        assertEquals(10f, wrap180(10f), 1e-3f)
    }

    @Test
    fun fullTurnWrapsToZero() {
        assertEquals(0f, wrap180(360f), 1e-3f)
    }

    @Test
    fun largeInputWraps() {
        assertEquals(45f, wrap180(720f + 45f), 1e-3f)
    }
}
