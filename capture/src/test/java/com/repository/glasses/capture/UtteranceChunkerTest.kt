package com.repository.glasses.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan task 2.6 -- the chunking and banking that sits behind transcribeUtterance.
 *
 * The encoder window is 5 s. An utterance can be up to 12 s, so capture splits it
 * itself and concatenates the banked text: the listener sees ONE call and ONE
 * string, which is what keeps the Binder contract a single synchronous
 * request/response like detectFaces.
 *
 * The return contract is three-valued and every value is load-bearing:
 *   text -- a real transcript,
 *   ""   -- the model ran and heard nothing. This is an explicit empty final and
 *           the phone treats it as CANCEL. Collapsing it to null would turn a
 *           deliberate cancel into a remote-fallback round trip that then
 *           transcribes the same silence again.
 *   null -- local STT is UNAVAILABLE and the caller must fall back to remote.
 */
class UtteranceChunkerTest {

    private val bytesPerSample = 2
    private val rate = 16_000

    private fun bytesFor(seconds: Double): Int =
        (seconds * rate).toInt() * bytesPerSample

    // ---------------- windowing ----------------

    @Test
    fun aTwelveSecondUtteranceSplitsIntoThreeFiveSecondWindows() {
        val w = UtteranceChunker.windows(bytesFor(12.0))
        assertEquals(3, w.size)
        assertEquals(0, w[0].first)
        assertEquals(bytesFor(5.0), w[0].second)
        assertEquals(bytesFor(5.0), w[1].first)
        assertEquals(bytesFor(10.0), w[1].second)
        assertEquals(bytesFor(10.0), w[2].first)
        assertEquals("the tail window is the remainder, not a padded full window",
            bytesFor(12.0), w[2].second)
    }

    @Test
    fun aThreeSecondUtteranceIsASingleWindow() {
        val w = UtteranceChunker.windows(bytesFor(3.0))
        assertEquals(1, w.size)
        assertEquals(0 to bytesFor(3.0), w[0])
    }

    @Test
    fun windowsCoverEveryByteExactlyOnceWithNoGapAndNoOverlap() {
        // A gap drops audio silently -- words vanish from the middle of an
        // utterance with nothing in the log to show for it.
        val total = bytesFor(12.0) - 2
        val w = UtteranceChunker.windows(total)
        assertEquals(0, w.first().first)
        assertEquals(total, w.last().second)
        for (i in 1 until w.size) {
            assertEquals("window $i must start exactly where ${i - 1} ended",
                w[i - 1].second, w[i].first)
        }
    }

    @Test
    fun anExactMultipleOfTheWindowDoesNotProduceATrailingEmptyWindow() {
        // An empty trailing window would run the encoder on zero samples.
        val w = UtteranceChunker.windows(bytesFor(10.0))
        assertEquals(2, w.size)
        assertTrue(w.none { it.first == it.second })
    }

    @Test
    fun anEmptyPayloadYieldsNoWindows() {
        assertTrue(UtteranceChunker.windows(0).isEmpty())
    }

    @Test
    fun anOddByteCountIsTruncatedToWholeSamplesRatherThanSplittingOne() {
        // int16 little-endian: half a sample at a window edge would be decoded as
        // a garbage sample by whichever window got it.
        val w = UtteranceChunker.windows(bytesFor(1.0) + 1)
        assertEquals(1, w.size)
        assertEquals(bytesFor(1.0), w[0].second)
    }

    // ---------------- payload admission ----------------

    @Test
    fun theMaximumPayloadIsTwelveSecondsOfSixteenKilohertzMonoInt16() {
        assertEquals(384_000, UtteranceChunker.MAX_UTTERANCE_BYTES)
    }

    @Test
    fun anOversizePayloadIsRefusedRatherThanTruncated() {
        // Truncating would silently drop the end of what the user said. The
        // caller must split and re-submit, and the null tells it to.
        assertTrue(UtteranceChunker.isTooLarge(UtteranceChunker.MAX_UTTERANCE_BYTES + 1))
        assertTrue(!UtteranceChunker.isTooLarge(UtteranceChunker.MAX_UTTERANCE_BYTES))
    }

    // ---------------- banking ----------------

    @Test
    fun bankedWindowTextsJoinWithASingleSpace() {
        assertEquals("привет как дела",
            UtteranceChunker.bank(listOf("привет", "как", "дела")))
    }

    @Test
    fun windowsThatProducedNothingDoNotLeaveDoubleSpaces() {
        // A silent middle window is normal (a pause mid-utterance). It must not
        // show up as ragged whitespace in the text the user sees.
        assertEquals("привет дела", UtteranceChunker.bank(listOf("привет", "", "  ", "дела")))
    }

    @Test
    fun anAllSilentResultIsTheEmptyFinalNotNull() {
        // THE cancel contract. "" and null mean opposite things downstream.
        assertEquals("", UtteranceChunker.bank(listOf("", "", "")))
        assertEquals("", UtteranceChunker.bank(emptyList()))
    }

    @Test
    fun aNullWindowPoisonsTheWholeUtteranceIntoUnavailable() {
        // One window failing to encode means we have a HOLE in the transcript.
        // Returning the surviving windows would hand the user a sentence with
        // the middle silently missing, which reads as a complete sentence.
        assertNull(UtteranceChunker.bankOrNull(listOf("привет", null, "дела")))
        assertEquals("привет дела", UtteranceChunker.bankOrNull(listOf("привет", "дела")))
    }
}
