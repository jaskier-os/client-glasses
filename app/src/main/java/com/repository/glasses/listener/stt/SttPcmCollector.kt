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
    /** Session id, so every line here can be matched to the session that opened it. */
    private val sessionId: Long = 0L,
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

    /**
     * Frames received from MicBus. The FIRST one is the single most valuable
     * line in this whole trace: it is the only direct proof that the mic
     * subscription is actually delivering audio. The live incident could not
     * distinguish "never subscribed" from "subscribed but the producer was not
     * running", and those have completely different fixes.
     */
    private var framesIn = 0L
    private var framesDropped = 0L
    private var utterancesOut = 0L
    private val createdMs = System.currentTimeMillis()

    private fun sid() = "s$sessionId"

    private val worker = Thread({ workerLoop() }, "SttWorker").apply {
        // Below the mic thread: a late transcript is recoverable, dropped audio
        // is not.
        priority = Thread.NORM_PRIORITY - 1
        isDaemon = true
        start()
    }

    override fun onPcmFrame(pcmMono16k: ShortArray, offset: Int, length: Int, epochNanos: Long) {
        if (!running.get() || length <= 0) return
        framesIn++
        // Never let a logging failure propagate onto the mic thread: it would
        // take down every other mic consumer with it. Hence the wrapping, and
        // hence only milestone frames are logged -- one line per second per
        // session would drown the trace it is meant to make readable.
        if (framesIn == 1L) {
            SttTrace.i("${sid()} collector first mic frame after ${SttTrace.since(createdMs)}ms, $length samples")
        } else if (framesIn % 10L == 0L) {
            SttTrace.i("${sid()} collector frames=$framesIn dropped=$framesDropped queue=${queue.size}")
        }
        // Copy on the mic thread -- unavoidable, because the array is re-used the
        // moment we return. It is a memcpy of one second of 16 kHz mono.
        val copy = pcmMono16k.copyOfRange(offset, offset + length)
        if (!queue.offer(copy)) {
            framesDropped++
            try { Log.w(TAG, "worker behind; dropping a mic frame") } catch (_: Throwable) {}
            SttTrace.w("${sid()} collector queue full (${MAX_QUEUED_FRAMES}); dropped frame #$framesDropped -- worker is wedged")
        }
    }

    override fun onStreamStart() {
        SttTrace.i("${sid()} collector mic stream START")
    }

    override fun onStreamStop() {
        // The mic went away mid-utterance: whatever was accumulated can never be
        // completed, and holding it would splice it onto the NEXT session's
        // speech.
        SttTrace.i("${sid()} collector mic stream STOP after frames=$framesIn; resetting VAD")
        segmenter.reset()
    }

    /** Stop delivering. Idempotent. The worker exits on its next poll. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        SttTrace.i(
            "${sid()} collector stop: frames=$framesIn dropped=$framesDropped " +
                "utterances=$utterancesOut lived=${SttTrace.since(createdMs)}ms"
        )
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
                if (utterance != null && running.get()) {
                    utterancesOut++
                    SttTrace.i(
                        "${sid()} collector utterance #$utterancesOut ready: " +
                            "${utterance.size} samples (${utterance.size * 1000 / 16000}ms) -> dispatching"
                    )
                    sink.onUtterance(utterance)
                }
            } catch (t: Throwable) {
                SttTrace.w("${sid()} collector utterance handling threw ${t.javaClass.simpleName}: ${t.message}")
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
