package com.repository.glasses.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan task 1.4 -- the pure detokenizer, JVM-testable without ONNX.
 *
 * The v3_e2e_rnnt vocabulary is SENTENCEPIECE over 1024 pieces, which is what
 * carries punctuation and capitalisation. The only piece-level rule that matters
 * is U+2581 (word-boundary marker) -> space.
 *
 * The empty-token case is load-bearing: it is where the explicit-empty-final
 * contract starts. A blank final is a CANCEL signal on the glasses (notification
 * reply -> CANCELLED -> dismiss), so "no tokens" must produce "" and not null.
 */
class RnntDetokenizeTest {

    @Test
    fun joinsSentencepiecePiecesAndMapsBoundaryMarkerToSpace() {
        val pieces = listOf("\u2581прив", "ет", ",", "\u2581как", "\u2581дела", "?")
        assertEquals("привет, как дела?", RnntDecoder.detokenize(pieces))
    }

    @Test
    fun leadingBoundaryMarkerDoesNotProduceLeadingSpace() {
        assertEquals("привет", RnntDecoder.detokenize(listOf("\u2581привет")))
        assertEquals("да", RnntDecoder.detokenize(listOf("\u2581да")))
    }

    @Test
    fun punctuationAndCapitalisationSurviveUntouched() {
        // These come from the model, not from us -- we must not normalise them.
        val pieces = listOf("\u2581Привет", ",", "\u2581Мир", "!")
        assertEquals("Привет, Мир!", RnntDecoder.detokenize(pieces))
    }

    @Test
    fun emptyTokenListYieldsEmptyString() {
        assertEquals("", RnntDecoder.detokenize(emptyList()))
    }

    @Test
    fun whitespaceOnlyResultCollapsesToEmptyString() {
        // A model that emits only boundary markers must not produce " " -- a
        // whitespace-only "final" would defeat the blank-is-cancel contract by
        // looking non-blank to isNotBlank() callers upstream.
        assertEquals("", RnntDecoder.detokenize(listOf("\u2581", "\u2581")))
    }

    @Test
    fun charwiseVocabHasNoBoundaryMarkerSoJoinIsPlain() {
        // v3_rnnt is CHARWISE; the U+2581 replace must be a no-op there.
        assertEquals("да", RnntDecoder.detokenize(listOf("д", "а")))
        assertEquals("а б", RnntDecoder.detokenize(listOf("а", " ", "б")))
    }
}
