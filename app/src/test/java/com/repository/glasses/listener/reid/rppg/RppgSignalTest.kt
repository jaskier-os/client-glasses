package com.repository.glasses.listener.reid.rppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class RppgSignalTest {

    /** Pearson correlation of two equal-length arrays after mean removal. */
    private fun pearson(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        val n = a.size
        val ma = a.sum().toDouble() / n
        val mb = b.sum().toDouble() / n
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db
            va += da * da
            vb += db * db
        }
        if (va == 0.0 || vb == 0.0) return 0.0
        return cov / sqrt(va * vb)
    }

    @Test
    fun posConstantInputIsZero() {
        val n = 64
        val r = FloatArray(n) { 0.4f }
        val g = FloatArray(n) { 0.6f }
        val b = FloatArray(n) { 0.5f }
        val p = RppgSignal.pos(r, g, b)
        assertEquals(n, p.size)
        val maxAbs = p.maxOf { abs(it) }
        assertTrue("constant input must produce ~zero pulse, was $maxAbs", maxAbs < 1e-3f)
    }

    @Test
    fun posRecoversSinusoidInGreen() {
        val n = 300
        val fps = 30.0
        val hz = 1.2
        val ref = FloatArray(n) { i -> sin(2 * PI * hz * i / fps).toFloat() }
        val r = FloatArray(n) { 0.4f }
        val g = FloatArray(n) { i -> (0.5 + 0.02 * sin(2 * PI * hz * i / fps)).toFloat() }
        val b = FloatArray(n) { 0.5f }
        val p = RppgSignal.pos(r, g, b)
        assertEquals(n, p.size)
        val corr = abs(pearson(p, ref))
        assertTrue("|corr| with injected sinusoid must exceed 0.9, was $corr", corr > 0.9)
    }

    @Test
    fun chromConstantInputIsZero() {
        val n = 64
        val r = FloatArray(n) { 0.4f }
        val g = FloatArray(n) { 0.6f }
        val b = FloatArray(n) { 0.5f }
        val p = RppgSignal.chrom(r, g, b)
        assertEquals(n, p.size)
        val maxAbs = p.maxOf { abs(it) }
        assertTrue("constant input must produce ~zero pulse, was $maxAbs", maxAbs < 1e-3f)
    }

    @Test
    fun mismatchedLengthsThrow() {
        try {
            RppgSignal.pos(FloatArray(4), FloatArray(4), FloatArray(3))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
