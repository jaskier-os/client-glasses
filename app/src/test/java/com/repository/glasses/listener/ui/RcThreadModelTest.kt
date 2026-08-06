package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thread model is a pure merge of CH_RC_MESSAGES_RESP frames.
 *
 * There is exactly one channel for both the reply to a request and the live delta, so what decides
 * append-versus-replace is `seq` monotonicity, never the channel. Everything the thread screen
 * renders -- ordering, the earlier-messages marker, whether the microphone may run -- is derived
 * here so it is testable without a device.
 */
class RcThreadModelTest {

    private fun frame(
        rows: String,
        more: Boolean = false,
        lastSeq: Long = -1,
    ) = """{"rows":[$rows],"more":$more,"lastSeq":$lastSeq}"""

    private fun row(seq: Long, role: String, text: String, extra: String = "") =
        """{"q":$seq,"r":"$role","x":"$text"$extra}"""

    private fun openModel(id: String = "s-1"): RcThreadModel =
        RcThreadModel().apply { open(id) }

    @Test
    fun rowsRenderInSeqOrderRegardlessOfFrameOrder() {
        val m = openModel()
        assertTrue(
            m.accept(
                "s-1",
                frame(row(12, "assistant", "second") + "," + row(11, "user", "first"))
            )
        )
        assertEquals(listOf(11L, 12L), m.rows.map { it.seq })
        assertEquals(listOf("user", "assistant"), m.rows.map { it.role })
    }

    @Test
    fun aSecondFrameAppendsOnlyRowsNewerThanTheHighestHeld() {
        val m = openModel()
        m.accept("s-1", frame(row(1, "user", "a") + "," + row(2, "assistant", "b")))
        // Overlapping frame: seq 2 is already held and must not render twice.
        m.accept("s-1", frame(row(2, "assistant", "b") + "," + row(3, "assistant", "c")))
        assertEquals(listOf(1L, 2L, 3L), m.rows.map { it.seq })
        assertEquals(listOf("a", "b", "c"), m.rows.map { it.text })
    }

    @Test
    fun anOverlappingRowNeverOverwritesTheHeldCopy() {
        val m = openModel()
        m.accept("s-1", frame(row(4, "assistant", "original")))
        m.accept("s-1", frame(row(4, "assistant", "rewritten")))
        assertEquals(listOf("original"), m.rows.map { it.text })
    }

    @Test
    fun redeliveringAnIdenticalFrameChangesNothing() {
        val m = openModel()
        val f = frame(row(7, "user", "hello") + "," + row(8, "assistant", "hi"), more = true)
        m.accept("s-1", f)
        val before = m.rows.toList()
        val moreBefore = m.moreAbove
        assertFalse("a frame that adds no rows reports no change", m.accept("s-1", f))
        assertEquals(before, m.rows)
        assertEquals(moreBefore, m.moreAbove)
    }

    @Test
    fun moreTruePrependsTheEarlierMessagesMarker() {
        val m = openModel()
        m.accept("s-1", frame(row(9, "assistant", "tail"), more = true))
        assertEquals(RcThreadItem.EarlierOnPhone, m.items().first())
        assertEquals(2, m.items().size)

        val n = openModel()
        n.accept("s-1", frame(row(9, "assistant", "tail"), more = false))
        assertTrue(n.items().none { it is RcThreadItem.EarlierOnPhone })
    }

    @Test
    fun moreStaysTrueOnceSetBecauseTheDroppedRowsDoNotComeBack() {
        val m = openModel()
        m.accept("s-1", frame(row(9, "assistant", "tail"), more = true))
        // A live delta carries more:false -- it is a delta, not a statement about history.
        m.accept("s-1", frame(row(10, "assistant", "next"), more = false))
        assertTrue("older rows are still only on the phone", m.moreAbove)
        assertEquals(RcThreadItem.EarlierOnPhone, m.items().first())
    }

    @Test
    fun aFrameForAnotherSessionIsDropped() {
        val m = openModel("s-1")
        m.accept("s-1", frame(row(1, "user", "mine")))
        assertFalse("rows tagged with another session may never render here",
            m.accept("s-2", frame(row(2, "assistant", "theirs"))))
        assertEquals(listOf("mine"), m.rows.map { it.text })
    }

    @Test
    fun aFrameArrivingWithNoOpenSessionIsDropped() {
        val m = RcThreadModel()
        assertFalse(m.accept("s-1", frame(row(1, "user", "orphan"))))
        assertTrue(m.rows.isEmpty())
    }

    @Test
    fun seenSeqIsTheHighestRenderedSeqAndMinusOneWhenEmpty() {
        val m = openModel()
        assertEquals(-1L, m.seenSeq())
        m.accept("s-1", frame(row(3, "user", "a") + "," + row(9, "assistant", "b")))
        assertEquals(9L, m.seenSeq())
    }

    @Test
    fun openingAnotherSessionDiscardsTheOldThread() {
        val m = openModel("s-1")
        m.accept("s-1", frame(row(5, "user", "old"), more = true))
        m.open("s-2")
        assertTrue(m.rows.isEmpty())
        assertFalse(m.moreAbove)
        assertEquals(-1L, m.seenSeq())
        assertFalse("the old session's rows must not leak into the new thread",
            m.accept("s-1", frame(row(6, "assistant", "stale"))))
    }

    @Test
    fun reopeningTheSameSessionAlsoStartsFromEmpty() {
        val m = openModel("s-1")
        m.accept("s-1", frame(row(5, "user", "old")))
        m.open("s-1")
        assertTrue("a re-open re-requests the tail; keeping rows would double them",
            m.rows.isEmpty())
    }

    @Test
    fun closeClearsEverythingAndRefusesLaterFrames() {
        val m = openModel()
        m.accept("s-1", frame(row(2, "user", "a")))
        m.close()
        assertNull(m.sessionId)
        assertTrue(m.rows.isEmpty())
        assertFalse(m.accept("s-1", frame(row(3, "assistant", "late"))))
    }

    @Test
    fun toolRowsCarryTheirCountAndPromptRowsTheirOptions() {
        val m = openModel()
        m.accept(
            "s-1",
            frame(
                row(1, "tools", "Read, Grep", ",\"c\":7") + "," +
                    row(2, "prompt", "Allow Bash?", ",\"o\":[\"Yes\",\"No\"],\"i\":\"req-3\"")
            )
        )
        assertEquals(7, m.rows[0].toolCount)
        assertEquals(listOf("Yes", "No"), m.rows[1].options)
        assertEquals("req-3", m.rows[1].requestId)
    }

    @Test
    fun aTrailingAnswerablePromptBlocksTheThread() {
        val m = openModel()
        m.accept("s-1", frame(row(1, "prompt", "Allow Bash?", ",\"o\":[\"Yes\",\"No\"],\"i\":\"r1\"")))
        val blocking = m.blockingPrompt()
        assertEquals("Allow Bash?", blocking?.text)
        assertEquals(listOf("Yes", "No"), blocking?.options)
    }

    @Test
    fun aPromptWithNoOptionsStillBlocksButOffersNothingToSelect() {
        val m = openModel()
        m.accept("s-1", frame(row(1, "prompt", "Approve this edit?")))
        val blocking = m.blockingPrompt()
        assertEquals("Approve this edit?", blocking?.text)
        assertTrue("nothing to select means it can only be answered on the phone",
            blocking!!.options.isEmpty())
    }

    @Test
    fun anAnsweredPromptNoLongerBlocks() {
        val m = openModel()
        m.accept("s-1", frame(row(1, "prompt", "Allow Bash?", ",\"o\":[\"Yes\"],\"i\":\"r1\"")))
        m.accept("s-1", frame(row(2, "assistant", "Running it.")))
        assertNull("a later row proves the prompt was resolved", m.blockingPrompt())
    }

    @Test
    fun malformedFramesAreIgnoredAndNeverThrow() {
        val m = openModel()
        m.accept("s-1", frame(row(1, "user", "keep")))
        assertFalse(m.accept("s-1", "not json"))
        assertFalse(m.accept("s-1", ""))
        assertFalse(m.accept("s-1", """{"rows":"nonsense"}"""))
        assertFalse(m.accept("s-1", """[1,2,3]"""))
        assertEquals(listOf("keep"), m.rows.map { it.text })
    }

    @Test
    fun rowsWithNoUsableRoleOrSeqAreSkippedRatherThanRenderedBlank() {
        val m = openModel()
        m.accept(
            "s-1",
            """{"rows":[{"r":"user","x":"no seq"},{"q":2},{"q":3,"r":"user","x":"good"},7],""" +
                """"more":false,"lastSeq":3}"""
        )
        assertEquals(listOf("good"), m.rows.map { it.text })
    }

    @Test
    fun theRowListIsCappedSoALongThreadCannotGrowWithoutBound() {
        val m = openModel()
        for (i in 0 until RcThreadModel.MAX_ROWS + 15) {
            m.accept("s-1", frame(row(i.toLong(), "assistant", "row$i")))
        }
        assertEquals(RcThreadModel.MAX_ROWS, m.rows.size)
        assertEquals("the newest rows survive", "row${RcThreadModel.MAX_ROWS + 14}", m.rows.last().text)
        assertTrue("dropping rows locally is the same loss `more` reports", m.moreAbove)
    }
}
