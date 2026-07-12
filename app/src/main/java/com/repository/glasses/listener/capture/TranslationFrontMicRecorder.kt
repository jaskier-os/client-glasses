package com.repository.glasses.listener.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.repository.glasses.listener.bt.GlassesBtClient

/**
 * Captures beamformed front-facing audio from the 4-mic array for translation.
 *
 * Primary path: opens 8-ch AudioRecord (0x6000FC mask, 16kHz), extracts channels
 * [2,3,4,5,6,7] (4 mic + 2 ref), feeds to the iFlytek CAE beamformer via JNI
 * (libcae.so), and sends the beamformed mono output (beam 1) over BT.
 *
 * Fallback: if CAE init fails (missing libcae.so, auth issue, etc.), falls back
 * to raw 4-ch capture with channel 2 (FRONT) extraction + 24x gain -- the
 * original omnidirectional behavior.
 *
 * Dead-mic detection: if the recording loop reads all-zero PCM for >2 seconds,
 * the AudioRecord is stopped, released, and re-created. If the retry also
 * produces dead audio, the CAE path falls back to the 4ch path automatically.
 *
 * CAE input: 256 frames * 6ch * 2 bytes = 3072 bytes per block.
 * CAE output: ~512 mono samples every 2 input blocks (32ms at 16kHz).
 */
class TranslationFrontMicRecorder(
    private val btClient: GlassesBtClient
) {
    var remoteLog: ((String) -> Unit)? = null

    @Volatile private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    @Volatile private var usingCae = false
    @Volatile private var twoWayActive = false

    /**
     * When true, the far-party HFP call downlink (the 8ch array's hardware-echo channel)
     * is extracted per block and streamed to the phone over CH_AUDIO_DATA_CALL, and the
     * front-beam send is suspended (the wearer's near-party beam is out of scope for the
     * phone's "system audio" translation during a call). Set by ListenerService on SCO
     * transitions, gated on translation being active. Only effective on the 8ch CAE path
     * (the 4ch fallback mask does not include the echo channel). Volatile: written on the
     * main thread, read on the audio thread.
     */
    @Volatile var callAudioActive = false

    /**
     * Which raw 8ch channel carries the hardware echo / SCO downlink. Confirmed by ear to
     * be c6 (c7 is the second echo channel). Left as a runtime-flippable var so the c6-vs-c7
     * choice can be verified on-device during a live call without a rebuild.
     */
    @Volatile var callEchoChannel = 6
    private var callFallbackWarned = false

    /* -- JNI: CAE beamformer -- */
    private external fun nativeCaeInit(workDir: String): Boolean
    private external fun nativeCaeProcess(input6ch: ByteArray): ByteArray?
    private external fun nativeCaeDestroy()

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SAMPLE = 2  // PCM 16-bit

        /* 8-ch mode for CAE path */
        private const val NUM_CHANNELS_8 = 8
        private const val BYTES_PER_FRAME_8 = NUM_CHANNELS_8 * BYTES_PER_SAMPLE  // 16
        private const val CHANNEL_MASK_8CH = 0x6000FC  // AUDIO_CHANNEL_IN_8
        private const val CAE_INPUT_CHANNELS = 6
        private const val CAE_BLOCK_FRAMES = 256
        private const val CAE_BLOCK_BYTES_8CH = CAE_BLOCK_FRAMES * BYTES_PER_FRAME_8  // 4096
        private const val CAE_BLOCK_BYTES_6CH = CAE_BLOCK_FRAMES * CAE_INPUT_CHANNELS * BYTES_PER_SAMPLE  // 3072
        private val EXTRACT_MAP = intArrayOf(2, 3, 4, 5, 6, 7)
        private const val INWARD_BLOCK_BYTES = CAE_BLOCK_FRAMES * BYTES_PER_SAMPLE  // 512 -- ch0 mono per block

        /* 4-ch fallback (original behavior) */
        private const val NUM_CHANNELS_4 = 4
        private const val BYTES_PER_FRAME_4 = NUM_CHANNELS_4 * BYTES_PER_SAMPLE  // 8
        private const val CHANNEL_CONFIG_4CH = 60  // 0x3C
        private const val EXTRACT_CHANNEL = 2  // FRONT
        private const val CHUNK_FRAMES = 1024  // 64ms
        private const val CHUNK_RAW_BYTES_4CH = CHUNK_FRAMES * BYTES_PER_FRAME_4  // 8192
        private const val CHUNK_MONO_BYTES = CHUNK_FRAMES * BYTES_PER_SAMPLE  // 2048
        private const val MIC_GAIN = 24

        /** Consecutive all-zero blocks before declaring mic dead. ~2s at 16ms/block. */
        private const val DEAD_MIC_THRESHOLD_CAE = 125
        /** Consecutive all-zero blocks before declaring mic dead in 4ch path. ~2s at 64ms/block. */
        private const val DEAD_MIC_THRESHOLD_4CH = 31

        init {
            System.loadLibrary("cae_beamform_jni")
        }
    }

    fun start(twoWay: Boolean = false) {
        if (isRecording) return
        twoWayActive = twoWay

        /* Try CAE path first */
        usingCae = try {
            nativeCaeInit("/system/etc")
        } catch (e: UnsatisfiedLinkError) {
            remoteLog?.invoke("FrontMic: cae_beamform_jni load failed: ${e.message}")
            false
        } catch (e: Exception) {
            remoteLog?.invoke("FrontMic: CAE init exception: ${e.message}")
            false
        }

        if (usingCae) {
            remoteLog?.invoke("FrontMic: CAE beamformer initialized -- using 8ch capture (twoWay=$twoWayActive)")
            startCaePath()
        } else {
            remoteLog?.invoke("FrontMic: CAE unavailable -- falling back to raw 4ch capture (twoWay=$twoWayActive)")
            startFallbackPath()
        }
    }

    fun stop() {
        isRecording = false
        twoWayActive = false
        if (Thread.currentThread() != recordingThread) {
            recordingThread?.join(2000)
        }
        recordingThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        if (usingCae) {
            try { nativeCaeDestroy() } catch (_: Exception) {}
            usingCae = false
        }
        remoteLog?.invoke("FrontMic: stopped")
    }

    /** Check if a PCM buffer is all zeros. */
    private fun isAllZero(buf: ByteArray, length: Int): Boolean {
        for (i in 0 until length) {
            if (buf[i] != 0.toByte()) return false
        }
        return true
    }

    /**
     * Try to create and start an 8ch AudioRecord.
     * Returns the record on success, null on failure.
     */
    private fun create8chRecord(): AudioRecord? {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_MASK_8CH, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CAE_BLOCK_BYTES_8CH * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_MASK_8CH,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            remoteLog?.invoke("FrontMic: 8ch AudioRecord creation failed: ${e.message}")
            return null
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            remoteLog?.invoke("FrontMic: 8ch AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return null
        }
        return rec
    }

    /* ---- CAE beamformer path ---- */

    private fun startCaePath() {
        val initialRec = create8chRecord()
        if (initialRec == null) {
            fallbackToCh2()
            return
        }

        remoteLog?.invoke("FrontMic: 8ch AudioRecord created: channels=${initialRec.channelCount} rate=${initialRec.sampleRate}")
        audioRecord = initialRec
        isRecording = true
        initialRec.startRecording()

        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rawBuf = ByteArray(CAE_BLOCK_BYTES_8CH)
            val sixChBuf = ByteArray(CAE_BLOCK_BYTES_6CH)
            val inwardBuf = if (twoWayActive) ByteArray(INWARD_BLOCK_BYTES) else null
            // Reused mono buffer for the far-party call downlink (echo channel).
            val callBuf = ByteArray(INWARD_BLOCK_BYTES)
            var consecutiveZeroBlocks = 0
            var deadMicRetried = false
            var currentRec: AudioRecord = initialRec

            while (isRecording) {
                val bytesRead = currentRec.read(rawBuf, 0, CAE_BLOCK_BYTES_8CH)

                // Handle AudioRecord errors
                if (bytesRead < 0) {
                    when (bytesRead) {
                        AudioRecord.ERROR_DEAD_OBJECT -> {
                            remoteLog?.invoke("FrontMic: ERROR_DEAD_OBJECT -- AudioRecord is permanently broken")
                            break
                        }
                        AudioRecord.ERROR_INVALID_OPERATION -> {
                            remoteLog?.invoke("FrontMic: ERROR_INVALID_OPERATION -- AudioRecord not initialized")
                            break
                        }
                        else -> {
                            remoteLog?.invoke("FrontMic: AudioRecord.read error=$bytesRead")
                            continue
                        }
                    }
                }
                if (bytesRead != CAE_BLOCK_BYTES_8CH) continue

                // Dead-mic detection: count consecutive all-zero blocks
                if (isAllZero(rawBuf, CAE_BLOCK_BYTES_8CH)) {
                    consecutiveZeroBlocks++
                    if (consecutiveZeroBlocks == DEAD_MIC_THRESHOLD_CAE) {
                        remoteLog?.invoke("FrontMic: dead mic detected ($consecutiveZeroBlocks consecutive zero blocks, ~2s)")
                        if (deadMicRetried) {
                            remoteLog?.invoke("FrontMic: dead mic persists after retry -- falling back to 4ch")
                            break
                        }
                        // Try restarting the AudioRecord once
                        deadMicRetried = true
                        consecutiveZeroBlocks = 0
                        remoteLog?.invoke("FrontMic: restarting 8ch AudioRecord (retry)")
                        try { currentRec.stop() } catch (_: Exception) {}
                        currentRec.release()
                        audioRecord = null

                        Thread.sleep(100)  // brief pause for HAL release

                        val newRec = create8chRecord()
                        if (newRec == null) {
                            remoteLog?.invoke("FrontMic: 8ch re-creation failed -- falling back to 4ch")
                            break
                        }
                        audioRecord = newRec
                        newRec.startRecording()
                        currentRec = newRec
                        remoteLog?.invoke("FrontMic: 8ch AudioRecord restarted successfully")
                    }
                    continue
                } else {
                    consecutiveZeroBlocks = 0
                }

                /* Extract ch0 (post-algorithm / inward) for two-way mode.
                 * Done every block before CAE (which only outputs every 2nd block)
                 * so inward audio has continuous 16ms chunks. */
                if (twoWayActive && inwardBuf != null) {
                    for (i in 0 until CAE_BLOCK_FRAMES) {
                        val srcIdx = i * NUM_CHANNELS_8 * BYTES_PER_SAMPLE  // ch0 is first
                        val raw = (rawBuf[srcIdx].toInt() and 0xFF) or (rawBuf[srcIdx + 1].toInt() shl 8)
                        val amplified = (raw.toShort().toInt() * MIC_GAIN).coerceIn(-32768, 32767)
                        val dstIdx = i * BYTES_PER_SAMPLE
                        inwardBuf[dstIdx] = (amplified and 0xFF).toByte()
                        inwardBuf[dstIdx + 1] = (amplified shr 8).toByte()
                    }
                    val b64Inward = Base64.encodeToString(inwardBuf, Base64.NO_WRAP)
                    btClient.sendInwardAudioData(b64Inward)
                }

                /* Extract the far-party call downlink (echo channel, raw 8ch index
                 * callEchoChannel = 6) per block while a call is active. Loud and clean
                 * already, so no gain. Mirrors the ch0 inward extraction above. */
                if (callAudioActive) {
                    val ch = callEchoChannel
                    for (i in 0 until CAE_BLOCK_FRAMES) {
                        val srcIdx = (i * NUM_CHANNELS_8 + ch) * BYTES_PER_SAMPLE
                        val dstIdx = i * BYTES_PER_SAMPLE
                        callBuf[dstIdx] = rawBuf[srcIdx]
                        callBuf[dstIdx + 1] = rawBuf[srcIdx + 1]
                    }
                    val b64Call = Base64.encodeToString(callBuf, Base64.NO_WRAP)
                    btClient.sendCallAudioData(b64Call)
                }

                /* Extract channels [2,3,4,5,6,7] from 8ch interleaved */
                for (f in 0 until CAE_BLOCK_FRAMES) {
                    for (c in 0 until CAE_INPUT_CHANNELS) {
                        val srcIdx = (f * NUM_CHANNELS_8 + EXTRACT_MAP[c]) * BYTES_PER_SAMPLE
                        val dstIdx = (f * CAE_INPUT_CHANNELS + c) * BYTES_PER_SAMPLE
                        sixChBuf[dstIdx] = rawBuf[srcIdx]
                        sixChBuf[dstIdx + 1] = rawBuf[srcIdx + 1]
                    }
                }

                /* Feed to CAE -- returns beamformed mono PCM or null if no output yet.
                 * Keep the beamformer warm even during a call, but do NOT send the front
                 * beam while call audio is active: the front beam is the wearer's near
                 * party, which is out of scope for the phone's "system audio" translation,
                 * and suspending it halves RFCOMM load during the call. */
                val beamOutput = try {
                    nativeCaeProcess(sixChBuf)
                } catch (e: Exception) {
                    remoteLog?.invoke("FrontMic: CAE process exception: ${e.message}")
                    null
                } ?: continue

                if (!callAudioActive) {
                    val b64 = Base64.encodeToString(beamOutput, Base64.NO_WRAP)
                    btClient.sendAudioData(b64)
                }
            }

            // If we exited the loop while still supposed to be recording,
            // the mic is broken -- fall back to 4ch on the main thread.
            if (isRecording) {
                remoteLog?.invoke("FrontMic: CAE loop exited while recording -- scheduling 4ch fallback")
                try { currentRec.stop() } catch (_: Exception) {}
                currentRec.release()
                audioRecord = null
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (isRecording) {
                        fallbackToCh2()
                    }
                }
            }
        }, "FrontMicCAE").also { it.start() }

        remoteLog?.invoke("FrontMic: started (CAE beamformed via 8ch capture)")
    }

    /* ---- Fallback: raw 4-ch, extract channel 2 (FRONT) ---- */

    private fun fallbackToCh2() {
        try { nativeCaeDestroy() } catch (_: Exception) {}
        usingCae = false
        remoteLog?.invoke("FrontMic: switching to raw 4ch fallback")
        startFallbackPath()
    }

    private fun startFallbackPath() {
        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_4CH, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_RAW_BYTES_4CH * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_4CH,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            remoteLog?.invoke("FrontMic: 4ch AudioRecord creation failed: ${e.message}")
            return
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            remoteLog?.invoke("FrontMic: 4ch AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return
        }

        if (twoWayActive) {
            remoteLog?.invoke("FrontMic: WARNING -- 4ch fallback (0x3C) does not include ch0; two-way inward audio unavailable on this path")
        }
        remoteLog?.invoke("FrontMic: 4ch AudioRecord created: channels=${rec.channelCount} rate=${rec.sampleRate} extractCh=$EXTRACT_CHANNEL gain=$MIC_GAIN")
        audioRecord = rec
        isRecording = true
        rec.startRecording()

        recordingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rawBuf = ByteArray(CHUNK_RAW_BYTES_4CH)
            val monoBuf = ByteArray(CHUNK_MONO_BYTES)
            var consecutiveZeroBlocks = 0

            while (isRecording) {
                val bytesRead = rec.read(rawBuf, 0, CHUNK_RAW_BYTES_4CH)

                // Handle AudioRecord errors
                if (bytesRead < 0) {
                    when (bytesRead) {
                        AudioRecord.ERROR_DEAD_OBJECT -> {
                            remoteLog?.invoke("FrontMic: 4ch ERROR_DEAD_OBJECT -- AudioRecord is permanently broken")
                            break
                        }
                        AudioRecord.ERROR_INVALID_OPERATION -> {
                            remoteLog?.invoke("FrontMic: 4ch ERROR_INVALID_OPERATION -- AudioRecord not initialized")
                            break
                        }
                        else -> {
                            remoteLog?.invoke("FrontMic: 4ch AudioRecord.read error=$bytesRead")
                            continue
                        }
                    }
                }
                if (bytesRead != CHUNK_RAW_BYTES_4CH) continue

                // Dead-mic detection for 4ch path
                if (isAllZero(rawBuf, CHUNK_RAW_BYTES_4CH)) {
                    consecutiveZeroBlocks++
                    if (consecutiveZeroBlocks == DEAD_MIC_THRESHOLD_4CH) {
                        remoteLog?.invoke("FrontMic: 4ch dead mic detected (${consecutiveZeroBlocks} consecutive zero blocks, ~2s) -- stopping")
                        break
                    }
                    continue
                } else {
                    consecutiveZeroBlocks = 0
                }

                // The 4ch fallback mask (0x3C) does not include the echo channel, so the
                // far-party call downlink is unavailable on this path. Warn once so the
                // silent-captions cause is diagnosable.
                if (callAudioActive && !callFallbackWarned) {
                    callFallbackWarned = true
                    remoteLog?.invoke("FrontMic: WARNING -- call audio requested but on 4ch fallback; echo channel unavailable, no call downlink will stream")
                }

                val chOffset = EXTRACT_CHANNEL * BYTES_PER_SAMPLE
                for (i in 0 until CHUNK_FRAMES) {
                    val srcIdx = i * BYTES_PER_FRAME_4 + chOffset
                    val raw = (rawBuf[srcIdx].toInt() and 0xFF) or (rawBuf[srcIdx + 1].toInt() shl 8)
                    val amplified = (raw.toShort().toInt() * MIC_GAIN).coerceIn(-32768, 32767)
                    val dstIdx = i * BYTES_PER_SAMPLE
                    monoBuf[dstIdx] = (amplified and 0xFF).toByte()
                    monoBuf[dstIdx + 1] = (amplified shr 8).toByte()
                }

                val b64 = Base64.encodeToString(monoBuf, Base64.NO_WRAP)
                btClient.sendAudioData(b64)
            }

            if (isRecording) {
                remoteLog?.invoke("FrontMic: 4ch loop exited while recording -- mic is dead, giving up")
            }
        }, "FrontMicFallback").also { it.start() }

        remoteLog?.invoke("FrontMic: started (raw 4ch fallback, front-facing ch$EXTRACT_CHANNEL)")
    }
}
