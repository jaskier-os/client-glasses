package com.repository.glasses.listener.audio

import com.repository.glasses.tracing.GT

/**
 * WebRTC AECM (Acoustic Echo Canceller for Mobile).
 * Port of theeasiestway/android-webrtc-aecm.
 * Frame size: 160 samples (10ms at 16kHz).
 */
class WebRtcAecm(
    private val sampleRate: Int = 16000,
    private val log: ((String) -> Unit)? = null
) {
    // Field names MUST match JNI C code GetFieldID calls
    @Suppress("unused")
    class AecmConfig {
        var mAecmMode: Short = 3  // AGGRESSIVE
        var mCngMode: Short = 1   // ENABLE
    }

    companion object {
        init {
            System.loadLibrary("AEC")
        }
        const val FRAME_SIZE = 160 // 10ms at 16kHz

        @JvmStatic private external fun nativeCreateAecmInstance(): Long
        @JvmStatic private external fun nativeFreeAecmInstance(aecmHandler: Long): Int
        @JvmStatic private external fun nativeInitializeAecmInstance(aecmHandler: Long, samplingFrequency: Int): Int
        @JvmStatic private external fun nativeBufferFarend(aecmHandler: Long, farend: ShortArray, nrOfSamples: Int): Int
        @JvmStatic private external fun nativeAecmProcess(aecmHandler: Long, nearendNoisy: ShortArray, nearendClean: ShortArray?, nrOfSamples: Short, msInSndCardBuf: Short): ShortArray?
        @JvmStatic private external fun nativeSetConfig(aecmHandler: Long, aecmConfig: AecmConfig): Int
    }

    @Volatile private var handle: Long = -1
    // Pre-allocated frame buffers to avoid GC pressure on audio thread
    private val farFrame = ShortArray(FRAME_SIZE)
    private val nearFrame = ShortArray(FRAME_SIZE)

    fun initialize(): Boolean {
        handle = nativeCreateAecmInstance()
        if (handle == -1L) {
            log?.invoke("WebRtcAecm: create failed")
            return false
        }
        val ret = nativeInitializeAecmInstance(handle, sampleRate)
        if (ret != 0) {
            log?.invoke("WebRtcAecm: init failed ($ret)")
            nativeFreeAecmInstance(handle)
            handle = -1
            return false
        }
        val config = AecmConfig()
        config.mAecmMode = 4 // MOST_AGGRESSIVE
        nativeSetConfig(handle, config)
        log?.invoke("WebRtcAecm: initialized (rate=$sampleRate mode=MOST_AGGRESSIVE)")
        return true
    }

    /**
     * Process a chunk of mic + echo ref. Splits into 160-sample frames.
     * Remainder samples (2048 % 160 = 128) are also processed as a partial 80-sample frame
     * since AECM accepts 80 or 160 sample blocks.
     * @param delayMs estimated delay between far-end playback and near-end capture
     */
    fun processChunk(mic: ShortArray, echo: ShortArray, out: ShortArray, length: Int, delayMs: Int = 10): Int = GT.section("audio.aec.webrtc") {
        if (handle == -1L) return@section 0
        var offset = 0
        while (offset + FRAME_SIZE <= length) {
            System.arraycopy(echo, offset, farFrame, 0, FRAME_SIZE)
            nativeBufferFarend(handle, farFrame, FRAME_SIZE)

            System.arraycopy(mic, offset, nearFrame, 0, FRAME_SIZE)
            val cleaned = nativeAecmProcess(handle, nearFrame, null, FRAME_SIZE.toShort(), delayMs.toShort())
            if (cleaned != null && cleaned.size >= FRAME_SIZE) {
                System.arraycopy(cleaned, 0, out, offset, FRAME_SIZE)
            } else {
                System.arraycopy(mic, offset, out, offset, FRAME_SIZE)
            }
            offset += FRAME_SIZE
        }
        // Process remainder as 80-sample frame if possible (AECM accepts 80 or 160)
        val remaining = length - offset
        if (remaining >= 80) {
            val remSize = if (remaining >= 160) 160 else 80
            val remFar = ShortArray(remSize)
            val remNear = ShortArray(remSize)
            System.arraycopy(echo, offset, remFar, 0, remSize)
            nativeBufferFarend(handle, remFar, remSize)
            System.arraycopy(mic, offset, remNear, 0, remSize)
            val cleaned = nativeAecmProcess(handle, remNear, null, remSize.toShort(), delayMs.toShort())
            if (cleaned != null && cleaned.size >= remSize) {
                System.arraycopy(cleaned, 0, out, offset, remSize)
            } else {
                System.arraycopy(mic, offset, out, offset, remSize)
            }
            offset += remSize
        }
        // Any final tail (< 80 samples) copied raw
        if (offset < length) {
            System.arraycopy(mic, offset, out, offset, length - offset)
        }
        length
    }

    fun release() {
        if (handle != -1L) {
            nativeFreeAecmInstance(handle)
            handle = -1
            log?.invoke("WebRtcAecm: released")
        }
    }

    fun isAvailable(): Boolean = handle != -1L
}
