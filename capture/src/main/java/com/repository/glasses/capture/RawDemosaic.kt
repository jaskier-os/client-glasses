package com.repository.glasses.capture

import android.graphics.Bitmap
import android.util.Log

/**
 * Bilinear RGGB demosaic + white balance + sRGB gamma, producing an
 * ARGB_8888 Bitmap from a RAW_SENSOR 10-bit frame.
 *
 * We keep this pure-Kotlin (no NDK/JNI) so it stays in one APK, at the
 * cost of ~1 s per 12 Mpx frame on the glasses A55 cluster. The hot loop
 * operates on IntArray / FloatArray directly with minimal allocations.
 */
object RawDemosaic {
    private const val TAG = "RawDemosaic"

    /**
     * Fast 4x4 grayscale preview: for each 4x4 Bayer super-block, average the
     * 8 green samples into an 8-bit intensity. Output is 1/4 the sensor
     * resolution (e.g. 4032x3024 -> 1008x756) and ~4x faster than the full
     * [binToBitmap] path. Skips WB + RGB demosaic because the preview is
     * rendered as monochrome green on the waveguide downstream.
     *
     * Pixel format is ARGB_8888 with R=G=B=luma so downstream rotation /
     * scale / JPEG encode don't have to branch on format.
     */
    fun fastPreviewToBitmap(
        raw: ShortArray,
        width: Int, height: Int,
        blackLevel: Float, whiteLevel: Float,
    ): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        require(raw.size >= width * height)
        val outW = width / 4
        val outH = height / 4
        val bl = blackLevel
        val maxSum = (whiteLevel - blackLevel) * 8f
        // Pass 1: compute sum-of-8-greens per block AND a 256-bin histogram.
        val sums = FloatArray(outW * outH)
        val hist = IntArray(256)
        val binScale = 255f / maxSum.coerceAtLeast(1f)
        for (by in 0 until outH) {
            val srcY0 = by * 4
            for (bx in 0 until outW) {
                val srcX0 = bx * 4
                var sumG = 0f
                for (qy in 0..1) {
                    val y = srcY0 + qy * 2
                    val rowG1 = y * width
                    val rowG2 = (y + 1) * width
                    for (qx in 0..1) {
                        val x = srcX0 + qx * 2
                        val g1 = (raw[rowG1 + x + 1].toInt() and 0xFFFF).toFloat() - bl
                        val g2 = (raw[rowG2 + x].toInt() and 0xFFFF).toFloat() - bl
                        sumG += g1 + g2
                    }
                }
                if (sumG < 0f) sumG = 0f
                sums[by * outW + bx] = sumG
                val bin = (sumG * binScale).toInt().coerceIn(0, 255)
                hist[bin]++
            }
        }
        // Percentile-based stretch: use p2..p98 so a handful of hot pixels or
        // deep black borders don't flatten the whole gradient. Previous min/max
        // approach gave mean=1 because one saturated pixel set sMax=7672 while
        // 99% of the frame was near 0.
        val total = outW * outH
        val pLowCount = (total * 0.02f).toInt().coerceAtLeast(1)
        val pHighCount = (total * 0.98f).toInt().coerceAtLeast(1)
        var cum = 0
        var pLowBin = 0
        var pHighBin = 255
        for (b in 0..255) {
            cum += hist[b]
            if (cum >= pLowCount && pLowBin == 0) pLowBin = b
            if (cum >= pHighCount) { pHighBin = b; break }
        }
        // Convert bin indices back to sum space.
        val sLow = pLowBin.toFloat() / binScale
        val sHigh = pHighBin.toFloat() / binScale
        val range = (sHigh - sLow).coerceAtLeast(1f)
        val gain = 255f / range
        val outPx = IntArray(outW * outH)
        for (i in 0 until outW * outH) {
            var v = ((sums[i] - sLow) * gain).toInt()
            if (v < 0) v = 0 else if (v > 255) v = 255
            outPx[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(outPx, 0, outW, 0, 0, outW, outH)
        Log.i(TAG, "fastPreviewToBitmap ${width}x${height} -> ${outW}x${outH} p2=$sLow p98=$sHigh durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
        return bmp
    }

    /**
     * 2x2 Bayer binning: each RGGB quad becomes one RGB pixel. Output is
     * half the sensor resolution (e.g. 4032x3024 -> 2016x1512). Much faster
     * than a proper demosaic (one pass, no neighbor lookups) and implicitly
     * averages the two G samples for free, giving a small denoise bonus.
     * Trade-off: half the spatial resolution vs. bilinear demosaic.
     */
    fun binToBitmap(
        raw: ShortArray,
        width: Int, height: Int,
        blackLevel: Float, whiteLevel: Float,
        wbR: Float, wbG: Float, wbB: Float,
    ): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        require(raw.size >= width * height)
        val halfW = width / 2
        val halfH = height / 2
        val range = whiteLevel - blackLevel
        val rGain = wbR / range
        val gGain = wbG / range * 0.5f  // x0.5 because we average G1+G2
        val bGain = wbB / range
        val bl = blackLevel
        val outPx = IntArray(halfW * halfH)

        // CCM: post-WB camera-RGB -> sRGB primary mapping. Each row sums to
        // 1.0 so a neutral grey stays neutral. Diagonal >1 with negative
        // off-diagonals subtracts inter-channel crosstalk and expands the
        // gamut. Hand-tuned for the Rokid sensor look; without this stills
        // come out flat/desaturated vs HAL-processed video.
        val m00 = 1.65f; val m01 = -0.50f; val m02 = -0.15f
        val m10 = -0.25f; val m11 = 1.55f; val m12 = -0.30f
        val m20 = -0.15f; val m21 = -0.40f; val m22 = 1.55f

        // Saturation lift in linear space, around BT.601 luma.
        val sat = 1.45f

        for (y in 0 until halfH) {
            val sy0 = 2 * y * width
            val sy1 = (2 * y + 1) * width
            val dstRow = y * halfW
            for (x in 0 until halfW) {
                val sx0 = 2 * x
                val sx1 = sx0 + 1
                val rv = ((raw[sy0 + sx0].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                val g1v = ((raw[sy0 + sx1].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                val g2v = ((raw[sy1 + sx0].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                val bv = ((raw[sy1 + sx1].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                // Linear, WB-applied [0..1+] camera RGB.
                val rl = rv * rGain
                val gl = (g1v + g2v) * gGain
                val bl2 = bv * bGain
                // CCM -> linear sRGB.
                var rc = m00 * rl + m01 * gl + m02 * bl2
                var gc = m10 * rl + m11 * gl + m12 * bl2
                var bc = m20 * rl + m21 * gl + m22 * bl2
                // Saturation around luma.
                val luma = 0.299f * rc + 0.587f * gc + 0.114f * bc
                rc = luma + sat * (rc - luma)
                gc = luma + sat * (gc - luma)
                bc = luma + sat * (bc - luma)
                if (rc < 0f) rc = 0f else if (rc > 1f) rc = 1f
                if (gc < 0f) gc = 0f else if (gc > 1f) gc = 1f
                if (bc < 0f) bc = 0f else if (bc > 1f) bc = 1f
                // sRGB encode.
                val rs = if (rc <= 0.0031308f) 12.92f * rc else 1.055f * Math.pow(rc.toDouble(), 1.0/2.4).toFloat() - 0.055f
                val gs = if (gc <= 0.0031308f) 12.92f * gc else 1.055f * Math.pow(gc.toDouble(), 1.0/2.4).toFloat() - 0.055f
                val bs2 = if (bc <= 0.0031308f) 12.92f * bc else 1.055f * Math.pow(bc.toDouble(), 1.0/2.4).toFloat() - 0.055f
                val ri = (rs * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (gs * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (bs2 * 255f + 0.5f).toInt().coerceIn(0, 255)
                outPx[dstRow + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }

        val bmp = Bitmap.createBitmap(outPx, halfW, halfH, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "bin ${width}x${height}->${halfW}x${halfH} ccm+sat durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
        return bmp
    }

    /**
     * @param raw RAW_SENSOR buffer, width*height uint16 (stored in Short).
     *   CFA pattern assumed RGGB (verified on Rokid sensor via rawpy:
     *   raw_pattern = [[0,1],[3,2]]).
     * @param width pixel width (e.g. 4032)
     * @param height pixel height (e.g. 3024)
     * @param blackLevel sensor black level (e.g. 64)
     * @param whiteLevel sensor max value (e.g. 1023)
     * @param wbR multiplicative gain for the R channel (e.g. 1.88 from DNG)
     * @param wbG multiplicative gain for both G channels (usually 1.0)
     * @param wbB multiplicative gain for the B channel (e.g. 1.83)
     */
    fun demosaicToBitmap(
        raw: ShortArray,
        width: Int, height: Int,
        blackLevel: Float, whiteLevel: Float,
        wbR: Float, wbG: Float, wbB: Float,
    ): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        require(raw.size >= width * height) { "raw too small: ${raw.size} < ${width * height}" }
        val range = whiteLevel - blackLevel

        // Normalize to float [0, 1] with black-level subtraction and WB, in place in a float array.
        val norm = FloatArray(width * height)
        run {
            var idx = 0
            for (y in 0 until height) {
                val rowEven = (y and 1) == 0
                for (x in 0 until width) {
                    val v = (raw[idx].toInt() and 0xFFFF) - blackLevel.toInt()
                    val vf = if (v <= 0) 0f else (v.toFloat() / range)
                    // RGGB CFA: (even row, even col)=R, (even,odd)=G, (odd,even)=G, (odd,odd)=B
                    val ch = if (rowEven) (if ((x and 1) == 0) 0 else 1) else (if ((x and 1) == 0) 1 else 2)
                    norm[idx] = when (ch) {
                        0 -> vf * wbR
                        1 -> vf * wbG
                        else -> vf * wbB
                    }
                    idx++
                }
            }
        }

        // Bilinear demosaic. Allocate ARGB pixel array. sRGB gamma applied inline.
        val outPx = IntArray(width * height)

        // Helper to read with edge replication (rare in inner loops; inline for speed).
        fun at(x: Int, y: Int): Float {
            val xc = if (x < 0) 0 else if (x >= width) width - 1 else x
            val yc = if (y < 0) 0 else if (y >= height) height - 1 else y
            return norm[yc * width + xc]
        }

        for (y in 0 until height) {
            val rowEven = (y and 1) == 0
            for (x in 0 until width) {
                val colEven = (x and 1) == 0
                val r: Float
                val g: Float
                val b: Float
                when {
                    rowEven && colEven -> {
                        // R pixel -> G=avg of 4 neighbors, B=avg of 4 diagonals
                        r = norm[y * width + x]
                        g = 0.25f * (at(x, y - 1) + at(x, y + 1) + at(x - 1, y) + at(x + 1, y))
                        b = 0.25f * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1))
                    }
                    rowEven && !colEven -> {
                        // G pixel (even row) -> R=avg horizontal, B=avg vertical
                        g = norm[y * width + x]
                        r = 0.5f * (at(x - 1, y) + at(x + 1, y))
                        b = 0.5f * (at(x, y - 1) + at(x, y + 1))
                    }
                    !rowEven && colEven -> {
                        // G pixel (odd row) -> R=avg vertical, B=avg horizontal
                        g = norm[y * width + x]
                        r = 0.5f * (at(x, y - 1) + at(x, y + 1))
                        b = 0.5f * (at(x - 1, y) + at(x + 1, y))
                    }
                    else -> {
                        // B pixel -> G=avg 4 neighbors, R=avg 4 diagonals
                        b = norm[y * width + x]
                        g = 0.25f * (at(x, y - 1) + at(x, y + 1) + at(x - 1, y) + at(x + 1, y))
                        r = 0.25f * (at(x - 1, y - 1) + at(x + 1, y - 1) + at(x - 1, y + 1) + at(x + 1, y + 1))
                    }
                }
                val rs = srgbGamma(r.coerceIn(0f, 1f))
                val gs = srgbGamma(g.coerceIn(0f, 1f))
                val bs = srgbGamma(b.coerceIn(0f, 1f))
                val ri = (rs * 255f + 0.5f).toInt()
                val gi = (gs * 255f + 0.5f).toInt()
                val bi = (bs * 255f + 0.5f).toInt()
                outPx[y * width + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }

        val bmp = Bitmap.createBitmap(outPx, width, height, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "demosaic ${width}x${height} durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
        return bmp
    }

    /** sRGB encoding (linear -> gamma-encoded). */
    private fun srgbGamma(x: Float): Float {
        return if (x <= 0.0031308f) 12.92f * x
        else 1.055f * Math.pow(x.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
    }
}
