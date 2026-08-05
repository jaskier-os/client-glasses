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
    fun `select and back are delivered as discrete actions`() {
        watch.open(sid = 1)
        watch.select(sid = 1)
        watch.back(sid = 1)
        flush()
        assertEquals(listOf(RemoteAction.SELECT, RemoteAction.BACK), sink.actions)
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
    fun `a replayed SELECT is dropped rather than confirming twice`() {
        watch.open(sid = 1)
        val s = watch.nextSeq()
        watch.select(sid = 1, seqOverride = s)
        watch.select(sid = 1, seqOverride = s)
        watch.select(sid = 1, seqOverride = s)
        flush()
        assertEquals("SELECT must be at-most-once", 1, sink.events.size)
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
    fun `a session id is never reusable, so a captured OPEN cannot resurrect one`() {
        watch.open(sid = 42)
        watch.scroll(sid = 42, steps = 1)
        flush()
        sink.clear()
        watch.close(sid = 42)

        // Replay of the captured OPEN plus its events.
        watch.open(sid = 42)
        watch.scroll(sid = 42, steps = 1)
        flush()
        assertTrue("a replayed session must not be admitted", sink.events.isEmpty())
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
    fun `the session map is bounded per source`() {
        for (sid in 1L..20L) {
            watch.open(sid = sid)
            tick(1)
        }
        // Bounded regardless of how many sessions a peer mints.
        assertTrue(router.anyOpenSession())
        for (sid in 1L..(20L - RemoteInputRouter.MAX_SESSIONS_PER_SOURCE)) {
            watch.scroll(sid = sid, steps = 1)
        }
        flush()
        assertTrue("evicted sessions must not accept input", sink.events.isEmpty())
    }

    // --- staleness ---

    @Test
    fun `an event older than the TTL is dropped`() {
        val openWms = now
        watch.open(sid = 1, wms = openWms)
        // 1000 ms passes locally, but the source says the event was produced at session start:
        // 1000 ms in flight, well past the 400 ms TTL.
        tick(1000)
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
    fun `frames from an unregistered source are rejected`() {
        val stranger = newSource("intruder")
        // Not registered: wire its frames straight into the router.
        stranger.attach { router.onFrame(it) }
        stranger.open(sid = 1)
        stranger.scroll(sid = 1, steps = 1)
        flush()
        assertTrue(sink.events.isEmpty())
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
        gadget.select(sid = 1)
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
        assertEquals(1, gadget.detachCount)
        gadget.attach { router.onFrame(it) }
        gadget.open(sid = 1)
        gadget.scroll(sid = 1, steps = 1)
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
        router.clearAllSessions("rfcomm disconnect")
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
    fun `TTL default is the documented floor`() {
        assertEquals(400, RemoteInputRouter.DEFAULT_TTL_MS)
        assertNotEquals(0, RemoteInputRouter.DEFAULT_TTL_MS)
    }
}
