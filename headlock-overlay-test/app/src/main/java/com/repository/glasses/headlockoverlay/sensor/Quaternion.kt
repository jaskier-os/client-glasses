package com.repository.glasses.headlockoverlay.sensor

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Quaternion(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
) {
    fun normalized(): Quaternion {
        val len = sqrt(x * x + y * y + z * z + w * w)
        if (len < 1e-6f) return IDENTITY
        return Quaternion(x / len, y / len, z / len, w / len)
    }

    companion object {
        val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

        fun fromRotationVector(values: FloatArray): Quaternion {
            return Quaternion(
                x = values[0],
                y = values[1],
                z = values[2],
                w = values[3],
            ).normalized()
        }

        fun fromEulerRadians(pitch: Float, roll: Float, yaw: Float): Quaternion {
            val cy = cos(yaw * 0.5f)
            val sy = sin(yaw * 0.5f)
            val cp = cos(pitch * 0.5f)
            val sp = sin(pitch * 0.5f)
            val cr = cos(roll * 0.5f)
            val sr = sin(roll * 0.5f)
            return Quaternion(
                x = sr * cp * cy - cr * sp * sy,
                y = cr * sp * cy + sr * cp * sy,
                z = cr * cp * sy - sr * sp * cy,
                w = cr * cp * cy + sr * sp * sy,
            ).normalized()
        }
    }
}

fun Quaternion.inverse(): Quaternion = Quaternion(-x, -y, -z, w)

fun Quaternion.multiply(other: Quaternion): Quaternion {
    return Quaternion(
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
        w = w * other.w - x * other.x - y * other.y - z * other.z,
    ).normalized()
}

fun radiansToDegrees(rad: Float): Float = Math.toDegrees(rad.toDouble()).toFloat()

fun Quaternion.rotateVector(vx: Float, vy: Float, vz: Float): Triple<Float, Float, Float> {
    val tx = 2f * (y * vz - z * vy)
    val ty = 2f * (z * vx - x * vz)
    val tz = 2f * (x * vy - y * vx)
    return Triple(
        vx + w * tx + y * tz - z * ty,
        vy + w * ty + z * tx - x * tz,
        vz + w * tz + x * ty - y * tx,
    )
}

fun Quaternion.toEulerRadians(): Triple<Float, Float, Float> {
    val q = normalized()
    val sinPitch = 2f * (q.w * q.x + q.y * q.z).coerceIn(-1f, 1f)
    val pitch = asin(sinPitch)
    val sinYaw = 2f * (q.w * q.z + q.x * q.y)
    val cosYaw = 1f - 2f * (q.y * q.y + q.z * q.z)
    val yaw = atan2(sinYaw, cosYaw)
    val sinRoll = 2f * (q.w * q.y - q.z * q.x)
    val cosRoll = 1f - 2f * (q.x * q.x + q.z * q.z)
    val roll = atan2(sinRoll, cosRoll)
    return Triple(pitch, roll, yaw)
}
