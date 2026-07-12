package com.repository.glasses.headlockoverlay.ui

/**
 * Maps angular panel coordinates (yaw right = +, pitch up = +) plus a viewport
 * offset (in degrees) into screen pixels. A single horizontal FOV constant
 * drives px/deg so degrees are square (angularly uniform pixels across axes).
 */
class Projection(var horizontalFovDeg: Float = 28f) {
    fun pxPerDeg(viewWidthPx: Int): Float = viewWidthPx / horizontalFovDeg

    fun screenX(panelYawDeg: Float, offsetYawDeg: Float, viewWidthPx: Int): Float =
        viewWidthPx / 2f + (panelYawDeg + offsetYawDeg) * pxPerDeg(viewWidthPx)

    // +pitch = up = smaller Y. Uses the same px/deg scale (dimension / fov).
    fun screenY(panelPitchDeg: Float, offsetPitchDeg: Float, viewHeightPx: Int): Float =
        viewHeightPx / 2f - (panelPitchDeg + offsetPitchDeg) * (viewHeightPx / horizontalFovDeg)
}
