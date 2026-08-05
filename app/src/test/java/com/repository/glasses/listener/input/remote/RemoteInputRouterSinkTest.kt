package com.repository.glasses.listener.input.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression tests for the sink-identity race the cross-process bridge exposed.
 *
 * `RemoteInputRouter.clearSink` distinguishes callers by sink INSTANCE. That is exactly right when
 * each UI screen installs its own sink object, and degenerate when a single long-lived object (the
 * process bridge) installs itself on behalf of a succession of UI processes: a late teardown from a
 * departed UI then passes the identity check and clears a sink a healthy successor installed.
 *
 * These tests pin the router behaviour the bridge now depends on, so a future change to `clearSink`
 * cannot silently reintroduce the permanent-drop failure.
 */
class RemoteInputRouterSinkTest {

    private val now = AtomicLong(1_000L)

    /** Runs posted work inline so the tests stay deterministic. */
    private fun newRouter(): RemoteInputRouter = RemoteInputRouter(
        clock = { now.get() },
        post = { it.run() },
    )

    private class CountingSink : RemoteInputSink {
        var count = 0
        override fun onRemoteInput(e: RemoteInputEvent) { count++ }
    }

    @Test
    fun `clearSink from a stale instance leaves the current sink installed`() {
        val router = newRouter()
        val first = CountingSink()
        val second = CountingSink()

        router.setSink(first)
        router.setSink(second)
        // The departing screen tears down late, after its replacement already installed itself.
        router.clearSink(first)

        val source = FakeInputSource("watch")
        router.registerSource(source)
        source.open(sid = 1L)
        source.scroll(sid = 1L, steps = 1)

        assertEquals("stale teardown must not detach the live sink", 1, second.count)
        assertEquals(0, first.count)
    }

    @Test
    fun `clearSink with the current instance detaches`() {
        val router = newRouter()
        val sink = CountingSink()
        router.setSink(sink)
        router.clearSink(sink)

        val source = FakeInputSource("watch")
        router.registerSource(source)
        source.open(sid = 1L)
        source.scroll(sid = 1L, steps = 1)

        assertEquals(0, sink.count)
    }

    /**
     * The bridge installs ONE object for every UI process, so re-installing the same instance must
     * be idempotent rather than a detach/attach that could drop events in the gap.
     */
    @Test
    fun `re-installing the same sink instance keeps delivering`() {
        val router = newRouter()
        val sink = CountingSink()
        router.setSink(sink)
        router.setSink(sink)

        val source = FakeInputSource("watch")
        router.registerSource(source)
        source.open(sid = 1L)
        source.scroll(sid = 1L, steps = 1)

        assertEquals(1, sink.count)
    }

    /**
     * One source losing its transport must not end another source's session. Until this held, a
     * second input device was not the zero-change addition the layer promises.
     */
    @Test
    fun `clearing one source's session leaves other sources untouched`() {
        val router = newRouter()
        val sink = CountingSink()
        router.setSink(sink)

        val watch = FakeInputSource("watch")
        val gadget = FakeInputSource("gadget")
        router.registerSource(watch)
        router.registerSource(gadget)

        watch.open(sid = 1L)
        gadget.open(sid = 1L)

        router.clearSession("watch", "transport lost")

        // The gadget's session survives, so its next event is still accepted.
        gadget.scroll(sid = 1L, steps = 1)
        assertEquals("the other source's session must survive", 1, sink.count)
    }
}
