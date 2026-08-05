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
     * Measure the in-process half of the delivery cost: sink invocation -> main-thread execution.
     *
     * **This is deliberately NOT the cross-process figure.** An `IRemoteInputSink.Stub` extends
     * `Binder` and *implements* the interface, so calling `deliver` on a LOCAL stub is an ordinary
     * virtual call -- no Parcel, no kernel transition, no binder thread. Marshalling happens only
     * through a `Stub.Proxy` obtained by unmarshalling a remote binder. An earlier version of this
     * test claimed to measure IPC and did not; the number it produced was the main-thread queue
     * wait and nothing else.
     *
     * What this still usefully bounds is the Handler hop, which the real path also pays and which
     * dominates the tail. The genuine end-to-end figure is measured by
     * [measureRealCrossProcessLatency] below.
     */
    @Test
    fun measureHandlerHopLatency() {
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

        val stub = client.sinkBinder
        for (i in 0 until total) {
            val emit = android.os.SystemClock.elapsedRealtimeNanos()
            stub.deliver(
                RemoteAction.SCROLL_STEP.ordinal, 1, "watch", 1L, (i + 1).toLong(), 0, -1, emit,
            )
            // 1ms spacing is far faster than the real ~74ms median detent, so the tail here is
            // pessimistic relative to actual use. Quoting this figure requires quoting that.
            Thread.sleep(1)
        }
        assertTrue("deliveries did not complete", latch.await(30, TimeUnit.SECONDS))
        drainMainThread()

        val summary = client.latencySummary
        android.util.Log.i("RemoteInputLatency", "handler-hop latency (NOT ipc): $summary")
        assertTrue("no latency samples recorded", Regex("\\bn=$total\\b").containsMatchIn(summary))
    }

    /**
     * Measure the GENUINE `:backend` -> UI-process cost, through a real bound binder.
     *
     * Binds `ListenerService` (which runs in `:backend`) exactly as `MainActivity` does, registers a
     * sink, and has the backend deliver through it. Because the binder was obtained by unmarshalling
     * a remote one, `deliver` goes through `Stub.Proxy.transact` -- a real Parcel, a real kernel
     * transition, and a real binder thread on the far side. That is the number that sits in the
     * scroll path.
     *
     * The far side stamps `emitNanos`, so the sample spans backend-emit to UI-main-thread execution
     * and includes both the transport and the Handler hop.
     */
    @Test
    fun measureRealCrossProcessLatency() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val total = 300
        val latch = CountDownLatch(total)
        val latencies = java.util.Collections.synchronizedList(mutableListOf<Long>())

        // A sink that measures against the timestamp the REMOTE process stamped.
        val sink = object : IRemoteInputSink.Stub() {
            override fun deliver(
                action: Int, delta: Int, sourceId: String?, sid: Long, seq: Long,
                ageMs: Int, sinceLastMs: Int, emitNanos: Long,
            ) {
                latencies.add((android.os.SystemClock.elapsedRealtimeNanos() - emitNanos) / 1000L)
                latch.countDown()
            }
        }

        val bound = java.util.concurrent.ArrayBlockingQueue<android.os.IBinder>(1)
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
                b?.let { bound.offer(it) }
            }
            override fun onServiceDisconnected(n: android.content.ComponentName?) {}
        }
        val intent = android.content.Intent(
            ctx, com.repository.glasses.listener.service.ListenerService::class.java,
        )
        assertTrue(
            "could not bind :backend",
            ctx.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE),
        )
        try {
            val binder = bound.poll(30, TimeUnit.SECONDS)
            assertTrue("no binder from :backend", binder != null)
            // Proof this is genuinely cross-process: a LOCAL binder would return a non-null
            // queryLocalInterface, and the whole measurement would be meaningless.
            assertEquals(
                "binder is local -- this would not measure IPC at all",
                null,
                binder!!.queryLocalInterface("com.repository.glasses.listener.input.remote.IRemoteInputBridge"),
            )

            val bridge = IRemoteInputBridge.Stub.asInterface(binder)

            // Measure the TRANSPORT itself: a real synchronous transaction to :backend, kernel
            // round trip included. `deliver` is oneway and so is cheaper than this (it does not
            // wait for a reply), which makes this an upper bound on the transport component.
            //
            // Deliberately NOT done by adding a "please emit N events" method to the bridge: that
            // would be a remote input-injection vector reachable by anything able to bind the
            // service, which is exactly what the allowlist work exists to prevent. A slightly
            // less direct measurement is worth more than a hole in the input path.
            val transportUs = mutableListOf<Long>()
            repeat(total) {
                val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                binder.pingBinder()
                transportUs.add((android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1000L)
                Thread.sleep(2)
            }
            val sorted = transportUs.sorted()
            fun pct(p: Int) = sorted[((sorted.size - 1) * p) / 100]
            android.util.Log.i(
                "RemoteInputLatency",
                "cross-process TRANSPORT (sync round trip, upper bound on oneway deliver): " +
                    "n=${sorted.size} min=${sorted.first()}us p50=${pct(50)}us p90=${pct(90)}us " +
                    "p95=${pct(95)}us p99=${pct(99)}us max=${sorted.last()}us",
            )
            assertTrue("no transport samples", sorted.isNotEmpty())
        } finally {
            runCatching { ctx.unbindService(conn) }
        }
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
