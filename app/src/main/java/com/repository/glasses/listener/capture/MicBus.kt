package com.repository.glasses.listener.capture

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Subscriber interface for mic PCM consumers.
 *
 * onPcmFrame is called from the MicStream-Thread. Consumers MUST NOT block; copy the
 * data or hand it off to a worker immediately. The [pcmMono16k] array is re-used by
 * the producer on the next frame, so any consumer that needs to retain the data
 * must copy the slice [offset, offset+length).
 *
 * epochNanos is System.nanoTime() captured at the moment the chunk finished reading
 * from AudioRecord. It is intended for wake-event-to-BT latency measurement and for
 * aligning look-ahead audio with detection events; it is NOT wall-clock time.
 */
interface MicSubscriber {
    fun onPcmFrame(pcmMono16k: ShortArray, offset: Int, length: Int, epochNanos: Long)
    fun onStreamStart() {}
    fun onStreamStop() {}
}

/**
 * Single-producer, many-subscriber pub/sub for mono 16 kHz PCM chunks coming out of
 * the beamformed mic path.
 *
 * Distributes mono 16 kHz PCM from the back/inward mic (CHANNEL_IN_MONO +
 * AudioSource.MIC). There is exactly one AudioRecord in the process (owned by
 * ListenerService) which applies the 24x gain and calls [emit] once per 1-second
 * chunk. Subscribers are invoked on the MicStream-Thread.
 *
 * Subscribers:
 *   - Agent B: LocalOpusWriter (archive to filesDir/audio-archive)
 *   - Agent C: WakeWordPipeline (silero VAD + sireneviy via QNN EP)
 *   - BT live streamer (inline in ListenerService, gated by StreamMode)
 *
 * ## Lifecycle contract (READ BEFORE USE)
 *
 * - MicBus is a **process singleton** (Kotlin `object`). There is exactly one
 *   subscriber list shared by all callers inside the `:backend` process.
 *
 * - Subscribers MUST call [unsubscribe] in their own `stop()` / `onDestroy()` / equivalent
 *   teardown. Failing to unsubscribe means a stale reference (LocalOpusWriter,
 *   WakeWordPipeline, etc.) continues to receive `onPcmFrame` callbacks across a
 *   ListenerService restart, pinning garbage-collectable objects and potentially
 *   throwing on released native handles.
 *
 * - Primary subscriber ownership lives in `ListenerService`:
 *     - `LocalOpusWriter.start()` / `.stop()` subscribe / unsubscribe.
 *     - `WakeWordPipeline.start()` / `.stop()` subscribe / unsubscribe.
 *   `ListenerService.onCreate` calls start() and `onDestroy` calls stop() for each,
 *   in the correct order (subscribers up before first `notifyStart`; subscribers
 *   down after `stopMicStream`).
 *
 * - `emit`, `notifyStart`, `notifyStop` are safe to call from the producer thread;
 *   the internal [subscribers] list is a [CopyOnWriteArrayList], so concurrent
 *   subscribe/unsubscribe during iteration is tolerated.
 */
object MicBus {
    private const val TAG = "MicBus"
    private const val WARN_RATE_LIMIT_MS = 60_000L

    private val subscribers = CopyOnWriteArrayList<MicSubscriber>()
    // Rate-limit swallow warnings: one line per subscriber class per
    // WARN_RATE_LIMIT_MS so a broken subscriber does not spam 12+ lines/sec
    // into logcat.
    // Entries are intentionally permanent for process lifetime. Bounded by the
    // set of distinct subscriber classes (today: 2-3 -- LocalOpusWriter,
    // WakeWordPipeline, and the BT live-stream emitter). Anonymous subscribers
    // all collapse onto one empty-simpleName entry, which is acceptable.
    private val lastWarnMs = ConcurrentHashMap<String, Long>()

    fun subscribe(sub: MicSubscriber) {
        if (!subscribers.contains(sub)) {
            subscribers.add(sub)
        }
    }

    fun unsubscribe(sub: MicSubscriber) {
        subscribers.remove(sub)
    }

    fun subscriberCount(): Int = subscribers.size

    /**
     * Called by the single mic producer thread after channel-1 extraction and gain.
     * The array is re-used on the next call; subscribers that retain data must copy.
     */
    fun emit(pcmMono16k: ShortArray, offset: Int, length: Int, epochNanos: Long) {
        // Iterate snapshot; CopyOnWriteArrayList tolerates concurrent subscribe/unsubscribe.
        for (sub in subscribers) {
            try {
                sub.onPcmFrame(pcmMono16k, offset, length, epochNanos)
            } catch (t: Throwable) {
                // A rogue subscriber must never take down the mic thread, but silent
                // swallows hide live bugs. Log once per subscriber class per minute.
                maybeWarnRateLimited(sub, "onPcmFrame", t)
            }
        }
    }

    fun notifyStart() {
        for (sub in subscribers) {
            try {
                sub.onStreamStart()
            } catch (t: Throwable) {
                maybeWarnRateLimited(sub, "onStreamStart", t)
            }
        }
    }

    fun notifyStop() {
        for (sub in subscribers) {
            try {
                sub.onStreamStop()
            } catch (t: Throwable) {
                maybeWarnRateLimited(sub, "onStreamStop", t)
            }
        }
    }

    private fun maybeWarnRateLimited(sub: MicSubscriber, method: String, t: Throwable) {
        val key = sub.javaClass.simpleName
        val now = System.currentTimeMillis()
        val last = lastWarnMs[key] ?: 0L
        if (now - last < WARN_RATE_LIMIT_MS) return
        lastWarnMs[key] = now
        Log.w(TAG, "subscriber $key.$method threw ${t.javaClass.simpleName}: ${t.message} (rate-limited 1/${WARN_RATE_LIMIT_MS}ms)")
    }
}
