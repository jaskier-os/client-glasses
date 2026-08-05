package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure part of ScrollDrainer.
 *
 * The bug these guard against: `enqueue()` used to start an independent, self-sustaining
 * `postOnAnimation` drain chain per call, so N enqueues in one main-thread turn produced N
 * `scrollBy()` calls and N layout passes per frame over the SAME buffer.
 *
 * [FakeDrainer] mirrors `ScrollDrainer`'s ownership protocol exactly (claim on enqueue, re-post
 * while owned and non-empty, release on empty, generation-mismatch callbacks are no-ops) so the
 * chain-lifecycle invariants are testable without Android. Behaviour that genuinely needs a View --
 * detachment and a throwing `scrollBy` -- is covered by the instrumented test.
 */
class ScrollBufferTest {

    /** Simulates the Choreographer: queued (generation) callbacks, drained on demand. */
    private class FakeDrainer(val buffer: ScrollBuffer = ScrollBuffer()) {
        private val queue = ArrayDeque<Int>()
        var scrollByCalls = 0
            private set
        var appliedX = 0
            private set
        var appliedY = 0
            private set

        fun enqueue(dx: Int, dy: Int) {
            buffer.add(dx, dy)
            if (!buffer.isDraining) queue.addLast(buffer.startChain())
        }

        /** Run exactly one queued callback. Returns false when the queue is empty. */
        fun step(): Boolean {
            val generation = queue.removeFirstOrNull() ?: return false
            if (!buffer.isOwnedBy(generation)) return true   // stale callback: no-op
            if (buffer.isEmpty) { buffer.endChain(generation); return true }
            val (sx, sy) = buffer.takeSlice()
            scrollByCalls++
            appliedX += sx
            appliedY += sy
            if (!buffer.isEmpty && buffer.isOwnedBy(generation)) queue.addLast(generation)
            else buffer.endChain(generation)
            return true
        }

        /** Run one animation frame: every callback currently queued, and no more. */
        fun frame(): Int {
            val n = queue.size
            repeat(n) { step() }
            return n
        }

        fun runToQuiescence(maxFrames: Int = 5_000): Int {
            var frames = 0
            while (queue.isNotEmpty() && frames < maxFrames) { frame(); frames++ }
            return frames
        }

        val queued: Int get() = queue.size
    }

    // --- pixel conservation ---

    @Test
    fun `every enqueued pixel is eventually applied`() {
        val d = FakeDrainer()
        d.enqueue(0, 1000)
        d.runToQuiescence()
        assertEquals(1000, d.appliedY)
        assertTrue(d.buffer.isEmpty)
    }

    @Test
    fun `bursts accumulate rather than cancelling each other`() {
        val d = FakeDrainer()
        repeat(15) { d.enqueue(0, 40) }
        d.runToQuiescence()
        assertEquals(15 * 40, d.appliedY)
    }

    @Test
    fun `opposing deltas cancel arithmetically and drain to zero`() {
        val d = FakeDrainer()
        d.enqueue(0, 500)
        d.enqueue(0, -300)
        d.runToQuiescence()
        assertEquals(200, d.appliedY)
        assertTrue(d.buffer.isEmpty)
    }

    @Test
    fun `negative deltas drain fully`() {
        val d = FakeDrainer()
        d.enqueue(-777, 0)
        d.runToQuiescence()
        assertEquals(-777, d.appliedX)
    }

    @Test
    fun `pixels added mid-chain are still delivered`() {
        val d = FakeDrainer()
        d.enqueue(0, 400)
        d.frame()
        d.frame()
        // Arrives while a chain is already running -- must NOT start a second one.
        d.enqueue(0, 600)
        assertEquals("no second chain may be queued", 1, d.queued)
        d.runToQuiescence()
        assertEquals(1000, d.appliedY)
    }

    // --- the actual regression: one chain per view ---

    @Test
    fun `N enqueues in one turn issue one scrollBy per frame, not N`() {
        val d = FakeDrainer()
        repeat(8) { d.enqueue(0, 200) }
        assertEquals("exactly one chain for eight enqueues", 1, d.queued)
        repeat(5) { assertTrue("at most one scroll per frame", d.frame() <= 1) }
    }

    @Test
    fun `total scrollBy calls stay bounded under a heavy burst`() {
        val d = FakeDrainer()
        // 16 detents x 8 coalesced events x 120 px -- the worst remote-input burst.
        repeat(8) { d.enqueue(0, 16 * 120) }
        val frames = d.runToQuiescence()
        assertEquals(8 * 16 * 120, d.appliedY)
        assertEquals("one scrollBy per frame", frames, d.scrollByCalls)
        assertTrue("must not take pathologically many frames, took $frames", frames < 400)
    }

    // --- chain ownership ---

    @Test
    fun `clear releases ownership so the next enqueue can start a chain`() {
        val d = FakeDrainer()
        d.enqueue(0, 500)
        d.buffer.clear()
        assertFalse(d.buffer.isDraining)
        assertNull(d.buffer.owner)
        assertTrue(d.buffer.isEmpty)

        d.enqueue(0, 120)
        assertTrue("a fresh chain must start after clear", d.buffer.isDraining)
        d.runToQuiescence()
        assertEquals(120, d.appliedY)
    }

    @Test
    fun `a stale callback left over from a cleared chain is a no-op`() {
        val d = FakeDrainer()
        d.enqueue(0, 500)          // chain A queued
        d.buffer.clear()           // A abandoned; its callback is still queued
        d.enqueue(0, 300)          // chain B queued
        d.runToQuiescence()
        // A must not have applied any of the cleared 500 px, and B must deliver all 300.
        assertEquals(300, d.appliedY)
    }

    @Test
    fun `clear mid-chain drops the remainder without stranding the buffer`() {
        val d = FakeDrainer()
        d.enqueue(0, 1000)
        d.frame()
        val partial = d.appliedY
        assertTrue(partial in 1..999)
        d.buffer.clear()
        d.runToQuiescence()
        assertEquals("no further pixels after clear", partial, d.appliedY)
        assertFalse(d.buffer.isDraining)

        d.enqueue(0, 50)
        d.runToQuiescence()
        assertEquals(partial + 50, d.appliedY)
    }

    @Test
    fun `endChain only releases the owning generation`() {
        val b = ScrollBuffer()
        val a = b.startChain()
        b.endChain(a - 1)
        assertTrue("a foreign generation must not release ownership", b.isDraining)
        b.endChain(a)
        assertFalse(b.isDraining)
    }

    @Test
    fun `generations are never reused across chains`() {
        val b = ScrollBuffer()
        val seen = mutableSetOf<Int>()
        repeat(100) {
            val g = b.startChain()
            assertTrue("generation $g reused", seen.add(g))
            if (it % 2 == 0) b.clear() else b.endChain(g)
        }
    }

    // --- slice arithmetic ---

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
    fun `slicing is identical to the pre-extraction per-axis arithmetic`() {
        // The old drain() computed sliceDelta(p.dx) and sliceDelta(p.dy) independently and then
        // decremented each. takeSlice must be byte-for-byte equivalent.
        for (dx in listOf(0, 1, -1, 7, -7, 250, -250, 4000)) {
            for (dy in listOf(0, 1, -1, 7, -7, 250, -250, 4000)) {
                val b = ScrollBuffer()
                b.add(dx, dy)
                val expectedX = ScrollBuffer.sliceDelta(dx)
                val expectedY = ScrollBuffer.sliceDelta(dy)
                val (sx, sy) = b.takeSlice()
                assertEquals(expectedX, sx)
                assertEquals(expectedY, sy)
                assertEquals(dx - expectedX, b.dx)
                assertEquals(dy - expectedY, b.dy)
            }
        }
    }
}
