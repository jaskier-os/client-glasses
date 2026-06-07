package com.repository.glasses.listener.wakeword

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin wrapper around the JNI ACD bridge (acd_native.cpp). Opens a
 * PAL_STREAM_ACD against the SPEECH context (0x08001335) so the SoC DSP fires
 * a callback when voice activity is detected. While ACD is armed the AP can
 * release the AudioRecord and let audioserver drop the AudioIn wakelock --
 * silence costs nothing on the AP.
 *
 * Architecture:
 *   - Native worker thread (spawned in nativeStart) does pal_init / open /
 *     LOAD_SOUND_MODEL / RECOGNITION_CONFIG / pal_stream_start.
 *   - PAL fires stream_cb on its own thread; the JNI shim attaches to the JVM
 *     and invokes [SpeechCallback.onSpeech].
 *   - nativeStop signals the worker, joins it, releases JNI globals.
 *
 * Failure mode: if libpalclient.so cannot be dlopen'd (host build, missing
 * vendor lib, perms) [isAvailable] returns false. Callers should fall back
 * to the ARM-ONNX wakeword pipeline.
 */
internal class AcdNativeDetector {

    /**
     * Implemented by the caller; invoked on a JVM-attached PAL callback thread.
     * Keep it short -- do not block. epochNanos is steady_clock at callback time.
     */
    fun interface SpeechCallback {
        fun onSpeech(epochNanos: Long)
    }

    private val active = AtomicBoolean(false)
    @Volatile private var heldCallback: SpeechCallback? = null

    fun isRunning(): Boolean = active.get()

    /**
     * Arm the DSP-side ACD. Returns null on success or an error reason on
     * failure (caller should log and fall back).
     */
    fun start(callback: SpeechCallback): String? {
        if (!libLoaded) return "libacd_native.so not loaded: $loadError"
        if (!active.compareAndSet(false, true)) return "already running"
        heldCallback = callback
        return try {
            val rc = nativeStart(callback)
            if (rc != 0) {
                active.set(false)
                heldCallback = null
                "nativeStart rc=$rc"
            } else {
                Log.i(TAG, "ACD native armed")
                null
            }
        } catch (t: Throwable) {
            active.set(false)
            heldCallback = null
            Log.w(TAG, "nativeStart threw: ${t.message}", t)
            "nativeStart threw: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        if (!libLoaded) return
        try {
            nativeStop()
        } catch (t: Throwable) {
            Log.w(TAG, "nativeStop threw: ${t.message}", t)
        } finally {
            heldCallback = null
        }
    }

    companion object {
        private const val TAG = "AcdNativeDetector"

        @Volatile private var libLoaded = false
        @Volatile private var loadError: String? = null

        init {
            try {
                System.loadLibrary("acd_native")
                libLoaded = true
            } catch (t: Throwable) {
                loadError = "${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "loadLibrary(acd_native) failed: $loadError")
            }
        }

        /**
         * True if libacd_native.so loaded AND libpalclient.so is dlopen'able
         * AND /vendor/etc/models/acd/speech.eai is readable. A false here
         * means the ARM-ONNX fallback must run.
         */
        fun isAvailable(): Boolean {
            if (!libLoaded) return false
            return try {
                nativeIsAvailable()
            } catch (t: Throwable) {
                Log.w(TAG, "nativeIsAvailable threw: ${t.message}")
                false
            }
        }

        @JvmStatic private external fun nativeIsAvailable(): Boolean
        @JvmStatic private external fun nativeStart(callback: SpeechCallback): Int
        @JvmStatic private external fun nativeStop()
    }
}
