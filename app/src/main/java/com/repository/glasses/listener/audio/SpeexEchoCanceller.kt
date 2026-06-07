package com.repository.glasses.listener.audio

import com.repository.glasses.tracing.GT

/**
 * JNI wrapper for coherence-based echo suppression.
 * Processes full chunks: suppresses frequency bins correlated with echo ref,
 * preserves uncorrelated components (voice, ambient sounds).
 */
class SpeexEchoCanceller(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 2048, // full chunk size for FFT
    private val filterLength: Int = 0, // unused, kept for API compat
    private val log: ((String) -> Unit)? = null
) {
    companion object {
        init {
            System.loadLibrary("speex_echo_jni")
        }
    }

    private var nativeHandle: Long = 0

    fun initialize(): Boolean {
        nativeHandle = nativeInit(sampleRate, frameSize, filterLength)
        val ok = nativeHandle != 0L
        log?.invoke("CoherenceAEC init: handle=$nativeHandle frame=$frameSize ok=$ok")
        return ok
    }

    /**
     * Process a chunk. Mic and echo must be exactly frameSize samples.
     */
    fun processChunk(mic: ShortArray, echo: ShortArray, out: ShortArray, length: Int = mic.size): Int = GT.section("audio.aec.speex") {
        if (nativeHandle == 0L) return@section 0
        if (length != frameSize) {
            // If length doesn't match, copy mic to out unchanged
            System.arraycopy(mic, 0, out, 0, length)
            return@section length
        }
        nativeProcess(nativeHandle, mic, echo, out)
        length
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            log?.invoke("CoherenceAEC released")
        }
    }

    fun isAvailable(): Boolean = nativeHandle != 0L

    private external fun nativeInit(sampleRate: Int, frameSize: Int, filterLength: Int): Long
    private external fun nativeProcess(handle: Long, mic: ShortArray, echo: ShortArray, out: ShortArray)
    private external fun nativeDestroy(handle: Long)
}
