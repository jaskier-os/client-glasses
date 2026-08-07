package com.repository.glasses.capture

/**
 * Splitting one utterance into encoder-sized windows, and banking the resulting
 * texts back into one string.
 *
 * The GigaAM encoder graph is compiled for a FIXED 5 s input. An utterance may be
 * up to 12 s, so capture chunks the payload itself rather than making the
 * listener do it: one Binder call in, one string out, exactly like detectFaces.
 *
 * Pure arithmetic and string joining, with no ONNX and no NPU, so the part that
 * decides whether a word survives is JVM-testable.
 */
object UtteranceChunker {

    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2

    /** The encoder's compiled input length. */
    const val WINDOW_MS = 5_000

    /**
     * The longest utterance accepted in one call: 12 s = 384000 bytes.
     * Deliberately well under the 1 MB per-process Binder transaction buffer,
     * which is SHARED with the camera JPEGs ICaptureCallback.onFrame delivers.
     */
    const val MAX_UTTERANCE_MS = 12_000

    const val WINDOW_BYTES = WINDOW_MS / 1000 * SAMPLE_RATE * BYTES_PER_SAMPLE
    const val MAX_UTTERANCE_BYTES = MAX_UTTERANCE_MS / 1000 * SAMPLE_RATE * BYTES_PER_SAMPLE

    /**
     * Refused, not truncated. Truncating would silently drop the end of what the
     * user said; the caller is told so it can split and re-submit, or fall back.
     */
    fun isTooLarge(byteCount: Int): Boolean = byteCount > MAX_UTTERANCE_BYTES

    /**
     * [start, end) byte ranges covering the payload, in order, with no gap and no
     * overlap. A gap here would delete words from the middle of an utterance with
     * nothing to show for it in any log.
     *
     * The payload is truncated to whole int16 samples first: a half sample
     * straddling a window edge would be decoded as noise by whichever window
     * received it.
     */
    fun windows(byteCount: Int): List<Pair<Int, Int>> {
        val usable = (byteCount / BYTES_PER_SAMPLE) * BYTES_PER_SAMPLE
        if (usable <= 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>((usable + WINDOW_BYTES - 1) / WINDOW_BYTES)
        var off = 0
        while (off < usable) {
            val end = minOf(off + WINDOW_BYTES, usable)
            out += off to end
            off = end
        }
        return out
    }

    /**
     * Join per-window transcripts.
     *
     * A window that produced nothing is normal -- a pause mid-utterance -- and
     * must not leave ragged whitespace in text the user reads. An all-silent
     * result is "" and NEVER null: "" is the explicit empty final that the phone
     * reads as CANCEL, while null means local STT is unavailable. The two travel
     * to opposite branches downstream.
     */
    fun bank(windowTexts: List<String>): String =
        windowTexts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * As [bank], but a single failed window (null) poisons the WHOLE utterance
     * into unavailable.
     *
     * Returning the surviving windows would hand the user a sentence with its
     * middle silently missing, which still reads as a complete sentence -- the
     * worst possible failure. null sends the utterance to the remote fallback,
     * where the listener still holds the full PCM.
     */
    fun bankOrNull(windowTexts: List<String?>): String? {
        if (windowTexts.any { it == null }) return null
        @Suppress("UNCHECKED_CAST")
        return bank(windowTexts as List<String>)
    }
}
