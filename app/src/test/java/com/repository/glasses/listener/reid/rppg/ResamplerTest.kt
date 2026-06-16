package com.repository.glasses.listener.reid.rppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ResamplerTest {

    /** Inline Pearson correlation between two equal-length series. */
    private fun pearson(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        val n = a.size
        val ma = a.fold(0.0) { acc, v -> acc + v } / n
        val mb = b.fold(0.0) { acc, v -> acc + v } / n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val xa = a[i] - ma
            val xb = b[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        return num / sqrt(da * db)
    }

    @Test
    fun uniformInputResamplesToSameGridAndFps() {
        val n = 20
        val tMs = LongArray(n) { it * 100L }
        val v = FloatArray(n) { it.toFloat() }

        val out = Resampler.resampleUniform(tMs, v)

        // Grid covers [0, 1900] at step 100 -> floor(1900/100)+1 = 20 points.
        assertEquals(n, out.values.size)
        assertEquals(10.0f, out.fps, 1e-4f)
        for (i in 0 until n) {
            assertEquals(v[i], out.values[i], 1e-3f)
        }
    }

    @Test
    fun jitteredSinusoidStillCorrelatesWithUniformReference() {
        val rng = Random(42)
        val freqHz = 1.2
        val nominalDtMs = 1000.0 / 15.0 // ~15 fps
        val n = 150

        val tMs = LongArray(n)
        var t = 0.0
        for (i in 0 until n) {
            tMs[i] = t.toLong()
            // +/- 40% jitter on each dt, always positive.
            val jitter = 1.0 + (rng.nextDouble() - 0.5) * 0.8
            t += nominalDtMs * jitter
        }
        val v = FloatArray(n) { sin(2.0 * PI * freqHz * (tMs[it] / 1000.0)).toFloat() }

        val out = Resampler.resampleUniform(tMs, v)
        assertTrue(out.values.size > 10)

        // Build the reference sinusoid on the same uniform grid the resampler used.
        val stepMs = 1000.0 / out.fps
        val ref = FloatArray(out.values.size) { i ->
            val gt = tMs.first() + i * stepMs
            sin(2.0 * PI * freqHz * (gt / 1000.0)).toFloat()
        }

        val r = pearson(out.values, ref)
        assertTrue("correlation too low: $r", r > 0.95)
    }

    @Test
    fun fpsIsThousandOverMedianDt() {
        // dts: 100, 300, 100, 100 -> sorted 100,100,100,300 -> median 100.
        val tMs = longArrayOf(0L, 100L, 400L, 500L, 600L)
        val v = FloatArray(tMs.size) { it.toFloat() }

        val out = Resampler.resampleUniform(tMs, v)
        assertEquals(10.0f, out.fps, 1e-4f) // 1000 / 100
    }

    @Test
    fun fewerThanTwoSamplesReturnsEmpty() {
        val empty = Resampler.resampleUniform(LongArray(0), FloatArray(0))
        assertEquals(0, empty.values.size)
        assertEquals(0.0f, empty.fps, 0.0f)

        val one = Resampler.resampleUniform(longArrayOf(5L), floatArrayOf(1f))
        assertEquals(0, one.values.size)
        assertEquals(0.0f, one.fps, 0.0f)
    }

    @Test
    fun mismatchedLengthsThrow() {
        try {
            Resampler.resampleUniform(longArrayOf(0L, 100L), floatArrayOf(1f))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun endpointsAreClampedNoExtrapolation() {
        // First grid point is exactly t0, last grid point at or before tLast.
        val tMs = longArrayOf(0L, 100L, 200L)
        val v = floatArrayOf(10f, 20f, 30f)

        val out = Resampler.resampleUniform(tMs, v)
        assertEquals(3, out.values.size)
        assertEquals(10f, out.values.first(), 1e-3f)
        assertEquals(30f, out.values.last(), 1e-3f)
        // Midpoint linearly interpolated.
        assertEquals(20f, out.values[1], 1e-3f)
    }

    @Test
    fun duplicateTimestampsResampleWithoutCrash() {
        // Duplicate interior timestamp at 100. dts: 100, 0, 100 -> positive {100,100} -> median 100.
        // Grid: t0=0, tLast=200, step=100 -> floor(200/100)+1 = 3 points (0,100,200).
        val tMs = longArrayOf(0L, 100L, 100L, 200L)
        val v = floatArrayOf(0f, 10f, 99f, 30f)

        val out = Resampler.resampleUniform(tMs, v)

        assertEquals(3, out.values.size)
        for (x in out.values) {
            assertTrue("unexpected NaN", !x.isNaN())
        }
        // Endpoints clamp to first/last sample values.
        assertEquals(0f, out.values.first(), 1e-4f)
        assertEquals(30f, out.values.last(), 1e-4f)
    }

    @Test
    fun gridTimeOnInteriorSampleEqualsSampleValue() {
        // dts: 100, 150, 50 -> positive {100,150,50} sorted {50,100,150} -> median 100.
        // Grid: t0=0, tLast=300, step=100 -> 0,100,200,300. Grid time 100 == tMs[1].
        val tMs = longArrayOf(0L, 100L, 250L, 300L)
        val v = floatArrayOf(0f, 42f, 80f, 90f)

        val out = Resampler.resampleUniform(tMs, v)

        // out.values[1] corresponds to grid time 100, exactly on sample tMs[1].
        assertEquals(42f, out.values[1], 1e-4f)
    }

    @Test
    fun singleHugeOutlierDtDoesNotSkewMedian() {
        // dts: 100, 100, 5000, 100 -> sorted {100,100,100,5000} -> median (100+100)/2 = 100.
        val tMs = longArrayOf(0L, 100L, 200L, 5200L, 5300L)
        val v = FloatArray(tMs.size) { it.toFloat() }

        val out = Resampler.resampleUniform(tMs, v)
        assertEquals(10.0f, out.fps, 1e-4f) // 1000 / 100
    }

    @Test
    fun gridLengthFollowsFloorRule() {
        // t0=0, tLast=250, step=100 -> floor(250/100)+1 = 3 grid points (0,100,200).
        val tMs = longArrayOf(0L, 100L, 200L, 250L)
        val v = FloatArray(tMs.size) { it.toFloat() }

        val out = Resampler.resampleUniform(tMs, v)
        assertEquals(3, out.values.size)
    }
}
