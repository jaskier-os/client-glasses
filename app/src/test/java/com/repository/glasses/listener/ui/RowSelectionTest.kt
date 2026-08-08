package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The caret, extracted from the adapter so the one property that must never break is provable
 * without a device: no sequence of asynchronous list swaps can move the caret onto a row the user
 * did not aim at.
 */
class RowSelectionTest {

    private fun rc(id: String, ended: Boolean = false) =
        RcSessionState(id, "s$id", "~/w", turning = false, unread = false, ended = ended, lastSeq = 0)

    private fun conv(id: String) = ChatSummaryItem(id, "chat $id", "1m", 1, false)

    private fun rows(rc: List<RcSessionState> = emptyList(), conv: List<ChatSummaryItem> = emptyList()) =
        ChatRowBuilder.build(RcState(true, rc), conv)

    private fun sel(initial: List<ChatRow>) = RowSelection().apply { onRowsReplaced(initial) }

    @Test
    fun freshSelectionIsEmptyAndOpensNothing() {
        val s = sel(rows(conv = listOf(conv("c1"))))
        assertNull(s.key)
        assertEquals(-1, s.index)
        assertNull(s.selectedRow())
    }

    @Test
    fun theCaretStaysOnItsRowWhenRcRowsAppearAbove() {
        val s = sel(rows(conv = listOf(conv("c1"), conv("c2"))))
        s.select("conv:c2")
        val before = s.index
        val change = s.onRowsReplaced(rows(listOf(rc("a"), rc("b")), listOf(conv("c1"), conv("c2"))))
        assertEquals("conv:c2", s.key)
        assertTrue("the row genuinely moved, so this is not a no-op test", s.index > before)
        assertEquals(RowSelection.Change.MOVED, change)
    }

    @Test
    fun theCaretOnAHeaderRowIsNeverReAimedByAnAsyncInsert() {
        val s = sel(rows(conv = listOf(conv("c1"))))
        s.select(ChatRow.NewChat.key)
        repeat(6) { i -> s.onRowsReplaced(rows((0..i).map { rc("s$it") }, listOf(conv("c1")))) }
        assertEquals(ChatRow.NewChat.key, s.key)
    }

    @Test
    fun aVanishedCaretLandsNearWhereTheUserWasLookingAndSaysItWasRehomed() {
        val s = sel(rows(conv = listOf(conv("c1"), conv("c2"), conv("c3"))))
        s.select("conv:c2")
        val change = s.onRowsReplaced(rows(conv = listOf(conv("c1"), conv("c3"))))
        assertEquals("conv:c3", s.key)
        // REHOMED is the caller's signal to rebind the old row and clear its border. Reporting
        // MOVED instead would leave a caret painted on a row that no longer holds the caret.
        assertEquals(RowSelection.Change.REHOMED, change)
    }

    @Test
    fun anUnchangedListReportsNoChangeAtAll() {
        val r = rows(conv = listOf(conv("c1")))
        val s = sel(r)
        s.select("conv:c1")
        assertEquals(RowSelection.Change.NONE, s.onRowsReplaced(r))
    }

    @Test
    fun navigationStopsAtBothEndsWithoutLosingTheCaret() {
        val s = sel(rows(conv = listOf(conv("c1"))))
        s.select(ChatRow.NewChat.key)
        s.moveUp()
        assertEquals(ChatRow.NewChat.key, s.key)
        repeat(10) { s.moveDown() }
        assertEquals("conv:c1", s.key)
    }

    @Test
    fun anEmptyCaretIsClaimedByEitherDirection() {
        val down = sel(rows(conv = listOf(conv("c1"))))
        down.moveDown()
        assertEquals(ChatRow.NewChat.key, down.key)
        val up = sel(rows(conv = listOf(conv("c1"))))
        up.moveUp()
        assertEquals(ChatRow.NewChat.key, up.key)
    }

    @Test
    fun clearingDropsTheCaretEntirelyRatherThanRehomingIt() {
        val s = sel(rows(conv = listOf(conv("c1"))))
        s.select("conv:c1")
        assertEquals(RowSelection.Change.DROPPED, s.onRowsReplaced(rows(), dropSelection = true))
        assertNull(s.key)
        assertEquals(-1, s.index)
    }

    @Test
    fun anEmptyCaretIsNotPlantedByASubsequentSwap() {
        val s = sel(rows(conv = listOf(conv("c1"))))
        s.onRowsReplaced(rows(listOf(rc("a")), listOf(conv("c1"))))
        assertNull("a list update must not create a caret out of nothing", s.key)
    }

    @Test
    fun anEndedSessionIsSelectableButNotEnterable() {
        val s = sel(rows(listOf(rc("a", ended = true))))
        s.select("rc:a")
        assertEquals("rc:a", s.key)
        assertNull("an ended session may not be opened", s.selectedRcSession())
    }

    @Test
    fun selectingAKeyThatIsNotPresentIsRefusedRatherThanGuessed() {
        val s = sel(rows(conv = listOf(conv("c1"))))
        s.select("conv:c1")
        s.select("conv:nope")
        assertEquals("conv:c1", s.key)
    }

    /**
     * A refusal has to be REPORTED, not merely obeyed. A caller that asks for a caret, is silently
     * refused, and goes on believing one exists will act on whatever row the index later lands on
     * -- so it must land somewhere the user can act on deliberately.
     */
    @Test
    fun everyRefusalSaysSoRatherThanReportingSuccess() {
        val s = sel(rows(listOf(rc("a")), listOf(conv("c1"))))

        assertTrue("a present, selectable key is accepted", s.select("conv:c1"))
        assertFalse("an absent key is refused and says so", s.select("conv:nope"))
        assertFalse("an index off the end is refused and says so", s.selectIndex(99))
        assertFalse("a negative index is refused and says so", s.selectIndex(-1))
        assertEquals("no refusal moved the caret", "conv:c1", s.key)
    }

    /**
     * An index off the end of the list is a caller bug, never an instruction to clear the caret.
     * Routing it through select(null) would report SUCCESS while dropping the caret entirely, so
     * the caller would repaint nothing and the user would watch their caret vanish.
     */
    @Test
    fun anOutOfRangeIndexIsRefusedAndLeavesTheCaretWhereItWas() {
        val s = sel(rows(conv = listOf(conv("c1"), conv("c2"))))
        s.select("conv:c2")
        assertFalse("past the end is refused", s.selectIndex(99))
        assertFalse("before the start is refused", s.selectIndex(-1))
        assertEquals("neither refusal touched the caret", "conv:c2", s.key)
    }

    /**
     * A move that hits the end of the list is a refusal like any other and must say so. Reporting
     * a constant success here would reintroduce exactly the swallowed-refusal class this whole
     * selection type exists to close.
     */
    @Test
    fun aMoveThatHitsTheEndOfTheListReportsThatItRefused() {
        val r = rows(conv = listOf(conv("c1")))
        val s = sel(r)
        assertTrue("the first down-press claims a row", s.moveDown())
        // BOUNDED, deliberately. The loop condition IS the thing under test, so an implementation
        // that always reports success would turn this into an infinite loop -- a wedged worker
        // instead of a red bar. Past one press per row the implementation is wrong, so say so.
        walk(r.size) { s.moveDown() }
        assertFalse("there is nothing below the last row", s.moveDown())
        assertEquals("the refused move left the caret on the last row", "conv:c1", s.key)

        walk(r.size) { s.moveUp() }
        assertFalse("there is nothing above the first row", s.moveUp())
        assertEquals(ChatRow.NewChat.key, s.key)
    }

    /** Runs [step] until it refuses, failing rather than spinning if it never does. */
    private fun walk(limit: Int, step: () -> Boolean) {
        repeat(limit) { if (!step()) return }
        fail("a move reported success more than $limit times on a $limit-row list")
    }
}
