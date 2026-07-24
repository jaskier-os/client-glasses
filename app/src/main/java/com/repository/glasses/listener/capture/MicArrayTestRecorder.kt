package com.repository.glasses.listener.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records all 8 channels of the Rokid Glasses mic array to a raw PCM file.
 *
 * The Built-In Mic on this device supports AUDIO_CHANNEL_IN_8 at 16kHz.
 * Output: interleaved 8-channel 16-bit PCM at 16kHz.
 *
 * Channel layout (suspected, to be verified by listening):
 *   ch0-1: beamformed / voice recognition (processed)
 *   ch2-6: raw mic elements at different physical positions
 *   ch7:   hardware echo reference (speaker loopback)
 *
 * Usage: call [record] from any thread. It blocks for the requested duration,
 * writes to the output file, then returns. Call [cancel] from another thread
 * to abort early.
 */
class MicArrayTestRecorder {

    companion object {
        private const val TAG = "MicArrayTest"
        private const val SAMPLE_RATE = 16000
        private const val NUM_CHANNELS = 8
        private const val BYTES_PER_SAMPLE = 2  // PCM 16-bit
        private const val BYTES_PER_FRAME = NUM_CHANNELS * BYTES_PER_SAMPLE  // 16 bytes

        // Channel mask for 8 input channels.
        // AUDIO_CHANNEL_IN_8 = 0x6000FC in AOSP (position-based mask covering
        // LEFT|RIGHT|FRONT|BACK|LEFT_PROCESSED|RIGHT_PROCESSED plus two more).
        // The Rokid audio_policy_configuration.xml lists AUDIO_CHANNEL_IN_8 for
        // the Built-In Mic profile at 16kHz.
        //
        // If the position mask fails, fall back to channel index mask for 8 channels:
        // 0xC00000FF = index flag (0xC0000000) | bits 0-7.
        private const val CHANNEL_MASK_POSITION = 0x6000FC
        private const val CHANNEL_MASK_INDEX = 0xC00000FF.toInt()

        // Written to external storage (world-readable) so adb pull works without run-as
        private const val OUTPUT_PATH = "/sdcard/Download/mic_8ch.pcm"
    }

    var remoteLog: ((String) -> Unit)? = null
    private val cancelled = AtomicBoolean(false)

    /**
     * Records [durationSec] seconds of 8-channel audio to [OUTPUT_PATH].
     * Blocks the calling thread. Returns the output file path on success, null on failure.
     */
    @SuppressLint("MissingPermission")
    fun record(durationSec: Int, audioSource: Int = MediaRecorder.AudioSource.MIC): String? {
        cancelled.set(false)
        val log = { msg: String ->
            Log.i(TAG, msg)
            remoteLog?.invoke("MicArrayTest: $msg")
        }

        log("Starting 8-channel mic array recording for ${durationSec}s")

        // Try position mask first, fall back to index mask
        var channelMask = CHANNEL_MASK_POSITION
        var bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) {
            log("Position mask 0x${Integer.toHexString(CHANNEL_MASK_POSITION)} failed (bufSize=$bufSize), trying index mask")
            channelMask = CHANNEL_MASK_INDEX
            bufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
        }
        if (bufSize <= 0) {
            log("ERROR: both channel masks failed. getMinBufferSize=$bufSize")
            return null
        }

        // Ensure buffer is at least 4 frames worth
        val chunkFrames = 1024
        val chunkBytes = chunkFrames * BYTES_PER_FRAME
        bufSize = bufSize.coerceAtLeast(chunkBytes * 4)

        log("Using AudioSource=$audioSource channelMask=0x${Integer.toHexString(channelMask)}")
        val rec = try {
            AudioRecord(
                audioSource,
                SAMPLE_RATE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: Exception) {
            log("ERROR: AudioRecord creation failed: ${e.message}")
            return null
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log("ERROR: AudioRecord not initialized (state=${rec.state})")
            rec.release()
            return null
        }

        val actualChannels = rec.channelCount
        log("AudioRecord created: channels=$actualChannels rate=${rec.sampleRate} mask=0x${Integer.toHexString(channelMask)}")

        if (actualChannels != NUM_CHANNELS) {
            log("WARNING: expected $NUM_CHANNELS channels but got $actualChannels")
        }

        // Set preferred mic direction: 2 = MIC_DIRECTION_AWAY_FROM_USER (outward/front-facing)
        try {
            val dirResult = rec.setPreferredMicrophoneDirection(2)
            log("setPreferredMicrophoneDirection(AWAY_FROM_USER=2) -> result=$dirResult")
        } catch (e: Exception) {
            log("setPreferredMicrophoneDirection failed: ${e.message}")
        }

        val outFile = File(OUTPUT_PATH)
        val totalFrames = SAMPLE_RATE * durationSec
        val rawBuf = ByteArray(chunkBytes)
        var framesWritten = 0

        try {
            FileOutputStream(outFile).use { fos ->
                rec.startRecording()
                log("Recording started")

                while (framesWritten < totalFrames && !cancelled.get()) {
                    val bytesRead = rec.read(rawBuf, 0, chunkBytes)
                    if (bytesRead <= 0) {
                        log("WARNING: read returned $bytesRead, retrying")
                        continue
                    }
                    fos.write(rawBuf, 0, bytesRead)
                    framesWritten += bytesRead / BYTES_PER_FRAME
                }

                rec.stop()
            }
        } catch (e: Exception) {
            log("ERROR: recording failed: ${e.message}")
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            return null
        }

        rec.release()

        val fileSize = outFile.length()
        val actualDuration = framesWritten.toFloat() / SAMPLE_RATE
        log("Recording complete: ${actualChannels}ch, ${actualDuration}s, $fileSize bytes -> $OUTPUT_PATH")

        return OUTPUT_PATH
    }

    fun cancel() {
        cancelled.set(true)
    }
}
