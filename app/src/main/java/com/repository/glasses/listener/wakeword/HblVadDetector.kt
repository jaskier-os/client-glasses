package com.repository.glasses.listener.wakeword

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Drop-in low-power VAD backed by Rokid's HblVad in
 * /system/lib64/librokid_agc.so. Replaces silero_vad.onnx (~50 MB ONNX session,
 * multi-ms per frame on AP CPU) with a 419 KB classical-DSP VAD that runs in
 * effectively zero time per 20 ms frame.
 *
 * Mode 6 means 16 kHz internal sample rate with a fixed 320-sample (20 ms)
 * frame size. Callers MUST hand us exactly 320 int16 samples per [isSpeech]
 * call; anything else short-circuits to false.
 *
 * Available is true only when the JNI lib loaded AND nativeCreate succeeded
 * (which requires a successful dlopen of librokid_agc.so). On any failure the
 * pipeline must fall back to silero with a warning log line.
 *
 * The native handle is owned by this instance; [close] must be called exactly
 * once when the pipeline session ends.
 */
class HblVadDetector(mode: Int = 6) {

    // AtomicLong + CAS to 0 in close() guards against double-close and stops
    // racing isSpeech() calls from re-entering the JNI once close() has won.
    // The native side has its own per-handle in_use guard; the Kotlin guard
    // just shrinks the race window and avoids redundant JNI hops.
    private val handleRef: AtomicLong =
            AtomicLong(if (libLoaded) nativeCreate(mode) else 0L)

    val available: Boolean get() = handleRef.get() != 0L

    /**
     * Returns true if HblVad classified this 20 ms frame as speech.
     *
     * @param pcm exactly 320 int16 samples at 16 kHz, mono
     */
    fun isSpeech(pcm: ShortArray): Boolean {
        val h = handleRef.get()
        if (h == 0L || pcm.size != FRAME_SAMPLES) return false
        return nativeProcess(h, pcm, pcm.size) >= 1
    }

    fun close() {
        val h = handleRef.getAndSet(0L)
        if (h != 0L) {
            try { nativeDelete(h) } catch (t: Throwable) {
                Log.w(TAG, "nativeDelete threw: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HblVadDetector"
        const val FRAME_SAMPLES = 320  // 20 ms @ 16 kHz, mode=6

        @Volatile private var libLoaded = false
        @Volatile private var loadError: String? = null

        init {
            try {
                System.loadLibrary("hbl_vad")
                libLoaded = true
            } catch (t: Throwable) {
                loadError = "${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "loadLibrary(hbl_vad) failed: $loadError")
            }
        }

        /**
         * Must be called once with the app's private data dir before the
         * first nativeCreate. The JNI uses it to stage a local copy of
         * /system/lib64/librokid_agc.so when the classloader-namespace
         * blocks direct /system/lib64 dlopen.
         */
        fun configure(dataDir: String) {
            if (!libLoaded) return
            try { nativeSetDataDir(dataDir) } catch (t: Throwable) {
                Log.w(TAG, "nativeSetDataDir threw: ${t.message}")
            }
        }

        @JvmStatic private external fun nativeSetDataDir(dir: String)
        @JvmStatic private external fun nativeCreate(mode: Int): Long
        @JvmStatic private external fun nativeProcess(handle: Long, pcm: ShortArray, n: Int): Int
        @JvmStatic private external fun nativeDelete(handle: Long)
    }
}
