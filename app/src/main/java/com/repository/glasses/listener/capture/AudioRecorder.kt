package com.repository.glasses.listener.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.repository.glasses.tracing.GT
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "App:Audio"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val LOG_EVERY_N_FRAMES = 30
    }

    var remoteLog: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun record(durationSeconds: Int, callback: (String?) -> Unit) {
        Log.d(TAG, "event=record_request duration_s=$durationSeconds")
        Thread {
            val cookie = System.identityHashCode(this) xor durationSeconds
            GT.beginAsync("cap.audio_rec", cookie)
            val tStart = SystemClock.elapsedRealtime()
            Log.d(TAG, "event=record_thread_start cookie=$cookie")
            var audioRecord: AudioRecord? = null
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )

                val recordingsDir = File(context.filesDir, "recordings")
                recordingsDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val wavFile = File(recordingsDir, "recording_$timestamp.wav")

                val totalSamples = SAMPLE_RATE * durationSeconds
                val totalBytes = totalSamples * 2

                FileOutputStream(wavFile).use { fos ->
                    writeWavHeader(fos, 0)

                    audioRecord.startRecording()
                    remoteLog?.invoke("AudioRecorder: Recording started for ${durationSeconds}s")

                    val buffer = ShortArray(bufferSize / 2)
                    var bytesWritten = 0
                    var frameCount = 0
                    val loopStart = SystemClock.elapsedRealtime()

                    while (bytesWritten < totalBytes) {
                        val read = audioRecord.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val byteBuffer = ByteArray(read * 2)
                            for (i in 0 until read) {
                                byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                                byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                            }
                            fos.write(byteBuffer)
                            bytesWritten += byteBuffer.size
                            frameCount++
                            GT.counter("audio.frames", frameCount.toLong())
                            if (frameCount % LOG_EVERY_N_FRAMES == 0) {
                                val dt = SystemClock.elapsedRealtime() - loopStart
                                Log.v(TAG, "event=readLoopIter n=$frameCount bytes=$bytesWritten dt_ms=$dt")
                            }
                        }
                    }
                    Log.d(TAG, "event=readLoopDone frames=$frameCount bytes=$bytesWritten dt_ms=${SystemClock.elapsedRealtime() - loopStart}")

                    audioRecord.stop()
                    remoteLog?.invoke("AudioRecorder: Recording stopped, wrote $bytesWritten bytes")
                }

                // Fix WAV header with actual data size
                RandomAccessFile(wavFile, "rw").use { raf ->
                    val fileSize = wavFile.length()
                    val dataSize = fileSize - 44
                    raf.seek(4)
                    writeIntLE(raf, (fileSize - 8).toInt())
                    raf.seek(40)
                    writeIntLE(raf, dataSize.toInt())
                }

                callback(wavFile.absolutePath)

            } catch (e: Exception) {
                Log.e(TAG, "event=record_error msg=${e.message}")
                remoteLog?.invoke("AudioRecorder: Recording failed: ${e.message}")
                callback(null)
            } finally {
                audioRecord?.release()
                GT.endAsync("cap.audio_rec", cookie)
                Log.d(TAG, "event=record_done total_ms=${SystemClock.elapsedRealtime() - tStart}")
            }
        }.start()
    }

    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int) {
        val header = ByteArray(44)
        val totalSize = dataSize + 36
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        val blockAlign = 1 * 16 / 8

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntToArray(header, 4, totalSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntToArray(header, 16, 16)
        writeShortToArray(header, 20, 1)
        writeShortToArray(header, 22, 1)
        writeIntToArray(header, 24, SAMPLE_RATE)
        writeIntToArray(header, 28, byteRate)
        writeShortToArray(header, 32, blockAlign)
        writeShortToArray(header, 34, 16)

        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntToArray(header, 40, dataSize)

        fos.write(header)
    }

    private fun writeIntToArray(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = (value shr 8 and 0xFF).toByte()
        array[offset + 2] = (value shr 16 and 0xFF).toByte()
        array[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShortToArray(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun writeIntLE(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xFF)
        raf.write(value shr 8 and 0xFF)
        raf.write(value shr 16 and 0xFF)
        raf.write(value shr 24 and 0xFF)
    }
}
