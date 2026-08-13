package com.repository.glasses.listener.arstream

import android.media.AudioAttributes
import android.media.AudioFormat
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
class ArAudioBridge(private val log: ((String) -> Unit)? = null) {

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
                    // Voice-communication usage so the platform treats this as a call leg.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

        running = true
        rec.startRecording()
        uplinkThread = thread(name = "ArAudioBridge-uplink") { uplinkLoop() }
        log?.invoke("ArAudioBridge: started")
        return true
    }

    private fun uplinkLoop() {
        val rec = record ?: return
        val raw = ShortArray(BLOCK_FRAMES * NUM_CHANNELS_8)
        val inward = ShortArray(BLOCK_FRAMES)
        val outward = ShortArray(BLOCK_FRAMES)
        val mixed = ShortArray(BLOCK_FRAMES)
        val cleaned = ShortArray(BLOCK_FRAMES)

        while (running) {
            val read = try {
                rec.read(raw, 0, raw.size)
            } catch (e: Exception) {
                if (running) log?.invoke("ArAudioBridge: read failed: ${e.message}")
                break
            }
            if (read <= 0) continue

            val frames = read / NUM_CHANNELS_8
            if (frames <= 0) continue

            for (f in 0 until frames) {
                val base = f * NUM_CHANNELS_8
                inward[f] = applyGain(raw[base + UplinkMixer.CHANNEL_INWARD])
                outward[f] = applyGain(raw[base + OUTWARD_CHANNEL])
            }

            mixer.mix(inward, outward, mixed, frames)

            // Cancel our own playback out of the uplink before it leaves the device.
            val out = aecm?.let { a ->
                val far = farEnd.take(frames)
                a.processChunk(mixed, far, cleaned, frames, currentDelayMs())
                cleaned
            } ?: mixed

            if (!glassesMicMuted) onUplinkAudio?.invoke(out, frames)
        }
    }

    /** Feed PCM received from the phone: reference first, then play. */
    fun playDownlink(pcm: ShortArray, length: Int) {
        if (!running || playbackMuted) return
        // Register as far-end BEFORE playback so the canceller has it when the echo returns.
        farEnd.put(pcm, length)
        try {
            track?.write(pcm, 0, length)
            framesWritten += length
        } catch (e: Exception) {
            log?.invoke("ArAudioBridge: playback write failed: ${e.message}")
        }
    }

    /**
     * Round-trip delay estimate for AECM: how much audio we have written but the DAC has not yet
     * played, i.e. the buffer backlog. Better than a fixed constant, which is what the AECM
     * default assumes.
     */
    private fun currentDelayMs(): Int {
        val t = track ?: return DEFAULT_DELAY_MS
        return try {
            val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val backlogFrames = (framesWritten - played).coerceAtLeast(0L)
            val ms = (backlogFrames * 1000L / SAMPLE_RATE).toInt()
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
    }
}
