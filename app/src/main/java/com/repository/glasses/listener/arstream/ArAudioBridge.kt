package com.repository.glasses.listener.arstream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.repository.glasses.listener.audio.WebRtcAecm
import kotlin.concurrent.thread

/**
 * Full-duplex audio for a live AR session.
 *
 * Uplink: one 8-channel AudioRecord. ch0 (inward/wearer) and ch2 (outward/front) are mixed by
 * [UplinkMixer] into ONE mono 16 kHz stream, so the phone hears both mics as a single uniform
 * source.
 *
 * Downlink: the phone's mic PCM is played through the speaker, and the SAME buffer is handed to
 * WebRtcAecm as the far-end reference before playback.
 *
 * Two deliberate choices worth knowing:
 *
 *  - The far-end reference is the PCM we write to AudioTrack, NOT the hardware echo channels
 *    (ch6/ch7). Those are an ACOUSTIC tap: they also contain the wearer's own voice, and telling
 *    AECM that the near-end talker is far-end signal is exactly how a canceller destroys the
 *    speech it is supposed to keep.
 *  - The CAE beamformer is not used here. Its JNI symbols are bound to the
 *    TranslationFrontMicRecorder class name and it keeps global single-instance native state, so
 *    a second concurrent consumer is not possible. Raw ch2 with gain is the same path the
 *    existing 4-channel fallback already uses.
 */
class ArAudioBridge(
    private val context: Context,
    private val log: ((String) -> Unit)? = null
) {

    /** Emits mixed mono 16 kHz PCM16 for the phone. */
    var onUplinkAudio: ((ShortArray, Int) -> Unit)? = null

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aecm: WebRtcAecm? = null
    private var uplinkThread: Thread? = null

    private val mixer = UplinkMixer()

    @Volatile private var running = false
    @Volatile private var glassesMicMuted = false
    @Volatile private var playbackMuted = false

    /** Far-end (what we played) kept for AECM, aligned by consumption order. */
    private val farEnd = FarEndBuffer()

    @Volatile private var framesWritten = 0L
    @Volatile private var uplinkBlocks = 0L
    @Volatile private var downlinkBlocks = 0L

    /** Samples the AudioTrack refused because its buffer was full. Silent truncation otherwise. */
    @Volatile private var shortWrites = 0L
    @Volatile private var droppedSamples = 0L

    /**
     * Opens an 8-channel AudioRecord alongside the service's always-on mono mic pump. That is the
     * same arrangement the translation feature already uses in production (one 8-ch open, mono
     * pump left running), so it is known to work on this HAL -- do not "fix" it by stopping the
     * mono pump, which would take the wake-word pipeline down with it.
     */
    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK_8CH, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            log?.invoke("ArAudioBridge: 8ch min buffer unavailable ($minBuf)")
            return false
        }

        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_MASK_8CH,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: Exception) {
            log?.invoke("ArAudioBridge: AudioRecord create failed: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log?.invoke("ArAudioBridge: AudioRecord not initialized")
            try { rec.release() } catch (_: Exception) {}
            return false
        }
        record = rec

        val outMin = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_ASSISTANT -> STREAM_MUSIC, exactly like the production-audible
                    // TtsPlayer. USAGE_VOICE_COMMUNICATION maps to STREAM_VOICE_CALL, which in
                    // MODE_NORMAL (nothing here ever sets MODE_IN_COMMUNICATION, and it must not:
                    // it would preempt A2DP/HFP) routes to an absent call device = silence.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(outMin * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()

        aecm = WebRtcAecm(SAMPLE_RATE, log).also {
            if (!it.initialize()) log?.invoke("ArAudioBridge: AECM init failed, continuing without it")
        }

        logAudioRouting()

        running = true
        rec.startRecording()
        uplinkThread = thread(name = "ArAudioBridge-uplink") { uplinkLoop() }
        log?.invoke("ArAudioBridge: started")
        return true
    }

    /**
     * One-shot routing diagnostic. Audibility bugs on this device are always "which stream did the
     * platform pick, and is its volume zero", and neither is visible from any other log line.
     */
    private fun logAudioRouting() {
        val t = track
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val streamType = try { t?.streamType } catch (_: Exception) { null }
        val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        log?.invoke(
            "ArAudioBridge: routing usage=ASSISTANT streamType=$streamType " +
                "(STREAM_MUSIC=${AudioManager.STREAM_MUSIC}) musicVolume=$vol/$maxVol " +
                "audioMode=${am?.mode} trackState=${t?.state} playState=${t?.playState}"
        )
    }

    private fun uplinkLoop() {
        val rec = record ?: return
        val raw = ShortArray(BLOCK_FRAMES * NUM_CHANNELS_8)
        val inward = ShortArray(BLOCK_FRAMES)
        val outward = ShortArray(BLOCK_FRAMES)
        val mixed = ShortArray(BLOCK_FRAMES)
        val cleaned = ShortArray(BLOCK_FRAMES)
        // Leftover shorts from a read that did not land on a frame boundary.
        //
        // INVARIANT: every read starts on a frame boundary, which is exactly what this carry
        // maintains. A remainder is therefore the HEAD of an incomplete frame (channels
        // 0..carry-1), so leaving it at raw[0..carry-1] and appending the next read after it
        // reconstructs that frame correctly. Do NOT "simplify" this by discarding the remainder:
        // the next read would then begin mid-frame and every channel index would shift
        // permanently, routing the ch6/ch7 echo reference into the uplink -- the exact feedback
        // loop UplinkMixer exists to prevent.
        var carry = 0

        while (running) {
            val read = try {
                rec.read(raw, carry, raw.size - carry)
            } catch (e: Exception) {
                if (running) log?.invoke("ArAudioBridge: read failed: ${e.message}")
                break
            }
            if (read <= 0) continue

            val total = carry + read
            val frames = total / NUM_CHANNELS_8
            carry = total % NUM_CHANNELS_8
            if (frames <= 0) {
                // Not even one whole frame yet; keep what we have and read more.
                continue
            }

            for (f in 0 until frames) {
                val base = f * NUM_CHANNELS_8
                inward[f] = applyGain(raw[base + UplinkMixer.CHANNEL_INWARD])
                outward[f] = applyGain(raw[base + OUTWARD_CHANNEL])
            }
            if (carry > 0) {
                // Move the partial frame to the front for the next iteration.
                System.arraycopy(raw, frames * NUM_CHANNELS_8, raw, 0, carry)
            }

            mixer.mix(inward, outward, mixed, frames)

            // Cancel our own playback out of the uplink before it leaves the device.
            val out = aecm?.let { a ->
                val far = farEnd.take(frames)
                a.processChunk(mixed, far, cleaned, frames, currentDelayMs())
                cleaned
            } ?: mixed

            uplinkBlocks++
            if (uplinkBlocks == 1L || uplinkBlocks % 200L == 0L) {
                var peak = 0
                for (i in 0 until frames) { val a = kotlin.math.abs(out[i].toInt()); if (a > peak) peak = a }
                log?.invoke("ArAudioBridge: uplink #$uplinkBlocks frames=$frames peak=$peak muted=$glassesMicMuted")
            }
            if (!glassesMicMuted) onUplinkAudio?.invoke(out, frames)
        }
    }

    /** Feed PCM received from the phone: reference first, then play. */
    fun playDownlink(pcm: ShortArray, length: Int) {
        if (!running || playbackMuted) return
        // Register as far-end BEFORE playback so the canceller has it when the echo returns.
        farEnd.put(pcm, length)
        // Bound the drift between the network clock and the capture clock.
        farEnd.trimTo(MAX_FAR_END_SAMPLES)
        try {
            // NON_BLOCKING: this runs on the socket reader thread, and a blocking write would
            // stall control messages (mute, stop) behind a full playback buffer.
            val written = track?.write(pcm, 0, length, AudioTrack.WRITE_NON_BLOCKING) ?: 0
            if (written > 0) framesWritten += written
            if (written < length) {
                // Silently dropping the tail is how "audible but chopped" happens. Count it so the
                // difference between a routing failure and a buffer-pressure failure is visible.
                shortWrites++
                droppedSamples += (length - written.coerceAtLeast(0))
                if (shortWrites == 1L || shortWrites % 100L == 0L) {
                    log?.invoke(
                        "ArAudioBridge: short write #$shortWrites wrote=$written/$length " +
                            "droppedSamples=$droppedSamples"
                    )
                }
            }
            downlinkBlocks++
            if (downlinkBlocks == 1L || downlinkBlocks % 200L == 0L) {
                var peak = 0
                for (i in 0 until length) { val a = kotlin.math.abs(pcm[i].toInt()); if (a > peak) peak = a }
                // playbackHeadPosition is the acceptance instrument: a track routed nowhere still
                // accepts writes, but its head does not advance.
                val head = try { track?.playbackHeadPosition } catch (_: Exception) { null }
                log?.invoke(
                    "ArAudioBridge: downlink #$downlinkBlocks samples=$length peak=$peak " +
                        "head=$head written=$framesWritten shortWrites=$shortWrites"
                )
            }
        } catch (e: Exception) {
            log?.invoke("ArAudioBridge: playback write failed: ${e.message}")
        }
    }

    /**
     * Round-trip delay estimate for AECM.
     *
     * Two components: audio sitting in the far-end FIFO that the canceller has not consumed yet,
     * plus audio written to the AudioTrack that the DAC has not played yet. Counting only the
     * second would understate the delay by however far the FIFO has drifted.
     */
    private fun currentDelayMs(): Int {
        val t = track ?: return DEFAULT_DELAY_MS
        return try {
            val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val backlogFrames = (framesWritten - played).coerceAtLeast(0L)
            val fifoFrames = farEnd.depth().toLong()
            val ms = ((backlogFrames + fifoFrames) * 1000L / SAMPLE_RATE).toInt()
            (ms + SPEAKER_TO_MIC_MS).coerceIn(0, MAX_DELAY_MS)
        } catch (e: Exception) {
            DEFAULT_DELAY_MS
        }
    }

    private fun applyGain(s: Short): Short {
        val v = s.toInt() * MIC_GAIN
        return v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    fun setGlassesMicMuted(muted: Boolean) {
        glassesMicMuted = muted
        log?.invoke("ArAudioBridge: glasses mic muted=$muted")
    }

    /** Phone mic muted -> nothing to play; also stops feeding the far-end reference. */
    fun setPlaybackMuted(muted: Boolean) {
        playbackMuted = muted
        if (muted) farEnd.clear()
        log?.invoke("ArAudioBridge: playback muted=$muted")
    }

    fun stop() {
        if (!running) return
        running = false
        try { uplinkThread?.join(500) } catch (_: InterruptedException) {}
        uplinkThread = null

        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null

        // Uplink thread is already joined above, so nothing is mid-processChunk on the AECM.
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null

        try { aecm?.release() } catch (_: Exception) {}
        aecm = null
        farEnd.clear()
        framesWritten = 0
        log?.invoke("ArAudioBridge: stopped")
    }

    /**
     * FIFO of played samples used as the AECM far-end. Bounded: if playback stalls we drop the
     * oldest rather than growing without limit, since stale reference is worse than none.
     */
    private class FarEndBuffer {
        private val buf = ShortArray(MAX_SAMPLES)
        private var head = 0
        private var size = 0

        @Synchronized
        fun put(pcm: ShortArray, length: Int) {
            for (i in 0 until length) {
                if (size == MAX_SAMPLES) {
                    head = (head + 1) % MAX_SAMPLES
                    size--
                }
                buf[(head + size) % MAX_SAMPLES] = pcm[i]
                size++
            }
        }

        @Synchronized
        fun take(count: Int): ShortArray {
            val out = ShortArray(count)
            for (i in 0 until count) {
                if (size == 0) break
                out[i] = buf[head]
                head = (head + 1) % MAX_SAMPLES
                size--
            }
            return out
        }

        @Synchronized
        fun depth(): Int = size

        /**
         * Discard everything older than [keepSamples].
         *
         * The producer (network) and consumer (mic capture) clocks are independent, so the FIFO
         * drifts. Left alone it pins at the cap and the far-end reference ends up describing audio
         * that played a second ago -- worse than useless to a canceller, which then subtracts the
         * wrong signal. Trimming keeps the reference near the true acoustic delay.
         */
        @Synchronized
        fun trimTo(keepSamples: Int) {
            val excess = size - keepSamples
            if (excess <= 0) return
            head = (head + excess) % MAX_SAMPLES
            size -= excess
        }

        @Synchronized
        fun clear() {
            head = 0
            size = 0
        }

        private companion object {
            const val MAX_SAMPLES = 16000 // 1 second
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val NUM_CHANNELS_8 = 8
        const val CHANNEL_MASK_8CH = 0x6000FC // AUDIO_CHANNEL_IN_8
        const val OUTWARD_CHANNEL = 2 // FRONT, same channel the 4ch fallback path extracts
        const val BLOCK_FRAMES = 160 // 10 ms, matches WebRtcAecm.FRAME_SIZE
        const val MIC_GAIN = 24
        const val DEFAULT_DELAY_MS = 10
        const val SPEAKER_TO_MIC_MS = 10
        const val MAX_DELAY_MS = 500

        /** ~200 ms of far-end reference; beyond this the FIFO has drifted, not buffered. */
        const val MAX_FAR_END_SAMPLES = SAMPLE_RATE / 5
    }
}
