package com.repository.glasses.listener.stt

import android.util.Log
import com.repository.glasses.listener.capture.MicSubscriber
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds the on-glasses recogniser from the mic, as a MicBus SUBSCRIBER.
 *
 * There is exactly one AudioRecord in this process, owned by ListenerService and
 * published through MicBus. This class adds a consumer to it; it must never open
 * its own, which on this device presents as one of the two consumers silently
 * receiving no audio rather than as an error.
 *
 * Two rules the mic thread imposes, both of which fail SILENTLY when broken:
 *
 *  1. The producer re-uses its ShortArray on the next read, so the requested
 *     slice is COPIED here. Retaining the reference would hand the recogniser
 *     whatever the mic wrote most recently.
 *  2. onPcmFrame runs on the single MicStream-Thread shared with the wake-word
 *     pipeline and the archive writer, so it only copies and enqueues. All real
 *     work -- VAD accumulation, the Binder call to capture, the ~1.1 s per window
 *     encode -- happens on the worker thread. Doing any of it inline would stall
 *     every other mic consumer.
 */
class SttPcmCollector(
    private val segmenter: Segmenter,
    private val sink: Sink,
) : MicSubscriber {

    /**
     * Accumulates frames and returns a complete utterance when speech ends.
     * An interface rather than a direct SttVadSegmenter so the collector's
     * threading can be tested without driving a real energy VAD.
     */
    interface Segmenter {
        /** @return the finished utterance, or null while speech continues. */
        fun accept(pcm: ShortArray, offset: Int, length: Int): ShortArray?
        fun reset()
    }

    /** Receives whole utterances, on the worker thread. May block. */
    interface Sink {
        fun onUtterance(pcm: ShortArray)
    }

    private companion object {
        const val TAG = "SttPcmCollector"

        /**
         * Bounded so a stalled worker cannot grow the queue without limit. 16 s of
         * 1 s frames is far more than the 12 s maximum utterance, so reaching this
         * means the worker is wedged and dropping is the correct response --
         * better a lost utterance than an OOM in the listener process.
         */
        const val MAX_QUEUED_FRAMES = 16
    }

    private val queue = LinkedBlockingQueue<ShortArray>(MAX_QUEUED_FRAMES)
    private val running = AtomicBoolean(true)

    private val worker = Thread({ workerLoop() }, "SttWorker").apply {
        // Below the mic thread: a late transcript is recoverable, dropped audio
        // is not.
        priority = Thread.NORM_PRIORITY - 1
        isDaemon = true
        start()
    }

    override fun onPcmFrame(pcmMono16k: ShortArray, offset: Int, length: Int, epochNanos: Long) {
        if (!running.get() || length <= 0) return
        // Copy on the mic thread -- unavoidable, because the array is re-used the
        // moment we return. It is a memcpy of one second of 16 kHz mono.
        val copy = pcmMono16k.copyOfRange(offset, offset + length)
        if (!queue.offer(copy)) {
            // Never let a logging failure propagate onto the mic thread: it would
            // take down every other mic consumer with it.
            try { Log.w(TAG, "worker behind; dropping a mic frame") } catch (_: Throwable) {}
        }
    }

    override fun onStreamStop() {
        // The mic went away mid-utterance: whatever was accumulated can never be
        // completed, and holding it would splice it onto the NEXT session's
        // speech.
        segmenter.reset()
    }

    /** Stop delivering. Idempotent. The worker exits on its next poll. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        worker.interrupt()
    }

    private fun workerLoop() {
        while (running.get()) {
            val frame = try {
                queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                return
            }
            try {
                val utterance = segmenter.accept(frame, 0, frame.size)
                if (utterance != null && running.get()) sink.onUtterance(utterance)
            } catch (t: Throwable) {
                // The worker is created once. Letting it die here would silently
                // drop every LATER utterance with nothing to show for it.
                //
                // The report is itself wrapped: a recovery path that can throw
                // does not recover. (Caught in test -- the logger threw and took
                // the worker down exactly as a live logging failure would.)
                try {
                    Log.w(TAG, "utterance handling failed: ${t.javaClass.simpleName}: ${t.message}")
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Test hook: block until the queue has drained. */
    fun drainForTest(timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }
}
