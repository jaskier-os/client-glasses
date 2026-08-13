package com.repository.glasses.listener.arstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UplinkMixerTest {

    @Test
    fun sumsBothMicsWithHeadroom() {
        val mixer = UplinkMixer()
        val inward = ShortArray(4) { 1000 }
        val outward = ShortArray(4) { 1000 }
        val out = ShortArray(4)

        mixer.mix(inward, outward, out, 4)

        out.forEach { assertEquals(1000, it.toInt()) }
    }

    @Test
    fun clampsInsteadOfWrapping() {
        val mixer = UplinkMixer()
        val loud = ShortArray(2) { Short.MAX_VALUE }
        val out = ShortArray(2)

        mixer.mix(loud, loud, out, 2)

        out.forEach { assertTrue("expected positive, got $it", it > 0) }
    }

    @Test
    fun extractsRequestedChannelFromInterleavedFrame() {
        val interleaved = ShortArray(8) { (it * 100).toShort() }

        val inward = UplinkMixer.extractChannel(interleaved, UplinkMixer.CHANNEL_INWARD, channelCount = 8)
        assertEquals(0, inward[0].toInt())

        val beam = UplinkMixer.extractChannel(interleaved, 2, channelCount = 8)
        assertEquals(200, beam[0].toInt())
    }

    @Test
    fun refusesToExtractEchoReferenceChannels() {
        val interleaved = ShortArray(8)
        for (ch in UplinkMixer.FORBIDDEN_UPLINK_CHANNELS) {
            try {
                UplinkMixer.extractChannel(interleaved, ch, channelCount = 8)
                throw AssertionError("expected extractChannel($ch) to be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("echo reference"))
            }
        }
    }

    @Test
    fun beamformerChannelsExcludeTheEchoReference() {
        UplinkMixer.BEAMFORMER_CHANNELS.forEach {
            assertTrue("ch$it must not be an echo reference", it !in UplinkMixer.FORBIDDEN_UPLINK_CHANNELS)
        }
    }
}
