package com.repository.glasses.listener.input.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteInputRouterTest {

    private var now = 1_000L
    private lateinit var poster: ManualPoster
    private lateinit var router: RemoteInputRouter
    private lateinit var sink: RecordingSink
    private lateinit var watch: FakeInputSource
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        now = 1_000L
        poster = ManualPoster()
        logs.clear()
        router = RemoteInputRouter(
            clock = { now },
            post = poster.post,
            log = { logs.add(it) },
        )
        sink = RecordingSink()
        watch = FakeInputSource("watch", clock = { now })
        router.registerSource(watch)
        router.setSink(sink)
    }

    private fun newSource(id: String) = FakeInputSource(id, clock = { now })

    private fun flush() = poster.runAll()

    private fun tick(ms: Long) { now += ms }

    // --- happy path ---

    @Test
    fun `an opened session delivers scrolls in order`() {
        watch.open(sid = 7)
        watch.scroll(sid = 7, steps = 1)
        watch.scroll(sid = 7, steps = -2)
        watch.scroll(sid = 7, steps = 3)
        flush()
        assertEquals(
            listOf(RemoteAction.SCROLL_STEP, RemoteAction.SCROLL_STEP, RemoteAction.SCROLL_STEP),
            sink.actions,
        )
        assertEquals(listOf(1, -2, 3), sink.deltas)
    }

    @Test
    fun `same-direction scrolls in one burst are merged, conserving distance`() {
        // Merging is not dropping: the user's scroll distance arrives intact, in fewer events.
        watch.open(sid = 7)
        watch.scroll(sid = 7, steps = 1)
        watch.scroll(sid = 7, steps = 2)
        watch.scroll(sid = 7, steps = 4)
        flush()
        assertEquals(1, sink.events.size)
        assertEquals(7, sink.totalDelta())
    }

    @Test
    fun `tap and back are delivered as discrete actions`() {
        watch.open(sid = 1)
        watch.tap(sid = 1)
        watch.back(sid = 1)
        flush()
        assertEquals(listOf(RemoteAction.TAP, RemoteAction.BACK), sink.actions)
        assertEquals(listOf(0, 0), sink.deltas)
    }

    // --- direction. Asymmetric coverage here would hide a sign inversion. ---

    @Test
    fun `both scroll directions survive the router with their sign intact`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 3)
        watch.scroll(sid = 1, steps = -3)
        watch.scroll(sid = 1, steps = 1)
        watch.scroll(sid = 1, steps = -1)
        flush()
        assertEquals(listOf(3, -3, 1, -1), sink.deltas)
    }

    @Test
    fun `realistic coalesced step magnitudes pass through unchanged in both signs`() {
        // Measured hardware produces 1..4 typically, up to the contract cap of 8, both signs.
        watch.open(sid = 1)
        val expected = listOf(1, -1, 2, -2, 3, -3, 4, -4, 8, -8)
        // Alternate direction each time so nothing merges; that is covered separately.
        expected.forEach { watch.scroll(sid = 1, steps = it) }
        flush()
        assertEquals(expected, sink.deltas)
    }

    // --- sequencing ---

    @Test
    fun `a duplicate sequence number is dropped`() {
        watch.open(sid = 1)
        val s = watch.nextSeq()
        watch.scroll(sid = 1, steps = 1, seqOverride = s)
        watch.scroll(sid = 1, steps = 1, seqOverride = s)
        flush()
        assertEquals(1, sink.events.size)
    }

    @Test
    fun `a reordered earlier sequence number is dropped`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1, seqOverride = 10)
        watch.scroll(sid = 1, steps = 5, seqOverride = 9)
        flush()
        assertEquals(1, sink.events.size)
        assertEquals(1, sink.totalDelta())
    }

    @Test
    fun `a replayed TAP is dropped rather than acting twice`() {
        watch.open(sid = 1)
        val s = watch.nextSeq()
        watch.tap(sid = 1, seqOverride = s)
        watch.tap(sid = 1, seqOverride = s)
        watch.tap(sid = 1, seqOverride = s)
        flush()
        assertEquals("TAP must be at-most-once", 1, sink.events.size)
    }

    @Test
    fun `a sequence gap is applied rather than replayed`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1, seqOverride = 2)
        watch.scroll(sid = 1, steps = -1, seqOverride = 40)
        flush()
        assertEquals("the post-gap event is applied, not replayed", 2, sink.events.size)
        assertEquals(listOf(1, -1), sink.deltas)
        assertTrue("the gap must be logged", logs.any { it.contains("seq gap") })
    }

    @Test
    fun `sequence comparison is wrap-safe across the uint32 boundary`() {
        // A plain <= comparison deadlocks the source forever at the wrap.
        assertTrue(RemoteInputRouter.seqDiff(0L, 0xFFFFFFFFL) > 0)
        assertTrue(RemoteInputRouter.seqDiff(1L, 0xFFFFFFFEL) > 0)
        assertTrue(RemoteInputRouter.seqDiff(0xFFFFFFFEL, 0xFFFFFFFFL) < 0)
        assertEquals(1, RemoteInputRouter.seqDiff(5L, 4L))
        assertEquals(0, RemoteInputRouter.seqDiff(5L, 5L))

        // The session must WALK up to the boundary. A single jump of ~2^32 is correctly
        // indistinguishable from going backwards, which is the whole point of a wrap-safe compare.
        watch.emit(
            RemoteInputFrame.Lifecycle(1, "watch", 1L, 0xFFFFFFFDL, now, RemoteLifecycle.OPEN)
        )
        // Alternating directions so each event stays distinct through the merge.
        watch.scroll(sid = 1, steps = 1, seqOverride = 0xFFFFFFFEL)
        watch.scroll(sid = 1, steps = -1, seqOverride = 0xFFFFFFFFL)
        watch.scroll(sid = 1, steps = 1, seqOverride = 0L)
        watch.scroll(sid = 1, steps = -1, seqOverride = 1L)
        flush()
        assertEquals("the source must not deadlock at the wrap", 4, sink.events.size)
    }

    // --- sessions ---

    @Test
    fun `an action without an OPEN is refused`() {
        watch.scroll(sid = 99, steps = 1)
        flush()
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `a session expires after the idle window and stops accepting input`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertEquals(1, sink.events.size)
        assertTrue(router.anyOpenSession())

        tick(RemoteInputRouter.DEFAULT_SESSION_EXPIRY_MS)
        assertFalse(router.anyOpenSession())
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertEquals("no input after expiry", 1, sink.events.size)
    }

    @Test
    fun `PING holds a session open`() {
        watch.open(sid = 1)
        repeat(5) {
            tick(RemoteInputRouter.DEFAULT_SESSION_EXPIRY_MS / 2)
            watch.ping(sid = 1)
        }
        assertTrue(router.anyOpenSession())
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertEquals(1, sink.events.size)
    }

    @Test
    fun `CLOSE ends the session`() {
        watch.open(sid = 1)
        watch.close(sid = 1)
        assertFalse(router.anyOpenSession())
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `a session id older than one already accepted is refused as a replay`() {
        watch.open(sid = 42)
        watch.scroll(sid = 42, steps = 1)
        flush()
        sink.clear()
        watch.close(sid = 42)

        // A source's session counter only ever increases, so a lower id is a captured OPEN.
        watch.open(sid = 41)
        watch.scroll(sid = 41, steps = 1)
        flush()
        assertTrue("a replayed session must not be admitted", sink.events.isEmpty())
        assertFalse(router.anyOpenSession())
    }

    @Test
    fun `a duplicate OPEN for a live session is refused`() {
        watch.open(sid = 5)
        watch.scroll(sid = 5, steps = 1, seqOverride = 100)
        flush()
        sink.clear()
        // A replayed OPEN must not reset the sequence baseline back to zero.
        watch.open(sid = 5)
        watch.scroll(sid = 5, steps = 1, seqOverride = 50)
        flush()
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `only the newest session is live, so a peer cannot grow per-session state`() {
        // Spaced out so the lifecycle rate limit is not what refuses them.
        for (sid in 1L..20L) {
            watch.open(sid = sid)
            tick(1000)
        }
        assertTrue(router.anyOpenSession())
        // Every superseded session is gone; only the newest accepts input.
        for (sid in 1L..19L) watch.scroll(sid = sid, steps = 1)
        flush()
        assertTrue("superseded sessions must not accept input", sink.events.isEmpty())

        watch.scroll(sid = 20L, steps = 1)
        flush()
        assertEquals(1, sink.events.size)
    }

    @Test
    fun `session id zero is reserved and refused`() {
        watch.open(sid = 0L)
        watch.scroll(sid = 0L, steps = 1)
        flush()
        assertTrue(sink.events.isEmpty())
        assertFalse(router.anyOpenSession())
    }

    // --- staleness ---

    @Test
    fun `an event older than the TTL is dropped`() {
        val openWms = now
        watch.open(sid = 1, wms = openWms)
        // 2000 ms passes locally, but the source says the event was produced at session start,
        // so it has been 2000 ms in flight -- past the measured 1500 ms TTL.
        tick(2000)
        watch.scroll(sid = 1, steps = 1, wms = openWms)
        flush()
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `an event that kept pace with the local clock is fresh`() {
        watch.open(sid = 1, wms = 5_000)
        tick(1000)
        // The source's clock advanced by the same 1000 ms: zero in-flight delay.
        watch.scroll(sid = 1, steps = 1, wms = 6_000)
        flush()
        assertEquals(1, sink.events.size)
        assertEquals(0, sink.events[0].ageMs)
    }

    @Test
    fun `age is reported and lifecycle frames are TTL exempt`() {
        val openWms = now
        watch.open(sid = 1, wms = openWms)
        tick(200)
        watch.scroll(sid = 1, steps = 1, wms = openWms)
        flush()
        assertEquals(1, sink.events.size)
        assertEquals(200, sink.events[0].ageMs)

        // A PING produced long ago is still honoured -- lifecycle frames are TTL exempt, otherwise
        // a slow link would expire the very session the PING exists to keep alive.
        tick(5_000)
        watch.ping(sid = 1, wms = openWms)
        assertTrue(router.anyOpenSession())
    }

    @Test
    fun `an event right at the TTL boundary is admitted and just past it is not`() {
        val openWms = now
        watch.open(sid = 1, wms = openWms)
        tick(RemoteInputRouter.DEFAULT_TTL_MS.toLong())
        watch.scroll(sid = 1, steps = 1, wms = openWms)
        flush()
        assertEquals("exactly at the TTL is still fresh", 1, sink.events.size)

        sink.clear()
        tick(1)
        watch.scroll(sid = 1, steps = 1, wms = openWms)
        flush()
        assertTrue("one ms past the TTL is stale", sink.events.isEmpty())
    }

    @Test
    fun `the source clock wrapping past uint32 does not fabricate a huge age`() {
        assertEquals(1L, RemoteInputRouter.u32Delta(0L, 0xFFFFFFFFL))
        assertEquals(2L, RemoteInputRouter.u32Delta(1L, 0xFFFFFFFFL))
        watch.open(sid = 1, wms = 0xFFFFFFFFL)
        tick(100)
        watch.scroll(sid = 1, steps = 1, wms = 99L)  // wrapped past the uint32 boundary
        flush()
        assertEquals(1, sink.events.size)
    }

    // --- sink lifecycle ---

    @Test
    fun `events are dropped while no sink is attached`() {
        router.clearSink(sink)
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `clearSink only clears the sink that is actually installed`() {
        val newer = RecordingSink()
        router.setSink(newer)
        // The outgoing screen's teardown runs late; it must not unregister the live sink.
        router.clearSink(sink)
        assertTrue(router.hasSink)

        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertEquals(1, newer.events.size)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `a backlog is discarded when the sink detaches rather than replayed later`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1)
        // Detach before the queue drains.
        router.clearSink(sink)
        val newer = RecordingSink()
        router.setSink(newer)
        flush()
        assertTrue("stale backlog must not reach the new sink", newer.events.isEmpty())
    }

    @Test
    fun `a throwing sink does not strand the rest of the queue`() {
        var count = 0
        sink.thrower = { if (++count == 1) throw IllegalStateException("boom") }
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1)
        watch.scroll(sid = 1, steps = -1)
        flush()
        assertEquals(2, sink.events.size)
    }

    // --- rate limiting and bounded state ---

    @Test
    fun `a flood is rate limited but the session survives`() {
        watch.open(sid = 1)
        repeat(500) { watch.scroll(sid = 1, steps = 1) }
        flush()
        assertTrue("flood must be limited", sink.events.size < 500)
        assertTrue(router.droppedFor("watch") > 0)

        tick(RemoteInputRouter.RATE_WINDOW_MS)
        sink.clear()
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertEquals("the source recovers in the next window", 1, sink.events.size)
    }

    @Test
    fun `rejection logging is rate limited`() {
        repeat(1000) { watch.scroll(sid = 999, steps = 1) }
        assertTrue("rejection logs must be throttled, got ${logs.size}", logs.size < 20)
    }

    @Test
    fun `frames naming an unregistered source are rejected`() {
        // A compromised or buggy transport claiming to speak for a device that was never
        // registered. Registration IS the allowlist, so this must not be admitted.
        watch.emit(RemoteInputFrame.Lifecycle(1, "intruder", 1L, 1L, now, RemoteLifecycle.OPEN))
        watch.emit(
            RemoteInputFrame.Action(1, "intruder", 1L, 2L, now, RemoteAction.SCROLL_STEP, 1)
        )
        flush()
        assertTrue(sink.events.isEmpty())
        assertFalse(router.anyOpenSession())
    }

    @Test
    fun `frames with an unsupported version are rejected`() {
        watch.open(sid = 1)
        watch.emit(
            RemoteInputFrame.Action(2, "watch", 1L, 99L, 0L, RemoteAction.SCROLL_STEP, 1)
        )
        flush()
        assertTrue(sink.events.isEmpty())
    }

    // --- THE ABSTRACTION PROOF ---

    @Test
    fun `a second source of a different device type works with no router changes`() {
        // This is the proof that a future device is free: a source with a different id, registered
        // the same way, driving the same sink, with no edit to the router or the UI.
        val gadget = newSource("ble_gadget")
        router.registerSource(gadget)

        watch.open(sid = 1)
        gadget.open(sid = 1)
        watch.scroll(sid = 1, steps = 2)
        gadget.scroll(sid = 1, steps = -5)
        gadget.tap(sid = 1)
        flush()

        assertEquals(3, sink.events.size)
        assertEquals(listOf("watch", "ble_gadget", "ble_gadget"), sink.events.map { it.sourceId })
        assertEquals(listOf(2, -5, 0), sink.deltas)
    }

    @Test
    fun `sequence spaces are isolated per source`() {
        val gadget = newSource("ble_gadget")
        router.registerSource(gadget)
        watch.open(sid = 1)
        gadget.open(sid = 1)

        // Identical sid AND identical seq from two different devices. Neither may shadow the other.
        watch.scroll(sid = 1, steps = 1, seqOverride = 100)
        gadget.scroll(sid = 1, steps = 1, seqOverride = 100)
        watch.scroll(sid = 1, steps = 1, seqOverride = 101)
        gadget.scroll(sid = 1, steps = 1, seqOverride = 101)
        flush()
        assertEquals(4, sink.events.size)
    }

    @Test
    fun `one source flooding does not starve another`() {
        val gadget = newSource("ble_gadget")
        router.registerSource(gadget)
        watch.open(sid = 1)
        gadget.open(sid = 1)

        repeat(200) { watch.scroll(sid = 1, steps = 1) }
        flush()
        sink.clear()
        gadget.scroll(sid = 1, steps = 7)
        flush()
        assertEquals("the quiet source must still get through", 1, sink.events.size)
        assertEquals("ble_gadget", sink.events[0].sourceId)
    }

    @Test
    fun `registration is the allowlist so adding a device needs no router edit`() {
        assertEquals(setOf("watch"), router.registeredSourceIds())
        val gadget = newSource("ble_gadget")
        router.registerSource(gadget)
        assertEquals(setOf("watch", "ble_gadget"), router.registeredSourceIds())

        router.unregisterSource(gadget)
        assertEquals(setOf("watch"), router.registeredSourceIds())
        assertEquals("unregistering must detach the source", 1, gadget.detachCount)
        // Its frames are refused even if its transport is still somehow alive.
        watch.emit(
            RemoteInputFrame.Lifecycle(1, "ble_gadget", 5L, 1L, now, RemoteLifecycle.OPEN)
        )
        watch.emit(
            RemoteInputFrame.Action(1, "ble_gadget", 5L, 2L, now, RemoteAction.SCROLL_STEP, 1)
        )
        flush()
        assertTrue("an unregistered source is refused", sink.events.isEmpty())
    }

    @Test
    fun `an invalid source id is refused at registration`() {
        for (bad in listOf("", "Watch", "wa|tch", "a".repeat(17), "with space")) {
            var threw = false
            try {
                router.registerSource(FakeInputSource(bad))
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("sourceId '$bad' must be refused", threw)
        }
    }

    // --- transport churn ---

    @Test
    fun `clearing sessions on a transport drop forces a fresh OPEN`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1, seqOverride = 500)
        flush()
        sink.clear()

        // The socket dropped: frames may have been lost, so the sequence baseline is untrustworthy.
        router.clearSession("watch", "rfcomm disconnect")
        assertFalse(router.anyOpenSession())

        // The source reconnects and resumes at a LOWER seq. Without the clear this would have been
        // treated as stale forever and the remote would be silently dead.
        watch.open(sid = 2)
        watch.scroll(sid = 2, steps = 1, seqOverride = 3)
        flush()
        assertEquals(1, sink.events.size)
    }

    // --- status backchannel ---

    @Test
    fun `the source is told when a session opens and when the sink detaches`() {
        watch.statuses.clear()
        watch.open(sid = 1)
        assertTrue(watch.statuses.isNotEmpty())
        assertTrue(watch.statuses.last().sessionOpen)
        assertTrue(watch.statuses.last().sinkAttached)

        router.clearSink(sink)
        assertFalse(watch.statuses.last().sinkAttached)
        assertTrue("session survives the sink going away", watch.statuses.last().sessionOpen)
    }

    @Test
    fun `dropped events are reported to the source`() {
        watch.open(sid = 1)
        repeat(400) { watch.scroll(sid = 1, steps = 1) }
        assertTrue("the flood must have been dropped", router.droppedFor("watch") > 0)
        // The next window's PING carries the count back to the source.
        tick(RemoteInputRouter.RATE_WINDOW_MS)
        watch.statuses.clear()
        watch.ping(sid = 1)
        assertTrue(watch.statuses.isNotEmpty())
        assertTrue(watch.statuses.last().droppedTotal > 0)
    }

    @Test
    fun `a status push that throws does not break the router`() {
        val rude = object : InputSource {
            override val sourceId = "rude"
            private var s: ((RemoteInputFrame) -> Unit)? = null
            override fun attach(sink: (RemoteInputFrame) -> Unit) { s = sink }
            override fun detach() { s = null }
            override fun onStatus(status: RemoteInputStatus) = throw IllegalStateException("no")
            fun fire(f: RemoteInputFrame) = s?.invoke(f)
        }
        router.registerSource(rude)
        rude.fire(RemoteInputFrame.Lifecycle(1, "rude", 1L, 1L, 0L, RemoteLifecycle.OPEN))
        // Still functioning.
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertEquals(1, sink.events.size)
    }

    // --- the router must stay UI-agnostic ---

    @Test
    fun `the router exposes no keycode or focus concept`() {
        val members = RemoteInputRouter::class.java.declaredMethods.map { it.name } +
            RemoteInputRouter::class.java.declaredFields.map { it.name }
        for (m in members) {
            val lower = m.lowercase()
            assertFalse("router leaked a keycode concept: $m", lower.contains("keycode"))
            assertFalse("router leaked a UI focus concept: $m", lower.contains("focus"))
            assertFalse("router leaked a tab concept: $m", lower.contains("tab"))
            assertFalse("router named a specific device: $m", lower.contains("watch"))
        }
    }

    @Test
    fun `session ids from different sources do not collide`() {
        val gadget = newSource("ble_gadget")
        router.registerSource(gadget)
        watch.open(sid = 77)
        gadget.open(sid = 77)
        assertTrue(router.hasOpenSession("watch"))
        assertTrue(router.hasOpenSession("ble_gadget"))
        watch.close(sid = 77)
        assertFalse(router.hasOpenSession("watch"))
        assertTrue("closing one source must not close the other", router.hasOpenSession("ble_gadget"))
    }

    @Test
    fun `TTL default matches the measured link budget`() {
        // Derived from a measured watch->phone round trip (n=17, one-way p95 ~498 ms), NOT from
        // the plan's 20-50 ms estimate, which was out by roughly an order of magnitude. A 400 ms
        // value would drop legitimate input on a healthy link, and a TTL drop is silent.
        assertEquals(1500, RemoteInputRouter.DEFAULT_TTL_MS)
        assertNotEquals(0, RemoteInputRouter.DEFAULT_TTL_MS)
    }

    // --- tap timing. The glasses own single-vs-double; the router only supplies the interval. ---

    @Test
    fun `tap intervals are measured on the source clock, immune to transport jitter`() {
        watch.open(sid = 1, wms = 0)
        // Two taps the user made 350 ms apart, but which ARRIVE 420 ms apart after a queue stall.
        tick(10)
        watch.tap(sid = 1, wms = 100)
        tick(420)
        watch.tap(sid = 1, wms = 450)
        flush()
        assertEquals(2, sink.events.size)
        assertEquals(
            "first tap has no predecessor",
            RemoteInputEvent.NO_PREDECESSOR, sink.events[0].sinceLastMs,
        )
        assertEquals(
            "the user's real 350 ms, not the 420 ms of arrival",
            350, sink.events[1].sinceLastMs,
        )
    }

    @Test
    fun `tap intervals are reported across the range that decides single versus double`() {
        watch.open(sid = 1, wms = 0)
        var wms = 0L
        val gaps = listOf(100L, 200L, 399L, 400L, 401L, 500L)
        for (g in gaps) {
            wms += g
            tick(g)
            watch.tap(sid = 1, wms = wms)
        }
        flush()
        assertEquals(gaps.size, sink.events.size)
        assertEquals(RemoteInputEvent.NO_PREDECESSOR, sink.events[0].sinceLastMs)
        // Every subsequent interval is exactly what the source measured.
        assertEquals(gaps.drop(1).map { it.toInt() }, sink.events.drop(1).map { it.sinceLastMs })
    }

    @Test
    fun `three rapid taps report both intervals`() {
        watch.open(sid = 1, wms = 0)
        watch.tap(sid = 1, wms = 100)
        watch.tap(sid = 1, wms = 200)
        watch.tap(sid = 1, wms = 300)
        flush()
        assertEquals(3, sink.events.size)
        assertEquals(
            listOf(RemoteInputEvent.NO_PREDECESSOR, 100, 100),
            sink.events.map { it.sinceLastMs },
        )
    }

    @Test
    fun `a dropped stale event still advances the tap clock`() {
        // Otherwise the next tap would be timed against a long-superseded one and look like a
        // double tap the user never made.
        watch.open(sid = 1, wms = 0)
        tick(2000)
        watch.tap(sid = 1, wms = 0)      // 2000 ms in flight: past the 1500 ms TTL, dropped
        tick(10)
        watch.tap(sid = 1, wms = 2000)
        flush()
        assertEquals(1, sink.events.size)
        assertEquals(2000, sink.events[0].sinceLastMs)
    }

    @Test
    fun `a scroll between two taps does not corrupt the interval measurement`() {
        watch.open(sid = 1, wms = 0)
        watch.tap(sid = 1, wms = 100)
        watch.scroll(sid = 1, steps = 1, wms = 150)
        watch.tap(sid = 1, wms = 300)
        flush()
        assertEquals(3, sink.events.size)
        assertEquals(
            listOf(RemoteInputEvent.NO_PREDECESSOR, 50, 150),
            sink.events.map { it.sinceLastMs },
        )
    }

    @Test
    fun `two events stamped in the same millisecond report a zero interval, never negative`() {
        watch.open(sid = 1, wms = 0)
        watch.tap(sid = 1, wms = 100)
        watch.tap(sid = 1, wms = 100)
        flush()
        assertEquals(0, sink.events[1].sinceLastMs)
    }

    // --- regressions found by audit ---

    @Test
    fun `a session that expired can be reopened with the same id`() {
        // A wrist-down of 20 s expires the session while the source still considers it live. If
        // reopening were refused the remote would be dead until the user noticed.
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1)
        flush()
        sink.clear()

        tick(RemoteInputRouter.DEFAULT_SESSION_EXPIRY_MS + 1)
        assertFalse(router.anyOpenSession())

        watch.open(sid = 1)
        assertTrue("a legitimate reopen must be admitted", router.anyOpenSession())
        watch.scroll(sid = 1, steps = 1)
        flush()
        assertEquals(1, sink.events.size)
    }

    @Test
    fun `reopening a session resumes the sequence rather than resetting it`() {
        // This is what makes a captured OPEN plus its burst unreplayable WITHOUT also blocking a
        // legitimate reconnect: the replay cannot rewind the sequence.
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1, seqOverride = 900)
        flush()
        sink.clear()
        watch.close(sid = 1)

        // Replay the captured OPEN and the captured event.
        watch.emit(RemoteInputFrame.Lifecycle(1, "watch", 1L, 10L, now, RemoteLifecycle.OPEN))
        watch.scroll(sid = 1, steps = 1, seqOverride = 900)
        flush()
        assertTrue("the replayed burst must be refused", sink.events.isEmpty())

        // ...while a genuine continuation, carrying a higher sequence, proceeds.
        watch.scroll(sid = 1, steps = 1, seqOverride = 901)
        flush()
        assertEquals(1, sink.events.size)
    }

    @Test
    fun `sessions cleared on a transport drop remain unreplayable`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1, seqOverride = 500)
        flush()
        sink.clear()

        router.clearSession("watch", "rfcomm disconnect")

        // A burst captured before the drop, replayed after it.
        watch.emit(RemoteInputFrame.Lifecycle(1, "watch", 1L, 10L, now, RemoteLifecycle.OPEN))
        watch.scroll(sid = 1, steps = 1, seqOverride = 500)
        flush()
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `a keepalive survives an action flood so the session does not expire`() {
        // The flood is the victim's own traffic; starving its PINGs would let an attacker expire
        // the user's session simply by making noise.
        watch.open(sid = 1)
        repeat(30) {
            repeat(200) { watch.scroll(sid = 1, steps = 1) }
            tick(1000)
            watch.ping(sid = 1)
        }
        assertTrue("the session must survive", router.anyOpenSession())
        sink.clear()
        watch.scroll(sid = 1, steps = 3)
        flush()
        assertEquals(1, sink.events.size)
    }

    @Test
    fun `delivery order matches admission order under concurrent producers`() {
        // The sequence check orders ADMISSION; without enqueueing inside the same critical section
        // a later event could still be delivered first, and a tap would act on the wrong item.
        val fastPoster = ManualPoster()
        val fast = RemoteInputRouter(
            maxEventsPerSecond = Int.MAX_VALUE,
            clock = { now },
            post = fastPoster.post,
        )
        val source = FakeInputSource("stress", clock = { now })
        fast.registerSource(source)
        val fastSink = RecordingSink()
        fast.setSink(fastSink)
        source.emit(RemoteInputFrame.Lifecycle(1, "stress", 1L, 1L, now, RemoteLifecycle.OPEN))

        val seqCounter = java.util.concurrent.atomic.AtomicLong(100)
        val threads = (0 until 4).map {
            Thread {
                repeat(250) {
                    val s = seqCounter.incrementAndGet()
                    source.emit(
                        RemoteInputFrame.Action(
                            1, "stress", 1L, s, now, RemoteAction.SCROLL_STEP,
                            if (s % 2 == 0L) 1 else -1,
                        )
                    )
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        fastPoster.runAll()

        val seqs = fastSink.events.map { it.seq }
        assertEquals("delivered order must be strictly increasing", seqs.sorted(), seqs)
        assertTrue("the run must actually have delivered events", seqs.isNotEmpty())
    }

    // --- durable replay defence (survives a restart) ---

    /** Rebuilds a router over the SAME store, i.e. an app restart or reboot. */
    private fun restart(store: SessionStore, sink: RemoteInputSink): FakeInputSource {
        val r = RemoteInputRouter(store = store, clock = { now }, post = poster.post)
        val src = FakeInputSource("watch", clock = { now })
        r.registerSource(src)
        r.setSink(sink)
        routerAfterRestart = r
        return src
    }

    private var routerAfterRestart: RemoteInputRouter? = null

    @Test
    fun `a captured session cannot be replayed after a restart`() {
        // The whole point of persisting: RAM-only state means an attacker just waits for a reboot.
        val store = InMemorySessionStore()
        val s1 = RecordingSink()
        val src1 = restart(store, s1)
        src1.open(sid = 100)
        src1.tap(sid = 100, seqOverride = 10)
        src1.scroll(sid = 100, steps = 3, seqOverride = 11)
        flush()
        assertEquals(2, s1.events.size)

        // Restart. Everything in memory is gone; only the store survives.
        val s2 = RecordingSink()
        val src2 = restart(store, s2)
        // Replay the captured burst verbatim.
        src2.emit(RemoteInputFrame.Lifecycle(1, "watch", 100L, 9L, now, RemoteLifecycle.OPEN))
        src2.emit(RemoteInputFrame.Action(1, "watch", 100L, 10L, now, RemoteAction.TAP, 0))
        src2.emit(
            RemoteInputFrame.Action(1, "watch", 100L, 11L, now, RemoteAction.SCROLL_STEP, 3)
        )
        flush()
        assertTrue("a replayed tap must never reach the UI after a restart", s2.events.isEmpty())
    }

    @Test
    fun `an older session id is refused after a restart`() {
        val store = InMemorySessionStore()
        val s1 = RecordingSink()
        restart(store, s1).open(sid = 500)

        val s2 = RecordingSink()
        val src2 = restart(store, s2)
        src2.open(sid = 499)
        src2.scroll(sid = 499, steps = 1)
        flush()
        assertTrue(s2.events.isEmpty())
        assertFalse(routerAfterRestart!!.anyOpenSession())
    }

    @Test
    fun `a genuine new session after a restart works normally`() {
        // Fail-closed must not mean fail-always: the source's next real session must go through.
        val store = InMemorySessionStore()
        restart(store, RecordingSink()).open(sid = 100)

        val s2 = RecordingSink()
        val src2 = restart(store, s2)
        src2.open(sid = 101)
        src2.scroll(sid = 101, steps = 2)
        src2.tap(sid = 101)
        flush()
        assertEquals(2, s2.events.size)
    }

    @Test
    fun `the durable sequence floor runs ahead of what was applied`() {
        // A crash may therefore cost a few frames inside one session, but can never admit a
        // replayed one. That asymmetry is the design.
        val store = InMemorySessionStore()
        val src = restart(store, RecordingSink())
        src.open(sid = 100)
        src.scroll(sid = 100, steps = 1, seqOverride = 50)
        flush()
        assertTrue(
            "floor must be reserved ahead of the applied sequence",
            store.seqFloor("watch") > 50L,
        )
    }

    @Test
    fun `persistence does not write on every event`() {
        // A synchronous write per scroll detent would be felt on this hot path.
        val store = InMemorySessionStore()
        val src = restart(store, RecordingSink())
        src.open(sid = 100)
        var seq = 1L
        repeat(200) {
            tick(120)
            src.scroll(sid = 100, steps = 1, seqOverride = ++seq)
        }
        flush()
        assertTrue(
            "expected few reservations for 200 events, got ${store.reserveCount}",
            store.reserveCount <= 5,
        )
    }

    @Test
    fun `a live session is never gated on the write-ahead reservation`() {
        // The reservation runs ahead by SEQ_RESERVATION; consulting it during a live session would
        // refuse the source's next few hundred frames and stall it completely.
        val store = InMemorySessionStore()
        val src = restart(store, RecordingSink())
        val s = RecordingSink()
        routerAfterRestart!!.setSink(s)
        src.open(sid = 100)
        var seq = 1L
        // Paced like a real bezel (~10 frames/s), draining each time as a live UI would, so neither
        // the rate limit nor the queue's entry cap is what is under test.
        repeat(50) {
            tick(100)
            src.scroll(sid = 100, steps = if (it % 2 == 0) 1 else -1, seqOverride = ++seq)
            flush()
        }
        assertEquals("every frame of a live session must be accepted", 50, s.events.size)
    }

    @Test
    fun `forgetting a source identity restores acceptance after a source factory reset`() {
        val store = InMemorySessionStore()
        val src = restart(store, RecordingSink())
        src.open(sid = 900)
        val router2 = routerAfterRestart!!

        // The source was factory reset and its counter regressed. Fail-closed, correctly.
        val s = RecordingSink()
        router2.setSink(s)
        src.open(sid = 1)
        src.scroll(sid = 1, steps = 1)
        flush()
        assertTrue("a regressed counter must be refused by default", s.events.isEmpty())

        // Local physical recovery only. This is deliberately not reachable over the transport.
        router2.forgetSourceIdentity("watch")
        src.open(sid = 1)
        src.scroll(sid = 1, steps = 1)
        flush()
        assertEquals(1, s.events.size)
    }

    @Test
    fun `a replayed CLOSE cannot kill a live session`() {
        watch.open(sid = 1)
        watch.scroll(sid = 1, steps = 1, seqOverride = 100)
        flush()
        sink.clear()

        // A captured CLOSE, replayed. Without a sequence check this is a remote session kill.
        watch.emit(RemoteInputFrame.Lifecycle(1, "watch", 1L, 50L, now, RemoteLifecycle.CLOSE))
        assertTrue("a stale CLOSE must not end the session", router.anyOpenSession())
        watch.scroll(sid = 1, steps = 1, seqOverride = 101)
        flush()
        assertEquals(1, sink.events.size)

        // A genuine CLOSE, carrying a forward sequence, still works.
        watch.emit(RemoteInputFrame.Lifecycle(1, "watch", 1L, 102L, now, RemoteLifecycle.CLOSE))
        assertFalse(router.anyOpenSession())
    }

    @Test
    fun `concurrent sink swap never loses the newly installed sink`() {
        val a = RecordingSink()
        val b = RecordingSink()
        repeat(300) {
            router.setSink(a)
            val t = Thread { router.clearSink(a) }
            t.start()
            router.setSink(b)
            t.join()
            assertTrue("setSink(b) must not be undone by a late clearSink(a)", router.hasSink)
            router.clearSink(b)
        }
    }
}
