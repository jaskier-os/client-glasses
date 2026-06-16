package com.repository.glasses.listener.reid.rppg

/**
 * Per-face-track rolling buffer of timestamped spatial-mean RGB samples that, on
 * demand, computes a smoothed heart rate.
 *
 * One instance lives per active face track. A later (non-Phase-0) driver creates
 * the instance and feeds it [addSample] once per camera frame, then calls
 * [compute] (typically about once per second) to obtain the displayed BPM.
 *
 * Pipeline glued here (the Phase-0 siblings):
 *   per-channel [Resampler.resampleUniform] -> [RppgSignal.pos] -> [SpectralBpm.estimate] -> [BpmSmoother.update]
 *
 * Window / readiness / discontinuity rules:
 *   - Window: only the most recent [windowMs] of samples (by timestamp) are kept;
 *     older samples are evicted from the front on every [addSample].
 *   - Readiness ([isReady]): true once the buffered span
 *     (newest - oldest timestamp) reaches at least [READY_SPAN_FRACTION] * [windowMs]
 *     AND there are at least [MIN_READY_SAMPLES] samples. Below that, [compute]
 *     returns null without touching the smoother, so the smoother warm-up is honored.
 *   - Discontinuity: a gap of [gapResetMs] or more between the previous and the new
 *     timestamp clears all samples AND resets the smoother (the pulse signal is
 *     discontinuous and the prior phase is meaningless). A smaller gap persists the
 *     buffer.
 *
 * Precondition: timestamps passed to [addSample] are monotonically non-decreasing
 * (the camera frame clock). Out-of-order timestamps are not handled.
 *
 * compute() channel order: each of r, g, b is resampled onto the SAME uniform grid
 * derived from the shared timestamps first, so POS receives all three channels on a
 * common timebase; then POS produces the 1-D pulse and SpectralBpm estimates from it.
 *
 * Dependency-free beyond the rppg siblings + kotlin stdlib. Not thread-safe.
 */
class TrackBuffer(
    val windowMs: Long = 10_000L,
    val gapResetMs: Long = 1_500L,
    private val smoother: BpmSmoother = BpmSmoother(),
) {
    private val tMs = ArrayDeque<Long>()
    private val rs = ArrayDeque<Float>()
    private val gs = ArrayDeque<Float>()
    private val bs = ArrayDeque<Float>()

    /**
     * Append one spatial-mean RGB sample at [tMs] (ms). See class KDoc for the
     * monotonic-timestamp precondition and the gap-persist vs gap-reset rule.
     */
    fun addSample(tMs: Long, r: Float, g: Float, b: Float) {
        val last = this.tMs.lastOrNull()
        if (last != null && tMs - last >= gapResetMs) {
            // Discontinuity: drop the old window and the stale smoother phase.
            clearSamples()
            smoother.reset()
        }
        this.tMs.addLast(tMs)
        rs.addLast(r)
        gs.addLast(g)
        bs.addLast(b)
        evictOlderThan(tMs - windowMs)
    }

    /** See class KDoc for the exact readiness rule. */
    fun isReady(): Boolean {
        val n = tMs.size
        if (n < MIN_READY_SAMPLES) return false
        val span = tMs.last() - tMs.first()
        return span >= (windowMs * READY_SPAN_FRACTION).toLong()
    }

    /**
     * Run the end-to-end pipeline over the current window and feed the result to the
     * smoother. Returns the smoothed displayed BPM, or null while not [isReady] or
     * while the smoother is still warming / gating.
     */
    fun compute(): Float? {
        if (!isReady()) return null

        val n = tMs.size
        val t = LongArray(n)
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        var i = 0
        val tIt = tMs.iterator()
        val rIt = rs.iterator()
        val gIt = gs.iterator()
        val bIt = bs.iterator()
        while (tIt.hasNext()) {
            t[i] = tIt.next()
            r[i] = rIt.next()
            g[i] = gIt.next()
            b[i] = bIt.next()
            i++
        }

        // Resample each channel onto the same uniform grid (common timebase for POS).
        val ru = Resampler.resampleUniform(t, r)
        val gu = Resampler.resampleUniform(t, g)
        val bu = Resampler.resampleUniform(t, b)
        val fps = ru.fps
        if (fps <= 0f || ru.values.isEmpty()) return null
        val m = minOf(ru.values.size, gu.values.size, bu.values.size)
        if (m < 4) return null

        val rc = ru.values.copyOf(m)
        val gc = gu.values.copyOf(m)
        val bc = bu.values.copyOf(m)

        val pulse = RppgSignal.pos(rc, gc, bc)
        val estimate = SpectralBpm.estimate(pulse, fps)
        return smoother.update(estimate)
    }

    /** Clear all buffered samples and reset the smoother. */
    fun reset() {
        clearSamples()
        smoother.reset()
    }

    /** Number of buffered samples. */
    fun size(): Int = tMs.size

    /** Oldest buffered timestamp, or null when empty. */
    fun oldestTMs(): Long? = tMs.firstOrNull()

    private fun clearSamples() {
        tMs.clear()
        rs.clear()
        gs.clear()
        bs.clear()
    }

    private fun evictOlderThan(cutoff: Long) {
        while (tMs.isNotEmpty() && tMs.first() < cutoff) {
            tMs.removeFirst()
            rs.removeFirst()
            gs.removeFirst()
            bs.removeFirst()
        }
    }

    private companion object {
        const val READY_SPAN_FRACTION = 0.9f
        const val MIN_READY_SAMPLES = 30
    }
}
