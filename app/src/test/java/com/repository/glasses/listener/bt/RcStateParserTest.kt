package com.repository.glasses.listener.bt

import com.repository.glasses.listener.ui.RcState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CH_RC_STATE_PUSH carries a full authoritative snapshot. Anything the parser cannot understand
 * must fail SAFE -- dim and mic-refused -- rather than fail open.
 */
class RcStateParserTest {

    @Test
    fun parsesAFullSnapshot() {
        val s = RcStateParser.parse(
            """{"ws":true,"s":[
                 {"id":"a","n":"fix rfcomm teardown","w":"~/AI/clients/glasses",
                  "st":"open","t":true,"u":false,"q":12},
                 {"id":"b","n":"resume embeddings","w":"~/Repository",
                  "st":"ended","t":false,"u":true,"q":3}]}"""
        )!!
        assertTrue(s.wsConnected)
        assertEquals(2, s.sessions.size)
        val a = s.sessions[0]
        assertEquals("a", a.id)
        assertEquals("fix rfcomm teardown", a.name)
        assertEquals("~/AI/clients/glasses", a.folder)
        assertTrue(a.turning)
        assertFalse(a.unread)
        assertFalse(a.ended)
        assertEquals(12L, a.lastSeq)
        val b = s.sessions[1]
        assertTrue(b.ended)
        assertTrue(b.unread)
        assertEquals(3L, b.lastSeq)
    }

    @Test
    fun snapshotOrderIsPreservedBecauseItCarriesTheActivityOrder() {
        val s = RcStateParser.parse(
            """{"ws":true,"s":[{"id":"x"},{"id":"y"},{"id":"z"}]}"""
        )!!
        assertEquals(listOf("x", "y", "z"), s.sessions.map { it.id })
    }

    @Test
    fun missingWsDefaultsToFalse() {
        // Fail safe: dim the rows and refuse the mic rather than invite a dictation into a void.
        assertFalse(RcStateParser.parse("""{"s":[{"id":"a"}]}""")!!.wsConnected)
        assertFalse(RcStateParser.parse("""{"ws":"yes","s":[]}""")!!.wsConnected)
    }

    @Test
    fun anEmptySessionListIsAValidSnapshotMeaningNoSessions() {
        val s = RcStateParser.parse("""{"ws":true,"s":[]}""")!!
        assertTrue(s.wsConnected)
        assertTrue(s.sessions.isEmpty())
    }

    @Test
    fun aMissingSessionArrayIsAlsoAnEmptySnapshotNotAnError() {
        assertEquals(0, RcStateParser.parse("""{"ws":true}""")!!.sessions.size)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val s = RcStateParser.parse(
            """{"ws":true,"future":42,"s":[{"id":"a","n":"n","zz":{"deep":[1,2]}}]}"""
        )!!
        assertEquals(1, s.sessions.size)
        assertEquals("a", s.sessions[0].id)
    }

    @Test
    fun missingOptionalFieldsGetSafeDefaults() {
        val row = RcStateParser.parse("""{"ws":true,"s":[{"id":"a"}]}""")!!.sessions.single()
        assertEquals("a", row.name)
        assertEquals("", row.folder)
        assertFalse(row.turning)
        assertFalse(row.unread)
        assertFalse(row.ended)
        assertEquals(-1L, row.lastSeq)
    }

    @Test
    fun aSessionWithoutAnIdIsDroppedBecauseItCanNeverBeAddressed() {
        val s = RcStateParser.parse("""{"ws":true,"s":[{"n":"nameless"},{"id":"a"},{"id":""}]}""")!!
        assertEquals(listOf("a"), s.sessions.map { it.id })
    }

    @Test
    fun aMalformedFrameYieldsNoChangeAndDoesNotThrow() {
        // Null means "no change": the caller keeps whatever it last rendered rather than blanking
        // the list on one bad frame.
        assertNull(RcStateParser.parse("not json at all"))
        assertNull(RcStateParser.parse(""))
        assertNull(RcStateParser.parse("""{"ws":true,"s":"""))
        assertNull(RcStateParser.parse("""[1,2,3]"""))
    }

    @Test
    fun aNonObjectSessionEntryIsSkippedRatherThanKillingTheWholeFrame() {
        val s = RcStateParser.parse("""{"ws":true,"s":["junk",{"id":"a"},7]}""")!!
        assertEquals(listOf("a"), s.sessions.map { it.id })
    }

    @Test
    fun statusIsEndedOnlyForTheExactEndedToken() {
        fun ended(st: String) =
            RcStateParser.parse("""{"ws":true,"s":[{"id":"a","st":"$st"}]}""")!!.sessions.single().ended
        assertTrue(ended("ended"))
        assertFalse(ended("open"))
        assertFalse(ended(""))
        assertFalse("an unknown status must not silently kill a live session", ended("paused"))
    }

    @Test
    fun theParsedSnapshotFeedsTheRowBuilderDirectly() {
        val s: RcState = RcStateParser.parse("""{"ws":false,"s":[{"id":"a","t":true}]}""")!!
        assertFalse(s.wsConnected)
        assertTrue(s.sessions.single().turning)
    }
}
