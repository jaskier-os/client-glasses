package com.repository.glasses.listener.arstream

/**
 * Wire format for the live AR stream over WiFi-Direct.
 *
 * Video frames reuse the header the phone-side ScreenStreamDecoder already parses,
 * so that decoder is reused unmodified: [0] version, [1] flags (bit0 keyframe, bit1 config),
 * [2..5] streamId, [6..9] timestamp. Every frame is preceded by a 4-byte big-endian length
 * covering header + payload, because TCP gives a byte stream with no message boundaries.
 *
 * Video and audio use separate sockets so that video backpressure cannot stall voice.
 */
object ArStreamProtocol {
    const val VIDEO_PORT = 8850
    const val AUDIO_PORT = 8851

    const val VIDEO_HEADER_SIZE = 10
    const val VERSION: Byte = 1
    const val FLAG_NONE: Byte = 0
    const val FLAG_KEYFRAME: Byte = 1
    const val FLAG_CONFIG: Byte = 2

    const val STREAM_ID = 1

    /** Audio socket message kinds (first byte after the length prefix). */
    const val MSG_AUDIO: Byte = 1
    const val MSG_CONTROL: Byte = 2

    /** Control opcodes, carried on the audio socket in both directions. */
    const val CTRL_MUTE_PHONE_MIC: Byte = 1
    const val CTRL_MUTE_GLASSES_MIC: Byte = 2
    const val CTRL_REQUEST_KEYFRAME: Byte = 3
    const val CTRL_STOP: Byte = 4

    const val AUDIO_SAMPLE_RATE = 16000

    /** Max body we will accept from the peer, so a corrupt length cannot force a huge allocation. */
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    fun frameVideo(payload: ByteArray, keyframe: Boolean, config: Boolean, timestampMs: Long): ByteArray {
        var flags = FLAG_NONE.toInt()
        if (keyframe) flags = flags or FLAG_KEYFRAME.toInt()
        if (config) flags = flags or FLAG_CONFIG.toInt()

        val body = ByteArray(VIDEO_HEADER_SIZE + payload.size)
        body[0] = VERSION
        body[1] = flags.toByte()
        body[2] = ((STREAM_ID ushr 24) and 0xFF).toByte()
        body[3] = ((STREAM_ID ushr 16) and 0xFF).toByte()
        body[4] = ((STREAM_ID ushr 8) and 0xFF).toByte()
        body[5] = (STREAM_ID and 0xFF).toByte()
        body[6] = ((timestampMs ushr 24) and 0xFF).toByte()
        body[7] = ((timestampMs ushr 16) and 0xFF).toByte()
        body[8] = ((timestampMs ushr 8) and 0xFF).toByte()
        body[9] = (timestampMs and 0xFF).toByte()
        System.arraycopy(payload, 0, body, VIDEO_HEADER_SIZE, payload.size)
        return withLengthPrefix(body)
    }

    fun frameAudio(pcm: ShortArray, length: Int = pcm.size): ByteArray {
        val body = ByteArray(1 + length * 2)
        body[0] = MSG_AUDIO
        var o = 1
        for (i in 0 until length) {
            val s = pcm[i].toInt()
            body[o++] = (s and 0xFF).toByte()
            body[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return withLengthPrefix(body)
    }

    fun frameControl(opcode: Byte, on: Boolean): ByteArray =
        withLengthPrefix(byteArrayOf(MSG_CONTROL, opcode, if (on) 1 else 0))

    /** Decode a little-endian PCM16 audio body (excluding the leading MSG_AUDIO byte). */
    fun decodeAudio(body: ByteArray): ShortArray {
        val samples = (body.size - 1) / 2
        val out = ShortArray(samples)
        var o = 1
        for (i in 0 until samples) {
            val lo = body[o++].toInt() and 0xFF
            val hi = body[o++].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    private fun withLengthPrefix(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = ((body.size ushr 24) and 0xFF).toByte()
        out[1] = ((body.size ushr 16) and 0xFF).toByte()
        out[2] = ((body.size ushr 8) and 0xFF).toByte()
        out[3] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }
}
