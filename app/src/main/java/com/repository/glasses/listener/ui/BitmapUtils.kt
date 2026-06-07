package com.repository.glasses.listener.ui

import android.graphics.Bitmap

object BitmapUtils {

    /**
     * Converts a color bitmap to monochrome green for the Rokid waveguide display.
     * Calculates luminance per pixel, applies adaptive brightness boost,
     * and outputs green-channel-only ARGB_8888 bitmap.
     */
    fun toMonochromeGreen(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        // First pass: find max luminance for adaptive brightness boost
        var maxLum = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = ((0.2126 * r) + (0.7152 * g) + (0.0722 * b)).toInt()
            if (lum > maxLum) maxLum = lum
        }
        // Boost factor: if max luminance is low, amplify to make content visible on waveguide
        val boost = if (maxLum > 30) (200.0 / maxLum).coerceAtMost(4.0) else 1.0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = ((0.2126 * r) + (0.7152 * g) + (0.0722 * b)) * boost
            val clamped = lum.toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (clamped shl 8)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
}
