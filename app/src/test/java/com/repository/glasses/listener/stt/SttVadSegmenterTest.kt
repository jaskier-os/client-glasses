package com.repository.glasses.listener.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plan task 1.1 -- energy VAD and utterance segmentation, ported from the
 * benchmark's field-tuned constants.
 *
 * End-of-speech moves onto the glasses for local sessions: today the PHONE owns
 * it (glassesVadEngine), but in local mode the phone never sees a transcriber
 * stream, so nothing would ever finalize.
 *
 * Pure Kotlin, no Android APIs, so this runs on the JVM.
 */
class SttVadSegmenterTest {

    private val sr = SttVadSegmenter.SAMPLE_RATE
    private val block = SttVadSegmenter.BLOCK

    /** PCM16 block of a 220 Hz tone at the given amplitude (0..1 of full scale). */
    private fun tone(amp: Float, n: Int = block, phase0: Int = 0): ShortArray =
        ShortArray(n) { i ->
            (sin(2.0 * PI * 220.0 * (i + phase0) / sr) * amp * 32767.0).toInt().toShort()
        }

    private fun silence(n: Int = block): ShortArray = ShortArray(n)

    /**
     * Feed the settle window then the calibration window, both quiet, so the
     * segmenter is armed with a low noise floor before the test's real input.
     */
    private fun SttVadSegmenter.warmUp(level: Float = 0.0005f) {
        val settleBlocks = (SttVadSegmenter.SETTLE_S * sr / block).toInt()
        val calibBlocks = (SttVadSegmenter.CALIB_S * sr / block).toInt()
        repeat(settleBlocks + calibBlocks + 1) { accept(tone(level)) }
    }

    private fun feedSilence(v: SttVadSegmenter, seconds: Float): List<SttVadSegmenter.Segment> {
        val out = ArrayList<SttVadSegmenter.Segment>()
        repeat((seconds * sr / block).toInt()) { v.accept(silence())?.let(out::add) }
        return out
    }

    private fun feedTone(
        v: SttVadSegmenter, seconds: Float, amp: Float = 0.5f
    ): List<SttVadSegmenter.Segment> {
        val out = ArrayList<SttVadSegmenter.Segment>()
        val n = (seconds * sr / block).toInt()
        repeat(n) { i -> v.accept(tone(amp, phase0 = i * block))?.let(out::add) }
        return out
    }

    @Test
    fun silenceAloneProducesNoSegment() {
        val v = SttVadSegmenter()
        v.warmUp()
        assertTrue(feedSilence(v, 5f).isEmpty())
    }

    @Test
    fun oneSecondOfSpeechProducesExactlyOneNaturalEndSegment() {
        val v = SttVadSegmenter()
        v.warmUp()
        val segs = feedTone(v, 1.0f) + feedSilence(v, 1.5f)
        assertEquals(1, segs.size)
        assertTrue("expected a natural end", segs[0].naturalEnd)
    }

    @Test
    fun segmentIncludesPrerollBeforeSpeechOnset() {
        val v = SttVadSegmenter()
        v.warmUp()
        feedSilence(v, 0.5f)
        val segs = feedTone(v, 1.0f) + feedSilence(v, 1.5f)
        assertEquals(1, segs.size)
        // 1.0 s of speech plus up to PREROLL_S of retained lead-in. Without the
        // preroll the segment could not exceed the speech length.
        val seconds = segs[0].pcm.size.toFloat() / sr
        assertTrue(
            "segment $seconds s should exceed the 1.0 s of speech via preroll",
            seconds > 1.05f
        )
    }

    @Test
    fun trailingSilenceIsTrimmedFromTheSegment() {
        val v = SttVadSegmenter()
        v.warmUp()
        val segs = feedTone(v, 1.0f) + feedSilence(v, 3.0f)
        assertEquals(1, segs.size)
        // 3 s of trailing silence was fed but only the endpointing window may be
        // retained; the segment must not have swallowed all of it.
        val seconds = segs[0].pcm.size.toFloat() / sr
        assertTrue("segment $seconds s swallowed the trailing silence", seconds < 2.5f)
    }

    @Test
    fun blipShorterThanMinSpeechIsDiscarded() {
        val v = SttVadSegmenter()
        v.warmUp()
        // 0.2 s < MIN_SPEECH_S (0.35 s)
        val segs = feedTone(v, 0.2f) + feedSilence(v, 1.5f)
        assertTrue("a $0.2 s blip must not produce a segment", segs.isEmpty())
    }

    @Test
    fun loudCalibrationIsClampedSoSpeechIsStillDetected() {
        val v = SttVadSegmenter()
        // Calibrate against a saturating level: without the CAL_MAX_FLOOR clamp
        // the threshold would land above any possible speech and gate the user
        // out entirely.
        val settleBlocks = (SttVadSegmenter.SETTLE_S * sr / block).toInt()
        val calibBlocks = (SttVadSegmenter.CALIB_S * sr / block).toInt()
        repeat(settleBlocks + calibBlocks + 1) { v.accept(tone(0.95f)) }
        assertTrue("calibration should have been clamped", v.calibrationClamped)
        val segs = feedTone(v, 1.0f) + feedSilence(v, 1.5f)
        assertEquals("speech must still be detected after a clamped calibration", 1, segs.size)
    }

    @Test
    fun continuousSpeechPastMaxUttForcesASplitAndContinuesSeamlessly() {
        val v = SttVadSegmenter()
        v.warmUp()
        // Speak continuously well past MAX_UTT_S with no pause.
        val segs = feedTone(v, SttVadSegmenter.MAX_UTT_S + 2.0f)
        assertTrue("expected a forced split", segs.isNotEmpty())
        assertTrue("a forced split must not be a natural end", !segs[0].naturalEnd)
        val seconds = segs[0].pcm.size.toFloat() / sr
        assertTrue(
            "forced split segment $seconds s must not exceed the payload cap",
            seconds <= SttVadSegmenter.MAX_UTT_S + 0.01f
        )
        // The utterance continues: a following natural end still yields a segment.
        val rest = feedSilence(v, 1.5f)
        assertTrue("speech after the split must still endpoint", rest.isNotEmpty())
    }

    @Test
    fun acceptReturnsNullWhileStillCalibrating() {
        val v = SttVadSegmenter()
        // Loud input during settle/calibration must not emit a segment.
        assertNull(v.accept(tone(0.9f)))
    }

    @Test
    fun resetClearsStateSoTheNextSessionRecalibrates() {
        val v = SttVadSegmenter()
        v.warmUp()
        feedTone(v, 0.5f)
        v.reset()
        // After reset the segmenter is uncalibrated again, so a loud block during
        // the settle window cannot produce a segment.
        assertNull(v.accept(tone(0.9f)))
    }

    @Test
    fun segmentPcmIsACopyNotAliasedToCallerBuffers() {
        val v = SttVadSegmenter()
        v.warmUp()
        val segs = feedTone(v, 1.0f) + feedSilence(v, 1.5f)
        assertEquals(1, segs.size)
        val seg = segs[0]
        assertNotNull(seg.pcm)
        // The producer reuses its array; the segmenter must have copied.
        val before = seg.pcm[100]
        val scratch = tone(0.9f)
        v.accept(scratch)
        scratch.fill(12345)
        assertEquals("segment PCM was aliased to a caller buffer", before, seg.pcm[100])
    }
}
