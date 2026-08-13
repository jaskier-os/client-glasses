package com.repository.glasses.listener.arstream

/**
 * Mixes the inward (wearer) mic and the outward beamformed mic into one mono uplink stream.
 *
 * Channels 6 and 7 of the 8-channel capture are the hardware acoustic echo reference -- they
 * carry what the speaker is playing, i.e. the phone user's own voice. They must never reach the
 * uplink: doing so would return the far-end signal to its sender, which is a feedback loop that
 * no amount of AEC on either end can undo. [extractChannel] enforces this rather than trusting
 * every future caller to remember.
 */
class UplinkMixer {

    fun mix(inward: ShortArray, outward: ShortArray, out: ShortArray, length: Int) {
        for (i in 0 until length) {
            val sum = (inward[i] * GAIN_INWARD + outward[i] * GAIN_OUTWARD).toInt()
            out[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    companion object {
        private const val GAIN_INWARD = 0.5f
        private const val GAIN_OUTWARD = 0.5f

        /** ch0 = inward/wearer (post-algorithm), ch2..ch5 = array mics feeding the beamformer. */
        const val CHANNEL_INWARD = 0
        val BEAMFORMER_CHANNELS = intArrayOf(2, 3, 4, 5)

        /** The speaker echo reference. Never transmitted. */
        val FORBIDDEN_UPLINK_CHANNELS = setOf(6, 7)

        fun extractChannel(interleaved: ShortArray, channel: Int, channelCount: Int): ShortArray {
            require(channel !in FORBIDDEN_UPLINK_CHANNELS) {
                "channel $channel is the speaker echo reference and must never be sent uplink"
            }
            val frames = interleaved.size / channelCount
            val out = ShortArray(frames)
            for (f in 0 until frames) out[f] = interleaved[f * channelCount + channel]
            return out
        }
    }
}
