package com.repository.glasses.headlockoverlay.math

/**
 * Wraps any angle in degrees into the half-open range (-180, 180].
 * e.g. 190 -> -170, -190 -> 170, 10 -> 10, 180 -> 180, 360 -> 0.
 */
fun wrap180(deg: Float): Float {
    var r = (deg + 180f) % 360f
    if (r <= 0f) r += 360f
    return r - 180f
}
