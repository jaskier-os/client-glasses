package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat list is a mixed list: two fixed header rows, an optional pinned RC section, then the
 * conversations. Every property asserted here exists to make ONE failure impossible -- an RC
 * session appearing asynchronously must never re-aim the caret onto a different row, because the
 * Assistant row starts the microphone.
 */
class ChatRowModelTest {

    private fun rc(
        id: String,
        name: String = "session $id",
        folder: String = "~/work",
        turning: Boolean = false,
        unread: Boolean = false,
        ended: Boolean = false,
        lastSeq: Long = 0L,
    ) = RcSessionState(
        id = id, name = name, folder = folder,
        turning = turning, unread = unread, ended = ended, lastSeq = lastSeq,
    )

    private fun conv(id: String) = ChatSummaryItem(
        id = id, title = "chat $id", relativeTime = "1m", turnCount = 2, isActive = false,
    )

    private fun build(
        sessions: List<RcSessionState> = emptyList(),
        conversations: List<ChatSummaryItem> = emptyList(),
        wsConnected: Boolean = true,
    ) = ChatRowBuilder.build(RcState(wsConnected, sessions), conversations)

    @Test
    fun rcSessionsArePinnedAboveConversations() {
        val rows = build(listOf(rc("a")), listOf(conv("c1")))
        val rcIdx = rows.indexOfFirst { it is ChatRow.RcSession }
        val convIdx = rows.indexOfFirst { it is ChatRow.Conversation }
        assertTrue("expected an RC row", rcIdx >= 0)
        assertTrue("expected a conversation row", convIdx >= 0)
        assertTrue("RC row must precede conversations", rcIdx < convIdx)
        // The two existing header rows keep position 0 and 1 -- they are untouched.
        assertEquals(ChatRow.NewChat, rows[0])
        assertEquals(ChatRow.Assistant, rows[1])
    }

    @Test
    fun rcGroupHeaderOnlyExistsWhenThereIsAtLeastOneRcRow() {
        assertTrue(build(conversations = listOf(conv("c1"))).none { it is ChatRow.RcGroup })
        assertTrue(build(listOf(rc("a"))).any { it is ChatRow.RcGroup })
    }

    @Test
    fun turningSessionsSortFirstAndTheRestKeepSnapshotOrder() {
        // The wire carries no lastActivity field; the phone sends the sessions most-recently-active
        // first, so snapshot order IS activity order and the sort must be stable within a group.
        val rows = build(listOf(rc("a"), rc("b", turning = true), rc("c"), rc("d", turning = true)))
        val ids = rows.filterIsInstance<ChatRow.RcSession>().map { it.id }
        assertEquals(listOf("b", "d", "a", "c"), ids)
    }

    @Test
    fun anEndedSessionNeverSortsToTheTopEvenIfTheSnapshotStillCallsItTurning() {
        // A stale snapshot can carry ended + turning together. Pinning that corpse first would
        // push a genuinely live session past the section cap.
        val ids = build(listOf(rc("a"), rc("b", turning = true, ended = true), rc("c", turning = true)))
            .filterIsInstance<ChatRow.RcSession>().map { it.id }
        assertEquals(listOf("c", "a", "b"), ids)
    }

    @Test
    fun resolvingASelectionAgainstAnEmptyListReportsNoRowRatherThanIndexZero() {
        assertEquals(-1, ChatRowBuilder.resolveSelection(emptyList(), "conv:c1", 3))
        assertEquals(-1, ChatRowBuilder.resolveSelection(emptyList(), null, -1))
    }

    @Test
    fun keysAreStableAndDistinctAcrossRowKinds() {
        val rows = build(listOf(rc("7")), listOf(conv("7")))
        val rcRow = rows.filterIsInstance<ChatRow.RcSession>().single()
        val convRow = rows.filterIsInstance<ChatRow.Conversation>().single()
        assertEquals("rc:7", rcRow.key)
        assertEquals("conv:7", convRow.key)
        assertNotEquals(rcRow.key, convRow.key)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        // Rebuilding from equal input must yield the same keys -- ids are not positional.
        assertEquals(rows.map { it.key }, build(listOf(rc("7")), listOf(conv("7"))).map { it.key })
    }

    @Test
    fun selectionFollowsItsKeyWhenARowIsInsertedAbove() {
        val before = build(conversations = listOf(conv("c1"), conv("c2")))
        val selectedIdx = before.indexOfFirst { it.key == "conv:c2" }
        val after = build(listOf(rc("new")), listOf(conv("c1"), conv("c2")))
        val resolved = ChatRowBuilder.resolveSelection(after, "conv:c2", selectedIdx)
        assertEquals("conv:c2", after[resolved].key)
        assertNotEquals(
            "the insert must move the index, otherwise this test proves nothing",
            selectedIdx, resolved,
        )
    }

    @Test
    fun selectionOnTheAssistantRowSurvivesAnRcInsertAbove() {
        // The Assistant row starts the mic. An RC insert must not shift what "selected" means, and
        // the anchor deliberately does NOT coincide with the answer: only the key lookup can pass.
        val after = build(listOf(rc("x"), rc("y")), listOf(conv("c1")))
        assertEquals(
            ChatRow.Assistant,
            after[ChatRowBuilder.resolveSelection(after, ChatRow.Assistant.key, previousIndex = 4)],
        )
    }

    @Test
    fun aVanishedSelectionNeverFallsBackOntoTheAssistantRow() {
        // The caret sat on the only RC row; the next snapshot drops it. The anchor clamp would
        // otherwise land on index 1, which is Assistant, and the very next tap starts the mic.
        val before = build(listOf(rc("a")))
        val anchor = before.indexOfFirst { it.key == "rc:a" }
        val after = build()
        val landed = after[ChatRowBuilder.resolveSelection(after, "rc:a", anchor)]
        assertNotEquals("a fallback may never arm the microphone", ChatRow.Assistant, landed)
        assertEquals(ChatRow.NewChat, landed)
    }

    @Test
    fun fallbackSkipsTheRcGroupMarkerBecauseItOpensNothing() {
        val rows = build(listOf(rc("a")), listOf(conv("c1")))
        val groupIdx = rows.indexOfFirst { it is ChatRow.RcGroup }
        assertTrue(groupIdx > 0)
        val landed = rows[ChatRowBuilder.resolveSelection(rows, "conv:gone", groupIdx)]
        assertFalse("the group marker is not selectable", landed is ChatRow.RcGroup)
        assertTrue(landed.selectable)
    }

    @Test
    fun vanishedSelectionFallsBackToTheNearestSurvivingRow() {
        val rows = build(conversations = listOf(conv("c1")))
        // No previous index known -> deterministic index 0.
        assertEquals(0, ChatRowBuilder.resolveSelection(rows, "conv:gone"))
        // Previous index known -> clamp into range, never past the end.
        assertEquals(rows.lastIndex, ChatRowBuilder.resolveSelection(rows, "conv:gone", 99))
        assertEquals(2, ChatRowBuilder.resolveSelection(rows, "conv:gone", 2))
        assertEquals(0, ChatRowBuilder.resolveSelection(rows, null, -1))
    }

    @Test
    fun absenceFromTheNextSnapshotRemovesTheRow() {
        val before = build(listOf(rc("a"), rc("b")))
        assertTrue(before.any { it.key == "rc:b" })
        val after = build(listOf(rc("a")))
        assertFalse("absence from the snapshot IS the removal instruction", after.any { it.key == "rc:b" })
    }

    @Test
    fun endedSessionIsFlaggedDimNonEnterableAndNeverTurningOrUnread() {
        val row = build(listOf(rc("a", ended = true, turning = true, unread = true)))
            .filterIsInstance<ChatRow.RcSession>().single()
        assertTrue(row.ended)
        assertFalse("an ended session may not spin", row.turning)
        assertFalse("an ended session may not show an unread bar", row.unread)
        assertFalse("an ended session is not enterable", row.enterable)
        assertTrue(row.dim)
    }

    @Test
    fun wsDownDimsEveryRcRowAndRefusesVoice() {
        val rows = build(listOf(rc("a"), rc("b", turning = true)), wsConnected = false)
            .filterIsInstance<ChatRow.RcSession>()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.dim })
        assertTrue(rows.all { !it.voiceAllowed })
        // Still enterable -- reading stale content is fine, dictating into a void is not.
        assertTrue(rows.all { it.enterable })
    }

    @Test
    fun voiceIsAllowedOnlyOnALiveIdleSessionWithTheWsUp() {
        val rows = build(listOf(rc("a"), rc("b", turning = true), rc("c", ended = true)))
            .filterIsInstance<ChatRow.RcSession>().associateBy { it.id }
        assertTrue(rows.getValue("a").voiceAllowed)
        assertFalse("no dictation into a turn in progress", rows.getValue("b").voiceAllowed)
        assertFalse("no dictation into an ended session", rows.getValue("c").voiceAllowed)
    }

    @Test
    fun thePinnedRcSectionIsCappedSoConversationsStayOnScreen() {
        val rows = build((1..9).map { rc("s$it") }, listOf(conv("c1")))
        val rcRows = rows.filterIsInstance<ChatRow.RcSession>()
        assertEquals(ChatRowBuilder.MAX_PINNED_RC, rcRows.size)
        assertEquals(
            "the cap keeps the newest sessions, in snapshot order",
            listOf("s1", "s2", "s3", "s4", "s5"), rcRows.map { it.id },
        )
        assertTrue(rows.any { it is ChatRow.Conversation })
    }

    @Test
    fun unreadAndTurningNeverCoRenderOnTheSameRow() {
        val row = build(listOf(rc("a", turning = true, unread = true)))
            .filterIsInstance<ChatRow.RcSession>().single()
        assertTrue(row.turning)
        assertFalse("turning wins; the two indicators share the same slot", row.unread)
    }
}
