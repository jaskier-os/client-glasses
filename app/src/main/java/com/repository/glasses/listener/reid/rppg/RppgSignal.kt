package com.repository.glasses.listener.reid.rppg

import kotlin.math.sqrt

/**
 * Pulse-signal extraction from per-frame spatial-mean RGB channels.
 *
 * Both methods operate over a whole window (one call per analysis window) and
 * return a 1-D pulse signal the same length as the input. They are pure,
 * dependency-free, and framework-agnostic.
 *
 * POS  -- Plane-Orthogonal-to-Skin (Wang et al., "Algorithmic Principles of
 *         Remote PPG", IEEE TBME 2017).
 * CHROM -- Chrominance-based method (de Haan & Jeanne, "Robust Pulse Rate from
 *         Chrominance-Based rPPG", IEEE TBME 2013).
 *
 * Outputs are mean-subtracted so a DC-free pulse waveform is returned; constant
 * (motionless) input therefore yields a near-zero signal.
 */
object RppgSignal {

    /**
     * POS pulse extraction.
     *
     * 1. Temporal normalization per channel: Cn = C / mean(C).
     * 2. S1 = Gn - Bn
     * 3. S2 = Gn + Bn - 2*Rn
     * 4. P  = S1 + (std(S1)/std(S2)) * S2   (alpha = 0 when std(S2) == 0)
     * 5. Mean-subtract P.
     *
     * @return pulse signal, length == input length.
     */
    fun pos(r: FloatArray, g: FloatArray, b: FloatArray): FloatArray {
        requireSameLength(r, g, b)
        val n = r.size
        if (n == 0) return FloatArray(0)

        val rn = normalize(r)
        val gn = normalize(g)
        val bn = normalize(b)

        val s1 = FloatArray(n) { gn[it] - bn[it] }
        val s2 = FloatArray(n) { gn[it] + bn[it] - 2f * rn[it] }

        val stdS2 = std(s2)
        val alpha = if (stdS2 == 0f) 0f else std(s1) / stdS2

        val p = FloatArray(n) { s1[it] + alpha * s2[it] }
        return meanSubtract(p)
    }

    /**
     * CHROM pulse extraction.
     *
     * Channels normalized by their means; then
     *   Xs = 3*Rn - 2*Gn
     *   Ys = 1.5*Rn + Gn - 1.5*Bn
     *   P  = Xs - (std(Xs)/std(Ys)) * Ys   (alpha = 0 when std(Ys) == 0)
     * Mean-subtracted before returning.
     *
     * @return pulse signal, length == input length.
     */
    fun chrom(r: FloatArray, g: FloatArray, b: FloatArray): FloatArray {
        requireSameLength(r, g, b)
        val n = r.size
        if (n == 0) return FloatArray(0)

        val rn = normalize(r)
        val gn = normalize(g)
        val bn = normalize(b)

        val xs = FloatArray(n) { 3f * rn[it] - 2f * gn[it] }
        val ys = FloatArray(n) { 1.5f * rn[it] + gn[it] - 1.5f * bn[it] }

        val stdYs = std(ys)
        val alpha = if (stdYs == 0f) 0f else std(xs) / stdYs

        val p = FloatArray(n) { xs[it] - alpha * ys[it] }
        return meanSubtract(p)
    }

    private fun requireSameLength(r: FloatArray, g: FloatArray, b: FloatArray) {
        require(r.size == g.size && g.size == b.size) {
            "r/g/b channel arrays must have equal length: ${r.size}/${g.size}/${b.size}"
        }
    }

    /** Cn = C / mean(C). Returns all-zeros when mean == 0 to avoid divide-by-zero. */
    private fun normalize(c: FloatArray): FloatArray {
        val mean = c.sum() / c.size
        if (mean == 0f) return FloatArray(c.size)
        return FloatArray(c.size) { c[it] / mean }
    }

    /** Population standard deviation. */
    private fun std(x: FloatArray): Float {
        val n = x.size
        if (n == 0) return 0f
        val mean = x.sum() / n
        var acc = 0f
        for (v in x) {
            val d = v - mean
            acc += d * d
        }
        return sqrt(acc / n)
    }

    private fun meanSubtract(x: FloatArray): FloatArray {
        val mean = x.sum() / x.size
        return FloatArray(x.size) { x[it] - mean }
    }
}
