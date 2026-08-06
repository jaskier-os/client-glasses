package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While the user is steering the list, a snapshot arriving from the phone may not reorder or
 * re-populate it under the caret. Content (status icon, unread bar) still updates live, because a
 * stale spinner is a lie and updating it moves nothing.
 */
class ListMutationGateTest {

    private var now = 0L
    private fun gate(holdMs: Long = ListMutationGate.HOLD_MS) = ListMutationGate(holdMs) { now }

    private fun rc(id: String, turning: Boolean = false, unread: Boolean = false) =
        RcSessionState(id, "s$id", "~/w", turning, unread, ended = false, lastSeq = 0)

    private fun conv(id: String) = ChatSummaryItem(id, "chat $id", "1m", 1, false)

    private fun rows(rc: List<RcSessionState>, conv: List<ChatSummaryItem>) =
        ChatRowBuilder.build(RcState(true, rc), conv)

    private val base = rows(emptyList(), listOf(conv("c1"), conv("c2")))

    @Test
    fun unfocusedSetChangeAppliesImmediately() {
        val next = rows(listOf(rc("a")), listOf(conv("c1"), conv("c2")))
        val d = gate().submit(base, next, focused = false)
        assertTrue(d is ListMutationGate.Decision.Apply)
        assertEquals(next.map { it.key }, (d as ListMutationGate.Decision.Apply).rows.map { it.key })
    }

    @Test
    fun focusedSetChangeIsDeferredAndKeepsTheVisibleOrder() {
        val next = rows(listOf(rc("a")), listOf(conv("c1"), conv("c2")))
        val d = gate().submit(base, next, focused = true)
        assertTrue("an insert above the caret must not land while focused",
            d is ListMutationGate.Decision.Deferred)
        assertEquals(base.map { it.key },
            (d as ListMutationGate.Decision.Deferred).rows.map { it.key })
    }

    @Test
    fun focusedRemovalIsAlsoDeferred() {
        val withRc = rows(listOf(rc("a")), listOf(conv("c1")))
        val d = gate().submit(withRc, rows(emptyList(), listOf(conv("c1"))), focused = true)
        assertTrue(d is ListMutationGate.Decision.Deferred)
        assertEquals(withRc.map { it.key },
            (d as ListMutationGate.Decision.Deferred).rows.map { it.key })
    }

    @Test
    fun focusedReorderIsDeferred() {
        val a = rows(listOf(rc("x"), rc("y")), listOf(conv("c1")))
        val b = rows(listOf(rc("y"), rc("x")), listOf(conv("c1")))
        assertTrue(gate().submit(a, b, focused = true) is ListMutationGate.Decision.Deferred)
    }

    @Test
    fun focusedContentOnlyChangeAppliesImmediately() {
        val a = rows(listOf(rc("x")), listOf(conv("c1")))
        val b = rows(listOf(rc("x", turning = true)), listOf(conv("c1")))
        val d = gate().submit(a, b, focused = true)
        assertTrue("a status flip moves nothing and must be live",
            d is ListMutationGate.Decision.ContentOnly)
        assertEquals(b, (d as ListMutationGate.Decision.ContentOnly).rows)
    }

    @Test
    fun deferredDecisionStillCarriesContentUpdatesForSurvivingRows() {
        val a = rows(listOf(rc("x")), listOf(conv("c1")))
        // Same snapshot flips x to turning AND inserts y: the insert waits, the spinner does not.
        val b = rows(listOf(rc("x", turning = true), rc("y")), listOf(conv("c1")))
        val d = gate().submit(a, b, focused = true) as ListMutationGate.Decision.Deferred
        assertEquals(a.map { it.key }, d.rows.map { it.key })
        val x = d.rows.filterIsInstance<ChatRow.RcSession>().single { it.id == "x" }
        assertTrue("the surviving row's content must update in place", x.turning)
    }

    @Test
    fun identicalSubmitProducesNoWork() {
        assertTrue(gate().submit(base, base, focused = true) is ListMutationGate.Decision.None)
        assertTrue(gate().submit(base, base, focused = false) is ListMutationGate.Decision.None)
    }

    @Test
    fun releasingFocusFlushesTheNewestDeferredSnapshot() {
        val g = gate()
        val first = rows(listOf(rc("a")), listOf(conv("c1"), conv("c2")))
        val second = rows(listOf(rc("a"), rc("b")), listOf(conv("c1"), conv("c2")))
        g.submit(base, first, focused = true)
        g.submit(base, second, focused = true)
        val flushed = g.release()
        assertEquals("only the newest snapshot survives; deltas do not queue",
            second.map { it.key }, flushed!!.map { it.key })
        assertNull("the pending snapshot is consumed exactly once", g.release())
    }

    @Test
    fun holdExpiresSoAFrozenCaretCannotStrandTheList() {
        val g = gate(holdMs = 5_000)
        val next = rows(listOf(rc("a")), listOf(conv("c1"), conv("c2")))
        now = 1_000
        assertTrue(g.submit(base, next, focused = true) is ListMutationGate.Decision.Deferred)
        now = 5_999
        assertNull("still inside the hold window", g.tick())
        now = 6_000
        assertEquals(next.map { it.key }, g.tick()!!.map { it.key })
        assertNull("the flush consumes the pending snapshot", g.tick())
    }

    @Test
    fun theHoldClockStartsAtTheFirstDeferralNotTheLatest() {
        val g = gate(holdMs = 5_000)
        now = 0
        g.submit(base, rows(listOf(rc("a")), listOf(conv("c1"), conv("c2"))), focused = true)
        now = 4_000
        val newest = rows(listOf(rc("a"), rc("b")), listOf(conv("c1"), conv("c2")))
        g.submit(base, newest, focused = true)
        now = 5_000
        assertEquals("a busy session must not postpone the flush forever",
            newest.map { it.key }, g.tick()!!.map { it.key })
    }

    @Test
    fun anUnfocusedSubmitClearsAnyPendingSnapshot() {
        val g = gate()
        val deferred = rows(listOf(rc("a")), listOf(conv("c1"), conv("c2")))
        g.submit(base, deferred, focused = true)
        val live = rows(listOf(rc("z")), listOf(conv("c1")))
        assertTrue(g.submit(base, live, focused = false) is ListMutationGate.Decision.Apply)
        assertNull("a stale deferred snapshot must never replay over a newer applied one", g.release())
        assertNull(g.tick())
    }

    @Test
    fun aLaterSnapshotMatchingTheVisibleSetCancelsThePendingChange() {
        val g = gate()
        val a = rows(listOf(rc("x")), listOf(conv("c1")))
        g.submit(a, rows(listOf(rc("x"), rc("y")), listOf(conv("c1"))), focused = true)
        // Every push is a full authoritative snapshot, so a later one that no longer contains y
        // means y is gone -- there is nothing left to flush and no stale insert may replay later.
        val contentOnly = rows(listOf(rc("x", unread = true)), listOf(conv("c1")))
        assertTrue(g.submit(a, contentOnly, focused = true) is ListMutationGate.Decision.ContentOnly)
        assertNull(g.release())
        assertNull(g.tick())
    }
}
