package com.repository.glasses.listener.ui

import com.repository.glasses.listener.bt.RcStateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End of the wire, glasses side.
 *
 * The phone learned to put sessions it discovered from the orchestrator's REST list into the
 * CH_RC_STATE_PUSH snapshot -- previously only sessions announced over its orchestrator WebSocket
 * were in there, so a session started in the phone's own RC UI reached the glasses as an empty
 * list and no row rendered at all.
 *
 * The frames below are VERBATIM hardware captures, not hand-written fixtures: the empty one and
 * then the populated one the phone actually pushed on link-up. They are here so that a phone-side
 * regression that reverts to pushing nothing, or that changes the field spelling, fails a glasses
 * test rather than silently rendering an empty chat list on the user's face.
 */
class RcDiscoveredSessionRendersTest {

    /** Captured from the phone at 04:15:55 -- the snapshot before the REST list was merged in. */
    private val emptyFrame = """{"ws":true,"s":[]}"""

    /** Captured at 04:15:56 -- the same link-up, after the REST reconcile found the session. */
    private val discoveredFrame =
        """{"ws":true,"s":[{"id":"bb05f47b-c842-4558-8172-27cd524779d6","n":"test",""" +
            """"w":"varingait","st":"open","t":false,"u":false,"q":-1}]}"""

    @Test
    fun theEmptyFrameRendersNoRcRowAtAll() {
        // This is the bug's signature. Pinned so "the glasses show nothing" stays a test failure.
        val rows = ChatRowBuilder.build(RcStateParser.parse(emptyFrame)!!, emptyList())
        assertTrue(rows.filterIsInstance<ChatRow.RcSession>().isEmpty())
        assertFalse("no RC header without RC rows", rows.any { it is ChatRow.RcGroup })
    }

    @Test
    fun theDiscoveredSessionFrameRendersOneEnterableRow() {
        val rows = ChatRowBuilder.build(RcStateParser.parse(discoveredFrame)!!, emptyList())
        assertTrue("the pinned RC header must appear", rows.any { it is ChatRow.RcGroup })
        val row = rows.filterIsInstance<ChatRow.RcSession>().single()
        assertEquals("bb05f47b-c842-4558-8172-27cd524779d6", row.id)
        assertEquals("test", row.name)
        assertEquals("varingait", row.folder)
        assertFalse("a discovered session is not mid-turn", row.turning)
        assertFalse("a discovered session carries no unread bit", row.unread)
        assertFalse(row.ended)
        assertTrue("the user must be able to open it", row.enterable)
        assertFalse("ws is up in the captured frame, so the row is not dim", row.dim)
    }

    @Test
    fun aDiscoveredSessionAcceptsVoiceOnceItIsListed() {
        // lastSeq is -1 on a discovered session (the phone has mirrored no rows for it yet). That
        // must not disqualify it from dictation, which is the whole point of showing the row.
        val row = ChatRowBuilder.build(RcStateParser.parse(discoveredFrame)!!, emptyList())
            .filterIsInstance<ChatRow.RcSession>().single()
        assertTrue(row.voiceAllowed)
    }

    @Test
    fun theSessionDisappearsWhenTheNextSnapshotOmitsIt() {
        // Removal path for exactly this feature: the phone drops a session from the REST merge and
        // the row must go, since absence from the snapshot IS the removal instruction.
        val present = ChatRowBuilder.build(RcStateParser.parse(discoveredFrame)!!, emptyList())
        assertTrue(present.any { it is ChatRow.RcSession })
        val gone = ChatRowBuilder.build(RcStateParser.parse(emptyFrame)!!, emptyList())
        assertTrue(gone.filterIsInstance<ChatRow.RcSession>().isEmpty())
    }
}
