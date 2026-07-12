package com.repository.glasses.headlockoverlay.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class QuaternionTest {
    @Test
    fun integratesConstantYawRate() {
        val integrator = GyroQuaternionIntegrator()
        for (i in 0..100) {
            integrator.onGyroscope(0f, 0f, 1f, i * 10_000_000L)
        }
        val yawRad = integrator.orientation.toEulerRadians().third
        val yawDeg = radiansToDegrees(yawRad)
        assertEquals(57.2958f, yawDeg, 1.0f)
    }

    @Test
    fun identityIsZeroEuler() {
        val (pitch, roll, yaw) = Quaternion.IDENTITY.toEulerRadians()
        assertEquals(0f, pitch, 1e-4f)
        assertEquals(0f, roll, 1e-4f)
        assertEquals(0f, yaw, 1e-4f)
    }
}
