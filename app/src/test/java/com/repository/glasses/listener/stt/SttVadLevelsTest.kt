package com.repository.glasses.listener.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The PCM level report the VAD prints for every utterance it hands over.
 *
 * Its entire job is to settle, from the log alone, whether an utterance that transcribed to
 * nothing was SILENCE the VAD should not have emitted or SPEECH the model failed on. Those have
 * completely different fixes, and the live incident could not tell them apart -- which cost a
 * whole round of debugging aimed at the wrong layer. So the numbers have to be right, and in
 * particular `gainedRms` has to be directly comparable against the `threshold` printed beside it.
 */
class SttVadLevelsTest {

    private val seg = SttVadSegmenter()

    private fun field(line: String, name: String): Float {
        val m = Regex("\\b$name=([0-9.]+)").find(line)
        assertTrue("no '$name' in: $line", m != null)
        return m!!.groupValues[1].toFloat()
    }

    @Test
    fun anEmptyBufferSaysSoRatherThanDividingByZero() {
        assertTrue(seg.levels(ShortArray(0)).contains("empty"))
    }

    @Test
    fun digitalSilenceReadsAsZeroOnEveryMeasure() {
        val line = seg.levels(ShortArray(16000))
        assertEquals(0f, field(line, "peak"), 1e-6f)
        assertEquals(0f, field(line, "rms"), 1e-6f)
        assertEquals(0f, field(line, "gainedPeak"), 1e-6f)
        assertEquals(0f, field(line, "gainedRms"), 1e-6f)
    }

    /** Short.MIN_VALUE has no positive counterpart; abs() of it is itself, which would read 0. */
    @Test
    fun theMostNegativeSampleReadsAsFullScaleNotZero() {
        val line = seg.levels(shortArrayOf(Short.MIN_VALUE))
        assertEquals(1f, field(line, "peak"), 1e-6f)
    }

    @Test
    fun theDurationIsReportedInMilliseconds() {
        assertTrue(seg.levels(ShortArray(16000)).contains("(1000ms)"))
        assertTrue(seg.levels(ShortArray(8000)).contains("(500ms)"))
    }

    /**
     * THE point of this suite.
     *
     * accept() clips each sample to +/-1 BEFORE squaring. Scaling the unclipped RMS by MIC_GAIN
     * afterwards gives a different number the moment any sample saturates -- and at 24x gain that
     * is most of a real utterance. gainedRms would then be incomparable with the threshold printed
     * beside it, which is the only reason it is printed at all.
     *
     * A full-scale square wave saturates every sample, so the correctly-clipped RMS is exactly
     * 1.0, while the naive `rms * MIC_GAIN` would be 24.0 (or 1.0 after a late clamp -- which is
     * why the check below uses a signal where the two answers genuinely differ).
     */
    @Test
    fun theGainedRmsIsClippedPerSampleExactlyAsTheVadClipsIt() {
        // Half the samples are loud enough to saturate after gain; half are silent.
        // Correct  : sqrt(mean(clip(v*24)^2)) = sqrt(0.5 * 1^2)          = 0.7071
        // Incorrect: min(1, sqrt(mean(v^2)) * 24) = min(1, 0.0707*24)    = 1.0
        val loud = (0.1f * 32767).toInt().toShort()
        val pcm = ShortArray(1000) { if (it % 2 == 0) loud else 0 }
        val line = seg.levels(pcm)

        val expectedClipped = sqrt(0.5).toFloat()
        assertEquals(
            "gainedRms must be accumulated from per-sample CLIPPED values, as accept() does; " +
                "scaling the unclipped RMS makes it incomparable with the threshold: $line",
            expectedClipped, field(line, "gainedRms"), 0.01f
        )
        // And it must NOT be the naive scaled-then-clamped value.
        assertTrue(
            "gainedRms looks like the naive min(1, rms*GAIN): $line",
            field(line, "gainedRms") < 0.95f
        )
    }

    @Test
    fun aGainedMeasureNeverExceedsFullScale() {
        val pcm = ShortArray(500) { Short.MAX_VALUE }
        val line = seg.levels(pcm)
        assertTrue("gainedPeak over full scale: $line", field(line, "gainedPeak") <= 1.0f)
        assertTrue("gainedRms over full scale: $line", field(line, "gainedRms") <= 1.0f)
    }

    /** The threshold is printed alongside, or the gained numbers have nothing to be read against. */
    @Test
    fun theThresholdIsPrintedBesideTheLevels() {
        assertTrue(seg.levels(ShortArray(160)).contains("threshold="))
    }

    /**
     * The report must describe the buffer actually handed over, so it has to be emitted from the
     * emit path -- not from accept(), where the utterance does not exist yet.
     */
    @Test
    fun theReportIsEmittedForTheSegmentThatIsHandedOver() {
        val lines = ArrayList<String>()
        val s = SttVadSegmenter().apply { trace = { lines.add(it) } }
        val block = SttVadSegmenter.BLOCK
        val quiet = ShortArray(block)
        // Settle, then calibrate, both on silence.
        repeat(64) { s.accept(quiet) }
        // Speak loudly enough to cross the threshold, for well over MIN_SPEECH_S.
        val loud = ShortArray(block) { if (it % 2 == 0) 4000 else -4000 }
        repeat(20) { s.accept(loud) }
        // Then go quiet long enough to end the utterance.
        var segment: SttVadSegmenter.Segment? = null
        repeat(40) { if (segment == null) segment = s.accept(quiet) }

        assertTrue("the VAD never emitted an utterance; trace: $lines", segment != null)
        val report = lines.lastOrNull { it.startsWith("vad segment levels") }
        assertTrue("no segment level report was emitted; trace: $lines", report != null)
        assertTrue(
            "the report must describe the buffer handed over (${segment!!.pcm.size} samples), " +
                "not some other one: $report",
            report!!.contains("samples=${segment!!.pcm.size}")
        )
        assertTrue(
            "a real utterance must not report as silence, or the diagnostic is useless: $report",
            field(report, "peak") > 0f && field(report, "gainedRms") > 0f
        )
    }
}
