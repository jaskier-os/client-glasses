package com.repository.glasses.listener.reid.rppg

import kotlin.math.abs

/**
 * Temporal smoother that turns a noisy stream of per-second [BpmEstimate]s (one
 * per face track, fed once/sec by a later pipeline stage) into a stable displayed
 * heart rate. Stateful: construct one instance per track.
 *
 * Stages applied to each [update]:
 *   1. Confidence gate -- estimates with [BpmEstimate.bandRatio] below
 *      [gateBandRatio] are ignored (too noisy to trust). A gated estimate never
 *      corrupts state: if a value is already locked it is returned unchanged; while
 *      still warming (nothing locked yet) null is returned.
 *   2. Clamp -- the incoming bpm is coerced into [[minBpm], [maxBpm]] before any
 *      comparison or accumulation, so an absurd input can never leak into the ring
 *      or the jump logic. SpectralBpm already clamps, but this class is robust on
 *      its own and clamps its output independently.
 *   3. Jump rejection vs sustained-shift acceptance -- once a displayed value is
 *      established, a candidate further than [maxJumpBpm] from it is NOT accepted
 *      immediately. It is held as a "pending shift": consecutive in-gate candidates
 *      that are all far from the current display AND near each other (within
 *      [maxJumpBpm] of the first pending candidate) build a counter. Only after
 *      [jumpPersistCount] such consecutive candidates is the shift accepted. Any
 *      candidate that is close to the display again, or that breaks away from the
 *      pending cluster, resets the pending counter -- so a one-off outlier (a single
 *      140 amid 70s) is discarded while a genuine held change (85 for 3+ samples)
 *      is taken.
 *   4. Median + EMA -- accepted bpms enter a ring of the last [historySize]; the
 *      ring MEDIAN is EMA-blended into the display:
 *      `displayed = emaAlpha*median + (1-emaAlpha)*displayedPrev`
 *      (initialised to the median on the first accept).
 *
 * Warm-up rule: [update] returns null until the FIRST accepted high-confidence
 * estimate; from then on it returns the (clamped) displayed value. The first
 * in-gate estimate is always an accept (no display to jump from yet), so a single
 * good sample locks the display.
 *
 * Dependency-free (kotlin.math only).
 */
class BpmSmoother(
    val gateBandRatio: Float = 0.5f,
    val historySize: Int = 7,
    val emaAlpha: Float = 0.25f,
    val maxJumpBpm: Float = 15f,
    val jumpPersistCount: Int = 3,
    val minBpm: Float = 42f,
    val maxBpm: Float = 240f,
) {
    private val ring = ArrayDeque<Float>()
    private var displayed: Float? = null

    // Pending-shift tracking.
    private var pendingAnchor: Float = 0f
    private var pendingCount: Int = 0

    /**
     * Feed one estimate; returns the current displayed BPM, or null while gated or
     * still warming (see class KDoc for the exact gate/warm-up return rule).
     */
    fun update(e: BpmEstimate): Float? {
        // 1. Confidence gate -- ignore noisy estimates without touching state.
        if (e.bandRatio < gateBandRatio) {
            return displayed
        }

        // 2. Clamp the candidate before any comparison or accumulation.
        val candidate = e.bpm.coerceIn(minBpm, maxBpm)

        val current = displayed
        if (current == null) {
            // First accept: lock the display.
            return accept(candidate)
        }

        // 3. Jump rejection vs sustained-shift acceptance.
        if (abs(candidate - current) <= maxJumpBpm) {
            // Close to the display: normal accept, clear any pending shift.
            pendingCount = 0
            return accept(candidate)
        }

        // Candidate is far from the display -> potential shift.
        if (pendingCount > 0 && abs(candidate - pendingAnchor) <= maxJumpBpm) {
            // Consistent with the pending cluster.
            pendingCount++
        } else {
            // Start (or restart) a pending cluster on this outlier.
            pendingAnchor = candidate
            pendingCount = 1
        }

        if (pendingCount >= jumpPersistCount) {
            // The shift persisted: accept it.
            pendingCount = 0
            return accept(candidate)
        }

        // Shift not yet confirmed: reject this sample, keep the old display.
        return current
    }

    /** Clear all state; the next [update] behaves like a fresh instance. */
    fun reset() {
        ring.clear()
        displayed = null
        pendingAnchor = 0f
        pendingCount = 0
    }

    /** Push into the ring, recompute median + EMA, return the new clamped display. */
    private fun accept(bpm: Float): Float {
        ring.addLast(bpm)
        while (ring.size > historySize) ring.removeFirst()

        val median = medianOf(ring)
        val prev = displayed
        val next = if (prev == null) median else emaAlpha * median + (1f - emaAlpha) * prev
        val clamped = next.coerceIn(minBpm, maxBpm)
        displayed = clamped
        return clamped
    }

    private fun medianOf(values: Collection<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        }
    }
}
