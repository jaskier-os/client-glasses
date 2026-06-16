package com.repository.glasses.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure [ScrfdFaceDetector.decodeFull] raw-FloatArray parser.
 * No Android framework classes are touched here; only the [count, 15*count] decode.
 */
class ScrfdFaceDetectorDecodeTest {

    /** Build a [count, then per-face 15 floats] raw array from per-face rows. */
    private fun raw(vararg faces: FloatArray): FloatArray {
        val out = FloatArray(1 + faces.size * 15)
        out[0] = faces.size.toFloat()
        for ((i, f) in faces.withIndex()) {
            require(f.size == 15) { "each face row must be 15 floats" }
            System.arraycopy(f, 0, out, 1 + i * 15, 15)
        }
        return out
    }

    private fun face(
        score: Float, x0: Float, y0: Float, x1: Float, y1: Float, kps: FloatArray,
    ): FloatArray = floatArrayOf(score, x0, y0, x1, y1, *kps)

    @Test
    fun decodesTwoFacesWithExactValues() {
        val k0 = floatArrayOf(10f, 11f, 12f, 13f, 14f, 15f, 16f, 17f, 18f, 19f)
        val k1 = floatArrayOf(20f, 21f, 22f, 23f, 24f, 25f, 26f, 27f, 28f, 29f)
        val r = raw(
            face(0.9f, 5f, 6f, 50f, 60f, k0),
            face(0.8f, 100f, 110f, 200f, 210f, k1),
        )
        val faces = ScrfdFaceDetector.decodeFull(r, 640, 480)
        assertEquals(2, faces.size)

        val f0 = faces[0]
        assertEquals(0.9f, f0.score, 1e-6f)
        assertEquals(5, f0.x0); assertEquals(6, f0.y0); assertEquals(50, f0.x1); assertEquals(60, f0.y1)
        assertTrue(k0.contentEquals(f0.kps))

        val f1 = faces[1]
        assertEquals(0.8f, f1.score, 1e-6f)
        assertEquals(100, f1.x0); assertEquals(110, f1.y0); assertEquals(200, f1.x1); assertEquals(210, f1.y1)
        assertTrue(k1.contentEquals(f1.kps))
    }

    @Test
    fun clampsBoxAndKeypointsToImageBounds() {
        val kps = floatArrayOf(-5f, 700f, 50f, -3f, 100f, 200f, 0f, 0f, 639f, 479f)
        val r = raw(face(0.7f, -10f, -20f, 9000f, 9000f, kps))
        val faces = ScrfdFaceDetector.decodeFull(r, 640, 480)
        assertEquals(1, faces.size)
        val f = faces[0]
        assertEquals(0, f.x0); assertEquals(0, f.y0)
        assertEquals(640, f.x1); assertEquals(480, f.y1)
        // kps x clamped to [0,640], y clamped to [0,480]
        val expected = floatArrayOf(0f, 480f, 50f, 0f, 100f, 200f, 0f, 0f, 639f, 479f)
        assertTrue(expected.contentEquals(f.kps))
    }

    @Test
    fun skipsDegenerateBox() {
        val good = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val deg = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val r = raw(
            face(0.5f, 30f, 30f, 10f, 60f, deg),  // x1 <= x0 -> skipped
            face(0.6f, 10f, 10f, 40f, 40f, good),
        )
        val faces = ScrfdFaceDetector.decodeFull(r, 640, 480)
        assertEquals(1, faces.size)
        assertEquals(0.6f, faces[0].score, 1e-6f)
    }

    @Test
    fun countZeroYieldsEmpty() {
        assertEquals(0, ScrfdFaceDetector.decodeFull(floatArrayOf(0f), 640, 480).size)
    }

    @Test
    fun emptyRawYieldsEmpty() {
        assertEquals(0, ScrfdFaceDetector.decodeFull(floatArrayOf(), 640, 480).size)
    }

    @Test
    fun truncatedFinalFaceIsDropped() {
        val k0 = floatArrayOf(10f, 11f, 12f, 13f, 14f, 15f, 16f, 17f, 18f, 19f)
        // Header claims 2 faces but only 1 full face + a short tail follows.
        val full = raw(face(0.9f, 5f, 6f, 50f, 60f, k0))
        val truncated = full.copyOf(full.size + 5)  // partial second face
        truncated[0] = 2f
        val faces = ScrfdFaceDetector.decodeFull(truncated, 640, 480)
        assertEquals(1, faces.size)
        assertEquals(5, faces[0].x0)
    }
}
