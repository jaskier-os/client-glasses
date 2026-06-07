package com.repository.glasses.listener.capture

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 4-second mic PCM prebuffer. Always captures from MicBus when subscribed
 * (wear-gated upstream -- the mic itself is off when not worn). On wake-word
 * fire the consumer calls [snapshotForFlush], which atomically captures the
 * current write head and the count of valid samples; the returned Snapshot
 * holds a copy of the older PCM in time-order (oldest first).
 *
 * Single-writer (MicBus thread) / single-reader (drain consumer) pattern.
 * No locks on the hot write path.
 */
class PrebufferingAudioSubscriber : MicSubscriber {

    companion object {
        private const val TAG = "PrebufferingAudio"
        // 4 s @ 16 kHz mono = 64000 samples = 128 KB.
        const val CAPACITY_SAMPLES = 4 * 16000
    }

    private val ring = ShortArray(CAPACITY_SAMPLES)
    @Volatile private var writePos = 0
    private val totalWritten = AtomicLong(0L)
    @Volatile private var lastFrameEpochNanos = 0L

    override fun onPcmFrame(pcmMono16k: ShortArray, offset: Int, length: Int, epochNanos: Long) {
        if (length <= 0) return
        // Fast path: copy into ring with single wrap.
        var src = offset
        var remaining = length
        var wp = writePos
        while (remaining > 0) {
            val canCopy = minOf(remaining, CAPACITY_SAMPLES - wp)
            System.arraycopy(pcmMono16k, src, ring, wp, canCopy)
            wp = (wp + canCopy) % CAPACITY_SAMPLES
            src += canCopy
            remaining -= canCopy
        }
        writePos = wp
        totalWritten.addAndGet(length.toLong())
        lastFrameEpochNanos = epochNanos
    }

    override fun onStreamStart() {
        Log.i(TAG, "stream start; reset")
        writePos = 0
        totalWritten.set(0L)
    }

    override fun onStreamStop() {
        Log.i(TAG, "stream stop")
    }

    /**
     * Snapshot the buffer state for a flush. Single-reader / single-writer
     * lock-free pattern: the writer (MicBus thread) may concurrently advance
     * `writePos` and `totalWritten` and overwrite the OLDEST samples in the
     * ring while we copy. Worst case: the read sees a torn tail of one
     * frame's worth (~10-20 ms) of newer-than-snapshot audio mixed into the
     * oldest position. STT tolerates this -- it's a momentary glitch in
     * 4 s of context that has no real time anchor anyway.
     *
     * Returns a Snapshot containing the samples in chronological order
     * (oldest first) and the epoch of the most-recent sample.
     *
     * Callers should call this AT MOST ONCE per wake-word fire --
     * subsequent live frames may overwrite the ring.
     */
    fun snapshotForFlush(): Snapshot {
        val written = totalWritten.get()
        val validSamples = minOf(written, CAPACITY_SAMPLES.toLong()).toInt()
        if (validSamples <= 0) {
            return Snapshot(ShortArray(0), lastFrameEpochNanos, 0)
        }
        val out = ShortArray(validSamples)
        val wp = writePos
        // Read oldest -> newest.
        val readStart = if (written < CAPACITY_SAMPLES) 0 else wp
        if (readStart + validSamples <= CAPACITY_SAMPLES) {
            System.arraycopy(ring, readStart, out, 0, validSamples)
        } else {
            val firstChunk = CAPACITY_SAMPLES - readStart
            System.arraycopy(ring, readStart, out, 0, firstChunk)
            System.arraycopy(ring, 0, out, firstChunk, validSamples - firstChunk)
        }
        return Snapshot(out, lastFrameEpochNanos, validSamples)
    }

    data class Snapshot(val samples: ShortArray, val newestEpochNanos: Long, val sampleCount: Int)
}
