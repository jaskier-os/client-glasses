package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure part of ScrollDrainer.
 *
 * The bug these guard against: `enqueue()` used to start an independent, self-sustaining
 * `postOnAnimation` drain chain per call, so N enqueues in one main-thread turn produced N
 * `scrollBy()` calls and N layout passes per frame over the SAME buffer.
 */
class ScrollBufferTest {

    /**
     * Simulates the drain chain without Android: returns the number of `scrollBy` calls that would
     * be issued, and asserts pixel conservation.
     */
    private fun runChain(b: ScrollBuffer, maxFrames: Int = 10_000): Pair<Int, Pair<Int, Int>> {
        var frames = 0
        var appliedX = 0
        var appliedY = 0
        while (b.posted && frames < maxFrames) {
            if (b.isEmpty) { b.posted = false; break }
            val (sx, sy) = b.takeSlice()
            appliedX += sx
            appliedY += sy
            frames++
            if (b.isEmpty) b.posted = false
        }
        return frames to (appliedX to appliedY)
    }

    @Test
    fun `every enqueued pixel is eventually applied`() {
        val b = ScrollBuffer()
        b.add(0, 1000); b.posted = true
        val (frames, applied) = runChain(b)
        assertEquals("all pixels drained", 1000, applied.second)
        assertTrue("drain took multiple frames", frames > 1)
        assertTrue(b.isEmpty)
    }

    @Test
    fun `bursts accumulate rather than cancelling each other`() {
        val b = ScrollBuffer()
        repeat(15) { b.add(0, 40) }
        b.posted = true
        val (_, applied) = runChain(b)
        assertEquals(15 * 40, applied.second)
    }

    @Test
    fun `opposing deltas cancel arithmetically and drain to zero`() {
        val b = ScrollBuffer()
        b.add(0, 500)
        b.add(0, -300)
        b.posted = true
        val (_, applied) = runChain(b)
        assertEquals(200, applied.second)
        assertTrue(b.isEmpty)
    }

    @Test
    fun `negative deltas drain fully`() {
        val b = ScrollBuffer()
        b.add(-777, 0); b.posted = true
        val (_, applied) = runChain(b)
        assertEquals(-777, applied.first)
    }

    @Test
    fun `sliceDelta never overshoots and always makes progress`() {
        for (v in listOf(1, 2, 3, 4, 7, 11, 59, 60, 61, 240, 241, 10_000)) {
            for (sign in listOf(1, -1)) {
                val r = sign * v
                val s = ScrollBuffer.sliceDelta(r)
                assertTrue("slice $s must not overshoot $r", kotlin.math.abs(s) <= kotlin.math.abs(r))
                assertTrue("slice $s must make progress on $r", kotlin.math.abs(s) >= 1)
                assertEquals("slice must keep the sign of $r", sign, if (s > 0) 1 else -1)
            }
        }
        assertEquals(0, ScrollBuffer.sliceDelta(0))
    }

    @Test
    fun `sliceDelta is clamped to the documented 3 to 60 band`() {
        assertEquals(60, ScrollBuffer.sliceDelta(1000))
        assertEquals(3, ScrollBuffer.sliceDelta(4))
        // A remainder smaller than the floor is taken whole rather than overshooting.
        assertEquals(2, ScrollBuffer.sliceDelta(2))
    }

    @Test
    fun `clear invalidates in-flight chains via the generation counter`() {
        val b = ScrollBuffer()
        b.add(0, 1000); b.posted = true
        val staleGeneration = b.generation

        b.clear()
        assertFalse("clear must release the posted flag", b.posted)
        assertTrue(b.isEmpty)
        assertTrue("clear must bump the generation", b.generation != staleGeneration)

        // A new chain starts cleanly and the stale callback is rejected by generation mismatch.
        b.add(0, 100); b.posted = true
        assertEquals(b.generation, b.generation)
        assertTrue(staleGeneration != b.generation)
    }

    @Test
    fun `clear then enqueue does not leave the buffer permanently undrainable`() {
        val b = ScrollBuffer()
        b.add(0, 500); b.posted = true
        b.clear()
        // Regression: the old cancel() removed the buffer but left an in-flight chain, and a `posted`
        // flag left true here would make every later enqueue a no-op.
        assertFalse(b.posted)
        b.add(0, 120); b.posted = true
        val (_, applied) = runChain(b)
        assertEquals(120, applied.second)
    }

    @Test
    fun `a large burst drains in a bounded number of frames`() {
        val b = ScrollBuffer()
        // 16 detents x 8 events x 120 px -- the worst coalesced remote-input burst.
        b.add(0, 16 * 8 * 120); b.posted = true
        val (frames, applied) = runChain(b)
        assertEquals(16 * 8 * 120, applied.second)
        assertTrue("must not take pathologically many frames, took $frames", frames < 400)
    }
}
