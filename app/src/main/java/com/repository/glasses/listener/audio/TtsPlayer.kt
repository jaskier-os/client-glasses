package com.repository.glasses.listener.audio

import android.media.AudioAttributes
import android.os.SystemClock
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import com.repository.glasses.tracing.GT
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TtsPlayer {

    companion object {
        private const val TAG = "App:Tts"
        private const val LOG_EVERY_N_SAMPLES = 50
        private const val WAV_HEADER_SIZE = 44
        private const val AMPLITUDE_WINDOW_MS = 50
        // PCM gain to compensate for volume ducking. 2.5x restores TTS to ~100%
        // when system volume is ducked to 40% (1.0 / 0.4 = 2.5).
        private const val PCM_GAIN = 5.0f
        // OGG/Opus magic bytes: "OggS"
        private val OGG_MAGIC = byteArrayOf(0x4F, 0x67, 0x67, 0x53)
    }

    interface TtsListener {
        fun onPlaybackStarted()
        fun onPlaybackFinished()
        fun onInterrupted(requestId: String)
        fun onTtsAmplitude(level: Float)
        /** Called before first TTS chunk plays. Return true to apply PCM gain. */
        fun shouldApplyGain(): Boolean = false
        /**
         * Called from the playback thread on entry/exit so the owner can hold a
         * bt-manager active session for the duration. Default no-op so callers that
         * don't care don't need to override.
         */
        fun onActiveSessionEnter() {}
        fun onActiveSessionExit() {}
    }

    data class TtsChunk(
        val requestId: String,
        val pcmData: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
        val isFinal: Boolean
    )

    private var listener: TtsListener? = null
    private var remoteLog: ((String) -> Unit)? = null
    private val queue = ConcurrentLinkedQueue<TtsChunk>()
    private val isPlaying = AtomicBoolean(false)
    private val isInterrupted = AtomicBoolean(false)
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile
    private var currentRequestId: String? = null
    @Volatile
    private var gainActive = false

    fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    fun setRemoteLog(log: (String) -> Unit) {
        this.remoteLog = log
    }


    private var cacheDir: File? = null

    fun setCacheDir(dir: File) { cacheDir = dir }

    fun enqueue(requestId: String, audioBase64: String, isFinal: Boolean) = GT.section("tts.enqueue") {
        GT.counter("tts.queue.depth", queue.size.toLong())
        val raw: ByteArray
        try {
            raw = Base64.decode(audioBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            remoteLog?.invoke("Failed to decode audio base64: ${e.message}")
            return
        }

        if (raw.size < 4) {
            remoteLog?.invoke("Audio chunk too small (${raw.size} bytes), skipping")
            return
        }

        val isOpus = raw.size >= 4 && raw[0] == OGG_MAGIC[0] && raw[1] == OGG_MAGIC[1] &&
                raw[2] == OGG_MAGIC[2] && raw[3] == OGG_MAGIC[3]

        val pcmData: ByteArray
        val sampleRate: Int
        val channels: Int
        val bitDepth = 16

        if (isOpus) {
            remoteLog?.invoke("Opus audio detected (${raw.size} bytes, header=${raw[0].toInt() and 0xFF},${raw[1].toInt() and 0xFF},${raw[2].toInt() and 0xFF},${raw[3].toInt() and 0xFF}), decoding...")
            try {
                val decoded = decodeOpusToPcm(raw)
                pcmData = decoded.pcm
                sampleRate = decoded.sampleRate
                channels = decoded.channels
                val durationMs = if (sampleRate > 0) (pcmData.size / (2 * channels) * 1000L / sampleRate) else 0
                remoteLog?.invoke("Opus decoded: ${pcmData.size} bytes PCM, ${sampleRate}Hz, ${channels}ch, ${durationMs}ms duration")
            } catch (e: Exception) {
                remoteLog?.invoke("Opus decode failed: ${e.message}")
                return
            }
        } else if (raw.size > WAV_HEADER_SIZE) {
            // Parse WAV header
            val header = ByteBuffer.wrap(raw, 0, WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            header.position(22)
            channels = header.short.toInt()
            sampleRate = header.int
            header.position(34)
            val wavBitDepth = header.short.toInt()
            pcmData = raw.copyOfRange(WAV_HEADER_SIZE, raw.size)
            remoteLog?.invoke("WAV audio: ${pcmData.size} bytes PCM, ${sampleRate}Hz, ${channels}ch, ${wavBitDepth}bit")
            queue.add(TtsChunk(requestId, pcmData, sampleRate, channels, wavBitDepth, isFinal))
            startIfNeeded()
            return
        } else {
            remoteLog?.invoke("Unknown audio format (${raw.size} bytes), skipping")
            return
        }

        queue.add(TtsChunk(requestId, pcmData, sampleRate, channels, bitDepth, isFinal))

        startIfNeeded()
    }

    private fun startIfNeeded() {
        // Dead thread recovery: if thread crashed, reset isPlaying so we can start a new one
        if (isPlaying.get() && (playbackThread == null || playbackThread?.isAlive != true)) {
            remoteLog?.invoke("Dead playback thread detected, resetting")
            isPlaying.set(false)
        }

        if (isPlaying.compareAndSet(false, true)) {
            isInterrupted.set(false)
            startPlaybackThread()
        }
    }

    private data class DecodedAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

    private fun decodeOpusToPcm(opusData: ByteArray): DecodedAudio = GT.section("tts.decode_opus") {
        val tmpFile = File(cacheDir ?: File("/tmp"), "tts_opus_${System.currentTimeMillis()}.ogg")
        try {
            FileOutputStream(tmpFile).use { it.write(opusData) }

            val extractor = MediaExtractor()
            extractor.setDataSource(tmpFile.absolutePath)

            if (extractor.trackCount == 0) throw IllegalStateException("No tracks in Opus file")

            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/opus"
            val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            remoteLog?.invoke("Opus extractor: mime=$mime, sr=${sr}Hz, ch=$ch, format=$format")

            val codec = MediaCodec.createDecoderByType(mime)
            val outFormat = MediaFormat.createAudioFormat("audio/raw", sr, ch)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmChunks = mutableListOf<ByteArray>()
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    if (bufInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        val chunk = ByteArray(bufInfo.size)
                        outBuf.get(chunk)
                        pcmChunks.add(chunk)
                        if (pcmChunks.size % LOG_EVERY_N_SAMPLES == 0) {
                            Log.v(TAG, "event=tts_sample n=${pcmChunks.size} size=${bufInfo.size}")
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val totalSize = pcmChunks.sumOf { it.size }
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }

            DecodedAudio(result, sr, ch)
        } finally {
            tmpFile.delete()
        }
    }

    fun interrupt() = GT.section("tts.interrupt") {
        val rid = currentRequestId ?: return@section
        isInterrupted.set(true)
        queue.clear()

        // Let the playback thread handle its own AudioTrack cleanup via the finally block.
        // Only join briefly to avoid blocking the caller for too long.
        playbackThread?.join(500)

        remoteLog?.invoke("TTS playback interrupted for $rid")
        listener?.onInterrupted(rid)
        currentRequestId = null
        isPlaying.set(false)
    }

    fun release() {
        interrupt()
        playbackThread?.join(1000)
        playbackThread = null
    }

    private fun startPlaybackThread() {
        playbackThread = Thread({
            var notifiedStart = false
            var lastChunkWasFinal = false
            var track: AudioTrack? = null
            var totalFramesWritten = 0L
            var lastChunkTime = SystemClock.elapsedRealtime()

            var trackSampleRate = 0

            // Acquire the bt-manager active-session for the whole playback lifecycle so
            // the RFCOMM idle watchdog can't tear the connection down mid-utterance.
            try { listener?.onActiveSessionEnter() } catch (_: Throwable) {}

            try {
                while (!isInterrupted.get()) {
                    val chunk = queue.poll()
                    if (chunk == null) {
                        if (lastChunkWasFinal) break
                        if (SystemClock.elapsedRealtime() - lastChunkTime > 15_000) {
                            remoteLog?.invoke("TTS timeout: no chunks for 15s, treating as finished")
                            break
                        }
                        Thread.sleep(100)
                        continue
                    }
                    lastChunkTime = SystemClock.elapsedRealtime()

                    if (!notifiedStart) {
                        currentRequestId = chunk.requestId
                        gainActive = listener?.shouldApplyGain() ?: false
                        listener?.onPlaybackStarted()
                        notifiedStart = true
                    }

                    // Recreate AudioTrack when sample rate changes (Opus decodes at 48kHz, WAV at 24kHz)
                    if (track == null || chunk.sampleRate != trackSampleRate) {
                        track?.let { old ->
                            // Drain before switching
                            if (totalFramesWritten > 0 && !isInterrupted.get()) {
                                while (old.playbackHeadPosition < totalFramesWritten.toInt() && !isInterrupted.get()) {
                                    Thread.sleep(50)
                                }
                            }
                            try { old.stop(); old.release() } catch (_: Exception) {}
                        }
                        if (track != null) {
                            remoteLog?.invoke("Sample rate changed: ${trackSampleRate}Hz -> ${chunk.sampleRate}Hz, recreating AudioTrack")
                        }
                        track = createStreamTrack(chunk)
                        currentTrack = track
                        trackSampleRate = chunk.sampleRate
                        totalFramesWritten = 0
                        track.play()
                    }

                    lastChunkWasFinal = chunk.isFinal
                    totalFramesWritten += streamChunk(track, chunk)
                }

                // Drain: wait for all written frames to finish playing
                track?.let { t ->
                    if (!isInterrupted.get()) {
                        while (t.playbackHeadPosition < totalFramesWritten.toInt() && !isInterrupted.get()) {
                            Thread.sleep(50)
                        }
                        // Let the audio pipeline fully flush before stopping
                        Thread.sleep(200)
                    }
                    listener?.onTtsAmplitude(0f)
                }
            } catch (e: Exception) {
                remoteLog?.invoke("TTS playback thread crashed: ${e.message}")
            } finally {
                track?.let { t ->
                    try { t.stop(); t.release() } catch (_: Exception) {}
                    if (currentTrack === t) currentTrack = null
                }

                gainActive = false

                if (!isInterrupted.get()) {
                    currentRequestId = null
                    isPlaying.set(false)
                    if (notifiedStart) {
                        listener?.onPlaybackFinished()
                    }
                }
                // Always release the bt-manager active session, even on crash/interrupt.
                try { listener?.onActiveSessionExit() } catch (_: Throwable) {}
            }
        }, "tts-playback").also { it.start() }
    }

    private fun createStreamTrack(chunk: TtsChunk): AudioTrack {
        val channelConfig = if (chunk.channels == 1)
            AudioFormat.CHANNEL_OUT_MONO
        else
            AudioFormat.CHANNEL_OUT_STEREO

        val audioFormat = when (chunk.bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            8 -> AudioFormat.ENCODING_PCM_8BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBuf = AudioTrack.getMinBufferSize(chunk.sampleRate, channelConfig, audioFormat)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(chunk.sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Stream PCM data to the AudioTrack in small windows, emitting amplitude for each.
     * write() blocks when the internal buffer is full, naturally pacing output.
     */
    private fun streamChunk(track: AudioTrack, chunk: TtsChunk): Long = GT.section("tts.stream_chunk") {
        val bytesPerFrame = (chunk.bitDepth / 8) * chunk.channels
        val windowBytes = chunk.sampleRate * chunk.channels * (chunk.bitDepth / 8) * AMPLITUDE_WINDOW_MS / 1000
        var offset = 0
        var framesWritten = 0L

        val gain = if (gainActive) PCM_GAIN else 1.0f

        while (offset < chunk.pcmData.size && !isInterrupted.get()) {
            val writeSize = minOf(windowBytes, chunk.pcmData.size - offset)

            // Apply PCM gain and compute RMS amplitude for this window
            if (chunk.bitDepth == 16 && writeSize >= 2) {
                var sumSq = 0.0
                var count = 0
                val buf = ByteBuffer.wrap(chunk.pcmData, offset, writeSize).order(ByteOrder.LITTLE_ENDIAN)
                while (buf.remaining() >= 2) {
                    val pos = buf.position()
                    val sample = buf.short.toInt()
                    val amplified = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buf.putShort(pos, amplified.toShort())
                    sumSq += amplified.toDouble() * amplified.toDouble()
                    count++
                }
                if (count > 0) {
                    val rms = kotlin.math.sqrt(sumSq / count)
                    val level = (rms / 16384.0).toFloat().coerceIn(0f, 1f)
                    listener?.onTtsAmplitude(level)
                }
            }

            val written = track.write(chunk.pcmData, offset, writeSize)
            if (written < 0) break
            offset += written
            framesWritten += written / bytesPerFrame
        }

        GT.counter("tts.frames_written", framesWritten)
        GT.counter("tts.bytes_written", offset.toLong())
        framesWritten
    }
}
