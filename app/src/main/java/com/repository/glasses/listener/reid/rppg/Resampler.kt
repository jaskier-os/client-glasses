package com.repository.glasses.listener.reid.rppg

/**
 * Resampled signal on a uniform time grid plus its effective sampling rate.
 *
 * @property values resampled amplitudes, one per uniform grid point.
 * @property fps    effective sample rate in Hz (1000 / stepMs); 0 when empty.
 */
data class Resampled(val values: FloatArray, val fps: Float)

/**
 * Resamples a non-uniformly-sampled signal onto a uniform time grid.
 *
 * rPPG frame samples arrive with jittered camera-frame timestamps. Spectral
 * analysis assumes a constant sample rate, so the signal must first be placed
 * on a uniform grid by linear interpolation. This object does only that; it
 * performs no filtering, smoothing, or spectral work.
 *
 * Precondition: tMs is sorted ascending. The caller's ring buffer is already
 * ordered, so this is not re-sorted internally. Non-positive consecutive dts
 * (duplicates or out-of-order pairs) are skipped when computing the step.
 */
object Resampler {

    /**
     * Resample [v] sampled at timestamps [tMs] onto a uniform grid.
     *
     * Step: median of the positive consecutive dts (tMs[i] - tMs[i-1]).
     * Grid: from tMs.first() to tMs.last() with stepMs spacing, count =
     *       floor((tLast - t0) / stepMs) + 1 points (grid[k] = t0 + k*stepMs).
     * Interp: linear between bracketing input samples; grid times outside
     *         [t0, tLast] clamp to the nearest endpoint value (no extrapolation).
     * fps: 1000 / stepMs.
     *
     * @return Resampled(empty, 0f) when size < 2 or no positive dt exists.
     * @throws IllegalArgumentException if tMs.size != v.size.
     */
    fun resampleUniform(tMs: LongArray, v: FloatArray): Resampled {
        require(tMs.size == v.size) {
            "tMs and v must have equal length: ${tMs.size}/${v.size}"
        }
        val n = tMs.size
        if (n < 2) return Resampled(FloatArray(0), 0f)

        val stepMs = medianPositiveDt(tMs)
        if (stepMs <= 0.0) return Resampled(FloatArray(0), 0f)

        val t0 = tMs.first()
        val tLast = tMs.last()
        val span = (tLast - t0).toDouble()
        val count = Math.floor(span / stepMs).toInt() + 1

        val out = FloatArray(count)
        var lo = 0 // left bracket index, advanced monotonically as gt increases
        val tFirst = t0.toDouble()
        val tLastD = tLast.toDouble()
        for (k in 0 until count) {
            val gt = t0 + k * stepMs
            out[k] = when {
                gt <= tFirst -> v.first()
                gt >= tLastD -> v.last()
                else -> {
                    while (lo + 1 < n && tMs[lo + 1].toDouble() <= gt) lo++
                    val tA = tMs[lo].toDouble()
                    val tB = tMs[lo + 1].toDouble()
                    val frac = if (tB == tA) 0.0 else (gt - tA) / (tB - tA)
                    (v[lo] + frac * (v[lo + 1] - v[lo])).toFloat()
                }
            }
        }
        return Resampled(out, (1000.0 / stepMs).toFloat())
    }

    private fun medianPositiveDt(tMs: LongArray): Double {
        val dts = ArrayList<Long>(tMs.size - 1)
        for (i in 1 until tMs.size) {
            val dt = tMs[i] - tMs[i - 1]
            if (dt > 0L) dts.add(dt)
        }
        if (dts.isEmpty()) return 0.0
        dts.sort()
        val m = dts.size
        return if (m % 2 == 1) {
            dts[m / 2].toDouble()
        } else {
            (dts[m / 2 - 1] + dts[m / 2]) / 2.0
        }
    }
}
