package com.repository.glasses.listener.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Muxes a WAV audio file (16kHz mono PCM16) into an MP4 video file,
 * producing a combined MP4 with both video and AAC audio tracks.
 */
class AudioVideoMuxer {

    companion object {
        private const val TAG = "App:AvMux"
        private const val LOG_EVERY_N_SAMPLES = 50
    }

    var remoteLog: ((String) -> Unit)? = null
    private var muxSampleCount = 0L

    private var thread: HandlerThread? = null

    /**
     * @param rotation Display rotation (degrees, one of 0/90/180/270) to encode into
     *   the output mp4's tkhd composition matrix. Use 0 if downstream consumers
     *   perform their own rotation.
     */
    fun mux(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        rotation: Int = 0,
        callback: (String?) -> Unit
    ) {
        thread = HandlerThread("AudioVideoMuxer").also { it.start() }
        Handler(thread!!.looper).post {
            val result = try {
                val out = doMux(videoPath, audioPath, outputPath)
                if (out != null && rotation != 0) {
                    try {
                        patchTkhdRotation(File(out), rotation)
                    } catch (e: Exception) {
                        remoteLog?.invoke("AudioVideoMuxer: tkhd patch failed: ${e.message}")
                    }
                }
                out
            } catch (e: Exception) {
                remoteLog?.invoke("AudioVideoMuxer: error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            Handler(Looper.getMainLooper()).post { callback(result) }
            thread?.quitSafely()
            thread = null
        }
    }

    /**
     * Rewrites the tkhd composition matrix of every track in the given mp4 to
     * encode the requested rotation. MediaMuxer.setOrientationHint is silently
     * ignored on this device's HAL, so we patch the matrix bytes ourselves.
     * The file is fully written by MediaMuxer at this point, so reads are
     * consistent and there is no FUSE flush race.
     */
    private fun patchTkhdRotation(file: File, rotation: Int) {
        val matrix = rotationCompositionMatrix(rotation)
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Walk top-level boxes to find moov.
        var moovStart = -1
        var moovEnd = bytes.size
        var p = 0
        while (p + 8 <= bytes.size) {
            bb.position(p)
            val sz32 = bb.int.toLong() and 0xFFFFFFFFL
            val tb = ByteArray(4)
            bb.get(tb)
            val typ = String(tb, Charsets.US_ASCII)
            val size = when {
                sz32 == 1L -> { bb.position(p + 8); bb.long }
                sz32 == 0L -> (bytes.size - p).toLong()
                else -> sz32
            }
            if (size <= 0 || p + size > bytes.size) break
            if (typ == "moov") {
                moovStart = p
                moovEnd = (p + size).toInt()
                break
            }
            p = (p + size).toInt()
        }
        if (moovStart < 0) {
            remoteLog?.invoke("AudioVideoMuxer: moov not found for tkhd patch")
            return
        }

        // Scan moov region for 'tkhd' markers and patch each container's matrix.
        var patched = 0
        var scan = moovStart + 8
        val t = 't'.code.toByte(); val k = 'k'.code.toByte()
        val h = 'h'.code.toByte(); val d = 'd'.code.toByte()
        while (scan + 8 <= moovEnd) {
            if (bytes[scan] == t && bytes[scan+1] == k &&
                bytes[scan+2] == h && bytes[scan+3] == d) {
                val boxStart = scan - 4
                val version = bytes[boxStart + 8].toInt() and 0xFF
                val timesSize = if (version == 0) 20 else 32
                val matrixOff = boxStart + 8 + 4 + timesSize + 8 + 2 + 2 + 2 + 2
                if (matrixOff + 36 <= moovEnd) {
                    bb.position(matrixOff)
                    for (v in matrix) bb.putInt(v)
                    patched++
                }
                scan += 4
            } else {
                scan++
            }
        }

        if (patched > 0) {
            FileOutputStream(file, false).use { it.write(bytes) }
            remoteLog?.invoke("AudioVideoMuxer: patched tkhd rot=$rotation tracks=$patched")
        } else {
            remoteLog?.invoke("AudioVideoMuxer: no tkhd to patch")
        }
    }

    /**
     * ISO/IEC 14496-12 composition matrix in row-major order:
     *   { a, b, u,  c, d, v,  x, y, w }
     * a,b,c,d,x,y are 16.16 fixed point; u,v,w are 2.30. For a display-only rotation
     * x=y=0, u=v=0, w=1.0 (0x40000000).
     */
    private fun rotationCompositionMatrix(rotation: Int): IntArray {
        val one = 0x00010000
        val neg = -one
        val w = 0x40000000
        return when (((rotation % 360) + 360) % 360) {
            90 -> intArrayOf(0, one, 0,   neg, 0, 0,   0, 0, w)
            180 -> intArrayOf(neg, 0, 0,  0, neg, 0,   0, 0, w)
            270 -> intArrayOf(0, neg, 0,  one, 0, 0,   0, 0, w)
            else -> intArrayOf(one, 0, 0, 0, one, 0,   0, 0, w)
        }
    }

    private fun doMux(videoPath: String, audioPath: String, outputPath: String): String? {
        val videoFile = File(videoPath)
        val audioFile = File(audioPath)
        if (!videoFile.exists() || videoFile.length() == 0L) {
            remoteLog?.invoke("AudioVideoMuxer: video file missing or empty")
            return null
        }
        if (!audioFile.exists() || audioFile.length() <= 44) {
            remoteLog?.invoke("AudioVideoMuxer: audio file missing or empty (${audioFile.length()} bytes)")
            return null
        }

        remoteLog?.invoke("AudioVideoMuxer: muxing video=${videoFile.length() / 1024}KB audio=${audioFile.length() / 1024}KB")

        // Extract video track from MP4
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoPath)
        var videoTrackIdx = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until videoExtractor.trackCount) {
            val fmt = videoExtractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIdx = i
                videoFormat = fmt
                break
            }
        }
        if (videoTrackIdx < 0 || videoFormat == null) {
            remoteLog?.invoke("AudioVideoMuxer: no video track found in $videoPath")
            videoExtractor.release()
            return null
        }
        videoExtractor.selectTrack(videoTrackIdx)

        // Read PCM audio from WAV (skip 44-byte header)
        val pcmData = readWavPcmData(audioPath)
        if (pcmData == null || pcmData.isEmpty()) {
            remoteLog?.invoke("AudioVideoMuxer: failed to read PCM from WAV")
            videoExtractor.release()
            return null
        }
        remoteLog?.invoke("AudioVideoMuxer: PCM data ${pcmData.size / 1024}KB")

        // Encode PCM to AAC
        val sampleRate = 16000
        val channelCount = 1
        val aacBitRate = 64000

        val audioEncFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, aacBitRate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder.configure(audioEncFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        // Encode all PCM to AAC buffers, collecting output
        val aacBuffers = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var aacOutputFormat: MediaFormat? = null
        var pcmOffset = 0
        var inputDone = false
        var outputDone = false
        var presentationTimeUs = 0L
        val bytesPerSample = 2 // 16-bit mono

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                val inIdx = audioEncoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = audioEncoder.getInputBuffer(inIdx)!!
                    val remaining = pcmData.size - pcmOffset
                    if (remaining <= 0) {
                        audioEncoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val chunkSize = minOf(remaining, inBuf.capacity())
                        inBuf.clear()
                        inBuf.put(pcmData, pcmOffset, chunkSize)
                        val pts = (pcmOffset.toLong() / bytesPerSample) * 1_000_000L / sampleRate
                        audioEncoder.queueInputBuffer(inIdx, 0, chunkSize, pts, 0)
                        pcmOffset += chunkSize
                    }
                }
            }

            // Drain output
            val info = MediaCodec.BufferInfo()
            val outIdx = audioEncoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    aacOutputFormat = audioEncoder.outputFormat
                }
                outIdx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        audioEncoder.releaseOutputBuffer(outIdx, false)
                        continue
                    }
                    if (info.size > 0) {
                        val outBuf = audioEncoder.getOutputBuffer(outIdx)!!
                        val data = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(data)
                        val infoCopy = MediaCodec.BufferInfo()
                        infoCopy.set(info.offset, info.size, info.presentationTimeUs, info.flags)
                        aacBuffers.add(Pair(data, infoCopy))
                        muxSampleCount++
                        if (muxSampleCount % LOG_EVERY_N_SAMPLES == 0L) {
                            Log.v(TAG, "event=mux_sample n=$muxSampleCount size=${info.size}")
                        }
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    audioEncoder.releaseOutputBuffer(outIdx, false)
                    if (eos) outputDone = true
                }
            }
        }
        audioEncoder.stop()
        audioEncoder.release()

        if (aacOutputFormat == null) {
            remoteLog?.invoke("AudioVideoMuxer: AAC encoder produced no output format")
            videoExtractor.release()
            return null
        }

        remoteLog?.invoke("AudioVideoMuxer: AAC encoded ${aacBuffers.size} buffers")

        // Mux video + audio into output MP4
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxVideoTrack = muxer.addTrack(videoFormat)
        val muxAudioTrack = muxer.addTrack(aacOutputFormat)
        muxer.start()

        // Write video track
        val videoBuf = ByteBuffer.allocate(1024 * 1024)
        val videoInfo = MediaCodec.BufferInfo()
        var videoFrames = 0
        while (true) {
            val size = videoExtractor.readSampleData(videoBuf, 0)
            if (size < 0) break
            videoInfo.offset = 0
            videoInfo.size = size
            videoInfo.presentationTimeUs = videoExtractor.sampleTime
            videoInfo.flags = videoExtractor.sampleFlags
            muxer.writeSampleData(muxVideoTrack, videoBuf, videoInfo)
            videoFrames++
            videoExtractor.advance()
        }
        videoExtractor.release()

        // Write audio track
        for ((data, info) in aacBuffers) {
            val buf = ByteBuffer.wrap(data)
            val muxInfo = MediaCodec.BufferInfo()
            muxInfo.set(0, data.size, info.presentationTimeUs, info.flags)
            muxer.writeSampleData(muxAudioTrack, buf, muxInfo)
        }

        muxer.stop()
        muxer.release()

        val outFile = File(outputPath)
        if (!outFile.exists() || outFile.length() == 0L) {
            remoteLog?.invoke("AudioVideoMuxer: output file missing or empty")
            return null
        }

        Log.d(TAG, "event=mux_done videoFrames=$videoFrames audioBufs=${aacBuffers.size} size_kb=${outFile.length() / 1024}")
        remoteLog?.invoke("AudioVideoMuxer: done videoFrames=$videoFrames audioBuffers=${aacBuffers.size} size=${outFile.length() / 1024}KB")
        return outputPath
    }

    private fun readWavPcmData(wavPath: String): ByteArray? {
        return try {
            val raf = RandomAccessFile(wavPath, "r")
            // Skip 44-byte WAV header
            if (raf.length() <= 44) {
                raf.close()
                return null
            }
            raf.seek(44)
            val dataSize = (raf.length() - 44).toInt()
            val data = ByteArray(dataSize)
            raf.readFully(data)
            raf.close()
            data
        } catch (e: Exception) {
            remoteLog?.invoke("AudioVideoMuxer: readWavPcmData failed: ${e.message}")
            null
        }
    }
}
