package com.repository.glasses.tracing

import androidx.tracing.Trace

/**
 * Centralized Perfetto/systrace instrumentation for the glasses app.
 *
 * All slice names are prefixed with "gt." so they sort together and can be
 * selected in trace_processor via:  WHERE s.name LIKE 'gt.%'
 *
 * Naming convention: gt.<subsystem>.<operation>
 *   subsystem: svc | bt | audio | cap | ui | input | sync | media | tts | head
 *   operation: short verb. Examples:
 *     gt.svc.onCreate
 *     gt.bt.send.audio_data
 *     gt.audio.opus.encode
 *     gt.cap.photo
 *     gt.input.dpad.down
 *
 * Counters (API 29+, androidx handles older safely):
 *     GT.counter("audio.queue", depth)
 *     GT.counter("bt.tx_bytes", totalTxBytes)
 *
 * Capture requirements (on the trace config side):
 *   atrace_apps: "com.repository.glasses.listener"
 *
 * `section` is inline: the lambda runs in the caller's frame, so plain
 * `return` / `return true` inside a wrapped function body works naturally.
 */
object GT {
    @PublishedApi
    internal const val PREFIX = "gt."

    /** Scoped trace: begin, run block, always end (try/finally semantics). */
    inline fun <T> section(name: String, block: () -> T): T {
        Trace.beginSection(PREFIX + name)
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    /** Begin a synchronous section. Caller MUST call [end] exactly once. */
    fun begin(name: String) {
        Trace.beginSection(PREFIX + name)
    }

    /** End the most recently begun section on this thread. */
    fun end() {
        Trace.endSection()
    }

    /** Begin an async section (can span threads / not strictly nested). */
    fun beginAsync(name: String, cookie: Int) {
        Trace.beginAsyncSection(PREFIX + name, cookie)
    }

    /** End an async section with the cookie used in [beginAsync]. */
    fun endAsync(name: String, cookie: Int) {
        Trace.endAsyncSection(PREFIX + name, cookie)
    }

    /** Emit a counter value. Non-negative; clamped to Int range per androidx API. */
    fun counter(name: String, value: Long) {
        val v = when {
            value < 0L -> 0
            value > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
            else -> value.toInt()
        }
        Trace.setCounter(PREFIX + name, v)
    }

    /** Emit an integer counter. */
    fun counter(name: String, value: Int) {
        Trace.setCounter(PREFIX + name, if (value < 0) 0 else value)
    }
}
