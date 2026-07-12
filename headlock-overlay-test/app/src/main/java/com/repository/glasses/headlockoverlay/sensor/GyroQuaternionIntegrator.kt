package com.repository.glasses.headlockoverlay.sensor

class GyroQuaternionIntegrator {
    var orientation: Quaternion = Quaternion.IDENTITY
        private set

    private var lastTimestampNs: Long = 0L

    fun reset() {
        orientation = Quaternion.IDENTITY
        lastTimestampNs = 0L
    }

    fun onGyroscope(gx: Float, gy: Float, gz: Float, timestampNs: Long) {
        if (lastTimestampNs == 0L) {
            lastTimestampNs = timestampNs
            return
        }
        val dt = ((timestampNs - lastTimestampNs) * 1e-9f).coerceIn(0f, 0.1f)
        lastTimestampNs = timestampNs
        if (dt <= 0f) return

        val half = dt * 0.5f
        val delta = Quaternion(
            x = gx * half,
            y = gy * half,
            z = gz * half,
            w = 1f,
        ).normalized()
        orientation = orientation.multiply(delta).normalized()
    }

    fun relativeEulerSince(reference: Quaternion): Triple<Float, Float, Float> {
        val relative = reference.inverse().multiply(orientation).normalized()
        return relative.toEulerRadians()
    }
}
