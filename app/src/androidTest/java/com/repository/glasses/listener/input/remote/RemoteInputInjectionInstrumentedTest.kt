package com.repository.glasses.listener.input.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device proof that the remote-input path works end to end on the real main-thread Looper.
 *
 * Everything below the sink is exercised by JVM unit tests; what those cannot cover is the thread
 * hop itself -- the queue's `post` lambda, the drain scheduling, and the ordering guarantee under a
 * real Looper that is also servicing the UI. This test drives the router directly (no Bluetooth,
 * no watch) so a failure means the glasses side is at fault and nothing else.
 *
 * Run with `adb shell am instrument`, NEVER `connectedAndroidTest` -- its teardown uninstalls the
 * app, and this app is deployed through the priv-app overlay slot.
 */
@RunWith(AndroidJUnit4::class)
class RemoteInputInjectionInstrumentedTest {

    private val mainHandler =
        android.os.Handler(InstrumentationRegistry.getInstrumentation().targetContext.mainLooper)

    private fun newRouter(sink: RemoteInputSink): RemoteInputRouter =
        RemoteInputRouter(
            clock = { android.os.SystemClock.elapsedRealtime() },
            post = { mainHandler.post(it) },
        ).also { it.setSink(sink) }

    /** Blocks until every runnable already queued on the main thread has run. */
    private fun drainMainThread(timeoutMs: Long = 5_000) {
        val latch = CountDownLatch(1)
        mainHandler.post { latch.countDown() }
        assertTrue("main thread did not drain", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
    }

    private class Collector : RemoteInputSink {
        val events = mutableListOf<RemoteInputEvent>()
        val threads = mutableSetOf<String>()
        override fun onRemoteInput(e: RemoteInputEvent) {
            threads.add(Thread.currentThread().name)
            events.add(e)
        }
    }

    @Test
    fun eventsArriveOnTheMainThreadInOrder() {
        val sink = Collector()
        val router = newRouter(sink)
        val source = FakeInputSource("watch")
        router.registerSource(source)

        source.open(sid = 1L)
        repeat(5) { source.scroll(sid = 1L, steps = 1) }
        drainMainThread()

        assertEquals("every scroll must be delivered", 5, sink.events.size)
        assertEquals(setOf("main"), sink.threads)
        // Sequence numbers must arrive monotonically: the queue may merge, never reorder.
        val seqs = sink.events.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
    }

    /**
     * A flood must not drop scroll DISTANCE and must not wedge the main thread.
     *
     * The queue is allowed to merge consecutive same-direction scrolls -- that is not loss, the
     * distance is summed -- so the assertion is on total delta rather than on event count.
     */
    @Test
    fun floodPreservesScrollDistanceWithoutStallingTheMainThread() {
        val sink = Collector()
        val router = newRouter(sink)
        val source = FakeInputSource("watch")
        router.registerSource(source)

        val count = 600
        source.open(sid = 1L)
        val startMs = android.os.SystemClock.elapsedRealtime()
        repeat(count) { source.scroll(sid = 1L, steps = 1) }
        drainMainThread(timeoutMs = 10_000)
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startMs

        val delivered = sink.events.filter { it.action == RemoteAction.SCROLL_STEP }.sumOf { it.delta }
        assertEquals("scroll distance must be conserved exactly", count, delivered)
        // Well inside the 5s ANR window; a regression that blocks the looper shows up here.
        assertTrue("flood took ${elapsedMs}ms, main thread likely stalled", elapsedMs < 5_000)
    }

    /**
     * Tap disambiguation must follow the SOURCE's clock, so the boundary cases are expressed as
     * `sinceLastMs` values rather than as real sleeps -- sleeping would test the transport's
     * jitter, which is exactly what this design refuses to depend on.
     */
    @Test
    fun tapIntervalsArePreservedAcrossTheHandoff() {
        val sink = Collector()
        val router = newRouter(sink)
        val source = FakeInputSource("watch")
        router.registerSource(source)

        source.open(sid = 1L)
        // Spacings that straddle the 400ms double-tap threshold in both directions.
        val spacings = listOf(100L, 200L, 399L, 401L, 500L)
        var wms = 10_000L
        source.tap(sid = 1L, wms = wms)
        for (gap in spacings) {
            wms += gap
            source.tap(sid = 1L, wms = wms)
        }
        drainMainThread()

        val taps = sink.events.filter { it.action == RemoteAction.TAP }
        assertEquals(spacings.size + 1, taps.size)
        assertEquals(
            "the first tap of a session has no predecessor",
            RemoteInputEvent.NO_PREDECESSOR,
            taps.first().sinceLastMs,
        )
        assertEquals(
            "the source's measured intervals must survive the queue and the thread hop",
            spacings.map { it.toInt() },
            taps.drop(1).map { it.sinceLastMs },
        )
    }

    @Test
    fun threeRapidTapsAllArrive() {
        val sink = Collector()
        val router = newRouter(sink)
        val source = FakeInputSource("watch")
        router.registerSource(source)

        source.open(sid = 1L)
        var wms = 5_000L
        repeat(3) { source.tap(sid = 1L, wms = wms); wms += 120L }
        drainMainThread()

        // Taps are discrete and must never be merged the way scrolls are.
        assertEquals(3, sink.events.count { it.action == RemoteAction.TAP })
    }

    @Test
    fun tapsInterleavedWithScrollKeepTheirOrder() {
        val sink = Collector()
        val router = newRouter(sink)
        val source = FakeInputSource("watch")
        router.registerSource(source)

        source.open(sid = 1L)
        source.scroll(sid = 1L, steps = 1)
        source.tap(sid = 1L)
        source.scroll(sid = 1L, steps = -1)
        source.tap(sid = 1L)
        drainMainThread()

        val actions = sink.events.map { it.action }
        // A tap must never be reordered past a scroll: it would select the wrong row.
        assertEquals(
            listOf(
                RemoteAction.SCROLL_STEP,
                RemoteAction.TAP,
                RemoteAction.SCROLL_STEP,
                RemoteAction.TAP,
            ),
            actions,
        )
    }

    @Test
    fun eventsWithNoSinkAreDroppedRatherThanReplayedLater() {
        val sink = Collector()
        val router = newRouter(sink)
        val source = FakeInputSource("watch")
        router.registerSource(source)

        source.open(sid = 1L)
        router.clearSink(sink)
        repeat(10) { source.scroll(sid = 1L, steps = 1) }
        drainMainThread()

        assertEquals("nothing may be delivered while detached", 0, sink.events.size)

        // Re-attaching must not flush the backlog: a burst of stale scrolls would move a list the
        // user stopped looking at seconds ago.
        router.setSink(sink)
        drainMainThread()
        assertEquals("the backlog must not be replayed on re-attach", 0, sink.events.size)
    }

    /**
     * The proof that a future device needs no UI changes: a second source with a different id gets
     * its own session and sequence space, and both are delivered through the one sink.
     */
    @Test
    fun twoSourcesAreIsolatedAndBothDeliver() {
        val sink = Collector()
        val router = newRouter(sink)
        val watch = FakeInputSource("watch")
        val gadget = FakeInputSource("gadget")
        router.registerSource(watch)
        router.registerSource(gadget)

        watch.open(sid = 1L)
        gadget.open(sid = 1L)
        watch.scroll(sid = 1L, steps = 1)
        gadget.scroll(sid = 1L, steps = 1)
        drainMainThread()

        assertEquals(setOf("watch", "gadget"), sink.events.map { it.sourceId }.toSet())
    }

    @Test
    fun concurrentProducersDoNotLoseOrCorruptEvents() {
        val sink = Collector()
        val router = newRouter(sink)
        val source = FakeInputSource("watch")
        router.registerSource(source)
        source.open(sid = 1L)

        // The real transport delivers on a Bluetooth callback thread, so hammer from off-main.
        val perThread = 100
        val threads = 4
        val seq = AtomicInteger(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                repeat(perThread) {
                    source.scroll(sid = 1L, steps = 1, seqOverride = seq.incrementAndGet().toLong())
                }
                done.countDown()
            }.start()
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        drainMainThread(timeoutMs = 10_000)

        val seqs = sink.events.map { it.seq }
        assertEquals("delivery order must match admission order", seqs.sorted(), seqs)
        assertTrue("nothing should have been delivered twice", seqs.toSet().size == seqs.size)
    }
}
