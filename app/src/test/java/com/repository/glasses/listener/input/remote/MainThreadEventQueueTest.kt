package com.repository.glasses.listener.input.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadEventQueueTest {

    private fun scroll(delta: Int, seq: Long = 1L) = RemoteInputEvent(
        action = RemoteAction.SCROLL_STEP, delta = delta,
        sourceId = "watch", sid = 1L, seq = seq, ageMs = 0, sinceLastMs = -1,
    )

    private fun tap(seq: Long = 1L) = RemoteInputEvent(
        action = RemoteAction.TAP, delta = 0,
        sourceId = "watch", sid = 1L, seq = seq, ageMs = 0, sinceLastMs = -1,
    )

    private class Harness(maxEntries: Int = MainThreadEventQueue.DEFAULT_MAX_ENTRIES) {
        val poster = ManualPoster()
        val delivered = mutableListOf<RemoteInputEvent>()
        val queue = MainThreadEventQueue(maxEntries = maxEntries, post = poster.post)

        init {
            queue.setDeliverer { delivered.add(it) }
        }

        fun flush() = poster.runAll()
        fun totalDelta() = delivered.sumOf { it.delta }
    }

    @Test
    fun `events are delivered in order`() {
        val h = Harness()
        h.queue.enqueue(scroll(1, 1))
        h.queue.enqueue(tap(2))
        h.queue.enqueue(scroll(-1, 3))
        h.flush()
        assertEquals(
            listOf(RemoteAction.SCROLL_STEP, RemoteAction.TAP, RemoteAction.SCROLL_STEP),
            h.delivered.map { it.action },
        )
    }

    @Test
    fun `only one drain is posted per burst`() {
        val h = Harness()
        repeat(10) { h.queue.enqueue(tap(it.toLong())) }
        assertEquals("a burst must post exactly one drain", 1, h.poster.pendingCount)
    }

    @Test
    fun `consecutive same-direction scrolls merge by summing, conserving distance`() {
        val h = Harness()
        h.queue.enqueue(scroll(2, 1))
        h.queue.enqueue(scroll(3, 2))
        h.queue.enqueue(scroll(1, 3))
        h.flush()
        assertEquals("merged into one event", 1, h.delivered.size)
        assertEquals("no distance lost", 6, h.totalDelta())
    }

    @Test
    fun `opposite directions never merge`() {
        val h = Harness()
        h.queue.enqueue(scroll(3, 1))
        h.queue.enqueue(scroll(-3, 2))
        h.flush()
        assertEquals(2, h.delivered.size)
        assertEquals(listOf(3, -3), h.delivered.map { it.delta })
    }

    @Test
    fun `a discrete action breaks the merge run`() {
        val h = Harness()
        h.queue.enqueue(scroll(1, 1))
        h.queue.enqueue(tap(2))
        h.queue.enqueue(scroll(1, 3))
        h.flush()
        assertEquals(3, h.delivered.size)
    }

    @Test
    fun `a merged event carries the latest sequence number`() {
        val h = Harness()
        h.queue.enqueue(scroll(1, 5))
        h.queue.enqueue(scroll(1, 6))
        h.flush()
        assertEquals(6L, h.delivered.single().seq)
    }

    @Test
    fun `scrolls from different sources never merge`() {
        val h = Harness()
        h.queue.enqueue(scroll(1, 1))
        h.queue.enqueue(scroll(1, 2).copy(sourceId = "ble_gadget"))
        h.flush()
        assertEquals(2, h.delivered.size)
    }

    @Test
    fun `scrolls from different sessions never merge`() {
        val h = Harness()
        h.queue.enqueue(scroll(1, 1))
        h.queue.enqueue(scroll(1, 2).copy(sid = 2L))
        h.flush()
        assertEquals(2, h.delivered.size)
    }

    // --- the requirement: carry surplus, never discard scroll distance ---

    @Test
    fun `an overflowing queue conserves every scroll step`() {
        val h = Harness(maxEntries = 4)
        // Alternate directions so nothing merges into the tail, then flood.
        var seq = 0L
        var expected = 0
        repeat(200) {
            val d = if (it % 2 == 0) 3 else -3
            expected += d
            h.queue.enqueue(scroll(d, ++seq))
        }
        h.flush()
        assertEquals("scroll distance must survive an overflowing queue", expected, h.totalDelta())
    }

    @Test
    fun `a stalled UI thread does not lose scroll distance`() {
        val h = Harness(maxEntries = 8)
        var seq = 0L
        // 500 same-direction detents arrive while nothing drains.
        repeat(500) { h.queue.enqueue(scroll(1, ++seq)) }
        assertTrue("the queue must stay bounded", h.queue.size <= 8)
        h.flush()
        assertEquals("every step must survive", 500, h.totalDelta())
    }

    @Test
    fun `the queue stays bounded under a mixed flood`() {
        val h = Harness(maxEntries = 8)
        var seq = 0L
        repeat(1000) {
            if (it % 3 == 0) h.queue.enqueue(tap(++seq))
            else h.queue.enqueue(scroll(if (it % 2 == 0) 1 else -1, ++seq))
        }
        assertTrue("bounded, got ${h.queue.size}", h.queue.size <= 8)
        assertTrue("some discrete actions were shed", h.queue.dropped > 0)
    }

    // --- drain-loop correctness ---

    @Test
    fun `an event enqueued during delivery is still delivered`() {
        val h = Harness()
        var reentered = false
        h.queue.setDeliverer {
            h.delivered.add(it)
            if (!reentered) {
                reentered = true
                // Re-entrant enqueue from inside delivery: the classic lost-wakeup case.
                h.queue.enqueue(tap(99))
            }
        }
        h.queue.enqueue(scroll(1, 1))
        h.flush()
        assertEquals("the re-entrant event must not be stranded", 2, h.delivered.size)
        assertEquals(RemoteAction.TAP, h.delivered[1].action)
    }

    @Test
    fun `the queue can be drained repeatedly without stalling`() {
        val h = Harness()
        repeat(20) { round ->
            h.queue.enqueue(scroll(1, round.toLong()))
            h.flush()
        }
        assertEquals(20, h.delivered.size)
    }

    @Test
    fun `a throwing deliverer does not strand the remaining queue`() {
        val h = Harness()
        var n = 0
        h.queue.setDeliverer {
            h.delivered.add(it)
            if (++n == 1) throw IllegalStateException("boom")
        }
        h.queue.enqueue(scroll(1, 1))
        h.queue.enqueue(scroll(-1, 2))
        h.queue.enqueue(tap(3))
        h.flush()
        assertEquals(3, h.delivered.size)
    }

    @Test
    fun `clear discards the backlog`() {
        val h = Harness()
        h.queue.enqueue(scroll(1, 1))
        h.queue.enqueue(scroll(-1, 2))
        h.queue.clear()
        h.flush()
        assertTrue(h.delivered.isEmpty())
        // ...and the queue is usable afterwards.
        h.queue.enqueue(scroll(5, 3))
        h.flush()
        assertEquals(1, h.delivered.size)
    }

    @Test
    fun `overflow never reorders motion past a queued tap`() {
        // Folding a new scroll into an arbitrary earlier one conserves pixels but applies motion
        // that happened AFTER a tap BEFORE it -- the tap would act on something the user never saw.
        // Two taps: the queue sheds the OLDEST discrete entry on overflow, so the second survives
        // and its ordering relative to the surrounding motion must be exactly preserved.
        val h = Harness(maxEntries = 4)
        h.queue.enqueue(scroll(10, 1))
        h.queue.enqueue(tap(2))
        h.queue.enqueue(scroll(-10, 3))
        h.queue.enqueue(tap(4))
        h.queue.enqueue(scroll(20, 5))   // overflow: sheds the FIRST tap
        h.flush()
        val tapIndex = h.delivered.indexOfFirst { it.action == RemoteAction.TAP }
        assertTrue("a tap must still be present", tapIndex >= 0)
        val before = h.delivered.take(tapIndex).sumOf { it.delta }
        assertEquals("motion after the tap must not be applied before it", 0, before)
        assertEquals("the newest event is still last", 20, h.delivered.last().delta)
    }

    @Test
    fun `overflow of an all-scroll queue folds adjacent entries and keeps order`() {
        val h = Harness(maxEntries = 3)
        // Alternating so nothing tail-merges.
        h.queue.enqueue(scroll(1, 1))
        h.queue.enqueue(scroll(-2, 2))
        h.queue.enqueue(scroll(4, 3))
        h.queue.enqueue(scroll(-8, 4))   // overflow: folds entry 0 into entry 1
        h.flush()
        assertEquals("net distance is conserved", 1 - 2 + 4 - 8, h.totalDelta())
        assertEquals("the newest event is still last", -8, h.delivered.last().delta)
    }

    @Test
    fun `a merged event carries the latest timing so tap intervals stay correct`() {
        val h = Harness()
        h.queue.enqueue(scroll(1, 1).copy(sinceLastMs = 500, ageMs = 5))
        h.queue.enqueue(scroll(1, 2).copy(sinceLastMs = 80, ageMs = 12))
        h.flush()
        val merged = h.delivered.single()
        assertEquals(2, merged.delta)
        assertEquals("interval must be to the newest event", 80, merged.sinceLastMs)
        assertEquals(12, merged.ageMs)
    }

    @Test
    fun `a failed post does not stall the queue permanently`() {
        var fail = true
        val delivered = mutableListOf<RemoteInputEvent>()
        val runnables = ArrayDeque<Runnable>()
        val q = MainThreadEventQueue(post = {
            if (fail) throw IllegalStateException("dead looper") else runnables.addLast(it)
        })
        q.setDeliverer { delivered.add(it) }

        try {
            q.enqueue(scroll(1, 1))
        } catch (_: IllegalStateException) {
        }

        fail = false
        q.enqueue(scroll(1, 2))
        while (runnables.isNotEmpty()) runnables.removeFirst().run()
        assertTrue("the queue must recover once posting works again", delivered.isNotEmpty())
    }

    @Test
    fun `concurrent producers lose no scroll distance`() {
        val h = Harness(maxEntries = 8)
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(500) { i -> h.queue.enqueue(scroll(1, (t * 1000 + i).toLong())) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        h.flush()
        assertEquals("every step from every producer must survive", 4 * 500, h.totalDelta())
    }
}
