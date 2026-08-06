package com.repository.glasses.listener.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Characterisation tests for the chunk reassembly extracted from GlassesBtClient.handleChunkedJson,
 * plus the two new local bounds (age eviction and stream cap). The first group pins today's
 * behaviour: a channel-keyed buffer, the prefix taken from the FINAL chunk, and no bounds at all.
 */
class ChunkAssemblerTest {

    private fun asm(nowMs: () -> Long = { 0L }) = ChunkAssembler(nowMs)

    /** Completion-or-nothing view, so the legacy characterisation assertions read unchanged. */
    private fun ChunkAssembler.accept(
        channel: String,
        args: List<String>,
        prefixIndex: Int
    ): ChunkAssembler.Outcome.Completed? =
        acceptFrame(channel, args, prefixIndex) as? ChunkAssembler.Outcome.Completed

    @Test
    fun singleFinalChunkCompletesImmediately() {
        val a = asm()
        val r = a.accept("ch.a", listOf("{\"a\":1}", "1"), prefixIndex = -1)
        assertEquals("{\"a\":1}", r?.json)
        assertNull(r?.prefix)
    }

    @Test
    fun multiChunkConcatenatesAllPiecesInOrder() {
        val a = asm()
        assertNull(a.accept("ch.a", listOf("aaa", "0"), prefixIndex = -1))
        assertNull(a.accept("ch.a", listOf("bbb", "0"), prefixIndex = -1))
        assertEquals("aaabbbccc", a.accept("ch.a", listOf("ccc", "1"), prefixIndex = -1)!!.json)
    }

    @Test
    fun prefixIsCarriedThroughOnCompletion() {
        val a = asm()
        a.accept("ch.h", listOf("c1", "aaa", "0"), prefixIndex = 0)
        val r = a.accept("ch.h", listOf("c1", "bbb", "1"), prefixIndex = 0)
        assertEquals("c1", r?.prefix)
        assertEquals("aaabbb", r?.json)
    }

    @Test
    fun legacyPathTakesThePrefixFromTheFinalChunk() {
        // Today's handleChunkedJson recomputes the prefix on every chunk and hands the final
        // chunk's value to onComplete. Pinned so the extraction cannot silently change it.
        val a = asm()
        a.accept("ch.h", listOf("first", "aaa", "0"), prefixIndex = 0)
        assertEquals("last", a.accept("ch.h", listOf("last", "bbb", "1"), prefixIndex = 0)!!.prefix)
    }

    @Test
    fun completionClearsBufferSoNextStreamStartsFresh() {
        val a = asm()
        a.accept("ch.a", listOf("aaa", "1"), prefixIndex = -1)
        assertEquals("zzz", a.accept("ch.a", listOf("zzz", "1"), prefixIndex = -1)!!.json)
    }

    @Test
    fun missingArgsAreReadAsEmptyStringsJustLikeGetOrElse() {
        // handleChunkedJson uses args.getOrElse { "" } on every index, so a short frame appends
        // nothing and is not final. Pinned so the extraction stays tolerant of the same input.
        val a = asm()
        assertNull(a.accept("ch.a", listOf("only"), prefixIndex = -1))
        assertEquals("onlyrest", a.accept("ch.a", listOf("rest", "1"), prefixIndex = -1)!!.json)
    }

    @Test
    fun partialStreamOlderThanSixtySecondsIsEvicted() {
        var now = 0L
        val a = asm { now }
        a.accept("ch.a", listOf("aaa", "0"), prefixIndex = -1)
        now = 61_000L
        // A chunk on ANOTHER channel triggers the sweep; the stale one is dropped. Its own final
        // chunk retires the tombstone without completing a truncated payload; the stream AFTER
        // that starts clean.
        a.accept("ch.b", listOf("bbb", "0"), prefixIndex = -1)
        assertNull(a.accept("ch.a", listOf("zzz", "1"), prefixIndex = -1))
        assertEquals("next", a.accept("ch.a", listOf("next", "1"), prefixIndex = -1)!!.json)
    }

    @Test
    fun concurrentPartialStreamsAreCappedAtSixteenOldestFirst() {
        var now = 0L
        val a = asm { now }
        repeat(17) { i -> now = i.toLong(); a.accept("ch$i", listOf("p$i", "0"), prefixIndex = -1) }
        // ch0 was the oldest and must have been evicted, so its final chunk cannot complete.
        assertNull(a.accept("ch0", listOf("zzz", "1"), prefixIndex = -1))
        // ch16 is still buffered and completes with its whole payload.
        assertEquals("p16tail", a.accept("ch16", listOf("tail", "1"), prefixIndex = -1)!!.json)
    }

    @Test
    fun anEvictedStreamNeverCompletesTruncated() {
        // The stream's own remaining chunks must be discarded, not silently completed as a tail.
        var now = 0L
        val a = asm { now }
        a.accept("ch.a", listOf("head", "0"), prefixIndex = -1)
        now = 61_000L
        a.accept("ch.b", listOf("x", "0"), prefixIndex = -1) // triggers the sweep
        assertNull(a.accept("ch.a", listOf("middle", "0"), prefixIndex = -1))
        assertNull(a.accept("ch.a", listOf("tail", "1"), prefixIndex = -1))
        // The tombstone is retired by that final chunk, so the NEXT stream works normally.
        assertEquals("fresh", a.accept("ch.a", listOf("fresh", "1"), prefixIndex = -1)!!.json)
    }

    @Test
    fun overflowEvictionAlsoRefusesToCompleteTruncated() {
        var now = 0L
        val a = asm { now }
        a.accept("victim", listOf("head", "0"), prefixIndex = -1)
        repeat(16) { i -> now = (i + 1).toLong(); a.accept("ch$i", listOf("p$i", "0"), prefixIndex = -1) }
        assertNull(a.accept("victim", listOf("tail", "1"), prefixIndex = -1))
    }

    @Test
    fun theCapEvictsOnlyTheOverflowNotTheWholeMap() {
        var now = 0L
        val a = asm { now }
        repeat(17) { i -> now = i.toLong(); a.accept("ch$i", listOf("p$i", "0"), prefixIndex = -1) }
        // ch1..ch16 must ALL still be buffered; only ch0 (the oldest) went.
        for (i in 1..16) {
            assertEquals("p${i}t", a.accept("ch$i", listOf("t", "1"), prefixIndex = -1)!!.json)
        }
    }

    @Test
    fun aStreamPastTheSizeCeilingIsDroppedNotCompleted() {
        val a = asm()
        val big = "x".repeat(1_000_000)
        repeat(4) { assertNull(a.accept("ch.a", listOf(big, "0"), prefixIndex = -1)) }
        assertNull(a.accept("ch.a", listOf("tail", "1"), prefixIndex = -1))
        assertEquals("small", a.accept("ch.a", listOf("small", "1"), prefixIndex = -1)!!.json)
    }

    @Test
    fun contactsLayoutReadsHashAtIndexTwoAndChunkAtIndexThree() {
        // CH_CONTACTS wire: ["LIST", agMac, hash, jsonChunk, isFinal]
        val a = asm()
        assertNull(a.accept("listener_contacts", listOf("LIST", "AA:BB", "h1", "{\"a\":", "0"), prefixIndex = 2))
        val r = a.accept("listener_contacts", listOf("LIST", "AA:BB", "h1", "1}", "1"), prefixIndex = 2)
        assertEquals("h1", r?.prefix)
        assertEquals("{\"a\":1}", r?.json)
    }

    @Test
    fun clearDropsEveryPartialStream() {
        val a = asm()
        a.accept("ch.a", listOf("aaa", "0"), prefixIndex = -1)
        a.clear()
        assertEquals("zzz", a.accept("ch.a", listOf("zzz", "1"), prefixIndex = -1)!!.json)
    }

    // --- streamId / seq format (spec 0.6.2 / 0.6.3) ---

    private val S = ChunkAssembler.SENTINEL

    private fun outcome(
        a: ChunkAssembler,
        channel: String,
        args: List<String>,
        prefixIndex: Int = -1
    ) = a.acceptFrame(channel, args, prefixIndex)

    @Test
    fun newFormatStreamCompletesAcrossTwoChunks() {
        val a = asm()
        assertEquals(
            ChunkAssembler.Outcome.None,
            outcome(a, "ch.a", listOf("aaa", "0", "${S}ch.a#1", "0"))
        )
        val r = outcome(a, "ch.a", listOf("bbb", "1", "${S}ch.a#1", "1"))
        assertEquals(ChunkAssembler.Outcome.Completed(null, "aaabbb"), r)
    }

    @Test
    fun twoStreamsInterleavedOnOneChannelBothCompleteUncorrupted() {
        // The property the whole rewrite exists for.
        val a = asm()
        outcome(a, "ch.a", listOf("A1", "0", "${S}ch.a#1", "0"))
        outcome(a, "ch.a", listOf("B1", "0", "${S}ch.a#2", "0"))
        outcome(a, "ch.a", listOf("A2", "0", "${S}ch.a#1", "1"))
        outcome(a, "ch.a", listOf("B2", "0", "${S}ch.a#2", "1"))
        assertEquals(
            ChunkAssembler.Outcome.Completed(null, "A1A2A3"),
            outcome(a, "ch.a", listOf("A3", "1", "${S}ch.a#1", "2"))
        )
        assertEquals(
            ChunkAssembler.Outcome.Completed(null, "B1B2B3"),
            outcome(a, "ch.a", listOf("B3", "1", "${S}ch.a#2", "2"))
        )
    }

    @Test
    fun prefixComesFromSeqZeroAndALaterDifferingPrefixIsIgnored() {
        val a = asm()
        outcome(a, "ch.h", listOf("chat-1", "aaa", "0", "${S}ch.h#1", "0"), prefixIndex = 0)
        val r = outcome(a, "ch.h", listOf("chat-WRONG", "bbb", "1", "${S}ch.h#1", "1"), prefixIndex = 0)
        assertEquals(ChunkAssembler.Outcome.Completed("chat-1", "aaabbb"), r)
    }

    @Test
    fun aStreamFirstSeenAtNonZeroSeqIsDroppedAsAnOrphan() {
        // Never complete a prefixed payload with a null prefix: it would route to the wrong chat.
        val a = asm()
        assertEquals(
            ChunkAssembler.Outcome.Failed("ch.h", "chunk_orphan"),
            outcome(a, "ch.h", listOf("chat-1", "bbb", "0", "${S}ch.h#9", "1"), prefixIndex = 0)
        )
        // The rest of that stream stays dropped rather than completing truncated.
        assertEquals(
            ChunkAssembler.Outcome.None,
            outcome(a, "ch.h", listOf("chat-1", "ccc", "1", "${S}ch.h#9", "2"), prefixIndex = 0)
        )
    }

    @Test
    fun aSequenceGapReturnsAVisibleFailureNotSilence() {
        // There is no reordering on this transport, so a gap means a LOST frame. Completing
        // corrupt is bad; completing never (a permanent spinner) is worse. It must be reported.
        val a = asm()
        outcome(a, "ch.a", listOf("aaa", "0", "${S}ch.a#1", "0"))
        assertEquals(
            ChunkAssembler.Outcome.Failed("ch.a", "chunk_gap"),
            outcome(a, "ch.a", listOf("ccc", "1", "${S}ch.a#1", "2"))
        )
    }

    @Test
    fun theStalenessClockRunsFromTheLastChunkNotTheStreamStart() {
        var now = 0L
        val a = asm { now }
        // A chunk every 40 s: the stream is healthy and must survive well past 60 s total.
        outcome(a, "ch.a", listOf("a", "0", "${S}ch.a#1", "0"))
        for (i in 1..5) {
            now += 40_000L
            assertEquals(
                ChunkAssembler.Outcome.None,
                outcome(a, "ch.a", listOf("a", "0", "${S}ch.a#1", "$i"))
            )
        }
        now += 40_000L
        assertEquals(
            ChunkAssembler.Outcome.Completed(null, "aaaaaaa"),
            outcome(a, "ch.a", listOf("a", "1", "${S}ch.a#1", "6"))
        )
    }

    @Test
    fun aStreamSilentForSixtySecondsIsEvictedWithAFailure() {
        var now = 0L
        val a = asm { now }
        outcome(a, "ch.a", listOf("aaa", "0", "${S}ch.a#1", "0"))
        now = 61_000L
        // Any later frame triggers the sweep, which must report the eviction.
        val r = outcome(a, "ch.b", listOf("x", "0", "${S}ch.b#1", "0"))
        assertEquals(ChunkAssembler.Outcome.Failed("ch.a", "chunk_stale"), r)
    }

    @Test
    fun theSixteenStreamCapEvictsOldestFirstWithAFailure() {
        var now = 0L
        val a = asm { now }
        outcome(a, "victim", listOf("p", "0", "${S}victim#1", "0"))
        var lastFailure: ChunkAssembler.Outcome? = null
        repeat(16) { i ->
            now = (i + 1).toLong()
            val r = outcome(a, "ch$i", listOf("p$i", "0", "${S}ch$i#1", "0"))
            if (r is ChunkAssembler.Outcome.Failed) lastFailure = r
        }
        assertEquals(ChunkAssembler.Outcome.Failed("victim", "chunk_overflow"), lastFailure)
    }

    @Test
    fun legacyTwoArgAndThreeArgFramesTakeTheLegacyPath() {
        val a = asm()
        assertEquals(
            ChunkAssembler.Outcome.Completed(null, "aaa"),
            outcome(a, "ch.a", listOf("aaa", "1"))
        )
        assertEquals(
            ChunkAssembler.Outcome.Completed("c1", "bbb"),
            outcome(a, "ch.h", listOf("c1", "bbb", "1"), prefixIndex = 0)
        )
    }

    @Test
    fun aChunkContainingTheLiteralTextCsHashStillTakesTheLegacyPath() {
        // Only the control character disambiguates; printable "cs#" occurs naturally in JSON.
        val a = asm()
        val r = outcome(a, "ch.a", listOf("{\"x\":\"cs#1\"}", "1", "cs#ch.a#1", "0"))
        assertEquals(ChunkAssembler.Outcome.Completed(null, "{\"x\":\"cs#1\"}"), r)
    }

    @Test
    fun rightArityButANonNumericSeqTakesTheLegacyPath() {
        val a = asm()
        val r = outcome(a, "ch.a", listOf("aaa", "1", "${S}ch.a#1", "seq"))
        assertEquals(ChunkAssembler.Outcome.Completed(null, "aaa"), r)
    }

    @Test
    fun wrongArityWithASentinelStillTakesTheLegacyPath() {
        val a = asm()
        val r = outcome(a, "ch.a", listOf("aaa", "1", "${S}ch.a#1", "0", "extra"))
        assertEquals(ChunkAssembler.Outcome.Completed(null, "aaa"), r)
    }

    @Test
    fun aNewFormatSeqZeroPurgesAStaleLegacyBufferOnThatChannel() {
        // A torn old-format stream must not concatenate onto a new-format one.
        val a = asm()
        outcome(a, "ch.a", listOf("TORN", "0"))
        outcome(a, "ch.a", listOf("aaa", "0", "${S}ch.a#1", "0"))
        assertEquals(
            ChunkAssembler.Outcome.Completed(null, "aaabbb"),
            outcome(a, "ch.a", listOf("bbb", "1", "${S}ch.a#1", "1"))
        )
    }

    @Test
    fun clearDropsNewFormatStreamsToo() {
        val a = asm()
        outcome(a, "ch.a", listOf("aaa", "0", "${S}ch.a#1", "0"))
        a.clear()
        // seq 1 on a cleared assembler is an orphan, not a silent continuation.
        assertEquals(
            ChunkAssembler.Outcome.Failed("ch.a", "chunk_orphan"),
            outcome(a, "ch.a", listOf("bbb", "1", "${S}ch.a#1", "1"))
        )
    }
}
