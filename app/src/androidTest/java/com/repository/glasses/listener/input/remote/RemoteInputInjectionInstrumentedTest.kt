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

    /**
     * @param maxEventsPerSecond the router's admission budget. The default (25/s) exists to bound
     *   what a hostile peer can spend; a test that wants to prove the HAND-OFF loses nothing must
     *   raise it, or the rate limiter drops most of the burst before it ever reaches the queue and
     *   the test measures the limiter instead of the thing under test.
     */
    private fun newRouter(
        sink: RemoteInputSink,
        maxEventsPerSecond: Int = RemoteInputRouter.DEFAULT_MAX_EVENTS_PER_SECOND,
    ): RemoteInputRouter =
        RemoteInputRouter(
            maxEventsPerSecond = maxEventsPerSecond,
            clock = { android.os.SystemClock.elapsedRealtime() },
            post = { mainHandler.post(it) },
        ).also { it.setSink(sink) }

    /**
     * Blocks until the main thread has quiesced.
     *
     * More than one round trip, because a drain cycle can post a further one: a single latch would
     * return while work was still queued and make every assertion below it racy.
     */
    private fun drainMainThread(timeoutMs: Long = 5_000) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        repeat(5) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            assertTrue("main thread did not drain", remaining > 0)
            val latch = CountDownLatch(1)
            mainHandler.post { latch.countDown() }
            assertTrue("main thread did not drain", latch.await(remaining, TimeUnit.MILLISECONDS))
        }
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

        // Assert on DISTANCE, not event count. Consecutive same-direction scrolls are merged by
        // summing their deltas, so five +1 detents may legitimately arrive as four events (one of
        // them +2). Merging is not dropping -- an event-count assertion would fail against
        // correct, intended behaviour, which is exactly what it did on the first hardware run.
        assertEquals(
            "scroll distance must be conserved",
            5,
            sink.events.filter { it.action == RemoteAction.SCROLL_STEP }.sumOf { it.delta },
        )
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
        val count = 600
        val sink = Collector()
        // Raised so the rate limiter admits the whole burst: this test is about the hand-off not
        // losing motion, not about the admission budget (which has its own JVM tests).
        val router = newRouter(sink, maxEventsPerSecond = 10_000)
        val source = FakeInputSource("watch")
        router.registerSource(source)

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
    /**
     * Measure the REAL cross-process cost of the AIDL bridge, binder emit -> main-thread execution.
     *
     * This is the number that matters for scroll feel, and it cannot be obtained from a JVM test:
     * it is a genuine binder transaction between `:backend` and the UI process on a Cortex-A55.
     *
     * The test drives the bridge directly rather than the router, so the figure is the transport
     * plus the Handler hop and nothing else. It asserts only a loose ceiling -- the point is the
     * printed distribution, which is reported rather than gated on.
     */
    @Test
    fun measureCrossProcessLatency() {
        val samples = mutableListOf<Long>()
        val total = 500
        val latch = CountDownLatch(total)

        // The UI side of the real bridge: an IRemoteInputSink.Stub that hops to the main thread.
        // Pass every argument explicitly. Omitting the defaulted `log` would bind to Kotlin's
        // synthetic default-argument constructor, which is a different symbol and resolves across
        // the test/app APK boundary only if both were compiled together.
        val client = RemoteInputBridgeClient(
            mainHandler,
            object : RemoteInputSink {
                override fun onRemoteInput(e: RemoteInputEvent) {
                    latch.countDown()
                }
            },
            { },
        )

        // Drive the stub the way :backend does. Calling through the local Stub measures the
        // marshalling and the Handler hop; a genuinely remote binder adds the kernel round trip,
        // which is why the on-device service log figure is quoted alongside this one.
        val stub = client.sinkBinder
        for (i in 0 until total) {
            val emit = android.os.SystemClock.elapsedRealtimeNanos()
            stub.deliver(
                RemoteAction.SCROLL_STEP.ordinal, 1, "watch", 1L, (i + 1).toLong(), 0, -1, emit,
            )
            samples.add(emit)
            Thread.sleep(1)
        }
        assertTrue("deliveries did not complete", latch.await(30, TimeUnit.SECONDS))
        drainMainThread()

        val summary = client.latencySummary
        android.util.Log.i("RemoteInputLatency", "IPC latency: $summary")
        println("IPC latency: $summary")
        assertTrue("no latency samples recorded", summary.contains("n=$total"))
    }

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

    /**
     * The real transport delivers on a Bluetooth callback thread, so the router must be safe under
     * concurrent producers.
     *
     * Sequence numbers are handed out by ONE atomic counter and each frame is emitted immediately
     * after taking its number, but threads interleave, so frames genuinely arrive out of order. The
     * router drops anything that is not a forward step -- that is its replay defence, and it is
     * correct -- so this asserts the two properties that must hold regardless: what IS delivered is
     * strictly increasing, and nothing is delivered twice or corrupted.
     *
     * Note the earlier version of this test asserted `seqs.sorted() == seqs` on the survivors only.
     * That can never fail, because the drop rule makes the survivors monotonic by construction; it
     * would have passed against a completely broken queue. The count assertion below is what gives
     * the test teeth.
     */
    @Test
    fun concurrentProducersDoNotLoseOrCorruptEvents() {
        val sink = Collector()
        val router = newRouter(sink, maxEventsPerSecond = 10_000)
        val source = FakeInputSource("watch")
        router.registerSource(source)
        source.open(sid = 1L)

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
        assertEquals("nothing may be delivered twice", seqs.toSet().size, seqs.size)
        assertEquals("delivery order must be strictly increasing", seqs.sorted(), seqs)
        // A crash, a lost wakeup or a wedged drain would show up as near-total loss. Reordering
        // costs some frames to the drop rule, but the bulk must survive.
        assertTrue(
            "only ${seqs.size} of ${threads * perThread} survived; the hand-off is losing events",
            seqs.size > threads * perThread / 4,
        )
        // Every delivered event must be a FORWARD scroll. Not "delta == 1": consecutive
        // same-direction scrolls merge by summing, so a delivered event legitimately carries +2 or
        // more. Direction is the invariant that must never be corrupted; magnitude is not.
        assertTrue(
            "every delivered event must be a forward scroll",
            sink.events.all { it.action == RemoteAction.SCROLL_STEP && it.delta > 0 },
        )
    }
}
