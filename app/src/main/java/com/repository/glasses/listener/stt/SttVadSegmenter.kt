package com.repository.glasses.listener.stt

import kotlin.math.sqrt

/**
 * Energy VAD and utterance segmentation for on-glasses local STT.
 *
 * WHY THIS EXISTS. Today the PHONE owns end-of-speech (`glassesVadEngine` in the
 * phone's ListenerService). In local mode the phone never opens a transcriber
 * stream, so nothing would ever finalize an utterance and the glasses would sit
 * in LISTENING forever. End-of-speech therefore moves onto the glasses for local
 * sessions only; remote sessions keep the phone's VAD untouched.
 *
 * The constants are a direct port of the benchmark's field-tuned energy VAD --
 * do not retune them without re-measuring on-device.
 *
 * ENDPOINTING DIFFERS BETWEEN PATHS, deliberately: remote sessions end on the
 * phone's Silero-style VadEngine, local sessions end here. They will disagree on
 * marginal utterances. This is documented in the design doc, not an accident.
 *
 * Pure Kotlin (no Android APIs) so it is JVM-testable. NOT thread-safe: feed it
 * from one thread (the STT worker), never from the mic callback thread.
 */
class SttVadSegmenter {

    /**
     * Where the trace lines go. A lambda rather than a direct SttTrace call so
     * this class stays free of Android APIs and JVM-testable, which is the
     * property the whole VAD test suite depends on.
     */
    var trace: ((String) -> Unit)? = null

    private fun t(msg: String) {
        try { trace?.invoke(msg) } catch (_: Throwable) {}
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** Samples per accept() call. 64 ms at 16 kHz. */
        const val BLOCK = 1024

        /** Trailing quiet that ends an utterance. */
        const val SILENCE_S = 0.8f

        /** Below this an utterance is discarded as a blip. */
        const val MIN_SPEECH_S = 0.35f

        /** Audio retained before speech onset so the first phoneme is not clipped. */
        const val PREROLL_S = 0.30f

        /** Speech threshold = noise floor x this. */
        const val NOISE_MULT = 3.0f

        /** Absolute floor clamp. */
        const val FLOOR = 0.004f

        /** Discarded after arming, before calibration begins (mic AGC settling). */
        const val SETTLE_S = 0.4f

        /** Median window for the noise floor. */
        const val CALIB_S = 1.0f

        /** A calibrated floor above this is a broken measurement, not a loud room. */
        const val CAL_MAX_FLOOR = 0.15f

        /**
         * Hard split. This is NOT a VAD decision: it is the Binder payload cap.
         * 12 s of 16 kHz mono int16 is 384 KB, deliberately under half the 1 MB
         * per-process transaction buffer that camera JPEGs also share. On a split
         * the caller banks the chunk's text and prefixes it onto the next, so the
         * user still sees ONE final.
         */
        const val MAX_UTT_S = 12.0f

        /**
         * Software gain the listener already applies to the mono mic channel.
         * The VAD must see the same levels the model will.
         */
        const val MIC_GAIN = 24.0f
    }

    /**
     * @param pcm the utterance PCM (int16 mono 16 kHz), including preroll and
     *   with trailing silence trimmed. Always a private copy.
     * @param naturalEnd true when the utterance ended on silence; false when it
     *   was force-split at MAX_UTT_S and the speaker is still going.
     */
    data class Segment(val pcm: ShortArray, val naturalEnd: Boolean) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Segment &&
                naturalEnd == other.naturalEnd && pcm.contentEquals(other.pcm))

        override fun hashCode(): Int = 31 * pcm.contentHashCode() + naturalEnd.hashCode()
    }

    private val settleNeeded = (SETTLE_S * SAMPLE_RATE / BLOCK).toInt()
    private val calibNeeded = (CALIB_S * SAMPLE_RATE / BLOCK).toInt()
    private val quietNeeded = (SILENCE_S * SAMPLE_RATE / BLOCK).toInt()
    private val prerollMax = maxOf(1, (PREROLL_S * SAMPLE_RATE / BLOCK).toInt())
    private val maxBlocks = (MAX_UTT_S * SAMPLE_RATE / BLOCK).toInt()
    private val minSpeechBlocks = (MIN_SPEECH_S * SAMPLE_RATE / BLOCK).toInt()

    private var settleBlocks = 0
    private val noise = ArrayList<Float>(calibNeeded)
    private var threshold = FLOOR
    private var calibrated = false

    /** True when calibration hit CAL_MAX_FLOOR and fell back to the fixed floor. */
    var calibrationClamped = false
        private set

    private val preroll = ArrayDeque<ShortArray>()
    private val speech = ArrayList<ShortArray>()
    private var talking = false
    private var quietBlocks = 0

    /**
     * Blocks whose level was ABOVE threshold in the current utterance. This is
     * what MIN_SPEECH_S is measured against -- not `speech.size`, which also
     * contains the preroll and the trailing detection window. Counting those
     * would let a 0.2 s cough plus 0.3 s of preroll clear a 0.35 s minimum.
     */
    private var voicedBlocks = 0

    /**
     * Blocks fed since the last [reset]. Purely for the trace: it converts a
     * "nothing happened" report into "the VAD saw N blocks and never
     * calibrated", which are different bugs (no mic frames vs a stuck
     * calibration).
     */
    private var blocksSeen = 0

    /** Whole blocks -> milliseconds, for the trace's duration fields. */
    private fun ms(blocks: Int): Int = blocks * BLOCK * 1000 / SAMPLE_RATE

    /** Discard all state so the next session recalibrates from scratch. */
    fun reset() {
        t("vad reset after $blocksSeen blocks (${ms(blocksSeen)}ms), calibrated=$calibrated talking=$talking")
        blocksSeen = 0
        settleBlocks = 0
        noise.clear()
        threshold = FLOOR
        calibrated = false
        calibrationClamped = false
        preroll.clear()
        speech.clear()
        talking = false
        quietBlocks = 0
        voicedBlocks = 0
    }

    /**
     * Feed one block of int16 mono 16 kHz PCM.
     *
     * The caller's array is never retained: everything kept is copied, because
     * the mic producer reuses its buffer on the next call.
     *
     * @return a Segment when this block completed an utterance, else null.
     */
    fun accept(pcm: ShortArray): Segment? {
        val n = pcm.size
        if (n == 0) return null
        blocksSeen++
        // The FIRST block is the proof that audio is actually reaching the VAD.
        // Without it, "no VAD lines" is ambiguous between a mic that never
        // emitted and a VAD that never triggered.
        if (blocksSeen == 1) t("vad first block: $n samples, settle=${ms(settleNeeded)}ms calib=${ms(calibNeeded)}ms")

        // Level after the same software gain the rest of the pipeline applies,
        // clipped to the full-scale range the model was trained on.
        var sumSq = 0.0
        for (i in 0 until n) {
            var v = pcm[i].toFloat() / 32768.0f * MIC_GAIN
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            sumSq += (v * v).toDouble()
        }
        val level = sqrt(sumSq / n).toFloat()

        // The first few hundred ms after arming are mic AGC settling; with 24x
        // gain that transient saturates and would calibrate a uselessly high
        // floor. Discard it, then take the median of what follows.
        if (settleBlocks < settleNeeded) {
            settleBlocks++
            return null
        }

        if (!calibrated) {
            noise.add(level)
            if (noise.size >= calibNeeded) {
                val sorted = noise.sorted()
                val floorV = sorted[sorted.size / 2]
                // A floor this high is a broken calibration, not a loud room:
                // speech peaks near 1.0 after gain, so anything above CAL_MAX
                // would gate the user out entirely.
                calibrationClamped = floorV > CAL_MAX_FLOOR
                threshold = if (calibrationClamped) FLOOR * NOISE_MULT
                else maxOf(FLOOR, floorV * NOISE_MULT)
                calibrated = true
                t(
                    "vad calibrated: noiseFloor=${"%.5f".format(floorV)} " +
                        "threshold=${"%.5f".format(threshold)} clamped=$calibrationClamped " +
                        "(mult=$NOISE_MULT floor=$FLOOR calMax=$CAL_MAX_FLOOR)"
                )
            }
            return null
        }

        if (!talking) {
            preroll.addLast(pcm.copyOf())
            while (preroll.size > prerollMax) preroll.removeFirst()
            if (level > threshold) {
                talking = true
                quietBlocks = 0
                voicedBlocks = 1
                t("vad speech_start at ${ms(blocksSeen)}ms level=${"%.5f".format(level)} threshold=${"%.5f".format(threshold)}")
                // Do NOT clear a carried tail from a forced split: that tail holds
                // the word straddling the boundary.
                if (speech.isEmpty()) speech.addAll(preroll)
                preroll.clear()
            }
            return null
        }

        speech.add(pcm.copyOf())
        if (level > threshold) {
            quietBlocks = 0
            voicedBlocks++
        } else {
            quietBlocks++
        }

        // Forced split at the Binder payload cap: the speaker has not paused, so
        // hand over what we have and keep collecting.
        if (speech.size >= maxBlocks) return emit(naturalEnd = false)

        // Natural end: enough trailing quiet.
        if (quietBlocks >= quietNeeded) return emit(naturalEnd = true)

        return null
    }

    /**
     * Build the segment and reset the utterance accumulator.
     *
     * On a natural end the trailing silence used to detect the end is trimmed --
     * it is endpointing evidence, not speech, and feeding it to the encoder just
     * burns NPU time.
     */
    private fun emit(naturalEnd: Boolean): Segment? {
        val blocks = if (naturalEnd) {
            // Keep a short tail so the final consonant is not clipped, but drop
            // the bulk of the detection window.
            val keep = maxOf(0, speech.size - quietBlocks + 1)
            speech.subList(0, minOf(keep, speech.size)).toList()
        } else {
            speech.toList()
        }

        val voiced = voicedBlocks
        val quiet = quietBlocks
        speech.clear()
        quietBlocks = 0
        voicedBlocks = 0
        talking = false

        t(
            "vad speech_end natural=$naturalEnd voiced=${ms(voiced)}ms " +
                "kept=${ms(blocks.size)}ms trimmedQuiet=${ms(quiet)}ms"
        )

        if (naturalEnd) {
            preroll.clear()
            // Below MIN_SPEECH_S this is a blip (a cough, a door), not an
            // utterance. Measured against VOICED blocks only -- preroll and the
            // trailing detection window are not speech.
            if (voiced < minSpeechBlocks) {
                t(
                    "vad DISCARDED: voiced=${ms(voiced)}ms below MIN_SPEECH_S=${(MIN_SPEECH_S * 1000).toInt()}ms " +
                        "(a blip, not an utterance)"
                )
                return null
            }
        }

        if (blocks.isEmpty()) {
            t("vad DISCARDED: no blocks accumulated")
            return null
        }

        val total = blocks.sumOf { it.size }
        val out = ShortArray(total)
        var off = 0
        for (b in blocks) {
            b.copyInto(out, off)
            off += b.size
        }
        return Segment(out, naturalEnd)
    }
}
