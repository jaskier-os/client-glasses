package com.repository.glasses.listener.stt

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Hands one finished utterance to the capture process and bounds the wait.
 *
 * Binder has NO client-side timeout. A wedged or swapping capture process would
 * otherwise hang the utterance forever, leaving the wearer in LISTENING with no
 * way out and no fallback. Every call is therefore run on a helper thread under
 * a deadline, and a call that misses its deadline is RETIRED: if its result
 * arrives later it is dropped, because the session it belonged to is over and
 * delivering it would put the previous sentence into the current bubble.
 *
 * Falling back is cheap by construction: the PCM never left the listener until
 * this call, so the phone still holds the audio and can batch-transcribe it.
 */
class SttDispatcher(
    private val bridge: Bridge,
    private val language: String,
    private val shortTimeoutMs: Long = SHORT_TIMEOUT_MS,
    private val longTimeoutMs: Long = LONG_TIMEOUT_MS,
    /**
     * Whether the capture process already holds the model. False costs a ~21 s
     * mapping of the context binary INSIDE the Binder call, so the budget has to
     * cover it; defaults to warm so existing callers keep the tight budget.
     */
    private val modelWarm: () -> Boolean = { true },
) {

    /** The capture-process call, injected so the timeout logic is testable. */
    interface Bridge {
        /** @return transcript, "" for an explicit empty final, null if unavailable. */
        fun transcribeUtterance(pcm: ByteArray, lang: String, utteranceId: Long): String?
    }

    enum class Status { OK, FAIL }

    /**
     * @param text meaningful ONLY when [status] is OK. "" there is an explicit
     *   empty final -- the cancel signal -- and must reach the phone as an empty
     *   string, not as a missing argument.
     */
    data class Result(val status: Status, val text: String)

    companion object {
        private const val TAG = "SttDispatcher"

        /** 16 kHz mono int16. */
        private const val BYTES_PER_SAMPLE = 2

        /** Payloads at or under 5 s. Measured: ~1.1 s per encoder window. */
        const val SHORT_TIMEOUT_MS = 4_000L

        /** Up to 12 s: three windows plus Binder and scheduling overhead. */
        const val LONG_TIMEOUT_MS = 8_000L

        private const val SHORT_UTTERANCE_BYTES = 5 * 16_000 * BYTES_PER_SAMPLE

        /**
         * Extra budget for the first call after the model was evicted. Measured on
         * device: mapping the 231 MB context binary takes ~21 s, during which the
         * Binder call cannot return. Timing out at the warm budget meant EVERY
         * first utterance was abandoned, the completed transcript arrived ~18 s
         * later as a LATE result and was dropped -- and since the model unloads
         * when idle, the next attempt was cold again. Local STT could never win.
         */
        const val COLD_LOAD_GRACE_MS = 30_000L

        /** Mirrors the AIDL ceiling; the 1 MB transaction buffer is shared with camera JPEGs. */
        const val MAX_UTTERANCE_BYTES = 384_000

        fun timeoutFor(byteCount: Int, modelWarm: Boolean = true): Long {
            val base =
                if (byteCount <= SHORT_UTTERANCE_BYTES) SHORT_TIMEOUT_MS else LONG_TIMEOUT_MS
            return if (modelWarm) base else base + COLD_LOAD_GRACE_MS
        }
    }

    private val callPool = Executors.newCachedThreadPool { r ->
        Thread(r, "SttBinderCall").apply { isDaemon = true }
    }

    /** Utterances retired by a timeout; their result, if it ever lands, is dropped. */
    private val retired = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** Ids whose late result was observed and suppressed. Evidence, not state. */
    private val lateResults = java.util.Collections.synchronizedList(ArrayList<Long>())

    /**
     * android.util.Log is not mocked on the JVM test classpath and THROWS there.
     * A logging failure must never escape a fallback path -- that is how a
     * recovery path stops recovering.
     */
    private fun logw(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) {}
    }

    private fun logi(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
    }

    fun transcribe(pcm: ShortArray, utteranceId: Long): Result {
        val bytes = toLittleEndianBytes(pcm)
        if (bytes.size > MAX_UTTERANCE_BYTES) {
            // Refused HERE rather than at the Binder: an oversize transaction
            // throws TransactionTooLargeException, and because the 1 MB buffer is
            // per-process and shared, the throw can land on an unrelated camera
            // callback instead of on us.
            logw("utt=$utteranceId ${bytes.size}B over the transaction limit")
            SttTrace.w("u$utteranceId binder REFUSED locally: ${bytes.size}B > $MAX_UTTERANCE_BYTES -> fallback to remote")
            return Result(Status.FAIL, "")
        }

        // The INSTANCE budgets, not the companion defaults: reading the companion
        // here would silently ignore any override and make the timeout untestable.
        // A cold model cannot answer inside the warm budget -- it is still mapping
        // 231 MB -- so grant the load its measured cost or abandon every first
        // utterance.
        val warm = modelWarm()
        val timeoutMs =
            (if (bytes.size <= SHORT_UTTERANCE_BYTES) shortTimeoutMs else longTimeoutMs) +
                (if (warm) 0L else COLD_LOAD_GRACE_MS)
        val t0 = System.currentTimeMillis()
        SttTrace.i(
            "u$utteranceId binder CALL transcribeUtterance bytes=${bytes.size} " +
                "lang=$language warm=$warm timeoutMs=$timeoutMs"
        )
        val future = callPool.submit<String?> {
            val text = bridge.transcribeUtterance(bytes, language, utteranceId)
            // If this utterance was already retired by a timeout, the result is
            // recorded as suppressed rather than returned to anyone: the session
            // it belonged to is over.
            if (retired.remove(utteranceId)) {
                logw("utt=$utteranceId result arrived after its deadline; dropped")
                SttTrace.w(
                    "u$utteranceId binder LATE result after ${SttTrace.since(t0)}ms " +
                        "(already retired); dropped"
                )
                synchronized(lateResults) { lateResults += utteranceId }
            }
            text
        }
        return try {
            val text = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val ms = SttTrace.since(t0)
            if (text == null) {
                logi("utt=$utteranceId local STT unavailable; falling back to remote")
                // null and "" are DIFFERENT answers and the distinction is the
                // whole contract: null means "capture cannot do this, you
                // transcribe it", "" means "the wearer said nothing, cancel".
                SttTrace.i("u$utteranceId binder RETURNED null in ${ms}ms -> local unavailable, FALLBACK TO REMOTE")
                Result(Status.FAIL, "")
            } else {
                // "" survives as "": it is an explicit empty final, and the phone
                // reads it as the wearer cancelling.
                if (text.isEmpty()) {
                    SttTrace.i("u$utteranceId binder RETURNED empty string in ${ms}ms -> intentional CANCEL final, not a failure")
                } else {
                    SttTrace.i("u$utteranceId binder RETURNED chars=${text.length} in ${ms}ms")
                }
                Result(Status.OK, text)
            }
        } catch (e: TimeoutException) {
            // Retire it, and deliberately do NOT cancel. cancel(true) cannot stop
            // an in-flight Binder transaction anyway -- it only interrupts the
            // waiting thread -- so cancelling would merely hide the late result
            // instead of letting the retired-id check observe and drop it. Let
            // the call finish and be discarded on arrival.
            retired.add(utteranceId)
            logw("utt=$utteranceId timed out after ${timeoutMs}ms; falling back to remote")
            SttTrace.w(
                "u$utteranceId binder TIMEOUT after ${timeoutMs}ms (capture never answered) " +
                    "-> retired, FALLBACK TO REMOTE"
            )
            Result(Status.FAIL, "")
        } catch (t: Throwable) {
            // DeadObjectException when capture has been force-stopped. A remote
            // fallback, not a crash on the worker thread.
            logw("utt=$utteranceId call failed: ${t.javaClass.simpleName}: ${t.message}")
            SttTrace.w(
                "u$utteranceId binder EXCEPTION after ${SttTrace.since(t0)}ms: " +
                    "${t.javaClass.simpleName}: ${t.message} -> FALLBACK TO REMOTE"
            )
            Result(Status.FAIL, "")
        }
    }

    /**
     * Utterance ids whose result arrived after the deadline and was therefore
     * NOT delivered. Always empty in normal operation; a non-empty list means a
     * transcript was correctly suppressed.
     */
    fun drainSuppressedLateResults(): List<Long> = synchronized(lateResults) {
        val out = ArrayList(lateResults)
        lateResults.clear()
        out
    }

    fun shutdown() {
        callPool.shutdownNow()
    }

    /**
     * The AIDL contract is little-endian int16. Getting the byte order wrong
     * yields noise that the model transcribes as confident garbage rather than
     * failing in any visible way.
     */
    private fun toLittleEndianBytes(pcm: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(pcm.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }
}
