package com.repository.glasses.listener.reid.rppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Unit tests for [RppgEngine]: the per-track driver that fans the AIDL onRppgSamples
 * batch into one [TrackBuffer] per trackingId and recomputes BPM on [tick].
 *
 * The 72-bpm test uses the REAL [TrackBuffer] (default factory) so it is a true
 * integration test of engine + buffer + the POS -> resample -> FFT -> smoother chain,
 * mirroring TrackBufferTest.compute_endToEnd_recovers72Bpm.
 */
class RppgEngineTest {

    /** Green oscillates at [freqHz] on a DC; r/b constant. Same shape as TrackBufferTest. */
    private fun greenAt(tMs: Long, freqHz: Double): Float {
        val tSec = tMs / 1000.0
        return 0.5f + (0.02 * sin(2.0 * PI * freqHz * tSec)).toFloat()
    }

    @Test
    fun singleTrack_recovers72Bpm() {
        val engine = RppgEngine()
        val id = 7L
        val fps = 15.0
        val stepMs = (1000.0 / fps).toLong()
        val freqHz = 1.2 // 72 bpm
        var t = 0L
        var lastBpm: Float? = null
        while (t <= 14_000L) {
            engine.onSamples(longArrayOf(id), floatArrayOf(0.4f, greenAt(t, freqHz), 0.3f), t)
            if (t % 1000L < stepMs) {
                engine.tick(t)
                val bpm = engine.bpmFor(id)
                if (bpm != null) lastBpm = bpm
            }
            t += stepMs
        }
        assertNotNull("expected a smoothed BPM after warm-up", lastBpm)
        assertTrue("expected ~72 bpm, got $lastBpm", abs(lastBpm!! - 72f) <= 4f)
    }

    @Test
    fun twoTracks_independentBuffers() {
        val engine = RppgEngine()
        val a = 1L
        val b = 2L
        val fps = 15.0
        val stepMs = (1000.0 / fps).toLong()
        var t = 0L
        var bpmA: Float? = null
        var bpmB: Float? = null
        while (t <= 14_000L) {
            // Track a at 72 bpm (1.2 Hz), track b at 90 bpm (1.5 Hz), same frame batch.
            engine.onSamples(
                longArrayOf(a, b),
                floatArrayOf(
                    0.4f, greenAt(t, 1.2), 0.3f,
                    0.4f, greenAt(t, 1.5), 0.3f,
                ),
                t,
            )
            if (t % 1000L < stepMs) {
                engine.tick(t)
                engine.bpmFor(a)?.let { bpmA = it }
                engine.bpmFor(b)?.let { bpmB = it }
            }
            t += stepMs
        }
        assertNotNull(bpmA)
        assertNotNull(bpmB)
        assertTrue("track a ~72, got $bpmA", abs(bpmA!! - 72f) <= 5f)
        assertTrue("track b ~90, got $bpmB", abs(bpmB!! - 90f) <= 5f)
        assertTrue("tracks must have distinct BPMs", abs(bpmA!! - bpmB!!) > 5f)
    }

    @Test
    fun trackTimeout_dropsStaleTrack() {
        val engine = RppgEngine(trackTimeoutMs = 2_000L)
        val id = 3L
        engine.onSamples(longArrayOf(id), floatArrayOf(0.4f, 0.5f, 0.3f), 0L)
        assertTrue(engine.activeTrackIds().contains(id))
        // Advance well past the timeout without feeding -> dropped on tick.
        engine.tick(10_000L)
        assertNull(engine.bpmFor(id))
        assertFalse(engine.activeTrackIds().contains(id))
    }

    @Test
    fun malformedBatch_ignoredWithoutCrash() {
        val engine = RppgEngine()
        // 2 ids but only 3 floats (needs 6) -> skipped.
        engine.onSamples(longArrayOf(1L, 2L), floatArrayOf(0.4f, 0.5f, 0.3f), 0L)
        assertTrue(engine.activeTrackIds().isEmpty())
        // A well-formed batch still works afterwards.
        engine.onSamples(longArrayOf(1L), floatArrayOf(0.4f, 0.5f, 0.3f), 10L)
        assertEquals(setOf(1L), engine.activeTrackIds())
    }

    @Test
    fun reset_clearsEverything() {
        val engine = RppgEngine()
        engine.onSamples(longArrayOf(1L, 2L), floatArrayOf(0.4f, 0.5f, 0.3f, 0.4f, 0.5f, 0.3f), 0L)
        assertFalse(engine.activeTrackIds().isEmpty())
        engine.reset()
        assertTrue(engine.activeTrackIds().isEmpty())
        assertNull(engine.bpmFor(1L))
        assertNull(engine.bpmFor(2L))
    }
}
