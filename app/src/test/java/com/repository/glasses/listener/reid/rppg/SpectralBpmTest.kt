package com.repository.glasses.listener.reid.rppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

/**
 * Unit tests for [SpectralBpm].
 *
 * Signals are synthetic, uniformly sampled sinusoids; bpm = freqHz * 60.
 * Tolerances are kept at +/- 2 bpm, physically justified by the FFT bin
 * resolution (fps / nfft) refined with parabolic interpolation.
 */
class SpectralBpmTest {

    private fun sinusoid(freqHz: Float, fps: Float, seconds: Float): FloatArray {
        val n = (fps * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i / fps
            sin(2.0 * PI * freqHz * t).toFloat()
        }
    }

    @Test
    fun pureSinusoid_1_2Hz_at15fps_is72bpm() {
        val signal = sinusoid(1.2f, 15f, 10f)
        val est = SpectralBpm.estimate(signal, 15f)
        assertEquals(72f, est.bpm, 2f)
    }

    @Test
    fun pureSinusoid_2_0Hz_at15fps_is120bpm() {
        val signal = sinusoid(2.0f, 15f, 10f)
        val est = SpectralBpm.estimate(signal, 15f)
        assertEquals(120f, est.bpm, 2f)
    }

    @Test
    fun pureSinusoid_1_0Hz_at20fps_is60bpm() {
        val signal = sinusoid(1.0f, 20f, 10f)
        val est = SpectralBpm.estimate(signal, 20f)
        assertEquals(60f, est.bpm, 2f)
    }

    @Test
    fun whiteNoise_hasLowSnr() {
        val rng = Random(42)
        val signal = FloatArray(150) { (rng.nextFloat() * 2f - 1f) }
        val est = SpectralBpm.estimate(signal, 15f)
        assertTrue("noise bandRatio should be low, got ${est.bandRatio}", est.bandRatio < 0.3f)
    }

    @Test
    fun cleanSinusoid_hasHighSnr() {
        val signal = sinusoid(1.2f, 15f, 10f)
        val est = SpectralBpm.estimate(signal, 15f)
        assertTrue("clean bandRatio should be high, got ${est.bandRatio}", est.bandRatio > 0.5f)
    }

    @Test
    fun harmonicSignal_picksFundamental() {
        val fps = 15f
        val n = (fps * 10f).toInt()
        val signal = FloatArray(n) { i ->
            val t = i / fps
            (sin(2.0 * PI * 1.0 * t) + 0.6 * sin(2.0 * PI * 2.0 * t)).toFloat()
        }
        val est = SpectralBpm.estimate(signal, fps)
        assertEquals("should pick 60 bpm fundamental, not 120", 60f, est.bpm, 2f)
    }

    @Test
    fun emptyOrInvalid_returnsZero() {
        assertEquals(0f, SpectralBpm.estimate(FloatArray(0), 15f).bpm, 0f)
        assertEquals(0f, SpectralBpm.estimate(FloatArray(0), 15f).bandRatio, 0f)
        assertEquals(0f, SpectralBpm.estimate(FloatArray(50), 0f).bpm, 0f)
        assertEquals(0f, SpectralBpm.estimate(FloatArray(2), 15f).bpm, 0f)
    }

    @Test
    fun nonFiniteInputReturnsZero() {
        val withNaN = sinusoid(1.2f, 15f, 10f).also { it[10] = Float.NaN }
        val nanEst = SpectralBpm.estimate(withNaN, 15f)
        assertEquals(0f, nanEst.bpm, 0f)
        assertEquals(0f, nanEst.bandRatio, 0f)

        val withInf = sinusoid(1.2f, 15f, 10f).also { it[10] = Float.POSITIVE_INFINITY }
        val infEst = SpectralBpm.estimate(withInf, 15f)
        assertEquals(0f, infEst.bpm, 0f)
        assertEquals(0f, infEst.bandRatio, 0f)
    }

    /**
     * FFT correctness: a single-bin cosine x[n] = cos(2*pi*k0*n/N) over N samples
     * must have its magnitude peak exactly at bin k0. Compared against a naive DFT
     * computed here so the radix-2 implementation is validated independently.
     */
    @Test
    fun fft_peaksAtExpectedBin() {
        val nfft = 64
        val k0 = 5
        val x = FloatArray(nfft) { n -> cos(2.0 * PI * k0 * n / nfft).toFloat() }

        val mag = SpectralBpm.magnitudeSpectrumForTest(x, nfft)

        // Peak bin of the one-sided spectrum.
        var peakBin = 0
        var peakVal = -1.0
        for (k in mag.indices) {
            if (mag[k] > peakVal) {
                peakVal = mag[k].toDouble()
                peakBin = k
            }
        }
        assertEquals(k0, peakBin)

        // Cross-check a few bins against a naive DFT magnitude.
        for (k in 0..10) {
            var re = 0.0
            var im = 0.0
            for (n in 0 until nfft) {
                val ang = -2.0 * PI * k * n / nfft
                re += x[n] * cos(ang)
                im += x[n] * sin(ang)
            }
            val naive = kotlin.math.sqrt(re * re + im * im)
            assertEquals("bin $k", naive, mag[k].toDouble(), 1e-3)
        }
    }
}
