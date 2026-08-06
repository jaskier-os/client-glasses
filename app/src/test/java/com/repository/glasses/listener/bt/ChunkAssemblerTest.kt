package com.repository.glasses.listener.bt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the chunk reassembly extracted from GlassesBtClient.handleChunkedJson.
 *
 * The assembler recognises exactly ONE layout, [prefix?][chunk][isFinal][streamId][seq]. Anything
 * else is refused as chunk_unframed rather than reassembled on a guess -- the group at the bottom
 * pins that, because a tolerant reader is how a torn payload reaches a screen looking complete.
 *
 * The bounds (age eviction, stream cap, size ceiling) are covered here too: each must produce a
 * VISIBLE failure, since a silently dropped stream leaves its waiting screen spinning forever.
 */
class ChunkAssemblerTest {

    private fun asm(nowMs: () -> Long = { 0L }) = ChunkAssembler(nowMs)

    private val S = ChunkAssembler.SENTINEL

    private fun outcome(
        a: ChunkAssembler,
        channel: String,
        args: List<String>,
        prefixIndex: Int = -1
    ) = a.acceptFrame(channel, args, prefixIndex)

    @Test
    fun aFramedStreamCompletesAcrossTwoChunks() {
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
    fun aReusedStreamIdStartingAtSeqZeroIsAcceptedNotSwallowed() {
        // The phone can restart and mint the same id again; a tombstone must not deafen it.
        val a = asm()
        outcome(a, "ch.a", listOf("aaa", "0", "${S}ch.a#1", "0"))
        outcome(a, "ch.a", listOf("ccc", "0", "${S}ch.a#1", "2")) // gap -> dropped + tombstoned
        assertEquals(
            ChunkAssembler.Outcome.Completed(null, "fresh"),
            outcome(a, "ch.a", listOf("fresh", "1", "${S}ch.a#1", "0"))
        )
    }

    @Test
    fun everyEvictedStreamIsReportedNotJustTheLastOne() {
        var now = 0L
        val a = asm { now }
        outcome(a, "ch.a", listOf("a", "0", "${S}ch.a#1", "0"))
        outcome(a, "ch.b", listOf("b", "0", "${S}ch.b#1", "0"))
        now = 61_000L
        // One sweep expires both; neither failure may be lost.
        val first = outcome(a, "ch.c", listOf("c", "0", "${S}ch.c#1", "0"))
        val reported = mutableListOf<String>()
        if (first is ChunkAssembler.Outcome.Failed) reported.add(first.channel)
        reported.addAll(a.drainFailures().map { it.channel })
        assertEquals(listOf("ch.a", "ch.b"), reported)
    }

    // --- stream-open accounting ---

    private fun recorderOn(a: ChunkAssembler): MutableList<String> {
        val seen = mutableListOf<String>()
        a.onStreamOpened = { channel -> seen.add(channel) }
        return seen
    }

    @Test
    fun branchIsReportedOncePerStreamNotOncePerChunk() {
        // A 100-chunk history send must not emit 100 log lines.
        val a = asm()
        val seen = recorderOn(a)
        repeat(99) { i -> outcome(a, "ch.a", listOf("x", "0", "${S}ch.a#1", "$i")) }
        outcome(a, "ch.a", listOf("x", "1", "${S}ch.a#1", "99"))
        assertEquals(listOf("ch.a"), seen)
    }

    @Test
    fun concurrentStreamsOnOneChannelAreEachReportedOnce() {
        val a = asm()
        val seen = recorderOn(a)
        outcome(a, "ch.a", listOf("a1", "0", "${S}ch.a#1", "0"))
        outcome(a, "ch.a", listOf("b1", "0", "${S}ch.a#2", "0"))
        outcome(a, "ch.a", listOf("a2", "1", "${S}ch.a#1", "1"))
        outcome(a, "ch.a", listOf("b2", "1", "${S}ch.a#2", "1"))
        assertEquals(listOf("ch.a", "ch.a"), seen)
    }

    @Test
    fun anOrphanedFrameOpensNoStreamAndIsNotReported() {
        val a = asm()
        val seen = recorderOn(a)
        assertEquals(
            ChunkAssembler.Outcome.Failed("ch.a", "chunk_orphan"),
            outcome(a, "ch.a", listOf("aaa", "1", "${S}ch.a#1", "7"))
        )
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun aStreamPastTheSizeCeilingIsDroppedNotCompleted() {
        // A peer that never sends a final chunk must not grow the buffer without limit, and the
        // over-long stream must not then complete truncated.
        val a = asm()
        val big = "x".repeat(ChunkAssembler.MAX_STREAM_CHARS / 2 + 1)
        assertEquals(
            ChunkAssembler.Outcome.None,
            outcome(a, "ch.a", listOf(big, "0", "${S}ch.a#1", "0"))
        )
        assertEquals(
            ChunkAssembler.Outcome.Failed("ch.a", ChunkAssembler.REASON_TOO_LARGE),
            outcome(a, "ch.a", listOf(big, "0", "${S}ch.a#1", "1"))
        )
        // The id is tombstoned, so the stream's own final chunk cannot complete a torn payload.
        assertEquals(
            ChunkAssembler.Outcome.None,
            outcome(a, "ch.a", listOf("tail", "1", "${S}ch.a#1", "2"))
        )
    }

    @Test
    fun theContactsLayoutReadsItsPrefixAtIndexTwo() {
        // CH_CONTACTS carries [op, mac, hash, chunk, isFinal, streamId, seq]: the one shipping
        // channel whose prefix is not at index 0, so the prefixIndex arithmetic is pinned here.
        val a = asm()
        assertEquals(
            ChunkAssembler.Outcome.None,
            outcome(a, "listener_contacts",
                listOf("LIST", "AA:BB", "h1", "{\"a\":", "0", "${S}c#1", "0"), prefixIndex = 2)
        )
        assertEquals(
            ChunkAssembler.Outcome.Completed("h1", "{\"a\":1}"),
            outcome(a, "listener_contacts",
                listOf("LIST", "AA:BB", "h1", "1}", "1", "${S}c#1", "1"), prefixIndex = 2)
        )
    }

    // --- refusal: there is no tolerant fallback left ---
    //
    // Each of these was previously reassembled by the channel-keyed legacy branch. That branch is
    // gone, so every one must now FAIL VISIBLY. Silence here would be the worst outcome available:
    // the waiting screen would spin forever with no line in the log saying why.

    private fun assertRefused(args: List<String>, prefixIndex: Int = -1) {
        assertEquals(
            ChunkAssembler.Outcome.Failed("ch.a", ChunkAssembler.REASON_UNFRAMED),
            outcome(asm(), "ch.a", args, prefixIndex)
        )
    }

    @Test
    fun aTwoArgFrameWithNoStreamIdIsRefused() {
        assertRefused(listOf("aaa", "1"))
    }

    @Test
    fun aPrefixedThreeArgFrameWithNoStreamIdIsRefused() {
        assertRefused(listOf("c1", "aaa", "1"), prefixIndex = 0)
    }

    @Test
    fun theRightArityWithoutTheSentinelIsRefused() {
        // A chunk whose text merely LOOKS like trailing framing must not be mistaken for it.
        assertRefused(listOf("aaa", "1", "cs#notasentinel", "0"))
    }

    @Test
    fun theRightArityWithANonNumericSeqIsRefused() {
        assertRefused(listOf("aaa", "1", "${S}ch.a#1", "x"))
    }

    @Test
    fun oneArgTooManyIsRefusedEvenWithASentinelAndDigitSeq() {
        assertRefused(listOf("extra", "aaa", "1", "${S}ch.a#1", "0"))
    }

    @Test
    fun aChunkContainingTheLiteralSentinelTextIsStillRefusedWithoutRealFraming() {
        assertRefused(listOf("payload containing ${S}ch.a#1 inline", "1"))
    }

    @Test
    fun aRefusedFrameLeavesNoBufferBehind() {
        // The refusal must not open a stream that a later framed seq 0 would then collide with.
        val a = asm()
        assertEquals(
            ChunkAssembler.Outcome.Failed("ch.a", ChunkAssembler.REASON_UNFRAMED),
            outcome(a, "ch.a", listOf("junk", "0"))
        )
        assertEquals(
            ChunkAssembler.Outcome.Completed(null, "clean"),
            outcome(a, "ch.a", listOf("clean", "1", "${S}ch.a#1", "0"))
        )
    }

    @Test
    fun clearDropsEveryInFlightStream() {
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
