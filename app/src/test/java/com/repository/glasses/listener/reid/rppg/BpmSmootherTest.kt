package com.repository.glasses.listener.reid.rppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for [BpmSmoother]. All deterministic; thresholds passed explicitly where
 * relevant so the spike-reject and sustained-shift cases run under identical params.
 */
class BpmSmootherTest {

    private fun hi(bpm: Float) = BpmEstimate(bpm, 0.9f)
    private fun lo(bpm: Float) = BpmEstimate(bpm, 0.1f)

    @Test
    fun confidenceGate_belowThreshold_returnsNullWhileWarming() {
        val s = BpmSmoother(gateBandRatio = 0.5f)
        // Low-confidence estimates never lock, even with a plausible bpm.
        assertNull(s.update(lo(72f)))
        assertNull(s.update(lo(72f)))
        assertNull(s.update(lo(72f)))
    }

    @Test
    fun confidenceGate_highConfidence_eventuallyReturnsNumber() {
        val s = BpmSmoother(gateBandRatio = 0.5f)
        var last: Float? = null
        repeat(5) { last = s.update(hi(72f)) }
        assertNotNull(last)
        assertEquals(72f, last!!, 1.5f)
    }

    @Test
    fun spike_singleOutlierRejected_displayStaysLow() {
        val s = BpmSmoother()
        s.update(hi(70f))
        s.update(hi(71f))
        s.update(hi(72f))
        s.update(hi(71f))
        val afterSpike = s.update(hi(140f))!!
        // The 140 spike must not move the display anywhere near 140.
        assertTrue("spike leaked into display: $afterSpike", afterSpike < 90f)
        val recovered = s.update(hi(71f))!!
        assertTrue("display drifted off ~71: $recovered", abs(recovered - 71f) < 8f)
    }

    @Test
    fun sustainedShift_accepted_displayMovesTowardNewLevel() {
        val s = BpmSmoother()
        // Lock around 70.
        repeat(5) { s.update(hi(70f)) }
        val locked = s.update(hi(70f))!!
        assertEquals(70f, locked, 2f)
        // Sustained shift to ~85 held for several estimates.
        var d = locked
        repeat(8) { d = s.update(hi(85f))!! }
        assertTrue("sustained shift not accepted, still $d", d > 80f)
    }

    @Test
    fun clamp_highOutOfRange_clampedToMax() {
        val s = BpmSmoother(maxBpm = 240f)
        var d: Float? = null
        repeat(6) { d = s.update(hi(300f)) }
        assertNotNull(d)
        assertTrue("not clamped to <=240: $d", d!! <= 240f)
    }

    @Test
    fun clamp_lowOutOfRange_clampedToMin() {
        val s = BpmSmoother(minBpm = 42f)
        var d: Float? = null
        repeat(6) { d = s.update(hi(20f)) }
        assertNotNull(d)
        assertTrue("not clamped to >=42: $d", d!! >= 42f)
    }

    @Test
    fun warmup_returnsNullBeforeFirstAccept() {
        val s = BpmSmoother()
        // First high-confidence estimate already produces a value (first accept).
        assertNotNull(s.update(hi(72f)))
    }

    @Test
    fun reset_clearsState() {
        val s = BpmSmoother()
        repeat(5) { s.update(hi(70f)) }
        assertNotNull(s.update(hi(70f)))
        s.reset()
        // After reset a gated estimate must return null (no last-good retained).
        assertNull(s.update(lo(70f)))
    }

    @Test
    fun nonFiniteEstimateIgnored() {
        // Warmed smoother: NaN bpm and Inf bandRatio must not change the display.
        val s = BpmSmoother()
        repeat(5) { s.update(hi(72f)) }
        val before = s.update(hi(72f))!!
        assertEquals(72f, before, 1.5f)
        assertEquals(before, s.update(BpmEstimate(Float.NaN, 0.9f))!!, 1e-3f)
        assertEquals(before, s.update(BpmEstimate(72f, Float.POSITIVE_INFINITY))!!, 1e-3f)
        // A following good estimate still works (state not corrupted).
        val after = s.update(hi(72f))!!
        assertEquals(72f, after, 1.5f)

        // Fresh smoother: non-finite returns null and does not corrupt state.
        val f = BpmSmoother()
        assertNull(f.update(BpmEstimate(Float.NaN, 0.9f)))
        assertNull(f.update(BpmEstimate(72f, Float.POSITIVE_INFINITY)))
        assertNotNull(f.update(hi(72f)))
    }

    @Test
    fun alternatingSpikesNeverWedge() {
        val s = BpmSmoother()
        floatArrayOf(72f, 140f, 72f, 145f, 72f, 138f, 72f).forEach { s.update(hi(it)) }
        val d = s.update(hi(72f))!!
        // Alternating spikes never persist (counter keeps resetting), display stays ~72.
        assertTrue("alternating spikes leaked into display: $d", d < 90f)
    }

    @Test
    fun evenRingMedianExact() {
        // historySize=2 -> even ring; emaAlpha=1f -> EMA returns the median directly.
        val s = BpmSmoother(historySize = 2, emaAlpha = 1f)
        s.update(hi(70f))
        val d = s.update(hi(72f))!!
        assertEquals(71.0f, d, 1e-3f)
    }
}
