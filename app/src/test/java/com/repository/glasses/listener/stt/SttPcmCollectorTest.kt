package com.repository.glasses.listener.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plan task 3.1 -- the MicBus subscriber that feeds the on-glasses recogniser.
 *
 * Two hazards, both of which produce corrupted audio rather than an error:
 *
 *  1. The producer RE-USES the ShortArray on the next frame. A subscriber that
 *     keeps the reference instead of copying the [offset, offset+length) slice
 *     ends up with whatever the mic wrote most recently -- so the recogniser
 *     transcribes a smeared buffer and nobody sees a stack trace.
 *
 *  2. onPcmFrame runs on the single MicStream-Thread shared with the wake-word
 *     pipeline and the archive writer. Blocking there stalls every other mic
 *     consumer, so the callback must copy and hand off, never compute.
 *
 * There is also a NEGATIVE invariant worth pinning: exactly ONE AudioRecord may
 * exist in this process. Opening a second one for STT would fight the existing
 * owner for the mic and is the single easiest mistake to make here.
 */
class SttPcmCollectorTest {

    private class RecordingSink : SttPcmCollector.Sink {
        val segments = ArrayList<ShortArray>()
        val latch = CountDownLatch(1)
        override fun onUtterance(pcm: ShortArray) {
            segments += pcm
            latch.countDown()
        }
    }

    /** A segmenter stand-in that emits after a fixed number of frames. */
    private class EveryNFrames(private val n: Int) : SttPcmCollector.Segmenter {
        private val acc = ArrayList<Short>()
        private var frames = 0
        override fun accept(pcm: ShortArray, offset: Int, length: Int): ShortArray? {
            for (i in offset until offset + length) acc += pcm[i]
            frames++
            if (frames < n) return null
            val out = acc.toShortArray()
            acc.clear(); frames = 0
            return out
        }
        override fun reset() { acc.clear(); frames = 0 }
    }

    /** Spin until [cond] holds; the collector hands frames to a worker thread. */
    private fun waitUntil(timeoutMs: Long = 2000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    /** Records the length of every slice it is handed. */
    private class SizeSpy : SttPcmCollector.Segmenter {
        val sizes = ArrayList<Int>()
        override fun accept(pcm: ShortArray, offset: Int, length: Int): ShortArray? {
            sizes += length
            return null
        }
        override fun reset() { sizes.clear() }
    }

    @Test
    fun aMicFrameIsSplitIntoSegmenterSizedBlocks() {
        // MicBus hands over ~1 s at a time (16000 samples). The segmenter counts
        // its settle, calibration, silence and min-speech windows in BLOCKs, so a
        // whole second arriving as ONE block collapsed all four counts to zero: it
        // never calibrated, never endpointed, and no utterance was ever produced.
        // The real VAD is exercised by SttVadSegmenterTest at its own block size;
        // what matters here is that the collector never hands it more than a block.
        val spy = SizeSpy()
        val c = SttPcmCollector(spy, RecordingSink())
        try {
            c.onPcmFrame(ShortArray(16000), 0, 16000, 0L)
            waitUntil { spy.sizes.sum() == 16000 }
        } finally {
            c.stop()
        }
        val block = SttVadSegmenter.BLOCK
        assertTrue(
            "no slice may exceed the segmenter's block size, got ${spy.sizes.maxOrNull()}",
            spy.sizes.all { it <= block }
        )
        assertEquals("every sample must be delivered exactly once", 16000, spy.sizes.sum())
        assertTrue("a 1 s frame must span many blocks", spy.sizes.size >= 16000 / block)
    }

    @Test
    fun theRetainedAudioIsACopyNotTheProducersArray() {
        val sink = RecordingSink()
        val c = SttPcmCollector(EveryNFrames(1), sink)
        val shared = shortArrayOf(1, 2, 3, 4)

        c.onPcmFrame(shared, 0, 4, 0L)
        // The producer overwrites its buffer on the very next read.
        shared.fill(999)
        c.drainForTest()

        assertTrue(sink.latch.await(2, TimeUnit.SECONDS))
        assertEquals(
            "the collector must hold a COPY; retaining the producer's array " +
                "silently feeds the recogniser the next frame's audio",
            listOf<Short>(1, 2, 3, 4), sink.segments.single().toList()
        )
    }

    @Test
    fun onlyTheRequestedSliceIsTaken() {
        val sink = RecordingSink()
        val c = SttPcmCollector(EveryNFrames(1), sink)
        c.onPcmFrame(shortArrayOf(9, 1, 2, 9, 9), 1, 2, 0L)
        c.drainForTest()
        assertTrue(sink.latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf<Short>(1, 2), sink.segments.single().toList())
    }

    @Test
    fun theMicThreadCallbackReturnsPromptly() {
        // The sink deliberately blocks for a second. If the collector ran it
        // inline, onPcmFrame would block with it and stall every other mic
        // subscriber (wake word, archive writer).
        val started = CountDownLatch(1)
        val slow = object : SttPcmCollector.Sink {
            override fun onUtterance(pcm: ShortArray) {
                started.countDown()
                Thread.sleep(1_000)
            }
        }
        val c = SttPcmCollector(EveryNFrames(1), slow)
        val t0 = System.nanoTime()
        c.onPcmFrame(ShortArray(16_000), 0, 16_000, 0L)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(
            "onPcmFrame took ${elapsedMs}ms; it must hand off, not compute",
            elapsedMs < 100
        )
        assertTrue(started.await(2, TimeUnit.SECONDS))
        c.stop()
    }

    @Test
    fun frameStoStoppedCollectorAreDropped() {
        val sink = RecordingSink()
        val c = SttPcmCollector(EveryNFrames(1), sink)
        c.stop()
        c.onPcmFrame(shortArrayOf(1, 2), 0, 2, 0L)
        assertFalse(
            "a stopped collector must not deliver: it is stopped precisely " +
                "because its session ended",
            sink.latch.await(300, TimeUnit.MILLISECONDS)
        )
    }

    @Test
    fun aSinkThatThrowsDoesNotKillTheWorker() {
        // The worker thread is created once per collector. If an exception in one
        // utterance killed it, every later utterance would be silently dropped
        // with no indication anything was wrong.
        val delivered = CountDownLatch(1)
        var first = true
        val flaky = object : SttPcmCollector.Sink {
            override fun onUtterance(pcm: ShortArray) {
                if (first) { first = false; throw IllegalStateException("boom") }
                delivered.countDown()
            }
        }
        val c = SttPcmCollector(EveryNFrames(1), flaky)
        c.onPcmFrame(shortArrayOf(1), 0, 1, 0L)
        c.onPcmFrame(shortArrayOf(2), 0, 1, 0L)
        assertTrue("the worker must survive a throwing sink",
            delivered.await(2, TimeUnit.SECONDS))
        c.stop()
    }

    @Test
    fun losingTheMicMidUtteranceDiscardsWhatWasAccumulated() {
        // The mic went away with speech half-captured. That fragment can never be
        // completed, and keeping it would splice the previous session's words
        // onto the front of the NEXT thing the wearer says -- which reads as a
        // plausible sentence, so nobody would spot it as corruption.
        val seg = object : SttPcmCollector.Segmenter {
            var resets = 0
            override fun accept(pcm: ShortArray, offset: Int, length: Int): ShortArray? = null
            override fun reset() { resets++ }
        }
        val c = SttPcmCollector(seg, RecordingSink())
        c.onPcmFrame(shortArrayOf(1, 2, 3), 0, 3, 0L)
        c.onStreamStop()
        assertEquals(
            "the mic stopping mid-utterance must discard the partial capture",
            1, seg.resets
        )
        c.stop()
    }

    @Test
    fun zeroLengthFramesAreIgnored() {
        val sink = RecordingSink()
        val c = SttPcmCollector(EveryNFrames(1), sink)
        c.onPcmFrame(ShortArray(4), 0, 0, 0L)
        assertFalse(sink.latch.await(300, TimeUnit.MILLISECONDS))
        c.stop()
    }

    // ---- the negative invariant ----

    @Test
    fun nothingUnderTheSttPackageOpensASecondAudioRecord() {
        // There is exactly ONE AudioRecord in this process, owned by
        // ListenerService and published through MicBus. A second one would fight
        // it for the mic; on this device that presents as one of the two
        // consumers silently receiving no audio at all.
        val dir = File("src/main/java/com/repository/glasses/listener/stt")
        assertTrue("stt source dir missing", dir.isDirectory)
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("AudioRecord(") }
            .map { it.name }
            .toList()
        assertEquals(
            "local STT must SUBSCRIBE to MicBus, never open its own AudioRecord",
            emptyList<String>(), offenders
        )
    }

    @Test
    fun theCollectorIsAMicSubscriber() {
        // Pins the integration point: if it stops being a MicSubscriber it stops
        // receiving audio, and every test above would still pass.
        val src = File(
            "src/main/java/com/repository/glasses/listener/stt/SttPcmCollector.kt"
        ).readText()
        assertNotNull(src)
        assertTrue("SttPcmCollector must implement MicSubscriber",
            src.contains("MicSubscriber"))
    }
}
