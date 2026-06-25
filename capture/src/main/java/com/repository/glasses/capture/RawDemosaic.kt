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

    // ---- Multi-core demosaic ----
    // The demosaic is the dominant photo-loading cost (~20s on the 4xA55 cluster):
    // its passes are per-pixel maps + a separable box blur, all embarrassingly
    // parallel. We split the work across all cores. A persistent fixed pool sized
    // to the CPU count is created once (cheap idle threads) so we don't pay a
    // pool-spinup cost per photo.
    private val CORES = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val pool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(CORES) { r ->
            Thread(r, "Demosaic-worker").apply { isDaemon = true }
        }

    // Precomputed linear->sRGB encode LUT: index = round(linear[0,1] * (N-1)),
    // value = encoded 0..255 byte. Replaces a per-pixel Math.pow in the hot loop.
    // N=4096 bins -> max quantization error well under 1/255, i.e. visually lossless.
    private const val SRGB_LUT_N = 4096
    private val SRGB_LUT: IntArray = IntArray(SRGB_LUT_N) { i ->
        val c = i.toFloat() / (SRGB_LUT_N - 1)
        val s = if (c <= 0.0031308f) 12.92f * c
                else 1.055f * Math.pow(c.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Run [body] over [total] indices split into [CORES] contiguous chunks, one per
     * worker, and block until all finish. [body] is called with the half-open
     * [start, end) range its chunk owns. Single-threaded fallback when total is
     * tiny or only one core is available, to avoid dispatch overhead.
     */
    private inline fun parallelChunks(total: Int, crossinline body: (start: Int, end: Int) -> Unit) {
        if (total <= 0) return
        if (CORES <= 1 || total < 4096) { body(0, total); return }
        val chunk = (total + CORES - 1) / CORES
        val latch = java.util.concurrent.CountDownLatch(CORES)
        for (c in 0 until CORES) {
            val s = c * chunk
            val e = minOf(s + chunk, total)
            if (s >= e) { latch.countDown(); continue }
            pool.execute {
                try { body(s, e) } finally { latch.countDown() }
            }
        }
        latch.await()
    }

    // ---- Local Reinhard tone-mapping (dodge-and-burn) ----
    //
    // A SINGLE global tone curve cannot expose a high-dynamic-range scene -- e.g. a
    // bright screen in a dim room. Any one curve must either roll the bright screen
    // off (losing the room's lift) or lift the room (washing the screen to flat
    // white). The bright Gmail screen and the dark room around it cannot both be
    // exposed by a global mapping.
    //
    // The fix is a LOCAL operator (Reinhard local / dodge-and-burn): each pixel is
    // scaled by 1 / (1 + Llocal), where Llocal is the key-scaled luminance of its
    // NEIGHBOURHOOD (a box-blur of luma*k). Bright regions have a high local average
    // -> less gain -> the screen keeps its content instead of clipping. Dark regions
    // have a low local average -> more gain -> the room is lifted. The overall
    // exposure is anchored by the geometric-mean (log-average) luminance, not the
    // median: on a dark scene the median collapses to 0 and pins gain to max, which
    // was the global path's failure mode.

    /** Target log-average exposure key (geometric-mean Reinhard). Higher = brighter
     *  overall. 0.18 is the classic photographic mid-grey key, validated locally. */
    private const val LOCAL_KEY = 0.18f
    /** Box-blur radius as a FRACTION of the output width, so the locality covers the
     *  same fraction of the frame at any output resolution. Larger = smoother / more
     *  global (fewer halos but less local contrast); smaller = more local contrast
     *  (can introduce halos around high-contrast edges). 0.03 gives radius ~60 at
     *  outW=2016 (the half-res photo path). */
    private const val LOCAL_RADIUS_FRAC = 0.03f
    /** Floor added to luma before the log (and to the log-average denominator) so a
     *  black pixel doesn't drive ln() to -inf and the key scale stays finite. */
    private const val LUMA_EPS = 1e-4f

    // ---- Clipped-highlight desaturation ----
    //
    // A blown highlight (bright lamp, sky, screen) pins ALL Bayer channels to ~white.
    // After black subtraction the quad reads R=G1=G2=B~range, but the per-channel WB
    // gains (rGain~1.7, gGain*2~1.0, bGain~1.8) then push the clipped pixel to high-R,
    // low-G, high-B -- i.e. MAGENTA/PURPLE -- even though the true colour is neutral
    // white. The post-CCM clamp to [0,1] happens far too late: by then the channels
    // have already been mixed and the magenta cast is baked in. The truth is that once
    // a channel clips its real value is unknowable, so the only safe estimate for a
    // fully-clipped pixel is NEUTRAL (its own luma). We therefore detect near-clip at
    // the RAW level and, in Pass 1 (before any tone map or CCM), lerp the post-WB
    // linear RGB toward luma by a clip amount t that ramps 0->1 from HIGHLIGHT_DESAT_FRAC
    // of full scale up to saturation. This pulls only blown highlights to white while
    // leaving every non-clipped colour exactly unchanged (t==0 => no-op), with no hard
    // edge. Cost: one max + one compare + a branch, and 3 FMAs only on clipped pixels.
    //
    /** Fraction of (whiteLevel - blackLevel) above which a raw sample is treated as
     *  clipping. Desaturation ramps from 0 here to full (neutral) at saturation. 0.98
     *  leaves a 2% guard band; tunable -- lower it if highlights still show colour
     *  fringing, raise it if near-white colours look washed out. */
    private const val HIGHLIGHT_DESAT_FRAC = 0.98f

    /**
     * Geometric mean (exp of the mean of ln) of [luma] over [count] pixels, with a
     * [LUMA_EPS] floor so black pixels don't send ln() to -inf. This log-average
     * luminance is the scene's overall exposure anchor: k = LOCAL_KEY / logavg.
     * Subsamples by [stride] to keep the single ln+sum pass cheap.
     */
    private fun logAverageLuma(luma: FloatArray, count: Int, stride: Int): Float {
        if (count <= 0) return LUMA_EPS
        var sumLn = 0.0
        var n = 0
        var i = 0
        while (i < count) {
            var v = luma[i]
            if (v < 0f) v = 0f
            sumLn += Math.log((v + LUMA_EPS).toDouble())
            n++
            i += stride
        }
        if (n <= 0) return LUMA_EPS
        return Math.exp(sumLn / n).toFloat().coerceAtLeast(LUMA_EPS)
    }

    /**
     * Separable O(n) box blur of [src] (an [w]x[h] array) into [dst] with a
     * (2*radius+1) window, using running sums. Border pixels replicate the edge
     * value (clamp-extend) so edges aren't darkened/brightened. Two passes:
     * horizontal then vertical, via a scratch buffer.
     */
    private fun boxBlur(src: FloatArray, dst: FloatArray, w: Int, h: Int, radius: Int) {
        val r = radius.coerceAtLeast(1)
        val win = 2 * r + 1
        val tmp = FloatArray(w * h)
        // Horizontal pass: src -> tmp. Each row is independent -> split over rows.
        parallelChunks(h) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val row = y * w
                // Seed the running sum for x=0 with clamp-extended left border.
                var sum = 0f
                for (k in -r..r) {
                    val xx = if (k < 0) 0 else if (k >= w) w - 1 else k
                    sum += src[row + xx]
                }
                tmp[row] = sum / win
                for (x in 1 until w) {
                    val add = (x + r).let { if (it >= w) w - 1 else it }
                    val sub = (x - r - 1).let { if (it < 0) 0 else it }
                    sum += src[row + add] - src[row + sub]
                    tmp[row + x] = sum / win
                }
            }
        }
        // Vertical pass: tmp -> dst. Each column is independent -> split over columns.
        parallelChunks(w) { xStart, xEnd ->
            for (x in xStart until xEnd) {
                var sum = 0f
                for (k in -r..r) {
                    val yy = if (k < 0) 0 else if (k >= h) h - 1 else k
                    sum += tmp[yy * w + x]
                }
                dst[x] = sum / win
                for (y in 1 until h) {
                    val add = (y + r).let { if (it >= h) h - 1 else it }
                    val sub = (y - r - 1).let { if (it < 0) 0 else it }
                    sum += tmp[add * w + x] - tmp[sub * w + x]
                    dst[y * w + x] = sum / win
                }
            }
        }
    }

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
        val n = halfW * halfH

        // Raw-domain clip threshold + ramp: a quad channel (raw-bl) at/above clipStart
        // is treated as clipping; the desat amount ramps to 1 at clipFull (= range).
        val clipFull = range.coerceAtLeast(1f)
        val clipStart = HIGHLIGHT_DESAT_FRAC * clipFull
        val clipRampInv = 1f / (clipFull - clipStart).coerceAtLeast(1f)

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

        // Pass 1: WB-applied linear RGB per pixel (stored) + a luma sample buffer
        // for the local tone map. Luma uses BT.601 over the post-WB linear RGB; G
        // dominates so this matches what fastPreview's green-sum approximates.
        val linR = FloatArray(n)
        val linG = FloatArray(n)
        val linB = FloatArray(n)
        val luma = FloatArray(n)
        // Pass 1 is per-output-row independent -> split the rows across cores.
        parallelChunks(halfH) { yStart, yEnd ->
            for (y in yStart until yEnd) {
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
                    var rl = rv * rGain
                    var gl = (g1v + g2v) * gGain
                    var blin = bv * bGain
                    // Clipped-highlight desaturation: drive off the MAX raw channel of the
                    // quad (the first to clip). t ramps 0->1 over [clipStart, clipFull];
                    // a fully-clipped (magenta-prone) pixel is lerped to its own luma =>
                    // neutral white. t==0 for any non-clipped pixel, so colours are intact.
                    val maxRaw = if (rv > g1v) (if (rv > g2v) (if (rv > bv) rv else bv) else (if (g2v > bv) g2v else bv))
                                 else (if (g1v > g2v) (if (g1v > bv) g1v else bv) else (if (g2v > bv) g2v else bv))
                    if (maxRaw > clipStart) {
                        val t = ((maxRaw - clipStart) * clipRampInv).coerceAtMost(1f)
                        val lum = 0.299f * rl + 0.587f * gl + 0.114f * blin
                        rl += t * (lum - rl)
                        gl += t * (lum - gl)
                        blin += t * (lum - blin)
                    }
                    val idx = dstRow + x
                    linR[idx] = rl; linG[idx] = gl; linB[idx] = blin
                    luma[idx] = 0.299f * rl + 0.587f * gl + 0.114f * blin
                }
            }
        }
        // LOCAL TONE MAP. Anchor exposure to the log-average luminance (geometric
        // mean), then box-blur the key-scaled luma into a local-average map so each
        // pixel's gain depends on its neighbourhood: bright regions get less gain
        // (screen content survives), dark regions get more (room is lifted).
        val logavg = logAverageLuma(luma, n, stride = 4)
        val k = LOCAL_KEY / logavg.coerceAtLeast(LUMA_EPS)
        val radius = (LOCAL_RADIUS_FRAC * halfW).toInt().coerceAtLeast(1)
        // lLocal = boxblur(luma * k). Scale luma by k in place; reuse luma's storage.
        parallelChunks(n) { s, e -> for (i in s until e) luma[i] = luma[i] * k }
        val lLocal = FloatArray(n)
        boxBlur(luma, lLocal, halfW, halfH, radius)
        Log.i(TAG, "localtonemap [photo]: logavg=${"%.5f".format(logavg)} k=${"%.3f".format(k)} radius=$radius (key=$LOCAL_KEY)")

        // Pass 2: per-pixel local scale = 1/(1+lLocal), applied (with the key k) to
        // each channel; then CCM, sat, sRGB. Per-index independent -> split across
        // cores. The sRGB encode uses the precomputed SRGB_LUT (linear[0,1] quantized
        // to SRGB_LUT_N bins) instead of a per-channel Math.pow -- ~9M pow calls per
        // photo were a large chunk of this pass.
        val outPx = IntArray(n)
        val lut = SRGB_LUT
        val lutMax = SRGB_LUT_N - 1
        parallelChunks(n) { s, e ->
            for (i in s until e) {
                val scale = k / (1f + lLocal[i])
                val rl = linR[i] * scale
                val gl = linG[i] * scale
                val bl2 = linB[i] * scale
                var rc = m00 * rl + m01 * gl + m02 * bl2
                var gc = m10 * rl + m11 * gl + m12 * bl2
                var bc = m20 * rl + m21 * gl + m22 * bl2
                val lm = 0.299f * rc + 0.587f * gc + 0.114f * bc
                rc = lm + sat * (rc - lm)
                gc = lm + sat * (gc - lm)
                bc = lm + sat * (bc - lm)
                if (rc < 0f) rc = 0f else if (rc > 1f) rc = 1f
                if (gc < 0f) gc = 0f else if (gc > 1f) gc = 1f
                if (bc < 0f) bc = 0f else if (bc > 1f) bc = 1f
                val ri = lut[(rc * lutMax + 0.5f).toInt()]
                val gi = lut[(gc * lutMax + 0.5f).toInt()]
                val bi = lut[(bc * lutMax + 0.5f).toInt()]
                outPx[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }

        val bmp = Bitmap.createBitmap(outPx, halfW, halfH, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "bin ${width}x${height}->${halfW}x${halfH} ccm+sat+localtonemap durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
        return bmp
    }

    /**
     * Speed-optimized COLOR demosaic for the ReID still path. Same color
     * character as [binToBitmap] (WB gains + the identical CCM + saturation)
     * so the look matches the photo path, but two optimizations bring it from
     * ~16s to well under 2s on this SoC:
     *
     *   (a) Heavier downsample. [downsample] is the source-pixel stride (must
     *       be even so the sampled RGGB quad stays aligned): each output pixel
     *       samples one full RGGB quad anchored at (downsample*x, downsample*y)
     *       in source space, so the output is width/downsample x
     *       height/downsample. For a 4032x3024 sensor, downsample=4 ->
     *       1008x756 (~1008px long edge), 6 -> 672x504. ReID / ML Kit does not
     *       need full spatial resolution.
     *
     *   (b) sRGB encode via a precomputed LUT instead of a per-channel
     *       Math.pow per pixel. The gamma curve is sampled into a 4096-entry
     *       byte table once; the hot loop quantizes the clamped linear value to
     *       a LUT index and reads the encoded 0..255 byte. This removes the
     *       ~3 pow() calls per pixel (millions total) that dominated runtime.
     *
     * Output is ARGB_8888, upright handling left to the caller (it bakes the
     * -90 rotation as for the photo path).
     */
    fun binToBitmapFast(
        raw: ShortArray,
        width: Int, height: Int,
        blackLevel: Float, whiteLevel: Float,
        wbR: Float, wbG: Float, wbB: Float,
        downsample: Int,
    ): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        require(raw.size >= width * height)
        require(downsample >= 2)
        // Each output pixel samples a full RGGB quad (x, x+1, y, y+1), so the
        // stride must be even to keep the quad on RGGB phase. Force even >= 2.
        val step = (downsample and 1.inv()).coerceAtLeast(2)
        val outW = width / step
        val outH = height / step
        val n = outW * outH
        val range = whiteLevel - blackLevel
        val rGain = wbR / range
        val gGain = wbG / range * 0.5f  // x0.5 because we average G1+G2
        val bGain = wbB / range
        val bl = blackLevel

        // Raw-domain clip threshold + ramp (see binToBitmap): without this, blown
        // highlights render magenta/purple after the per-channel WB gains.
        val clipFull = range.coerceAtLeast(1f)
        val clipStart = HIGHLIGHT_DESAT_FRAC * clipFull
        val clipRampInv = 1f / (clipFull - clipStart).coerceAtLeast(1f)

        // CCM + saturation: identical constants to binToBitmap so the ReID
        // still has the same color/look as the photo path.
        val m00 = 1.65f; val m01 = -0.50f; val m02 = -0.15f
        val m10 = -0.25f; val m11 = 1.55f; val m12 = -0.30f
        val m20 = -0.15f; val m21 = -0.40f; val m22 = 1.55f
        val sat = 1.45f

        // sRGB gamma LUT: index = clamped linear [0..1] quantized to LUT_N-1,
        // value = sRGB-encoded 0..255. Built once, replaces Math.pow per pixel.
        val lutN = 4096
        val gammaLut = IntArray(lutN)
        run {
            val inv = 1f / (lutN - 1)
            for (i in 0 until lutN) {
                val lin = i * inv
                val enc = if (lin <= 0.0031308f) 12.92f * lin
                else 1.055f * Math.pow(lin.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
                gammaLut[i] = (enc * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
        }
        val lutScale = (lutN - 1).toFloat()

        // Pass 1: WB-applied linear RGB per output pixel + luma for the local tone
        // map. Same operator as the photo path so the ReID still is as naturally
        // exposed (and matches the look). Two passes over the downsampled grid is
        // cheap; the grid is already 1/16 of the sensor.
        val linR = FloatArray(n)
        val linG = FloatArray(n)
        val linB = FloatArray(n)
        val luma = FloatArray(n)
        for (y in 0 until outH) {
            val sy0 = (y * step) * width
            val sy1 = (y * step + 1) * width
            val dstRow = y * outW
            for (x in 0 until outW) {
                val sx0 = x * step
                val sx1 = sx0 + 1
                val rv = ((raw[sy0 + sx0].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                val g1v = ((raw[sy0 + sx1].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                val g2v = ((raw[sy1 + sx0].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                val bv = ((raw[sy1 + sx1].toInt() and 0xFFFF) - bl).coerceAtLeast(0f)
                var rl = rv * rGain
                var gl = (g1v + g2v) * gGain
                var blin = bv * bGain
                // Clipped-highlight desaturation -> neutral white (see binToBitmap).
                val maxRaw = if (rv > g1v) (if (rv > g2v) (if (rv > bv) rv else bv) else (if (g2v > bv) g2v else bv))
                             else (if (g1v > g2v) (if (g1v > bv) g1v else bv) else (if (g2v > bv) g2v else bv))
                if (maxRaw > clipStart) {
                    val t = ((maxRaw - clipStart) * clipRampInv).coerceAtMost(1f)
                    val lum = 0.299f * rl + 0.587f * gl + 0.114f * blin
                    rl += t * (lum - rl)
                    gl += t * (lum - gl)
                    blin += t * (lum - blin)
                }
                val idx = dstRow + x
                linR[idx] = rl; linG[idx] = gl; linB[idx] = blin
                luma[idx] = 0.299f * rl + 0.587f * gl + 0.114f * blin
            }
        }
        // LOCAL TONE MAP, same operator as the photo path. Radius derived from this
        // path's (smaller) output width via the same fraction, so locality covers an
        // equivalent fraction of the frame.
        val logavg = logAverageLuma(luma, n, stride = 4)
        val k = LOCAL_KEY / logavg.coerceAtLeast(LUMA_EPS)
        val radius = (LOCAL_RADIUS_FRAC * outW).toInt().coerceAtLeast(1)
        for (i in 0 until n) luma[i] = luma[i] * k
        val lLocal = FloatArray(n)
        boxBlur(luma, lLocal, outW, outH, radius)
        Log.i(TAG, "localtonemap [reid]: logavg=${"%.5f".format(logavg)} k=${"%.3f".format(k)} radius=$radius (key=$LOCAL_KEY)")

        // Pass 2: per-pixel local scale = 1/(1+lLocal) (with key k) + CCM + sat +
        // LUT-encoded sRGB.
        val outPx = IntArray(n)
        for (i in 0 until n) {
            val scale = k / (1f + lLocal[i])
            val rl = linR[i] * scale
            val gl = linG[i] * scale
            val bl2 = linB[i] * scale
            var rc = m00 * rl + m01 * gl + m02 * bl2
            var gc = m10 * rl + m11 * gl + m12 * bl2
            var bc = m20 * rl + m21 * gl + m22 * bl2
            val lm = 0.299f * rc + 0.587f * gc + 0.114f * bc
            rc = lm + sat * (rc - lm)
            gc = lm + sat * (gc - lm)
            bc = lm + sat * (bc - lm)
            if (rc < 0f) rc = 0f else if (rc > 1f) rc = 1f
            if (gc < 0f) gc = 0f else if (gc > 1f) gc = 1f
            if (bc < 0f) bc = 0f else if (bc > 1f) bc = 1f
            val ri = gammaLut[(rc * lutScale + 0.5f).toInt()]
            val gi = gammaLut[(gc * lutScale + 0.5f).toInt()]
            val bi = gammaLut[(bc * lutScale + 0.5f).toInt()]
            outPx[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val bmp = Bitmap.createBitmap(outPx, outW, outH, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "binFast ${width}x${height}->${outW}x${outH} ds=$downsample ccm+sat+lut+localtonemap durMs=${android.os.SystemClock.elapsedRealtime() - t0}")
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
