package com.repository.glasses.listener.audio

import com.repository.glasses.tracing.GT
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opus encoder backed by the vendored libopus (JNI wrapper `opus_jni`).
 *
 * Encodes mono 16 kHz PCM16LE input into Opus frames (16 kbps CBR, 20 ms).
 * Each output frame in the returned ByteArray is prefixed with a 2-byte
 * little-endian length for framing over RFCOMM and local archive files.
 *
 * Public API is kept identical to the previous MediaCodec-backed version so
 * existing callers (ListenerService, LocalOpusWriter, etc.) do not change.
 *
 * Thread safety: a single instance MUST be driven from one thread at a time.
 * The native encoder holds no internal lock. Create a dedicated instance per
 * consumer (one for BT live stream, one for local archive writer, etc.).
 */
class OpusEncoder(
    private val sampleRate: Int = 16000,
    private val bitrate: Int = 16000,
    private val channels: Int = 1,
    private val log: ((String) -> Unit)? = null
) {
    companion object {
        private const val FRAME_MS = 20

        @Volatile
        private var libraryLoaded: Boolean = false

        @Volatile
        private var libraryLoadError: String? = null

        init {
            try {
                System.loadLibrary("opus_jni")
                libraryLoaded = true
            } catch (t: UnsatisfiedLinkError) {
                libraryLoaded = false
                libraryLoadError = t.message ?: t.javaClass.simpleName
                android.util.Log.e(
                    "OpusEncoder",
                    "System.loadLibrary(opus_jni) failed: ${libraryLoadError}"
                )
            } catch (t: Throwable) {
                libraryLoaded = false
                libraryLoadError = t.message ?: t.javaClass.simpleName
                android.util.Log.e(
                    "OpusEncoder",
                    "System.loadLibrary(opus_jni) threw ${t.javaClass.simpleName}: ${libraryLoadError}"
                )
            }
        }

        /** True if the native opus_jni .so loaded successfully at class init. */
        val nativeLoaded: Boolean
            get() = libraryLoaded

        /** Non-null if loadLibrary threw, for reporting to the user. */
        val nativeLoadError: String?
            get() = libraryLoadError

        @JvmStatic
        private external fun nativeCreate(sampleRateHz: Int, channels: Int, bitrateBps: Int): Long

        @JvmStatic
        private external fun nativeEncodeFrame(
            handle: Long,
            pcm: ShortArray,
            pcmOffset: Int,
            pcmFrames: Int,
            out: ByteArray,
            outOffset: Int
        ): Int

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }

    // 320 at 16 kHz mono
    private val frameSamples = sampleRate * FRAME_MS / 1000
    // PCM16LE bytes per frame, per channel sum
    private val framePcmBytes = frameSamples * channels * 2

    // PCM16 accumulator across encode() calls -- callers feed arbitrary byte
    // counts, libopus needs whole 20 ms frames.
    private val accumulator = ShortArray(frameSamples * channels)
    private var accumSamples = 0

    // Reusable scratch buffer for one encoded frame. RFC 6716 max Opus frame
    // is 1275 bytes; 1276 matches opus_jni's tmp[] precheck in the native layer.
    private val encodedScratch = ByteArray(1276)

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var available: Boolean = false

    // Per-instance flag so we log the first nativeEncodeFrame<0 return exactly once,
    // not every 20 ms. Resets to false on a new encoder instance.
    private val loggedNativeErrorOnce = AtomicBoolean(false)

    fun initialize(): Boolean = GT.section("audio.opus.init") {
        if (!libraryLoaded) {
            log?.invoke("OpusEncoder init skipped: native lib not loaded (${libraryLoadError ?: "unknown"})")
            return@section false
        }
        if (handle != 0L) {
            // Double initialize is a no-op.
            return@section available
        }
        try {
            val h = nativeCreate(sampleRate, channels, bitrate)
            if (h == 0L) {
                log?.invoke("OpusEncoder init failed: nativeCreate returned 0")
                available = false
                return@section false
            }
            handle = h
            available = true
            accumSamples = 0
            log?.invoke("OpusEncoder initialized (native libopus ${sampleRate}Hz, ${bitrate}bps, ${FRAME_MS}ms frames)")
            true
        } catch (e: Throwable) {
            log?.invoke("OpusEncoder init exception: ${e.message}")
            available = false
            handle = 0L
            false
        }
    }

    /**
     * Encode mono PCM16LE bytes. Returns length-prefixed Opus frames concatenated,
     * ready to write to RFCOMM socket or on-disk archive. Returns null if the
     * encoder is not available or no complete frame was produced by this call.
     *
     * Framing (per output frame, identical to the prior MediaCodec version):
     *   [len_lo, len_hi, opus_bytes...]
     * where len is little-endian uint16 of the opus payload size.
     */
    fun encode(pcmBytes: ByteArray, offset: Int = 0, length: Int = pcmBytes.size): ByteArray? =
        GT.section("audio.opus.encode") {
            if (!available || handle == 0L) return@section null
            if (length <= 0) return@section null
            if (offset < 0 || offset + length > pcmBytes.size) {
                // Real programmer error in the caller -- don't silently swallow.
                throw IllegalArgumentException(
                    "OpusEncoder.encode bad range offset=$offset length=$length arr=${pcmBytes.size}"
                )
            }

            val output = ArrayList<ByteArray>(4)

            var srcPos = offset
            val srcEnd = offset + length

            while (srcPos + 1 < srcEnd) {
                // Copy little-endian PCM16 bytes into the short accumulator.
                val bytesLeft = srcEnd - srcPos
                val samplesLeft = bytesLeft / 2
                val roomSamples = accumulator.size - accumSamples
                val copy = if (samplesLeft < roomSamples) samplesLeft else roomSamples

                var i = 0
                while (i < copy) {
                    val lo = pcmBytes[srcPos].toInt() and 0xFF
                    val hi = pcmBytes[srcPos + 1].toInt() // signed extend
                    accumulator[accumSamples + i] = ((hi shl 8) or lo).toShort()
                    srcPos += 2
                    i++
                }
                accumSamples += copy

                if (accumSamples < accumulator.size) {
                    // Not enough yet for a full frame; wait for more bytes.
                    break
                }

                // We have exactly one full frame -- encode it.
                val written = nativeEncodeFrame(
                    handle,
                    accumulator,
                    0,
                    frameSamples,
                    encodedScratch,
                    0
                )
                accumSamples = 0

                if (written < 0) {
                    if (loggedNativeErrorOnce.compareAndSet(false, true)) {
                        log?.invoke("OpusEncoder.encode native error code=$written; dropping frame (this log appears once per encoder instance)")
                    }
                    continue
                }
                if (written == 0) {
                    // DTX / empty output; skip.
                    continue
                }

                // Prepend 2-byte LE length -- identical wire format to the prior
                // MediaCodec path so the phone-side decoder is unchanged.
                val framed = ByteArray(2 + written)
                framed[0] = (written and 0xFF).toByte()
                framed[1] = ((written ushr 8) and 0xFF).toByte()
                System.arraycopy(encodedScratch, 0, framed, 2, written)
                output.add(framed)
            }

            if (output.isEmpty()) return@section null

            val totalSize = output.sumOf { it.size }
            val result = ByteArray(totalSize)
            var pos = 0
            for (frame in output) {
                System.arraycopy(frame, 0, result, pos, frame.size)
                pos += frame.size
            }
            result
        }

    /**
     * Encode mono PCM16 ShortArray (host-endian, one sample per element) directly.
     * Same framing contract as [encode]: returns length-prefixed Opus frames
     * concatenated, or null when no complete 20 ms frame is available yet.
     *
     * This is the preferred path for producers that already have PCM as shorts
     * (e.g. MicBus subscribers); it avoids the byte<->short conversion step.
     */
    fun encodeShort(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size): ByteArray? =
        GT.section("audio.opus.encode.short") {
            if (!available || handle == 0L) return@section null
            if (length <= 0) return@section null
            if (offset < 0 || offset + length > pcm.size) {
                // Real programmer error -- caller passed out-of-bounds indices. Throw
                // instead of silently returning null (which the caller treats as "not
                // enough samples yet").
                throw IllegalArgumentException(
                    "OpusEncoder.encodeShort bad range offset=$offset length=$length arr=${pcm.size}"
                )
            }

            val output = ArrayList<ByteArray>(8)

            var srcPos = offset
            val srcEnd = offset + length

            while (srcPos < srcEnd) {
                val samplesLeft = srcEnd - srcPos
                val roomSamples = accumulator.size - accumSamples
                val copy = if (samplesLeft < roomSamples) samplesLeft else roomSamples

                System.arraycopy(pcm, srcPos, accumulator, accumSamples, copy)
                srcPos += copy
                accumSamples += copy

                if (accumSamples < accumulator.size) break

                val written = nativeEncodeFrame(
                    handle,
                    accumulator,
                    0,
                    frameSamples,
                    encodedScratch,
                    0
                )
                accumSamples = 0

                if (written < 0) {
                    if (loggedNativeErrorOnce.compareAndSet(false, true)) {
                        log?.invoke("OpusEncoder.encodeShort native error code=$written; dropping frame (this log appears once per encoder instance)")
                    }
                    continue
                }
                if (written == 0) continue

                val framed = ByteArray(2 + written)
                framed[0] = (written and 0xFF).toByte()
                framed[1] = ((written ushr 8) and 0xFF).toByte()
                System.arraycopy(encodedScratch, 0, framed, 2, written)
                output.add(framed)
            }

            if (output.isEmpty()) return@section null

            val totalSize = output.sumOf { it.size }
            val result = ByteArray(totalSize)
            var pos = 0
            for (frame in output) {
                System.arraycopy(frame, 0, result, pos, frame.size)
                pos += frame.size
            }
            result
        }

    fun isAvailable(): Boolean = available && handle != 0L

    fun release() {
        synchronized(this) {
            val h = handle
            handle = 0L
            available = false
            accumSamples = 0
            if (h != 0L) {
                try {
                    nativeDestroy(h)
                } catch (e: Throwable) {
                    log?.invoke("OpusEncoder.release nativeDestroy threw: ${e.message}")
                }
            }
        }
    }
}
