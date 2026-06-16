package com.repository.glasses.listener.reid.rppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Unit tests for [TrackBuffer]: the per-face-track 10 s ring that accumulates
 * timestamped RGB-mean samples and computes a smoothed BPM via
 * POS -> resample -> SpectralBpm -> BpmSmoother.
 */
class TrackBufferTest {

    @Test
    fun windowing_keepsOnlyLastTenSecondsByTimestamp() {
        val buf = TrackBuffer()
        // 20 s of samples at 50 ms spacing (20 fps): only the last 10 s survive.
        var t = 0L
        while (t <= 20_000L) {
            buf.addSample(t, 0.4f, 0.5f, 0.3f)
            t += 50L
        }
        val newest = 20_000L
        // No sample older than newest - windowMs (10_000) may remain.
        assertTrue("oldest timestamp must be within the 10s window", buf.oldestTMs()!! >= newest - buf.windowMs)
        // At 50 ms spacing a 10 s window holds ~201 samples, far fewer than the ~401 appended.
        assertTrue("window must have evicted old samples", buf.size() < 250)
        assertTrue("window must retain a full window of samples", buf.size() > 150)
    }

    @Test
    fun isReady_falseUntilWindowSpanned_thenTrue() {
        val buf = TrackBuffer()
        // Half a window of data -> not ready.
        var t = 0L
        while (t < 5_000L) {
            buf.addSample(t, 0.4f, 0.5f, 0.3f)
            t += 50L
        }
        assertEquals(false, buf.isReady())
        // Fill past 0.9 * windowMs of span -> ready.
        while (t <= 10_500L) {
            buf.addSample(t, 0.4f, 0.5f, 0.3f)
            t += 50L
        }
        assertEquals(true, buf.isReady())
    }

    @Test
    fun gapBelowThreshold_persistsBuffer() {
        val buf = TrackBuffer()
        buf.addSample(0L, 0.4f, 0.5f, 0.3f)
        buf.addSample(50L, 0.4f, 0.5f, 0.3f)
        val before = buf.size()
        // Gap of 1.0 s (< 1.5 s) -> keep prior samples.
        buf.addSample(1_050L, 0.4f, 0.5f, 0.3f)
        assertEquals(before + 1, buf.size())
    }

    @Test
    fun gapAtOrAboveThreshold_resetsBuffer() {
        val buf = TrackBuffer()
        buf.addSample(0L, 0.4f, 0.5f, 0.3f)
        buf.addSample(50L, 0.4f, 0.5f, 0.3f)
        buf.addSample(100L, 0.4f, 0.5f, 0.3f)
        // Gap of exactly 1.5 s -> discontinuity, only the new sample remains.
        buf.addSample(1_600L, 0.4f, 0.5f, 0.3f)
        assertEquals(1, buf.size())
        assertEquals(1_600L, buf.oldestTMs())
    }

    @Test
    fun compute_returnsNull_whenNotReady() {
        val buf = TrackBuffer()
        var t = 0L
        while (t < 3_000L) {
            buf.addSample(t, 0.4f, 0.5f, 0.3f)
            t += 66L
        }
        assertNull(buf.compute())
    }

    @Test
    fun reset_clearsSamplesAndSmoother() {
        val buf = TrackBuffer()
        var t = 0L
        while (t <= 11_000L) {
            buf.addSample(t, 0.4f, 0.5f, 0.3f)
            t += 66L
        }
        assertTrue(buf.size() > 0)
        buf.reset()
        assertEquals(0, buf.size())
        assertNull(buf.oldestTMs())
        assertNull("compute after reset must be null (no samples)", buf.compute())
    }

    @Test
    fun backwardsTimestampDropped() {
        val buf = TrackBuffer()
        buf.addSample(0L, 0.4f, 0.5f, 0.3f)
        buf.addSample(50L, 0.4f, 0.5f, 0.3f)
        buf.addSample(100L, 0.4f, 0.5f, 0.3f)
        val sizeBefore = buf.size()
        val oldestBefore = buf.oldestTMs()
        // A backwards (out-of-order) timestamp must be dropped without mutating state.
        buf.addSample(40L, 0.9f, 0.9f, 0.9f)
        assertEquals("backwards sample must not change size", sizeBefore, buf.size())
        assertEquals("backwards sample must not change oldest tMs", oldestBefore, buf.oldestTMs())
    }

    @Test
    fun duplicateTimestampsComputeReturnsNull() {
        // Build a buffer that passes isReady() (>= 30 samples spanning >= 0.9 * window)
        // but whose timestamps are duplicate clusters: 30 samples all at t=0 plus one at
        // t=windowMs. The only positive consecutive dt is the single 10 s jump, so the
        // resampler's median dt is 10_000 ms and the uniform grid is only 2 points -- far
        // below MIN_RESAMPLED_POINTS (4). compute() must hit that guard and return null,
        // never crashing on the degenerate (sub-4-point) resample.
        //
        // gapResetMs is raised above windowMs so the single 10 s jump does NOT trip the
        // discontinuity reset (which would clear the t=0 cluster and leave one sample).
        // (Note: an *empty* resample is unreachable once isReady() holds, because a
        // non-zero span forces at least one positive dt -> a non-zero median step; this
        // test instead exercises the named MIN_RESAMPLED_POINTS short-circuit. Reaching
        // m < 4 needs a large step, which needs a large dt that would otherwise gap-reset.)
        val buf = TrackBuffer(windowMs = 10_000L, gapResetMs = 20_000L)
        repeat(30) { buf.addSample(0L, 0.4f, 0.5f, 0.3f) }
        buf.addSample(buf.windowMs, 0.4f, 0.5f, 0.3f)
        assertTrue("expected the duplicate-cluster buffer to be ready", buf.isReady())
        assertNull("degenerate resample must return null, not crash", buf.compute())
    }

    @Test
    fun compute_endToEnd_recovers72Bpm() {
        val buf = TrackBuffer()
        val fps = 15.0
        val stepMs = (1000.0 / fps).toLong() // ~66 ms
        val freqHz = 1.2 // 72 bpm
        // Feed ~14 s so the smoother gets several compute() calls each over a full window.
        var t = 0L
        var lastBpm: Float? = null
        while (t <= 14_000L) {
            val tSec = t / 1000.0
            val g = 0.5f + (0.02 * sin(2.0 * PI * freqHz * tSec)).toFloat()
            buf.addSample(t, 0.4f, g, 0.3f)
            // Call compute roughly once per second of data to warm the smoother.
            if (t % 1000L < stepMs) {
                val bpm = buf.compute()
                if (bpm != null) lastBpm = bpm
            }
            t += stepMs
        }
        assertNotNull("expected a smoothed BPM after warm-up", lastBpm)
        assertTrue(
            "expected ~72 bpm, got $lastBpm",
            abs(lastBpm!! - 72f) <= 3f
        )
    }
}
